package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.DocumentUtil
import cz.xprees.comark.format.FormatterExpressions.isArrayItem
import cz.xprees.comark.format.FormatterExpressions.isComponentStart
import cz.xprees.comark.format.FormatterExpressions.isEmptyProperty
import cz.xprees.comark.format.FormatterExpressions.isMultilineStringStart
import cz.xprees.comark.format.FormatterExpressions.isPropertyLine
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.psi.ComarkSlot
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownBlockQuote
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownListItem

/**
 * Decides how a line created by Enter in a Comark file is indented, for [ComarkLineIndentProvider] and
 * [ComarkEnterHandlerDelegate]. Text based (the document need not be committed), using the formatter's own view of the document
 * ([ComarkFormatter.lineContext]) so the new line starts where Reformat Code would put it:
 * - in a component body: the component's content indentation (the indentation of its opener, by the formatter's rules);
 * - in a component's props block or the front matter: YAML indentation - one level deeper after `key:` and `key: |`, aligned
 *   with the properties of an array item after `- key: value`, otherwise the previous line's;
 * - in a code fence inside a component: the previous line's indentation;
 * - in a component inside a list item: the same rules, applied to the component as a document of its own and shifted to the
 *   component's column, as [ComarkDocumentFormatter] formats it.
 *
 * Everything else - lists and block quotes in particular, whose continuation the Markdown plugin handles by inserting the
 * marker together with its indentation, top level Markdown and anything when the Comark formatter is disabled - gets `null`,
 * meaning "do what Markdown does". A list or block quote counts only when it is the innermost container of the line: a
 * component inside a list item is not list content.
 */
internal object ComarkEnterIndent {
    fun isEnabledFor(file: PsiFile?): Boolean = file is ComarkFile && ComarkSettings.getInstance().formatterEnabled

    /** Indentation step of [file], as used by [ComarkFormattingService] (see [ComarkIndent]). */
    fun tabSize(file: PsiFile): Int = ComarkIndent.indentSize(file)

    /**
     * Indentation for line [line] of [document] - a line just created by Enter, empty or holding the text that followed the
     * caret - or null to leave it to Markdown.
     */
    fun indentFor(file: PsiFile, document: Document, line: Int): String? {
        if (line <= 0 || line >= document.lineCount) return null
        if (continuesMarkdownContainer(file, document, line - 1)) return null
        // after a blank line, the line above it decides (Enter on the blank line below a list item continues the list)
        if (isBlank(document, line - 1) && continuesMarkdownContainer(file, document, line - 2)) return null
        val lines = linesUpTo(document, line + 1)
        val tabSize = tabSize(file)
        val component = listItemComponentAt(file, document, line - 1)
        if (component != null) {
            // formatted as a document of its own, at its opener's column
            val open = document.getLineNumber(component.textOffset)
            val prefix = leadingWhitespace(lines[open])
            val own = lines.subList(open, line + 1).map { it.substring(minOf(prefix.length, JsText.leadingWhitespace(it))) }
            return indentFor(own, line - open, tabSize)?.let { prefix + it }
        }
        return indentFor(lines, line, tabSize)
    }

    /** The Comark indentation of line [line] of [lines] (see [indentFor]), or null for Markdown's. */
    private fun indentFor(lines: List<String>, line: Int, tabSize: Int): String? {
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
     * Whether [line] is content of a component inside a list item (and not of a list or block quote within it). The Markdown
     * plugin's Enter handling sees the list item around the component and would start a new list item there, so Enter on
     * such a line is handled entirely by [ComarkEnterHandlerDelegate].
     */
    fun isListItemComponentContent(file: PsiFile, document: Document, line: Int): Boolean =
        !continuesMarkdownContainer(file, document, line) && listItemComponentAt(file, document, line) != null

    private fun isBlank(document: Document, line: Int): Boolean =
        line in 0 until document.lineCount && JsText.trim(document.getText(DocumentUtil.getLineTextRange(document, line))).isEmpty()

    /**
     * Whether the innermost container of [line] is a Markdown list item or block quote, whose continuation (marker and its
     * indentation) the Markdown plugin inserts on Enter. Components and slots are containers too, so the body of a component
     * inside a list item is not list content; a component's closing line belongs to the container around the component.
     */
    private fun continuesMarkdownContainer(file: PsiFile, document: Document, line: Int): Boolean {
        var element = elementAtLineEnd(file, document, line)
        while (element != null && element !is PsiFile) {
            when (element) {
                is MarkdownListItem, is MarkdownBlockQuote -> return true
                is ComarkSlot -> return false
                is ComarkBlockComponent -> if (!isClosingLine(element, document, line)) return false
            }
            element = element.parent
        }
        return false
    }

    /**
     * The innermost component inside a list item that line [line] is part of - in its body, or its opener line - if any.
     * Everything inside it is formatted relative to it, so it can be looked at as a document of its own.
     */
    private fun listItemComponentAt(file: PsiFile, document: Document, line: Int): ComarkBlockComponent? {
        var element = elementAtLineEnd(file, document, line)
        while (element != null && element !is PsiFile) {
            if (element is ComarkBlockComponent && element.parent is MarkdownListItem && !isClosingLine(element, document, line)) {
                return element
            }
            element = element.parent
        }
        return null
    }

    private fun isClosingLine(component: ComarkBlockComponent, document: Document, line: Int): Boolean {
        val close = component.closeMarker ?: return false
        return document.getLineNumber(close.textOffset) == line
    }

    /**
     * The element at the last non-blank character of [line], or null for a blank line. Lines above the caret are unchanged by
     * the Enter in progress, so the (not yet committed) PSI is accurate for them.
     */
    private fun elementAtLineEnd(file: PsiFile, document: Document, line: Int): PsiElement? {
        if (line < 0 || line >= document.lineCount) return null
        val chars = document.charsSequence
        var offset = document.getLineEndOffset(line) - 1
        val start = document.getLineStartOffset(line)
        while (offset >= start && chars[offset].let { it == ' ' || it == '\t' }) offset--
        if (offset < start || offset >= file.textLength) return null
        return PsiUtilCore.getElementAtOffset(file, offset)
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
