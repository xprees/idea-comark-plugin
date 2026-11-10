package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import cz.xprees.comark.format.FormatterExpressions.isArrayItem
import cz.xprees.comark.format.FormatterExpressions.isComponentStart
import cz.xprees.comark.format.FormatterExpressions.isEmptyProperty
import cz.xprees.comark.format.FormatterExpressions.isMultilineStringStart
import cz.xprees.comark.format.FormatterExpressions.isPropertyLine
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownListItem

/**
 * Decides how a line created by Enter in a Comark file is indented, for [ComarkLineIndentProvider] and
 * [ComarkEnterHandlerDelegate]. Text based (the document need not be committed), using the formatter's own view of the document
 * ([ComarkFormatter.lineContext]) so the new line starts where Reformat Code would put it:
 * - in a component body: the component's content indentation (the indentation of its opener, by the formatter's rules);
 * - in a component's props block or the front matter: YAML indentation - one level deeper after `key:` and `key: |`, aligned
 *   with the properties of an array item after `- key: value`, otherwise the previous line's;
 * - in a code fence inside a component: the previous line's indentation.
 *
 * Everything else - lists in particular, whose continuation the Markdown plugin handles by inserting the marker together with
 * its indentation, top level Markdown and anything when the Comark formatter is disabled - gets `null`, meaning "do what Markdown
 * does".
 */
internal object ComarkEnterIndent {
    fun isEnabledFor(file: PsiFile?): Boolean = file is ComarkFile && ComarkSettings.getInstance().formatterEnabled

    /** Indentation step of [file]: its code style indent size, as used by [ComarkFormattingService]. */
    fun tabSize(file: PsiFile): Int = CodeStyle.getIndentOptions(file).INDENT_SIZE.coerceAtLeast(0)

    /**
     * Indentation for line [line] of [document] - a line just created by Enter, empty or holding the text that followed the
     * caret - or null to leave it to Markdown.
     */
    fun indentFor(file: PsiFile, document: Document, line: Int): String? {
        if (line <= 0 || line >= document.lineCount) return null
        if (isInMarkdownList(file, document, line - 1) || isInMarkdownList(file, document, line - 2)) return null
        val lines = linesUpTo(document, line + 1)
        val tabSize = tabSize(file)
        val context = ComarkFormatter.lineContext(lines, line, tabSize)
        return when {
            // a `---` the formatter takes for YAML that is not props or front matter (a thematic break) is plain content
            context.insideYamlBlock -> yamlIndent(lines, line, context, tabSize)
                ?: if (context.componentDepth > 0 && !context.insideList) " ".repeat(context.contentIndent) else null
            context.insideCodeBlock -> if (context.componentDepth > 0) leadingWhitespace(lines[line - 1]) else null
            context.insideList || context.componentDepth == 0 -> null
            else -> " ".repeat(context.indent)
        }
    }

    /** The document's lines `0..lastLine` (or fewer at the end of the document). */
    private fun linesUpTo(document: Document, lastLine: Int): List<String> {
        val end = document.getLineEndOffset(minOf(lastLine, document.lineCount - 1))
        return document.immutableCharSequence.subSequence(0, end).toString().split('\n')
    }

    private fun leadingWhitespace(line: String): String = line.substring(0, JsText.leadingWhitespace(line))

    /**
     * Whether [line] belongs to a Markdown list item, as the Markdown plugin decides it for its own list handling: the element
     * at the line's last non-blank character is inside a list item. Lines above the caret are unchanged by the Enter in
     * progress, so the (not yet committed) PSI is accurate for them.
     */
    private fun isInMarkdownList(file: PsiFile, document: Document, line: Int): Boolean {
        if (line < 0 || line >= document.lineCount) return false
        val chars = document.charsSequence
        var offset = document.getLineEndOffset(line) - 1
        val start = document.getLineStartOffset(line)
        while (offset >= start && chars[offset].let { it == ' ' || it == '\t' }) offset--
        if (offset < start || offset >= file.textLength) return false
        val element = PsiUtilCore.getElementAtOffset(file, offset)
        return PsiTreeUtil.getParentOfType(element, MarkdownListItem::class.java, false) != null
    }

    private fun yamlIndent(lines: List<String>, line: Int, context: LineContext, tabSize: Int): String? {
        // The closing fence goes where the formatter puts it
        if (JsText.trim(lines[line]) == "---") return " ".repeat(context.indent)
        // Only a component's props block (right after the opener line) and the front matter are YAML. The formatter also
        // takes a `---` thematic break for a YAML block; that text is Markdown.
        var opener = line - 1
        while (opener >= 0 && JsText.trim(lines[opener]) != "---") {
            ProgressManager.checkCanceled()
            opener--
        }
        if (opener < 0 || (opener > 0 && !isComponentStart(lines[opener - 1]))) return null

        var previous = line - 1
        while (previous > opener && JsText.trim(lines[previous]).isEmpty()) previous--
        val previousLine = lines[previous]
        val base = leadingWhitespace(previousLine)
        if (previous == opener) return base
        val trimmed = JsText.trim(previousLine)
        return when {
            context.insideMultilineString && !isMultilineStringStart(trimmed) -> base
            isEmptyProperty(trimmed) || isMultilineStringStart(trimmed) -> base + " ".repeat(tabSize)
            isArrayItem(trimmed) && isPropertyLine(trimmed.substring(1)) -> {
                // `- key: value`: the item's next property aligns with `key`
                val dashEnd = JsText.skipWhitespace(trimmed, 1)
                base + " ".repeat(dashEnd)
            }
            else -> base
        }
    }
}
