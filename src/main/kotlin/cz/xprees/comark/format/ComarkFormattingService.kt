package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AbstractDocumentFormattingService
import com.intellij.formatting.service.FormattingNotificationService
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.util.DocumentUtil
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings

/**
 * Makes Code | Reformat Code (and reformat on save, and every other caller of `CodeStyleManager.reformat*`) use
 * [ComarkFormatter], through [ComarkDocumentFormatter] (which keeps content monarch-mdc would change), for Comark files while
 * [ComarkSettings.formatterEnabled] is on. When the file's code style selects a Nuxt Studio based style
 * ([ComarkStyle.usesStudio], *Code Style › Comark › Style*), the document is formatted by [StudioStyleAdapter] instead, applied
 * as described in [ComarkStudioFormatting]; everything below is about the VS Code style. With the setting off, [canFormat] declines and the platform falls back to its
 * formatting-model formatter, which for Comark files is the Markdown plugin's ([ComarkFormattingModelBuilder] hands over to it).
 *
 * Why a [FormattingService] and not a `FormattingModelBuilder`: the formatter is a text-to-text port of the VS Code extension's
 * (so both editors produce the same result), not a block model. [AbstractDocumentFormattingService] is the platform's base for
 * exactly that - the text is edited directly in the document, synchronously, inside the reformat command. Its sibling
 * `AsyncDocumentFormattingService` exists for external processes (it saves the file, runs in the background and swaps the whole
 * text), which an in-process formatter that takes milliseconds does not need.
 *
 * How the result is applied:
 * - The formatter maps input line *n* to output line *n*, so the edit is computed line by line and only lines that intersect the
 *   requested ranges are touched (selection reformat, "reformat only VCS changed text").
 * - Within a line only the indentation and the trailing whitespace differ, and each is replaced separately with the smallest
 *   possible edit, so the line's text, carets, range markers and the undo history stay where they are.
 * - For a partial reformat only the lines up to [ComarkFormatter.MAX_PASSES] + 2 past the range are formatted: a line's result
 *   depends on at most one line below it per pass, so this gives the same result for the range and keeps a problem further down
 *   the file from blocking it.
 * - The indentation step is the Comark code style's indent size (Settings | Editor | Code Style | Comark, 2 by default like
 *   monarch-mdc), unless EditorConfig or indent detection set one for the file ([ComarkIndent]). The formatter always indents
 *   with spaces, like the original (see [ComarkCodeStyleSettingsProvider] for why).
 * - What the formatter cannot see in the text - components inside list items, indented code blocks, code fences - comes from
 *   the PSI ([ComarkDocumentStructure]); see [ComarkDocumentFormatter] for what it does with it.
 * - Afterwards the platform's post-format processors run on the formatted ranges, as they do after model-based formatting. This
 *   keeps the Markdown plugin's table formatting for top level tables (it only visits the file's direct children, so tables
 *   inside components are left alone, as by the Markdown formatter). Its block quote processor never applies to Comark files,
 *   with or without this service, as it only accepts the plain Markdown language.
 *
 * When the formatter fails the way the original does ([ComarkFormatterException]), the document is left unchanged and the
 * problem is reported through the platform's formatting notifications, pointing at the offending line.
 */
class ComarkFormattingService : AbstractDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = FEATURES

    override fun canFormat(file: PsiFile): Boolean =
        file is ComarkFile && ComarkSettings.getInstance().formatterEnabled && ComarkFormatterControl.isFormattingAllowed(file)

    override fun formatDocument(
        document: Document,
        formattingRanges: List<TextRange>,
        formattingContext: FormattingContext,
        canChangeWhiteSpaceOnly: Boolean,
        quickFormat: Boolean,
    ) {
        val file = formattingContext.containingFile
        val ranges = formattingRanges.filter { it.startOffset <= document.textLength }
        if (ranges.isEmpty()) return
        val style = ComarkStyle.of(file)
        if (style.usesStudio) {
            formatStudio(document, ranges, formattingContext, canChangeWhiteSpaceOnly, style)
            return
        }
        val indentSize = ComarkIndent.indentSize(file)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        val structure = ComarkDocumentStructure.of(file)

        val allEdits = try {
            computeEdits(
                document.charsSequence, ranges, indentSize, canChangeWhiteSpaceOnly, ProgressManager::checkCanceled,
                structure,
            )
        } catch (e: ComarkFormatterException) {
            reportFailure(document, formattingContext, e)
            return
        }
        // lines between formatter off/on markers stay as they are
        val protected = ComarkFormatterControl.protectedLines(file, document.charsSequence)
        val edits = if (protected == null) allEdits else allEdits.filter { edit ->
            val first = document.getLineNumber(edit.start)
            val last = document.getLineNumber(maxOf(edit.start, edit.end - 1).coerceAtMost(document.textLength))
            protected.nextSetBit(first).let { it < 0 || it > last }
        }
        val markers = ranges.map { document.createRangeMarker(it).apply { isGreedyToRight = true } }
        try {
            if (edits.isNotEmpty()) applyEdits(document, edits)
            runPostFormatProcessors(file, document, markers, canChangeWhiteSpaceOnly)
        } finally {
            markers.forEach(RangeMarker::dispose)
        }
    }

    /** Reformat in a Nuxt Studio based style, see [ComarkStudioFormatting]. */
    private fun formatStudio(
        document: Document,
        ranges: List<TextRange>,
        context: FormattingContext,
        whitespaceOnly: Boolean,
        style: ComarkStyle,
    ) {
        val file = context.containingFile
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        val text = document.charsSequence
        val protected = ComarkFormatterControl.protectedLines(file, text)
        val result = try {
            // the engine keeps the blocks between formatter off/on markers verbatim, so its output already holds them
            StudioStyleAdapter.format(text.toString(), ComarkStudioFormatting.context(file, style), protected?.stream()?.toArray()?.toSet().orEmpty())
        } catch (e: RuntimeException) {
            if (e is ControlFlowException) throw e
            LOG.warn("Nuxt Studio style failed on ${file.name}", e)
            null
        }
        if (result == null) {
            FormattingNotificationService.getInstance(context.project).reportError(
                NOTIFICATION_GROUP,
                ComarkBundle.message("formatter.failed.title"),
                ComarkBundle.message("formatter.failed.studio"),
            )
            return
        }
        ProgressManager.checkCanceled()
        val whole = ranges.any { it.startOffset <= 0 && it.endOffset >= document.textLength }
        val edits = ComarkStudioFormatting.computeEdits(text, result, ranges, whole, protected, whitespaceOnly)
        val markers = ranges.map { document.createRangeMarker(it).apply { isGreedyToRight = true } }
        try {
            if (edits.isNotEmpty()) applyEdits(document, edits)
            // Studio aligns tables itself; the Markdown plugin's table formatting would undo that
            runPostFormatProcessors(file, document, markers, whitespaceOnly, skipMarkdown = true)
        } finally {
            markers.forEach(RangeMarker::dispose)
        }
        if (whole) file.originalFile.virtualFile?.let { ComarkStudioFormatting.scheduleFileNormalization(file.project, it) }
    }

    private fun reportFailure(document: Document, context: FormattingContext, e: ComarkFormatterException) {
        LOG.debug("Comark formatter failed on ${context.containingFile.name}", e)
        val line = e.line.coerceIn(0, maxOf(document.lineCount - 1, 0))
        val offset = if (document.lineCount == 0) 0 else document.getLineStartOffset(line)
        FormattingNotificationService.getInstance(context.project).reportErrorAndNavigate(
            NOTIFICATION_GROUP,
            ComarkBundle.message("formatter.failed.title"),
            ComarkBundle.message("formatter.failed.negative.indent", e.line + 1),
            context,
            offset,
        )
    }

    /**
     * Commits the edits so the PSI matches the text and lets the registered [PostFormatProcessor]s process each formatted range,
     * as `CoreFormattingService` does after model-based formatting.
     */
    private fun runPostFormatProcessors(
        file: PsiFile,
        document: Document,
        markers: List<RangeMarker>,
        whitespaceOnly: Boolean,
        skipMarkdown: Boolean = false,
    ) {
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        val settings = CodeStyle.getSettings(file)
        val processors = PostFormatProcessor.EP_NAME.extensionList.filter {
            (!whitespaceOnly || it.isWhitespaceOnly) && !(skipMarkdown && it.javaClass.name.startsWith(MARKDOWN_PACKAGE))
        }
        for (marker in markers) {
            if (!marker.isValid || !file.isValid) continue
            var range = marker.textRange
            for (processor in processors) range = processor.processText(file, range, settings)
        }
    }

    companion object {
        private val LOG = logger<ComarkFormattingService>()

        private const val MARKDOWN_PACKAGE = "org.intellij.plugins.markdown."

        /** Registered in `comark-formatter.xml`. */
        const val NOTIFICATION_GROUP: String = "Comark Formatter"

        /**
         * Explicit reformatting of whole files and of fragments, and ad hoc formatting (code inserted by completion or
         * refactorings): Comark content should look the same however it got there, as in VS Code, where inserting a component
         * also formats the document.
         */
        private val FEATURES = setOf(FormattingService.Feature.FORMAT_FRAGMENTS, FormattingService.Feature.AD_HOC_FORMATTING)

        /** Above this many changed lines the document is updated in bulk mode, which defers listeners until the end. */
        private const val BULK_EDIT_THRESHOLD = 100

        /** One replacement of `[start, end)` of the document text by [text]. */
        internal class Edit(val start: Int, val end: Int, val text: String)

        /**
         * The edits that turn [text] into its formatted form on the lines intersecting [ranges] (see [affectedLines]), in
         * document order. A pure function, unit tested on its own.
         *
         * @throws ComarkFormatterException when the formatter fails
         */
        internal fun computeEdits(
            text: CharSequence,
            ranges: List<TextRange>,
            tabSize: Int,
            whitespaceOnly: Boolean = false,
            checkCanceled: () -> Unit = {},
            structure: DocumentStructure = DocumentStructure.EMPTY,
        ): List<Edit> {
            val lineStarts = lineStarts(text)
            val lineCount = lineStarts.size
            val affected = affectedLines(lineStarts, text.length, ranges)
            val lastAffected = affected.lastOrNull() ?: return emptyList()

            // Format only as far as the result of the last affected line can depend on (see the class documentation). A component
            // inside a list item is formatted as a whole, which may reach further down.
            val lastNeeded = lastAffected + ComarkFormatter.MAX_PASSES + 2
            val formattedWhole = lastNeeded >= lineCount - 1 || structure.listItemComponents.isNotEmpty()
            val input = if (formattedWhole) text.toString() else text.subSequence(0, lineStarts[lastNeeded + 1] - 1).toString()
            val formatted = ComarkDocumentFormatter.format(input, tabSize, structure, checkCanceled)

            val edits = ArrayList<Edit>()
            for (line in affected) {
                checkCanceled()
                val start = lineStarts[line]
                val end = if (line + 1 < lineCount) lineStarts[line + 1] - 1 else text.length
                lineEdits(text, start, end, formatted[line], whitespaceOnly, edits)
            }
            // The formatter ends the file with a line break; it adds a final empty line when there is none
            if (formattedWhole && lastAffected == lineCount - 1 && formatted.size > lineCount) {
                edits += Edit(text.length, text.length, "\n")
            }
            return edits
        }

        /** Start offsets of the lines of [text] (separated by `\n`, as in IntelliJ documents). */
        private fun lineStarts(text: CharSequence): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            for (i in text.indices) if (text[i] == '\n') starts += i + 1
            return starts.toIntArray()
        }

        /**
         * Lines touched by [ranges]: a non-empty range affects the lines whose text or line break it overlaps (so a selection
         * of whole lines, ending at the start of the next line, does not reach that line); an empty range affects its line.
         */
        internal fun affectedLines(lineStarts: IntArray, textLength: Int, ranges: List<TextRange>): List<Int> {
            val result = sortedSetOf<Int>()
            for (range in ranges) {
                val first = lineOf(lineStarts, range.startOffset.coerceIn(0, textLength))
                val last = if (range.isEmpty) first else lineOf(lineStarts, (range.endOffset - 1).coerceIn(0, textLength))
                for (line in first..maxOf(first, last)) result += line
            }
            return result.toList()
        }

        private fun lineOf(lineStarts: IntArray, offset: Int): Int {
            val index = lineStarts.binarySearch(offset)
            return if (index >= 0) index else -index - 2
        }

        /**
         * Edits turning the line `text[start, end)` into [formatted]. The formatter keeps a line's content between its leading
         * and trailing whitespace (`formatted` is spaces or tabs, the content, and possibly kept trailing whitespace), so the two
         * ends are edited separately and the content itself is never replaced.
         */
        private fun lineEdits(text: CharSequence, start: Int, end: Int, formatted: String, whitespaceOnly: Boolean, out: MutableList<Edit>) {
            val original = text.subSequence(start, end)
            if (original.contentEquals(formatted)) return
            val contentStart = start + JsText.leadingWhitespace(original)
            val newIndent = formatted.takeWhile { it == ' ' || it == '\t' }
            val contentLength = JsText.trim(original.toString()).length
            val contentEnd = contentStart + contentLength
            if (contentEnd > end || newIndent.length + contentLength > formatted.length ||
                !text.regionMatches(contentStart, formatted, newIndent.length, contentLength)
            ) {
                // Not the shape the formatter produces; never expected, but stay correct: replace the differing middle
                val prefix = original.commonPrefixWith(formatted).length
                val suffix = minOf(original.length, formatted.length).let { max ->
                    var n = 0
                    while (n < max - prefix && original[original.length - 1 - n] == formatted[formatted.length - 1 - n]) n++
                    n
                }
                addEdit(out, start + prefix, end - suffix, formatted.substring(prefix, formatted.length - suffix), text, whitespaceOnly)
                return
            }
            // Indentation: keep the common prefix (e.g. reindenting from 4 to 2 spaces deletes two)
            val oldIndent = text.subSequence(start, contentStart)
            val keep = oldIndent.commonPrefixWith(newIndent).length
            addEdit(out, start + keep, contentStart, newIndent.substring(keep), text, whitespaceOnly)
            // Trailing whitespace (kept, in part, for hard line breaks)
            val newTrailing = formatted.substring(newIndent.length + contentLength)
            val oldTrailing = text.subSequence(contentEnd, end)
            if (oldTrailing.startsWith(newTrailing)) {
                addEdit(out, contentEnd + newTrailing.length, end, "", text, whitespaceOnly)
            } else {
                addEdit(out, contentEnd, end, newTrailing, text, whitespaceOnly)
            }
        }

        private fun addEdit(out: MutableList<Edit>, start: Int, end: Int, replacement: String, text: CharSequence, whitespaceOnly: Boolean) {
            if (start == end && replacement.isEmpty()) return
            if (text.subSequence(start, end).contentEquals(replacement)) return
            // "whitespace only" callers (e.g. quick fixes) must not lose characters the formatter merely trims like whitespace
            // (no-break spaces, the BOM, ...)
            if (whitespaceOnly && !isPlainWhitespace(text.subSequence(start, end))) return
            out += Edit(start, end, replacement)
        }

        private fun isPlainWhitespace(s: CharSequence) = s.all { it == ' ' || it == '\t' }

        /** Applies [edits] (in document order) from the end, so earlier offsets stay valid. */
        internal fun applyEdits(document: Document, edits: List<Edit>) {
            val apply = Runnable {
                for (edit in edits.asReversed()) document.replaceString(edit.start, edit.end, edit.text)
            }
            if (edits.size > BULK_EDIT_THRESHOLD) DocumentUtil.executeInBulk(document, apply) else apply.run()
        }
    }
}
