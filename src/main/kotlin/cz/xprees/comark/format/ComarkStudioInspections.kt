package cz.xprees.comark.format

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.meta.ComponentUsages
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The Studio style of a Comark file for the inspections: [StudioStyleAdapter.format] of the file's text, cached on the file
 * (dropped when it changes) per style context, so the daemon formats a document once per modification whatever the number of
 * inspections and passes. Inspections run in the background, never on the EDT.
 */
internal object ComarkStudioAnalysis {
    /** Larger files are not analysed (the engine is not interruptible). */
    const val MAX_LENGTH = 512 * 1024

    private val KEY = Key.create<CachedValue<ConcurrentHashMap<StudioStyleContext, Optional<StudioStyleResult>>>>("comark.studio.analysis")

    /**
     * The style a Comark [file] is checked against, or null when the Studio inspections don't apply: the file's effective preset
     * is not Studio based ([ComarkStyle.usesStudio]), or the file is a Markdown example injected into a code fence.
     */
    fun styleFor(file: PsiFile): ComarkStyle? {
        if (file !is ComarkFile) return null
        if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return null
        return ComarkStyle.of(file).takeIf { it.usesStudio }
    }

    fun result(file: PsiFile, style: ComarkStyle): StudioStyleResult? {
        val text = file.viewProvider.contents
        if (text.length > MAX_LENGTH) return null
        val context = ComarkStudioFormatting.context(file, style)
        val results = CachedValuesManager.getCachedValue(file, KEY) { CachedValueProvider.Result.create(ConcurrentHashMap(), file) }
        results[context]?.let { return it.orElse(null) }
        ProgressManager.checkCanceled()
        val result = Optional.ofNullable(StudioStyleAdapter.format(text.toString(), context))
        results[context] = result
        return result.orElse(null)
    }
}

/**
 * *Nuxt Studio will rewrite this*: the blocks Reformat Code keeps as written in the Nuxt Studio style because Studio would damage
 * them on save (raw HTML, HTML comments, reference links, table alignment, heading ids, ...; [StudioStyleResult.keptBlocks]), and,
 * as a weaker note, the blocks written in the style that could not be checked against Studio's database path
 * ([StudioStyleResult.unverifiedBlocks]).
 *
 * Active when the file's effective style is Nuxt Studio or Custom: that is when the project targets Studio, and other projects
 * get no Studio warnings (a project that depends on nuxt-studio but still uses the VS Code style is offered the Studio style
 * instead, see [ComarkStudioAdoption]). Blocks between formatter off/on markers are not reported, which is what the *Suppress
 * for this block* fix writes. The first line of each block is highlighted.
 */
class ComarkStudioRewriteInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val style = ComarkStudioAnalysis.styleFor(file) ?: return null
        val result = ComarkStudioAnalysis.result(file, style) ?: return null
        if (result.keptBlocks.isEmpty() && result.unverifiedBlocks.isEmpty()) return null
        val text = file.viewProvider.contents
        val lines = Lines(text)
        val marked = ComarkFormatterControl.markedLines(file, text)
        val problems = ArrayList<ProblemDescriptor>()
        for (block in result.keptBlocks) {
            if (marked != null && block.lines.any { marked.get(it) }) continue
            val first = block.lines.firstOrNull { it < lines.count && lines.text(it).isNotBlank() } ?: continue
            val range = lines.trimmedRange(first)
            val last = minOf(block.lines.last, lines.count - 1)
            val fixes = ArrayList<LocalQuickFix>()
            val firstLine = lines.text(first)
            if ("TABLE_ALIGNMENT" in block.reasons) fixes += RemoveTableAlignmentFix(first, last, firstLine)
            if ("HEADING_ID" in block.reasons) fixes += RemoveHeadingIdFix(first, last, firstLine)
            fixes += SuppressForBlockFix(first, last, firstLine)
            problems += manager.createProblemDescriptor(
                file,
                range,
                ComarkBundle.message("studio.inspection.rewrite.message", block.message),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
                *fixes.toTypedArray(),
            )
        }
        for (block in result.unverifiedBlocks) {
            if (marked != null && block.lines.any { marked.get(it) }) continue
            if (result.keptBlocks.any { kept -> kept.lines.any { it in block.lines } }) continue
            val first = block.lines.firstOrNull { it < lines.count && lines.text(it).isNotBlank() } ?: continue
            val last = minOf(block.lines.last, lines.count - 1)
            val fixes = if (block.frontMatter) emptyArray() else arrayOf<LocalQuickFix>(SuppressForBlockFix(first, last, lines.text(first)))
            problems += manager.createProblemDescriptor(
                file,
                lines.trimmedRange(first),
                ComarkBundle.message(if (block.frontMatter) "studio.inspection.unverified.frontmatter" else "studio.inspection.unverified.message"),
                ProblemHighlightType.WEAK_WARNING,
                isOnTheFly,
                *fixes,
            )
        }
        return problems.toTypedArray()
    }

    /** A fix on the lines `[first, last]` of a kept block; does nothing when the block's first line has changed since. */
    abstract class BlockFix(protected val first: Int, protected val last: Int, private val firstLine: String) : LocalQuickFix {
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val document = descriptor.psiElement?.containingFile?.viewProvider?.document ?: return
            if (last >= document.lineCount || line(document, first) != firstLine) return
            apply(document, descriptor.psiElement.containingFile)
        }

        protected abstract fun apply(document: Document, file: PsiFile)

        protected fun line(document: Document, line: Int): String =
            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))

        /** Replaces each line of the block for which [transform] returns a different text. */
        protected fun rewriteLines(document: Document, transform: (String) -> String) {
            for (line in last downTo first) {
                val old = line(document, line)
                val new = transform(old)
                if (new != old) document.replaceString(document.getLineStartOffset(line), document.getLineEndOffset(line), new)
            }
        }
    }

    /** Writes `<!-- @formatter:off -->` before the block and `<!-- @formatter:on -->` after it (the file's markers if set). */
    class SuppressForBlockFix(first: Int, last: Int, firstLine: String) : BlockFix(first, last, firstLine) {
        override fun getFamilyName(): String = ComarkBundle.message("studio.inspection.rewrite.fix.suppress")

        override fun apply(document: Document, file: PsiFile) {
            val markers = ComarkFormatterControl.markers(file)
            val indent = line(document, first).takeWhile { it == ' ' || it == '\t' }
            document.insertString(document.getLineEndOffset(last), "\n$indent<!-- ${markers.second} -->")
            document.insertString(document.getLineStartOffset(first), "$indent<!-- ${markers.first} -->\n")
        }
    }

    /** Turns `:---:`, `:---` and `---:` delimiter cells into `-----` (same width), as Studio writes the table. */
    class RemoveTableAlignmentFix(first: Int, last: Int, firstLine: String) : BlockFix(first, last, firstLine) {
        override fun getFamilyName(): String = ComarkBundle.message("studio.inspection.rewrite.fix.table.alignment")

        override fun apply(document: Document, file: PsiFile) = rewriteLines(document) { line ->
            if (DELIMITER_ROW.matches(line) && line.contains('|')) line.replace(':', '-') else line
        }

        companion object {
            private val DELIMITER_ROW = Regex("""^[ \t>]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
        }
    }

    /** Removes a trailing `{#id ...}` attribute list from the block's headings (Studio's text path drops it). */
    class RemoveHeadingIdFix(first: Int, last: Int, firstLine: String) : BlockFix(first, last, firstLine) {
        override fun getFamilyName(): String = ComarkBundle.message("studio.inspection.rewrite.fix.heading.id")

        override fun apply(document: Document, file: PsiFile) = rewriteLines(document) { line ->
            if (!HEADING.containsMatchIn(line)) line else ID.find(line)?.let { line.substring(0, it.range.first) } ?: line
        }

        companion object {
            private val HEADING = Regex("""^[ \t]{0,3}#{1,6}[ \t]""")
            private val ID = Regex("""[ \t]*\{[^{}]*#[^{}]*}[ \t]*$""")
        }
    }
}

/**
 * Emoji shortcodes (`:rocket:`) and character references (`&copy;`, `&#169;`) that Nuxt Studio silently turns into the characters
 * when it saves the file ([StudioStyleAdapter.conversions]); the fix writes what Studio writes. Active in the Nuxt Studio and
 * Custom styles, like [ComarkStudioRewriteInspection]. Code, link destinations, HTML, front matter and props are not checked.
 */
class ComarkStudioConversionInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        ComarkStudioAnalysis.styleFor(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val file = holder.file
        val text = file.viewProvider.contents
        if (text.length > ComarkStudioAnalysis.MAX_LENGTH) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val type = element.elementType
                if (type != MarkdownElementTypes.PARAGRAPH && type != MarkdownElementTypes.TABLE_CELL && type != ComarkTypes.INLINE_CONTENT &&
                    element !is MarkdownHeader
                ) return
                val range = element.textRange
                for (conversion in StudioStyleAdapter.conversions(text, range.startOffset, range.endOffset)) {
                    val leaf = file.findElementAt(conversion.start) ?: continue
                    if (ComponentUsages.isInsideNonContent(leaf)) continue
                    val key = if (conversion.kind == StudioConversion.Kind.EMOJI) "studio.inspection.conversion.emoji" else "studio.inspection.conversion.entity"
                    holder.registerProblem(
                        element,
                        TextRange(conversion.start, conversion.end).shiftLeft(range.startOffset),
                        ComarkBundle.message(key, conversion.token, conversion.replacement),
                        ReplaceFix(conversion.token, conversion.replacement),
                    )
                }
            }
        }
    }

    /** Replaces the token with what Studio writes for it. */
    class ReplaceFix(private val token: String, private val replacement: String) : LocalQuickFix {
        override fun getName(): String = ComarkBundle.message("studio.inspection.conversion.fix", replacement)

        override fun getFamilyName(): String = ComarkBundle.message("studio.inspection.conversion.fix.family")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            val document = element.containingFile?.viewProvider?.document ?: return
            val range = descriptor.textRangeInElement?.shiftRight(element.textRange.startOffset) ?: return
            if (range.endOffset > document.textLength || document.getText(range) != token) return
            document.replaceString(range.startOffset, range.endOffset, replacement)
        }
    }
}

/** Line access on a text whose lines end with `\n`. */
private class Lines(private val text: CharSequence) {
    private val starts: IntArray = run {
        val list = ArrayList<Int>()
        list += 0
        for (i in text.indices) if (text[i] == '\n') list += i + 1
        list.toIntArray()
    }

    val count: Int get() = starts.size

    private fun end(line: Int) = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length

    fun text(line: Int): String = text.substring(starts[line], end(line))

    /** The line without its leading and trailing whitespace (the whole line when it is blank). */
    fun trimmedRange(line: Int): TextRange {
        var start = starts[line]
        var end = end(line)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start < end) TextRange(start, end) else TextRange(starts[line], end(line))
    }
}
