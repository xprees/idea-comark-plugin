package cz.xprees.comark.format

import com.intellij.codeStyle.AbstractConvertLineSeparatorsAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import cz.xprees.comark.format.ComarkFormattingService.Companion.Edit
import cz.xprees.comark.psi.ComarkFile
import java.io.IOException
import java.util.BitSet

/**
 * Reformat Code in the Nuxt Studio based styles ([ComarkStyle.usesStudio]), for [ComarkFormattingService].
 *
 * The whole document is formatted once ([StudioStyleAdapter]) and the result is compared with the document line by line; each
 * differing stretch of lines becomes one edit, trimmed to the characters that actually differ, so carets, range markers and folds
 * outside the changes stay where they are:
 * - a partial reformat (a selection, "only VCS changed text", code inserted by completion) applies the changes of the top-level
 *   blocks that intersect the requested ranges, whole blocks at a time, and of the blank lines next to them;
 * - lines between formatter off/on markers are kept, and so is every block they touch, as if the engine had kept the block;
 * - a "whitespace only" reformat applies only edits that change whitespace.
 */
internal object ComarkStudioFormatting {
    private val LOG = logger<ComarkStudioFormatting>()

    /** The context the adapter needs for [file]: its name, collection and the style's options. */
    fun context(file: PsiFile, style: ComarkStyle): StudioStyleContext {
        val collection = try {
            if (file is ComarkFile) ComarkCollections.getInstance(file.project).collectionFor(file) else null
        } catch (e: RuntimeException) {
            LOG.debug("Cannot read the collection of ${file.name}", e)
            null
        }
        return StudioStyleContext(file.originalFile.name, collection, style.options)
    }

    /**
     * The edits turning [text] into [result] on the parts [ranges] select (see the class documentation), in document order.
     * [whole] says the ranges cover the whole document. A pure function, unit tested on its own.
     */
    fun computeEdits(
        text: CharSequence,
        result: StudioStyleResult,
        ranges: List<TextRange>,
        whole: Boolean,
        protectedLines: BitSet?,
        whitespaceOnly: Boolean = false,
    ): List<Edit> {
        val oldText = text.toString()
        val newText = result.text
        if (oldText == newText) return emptyList()
        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')
        val change = try {
            Diff.buildChanges(oldLines.toTypedArray(), newLines.toTypedArray())
        } catch (_: FilesTooBigForDiffException) {
            return emptyList()
        } ?: return emptyList()

        val oldStarts = lineStarts(oldText)
        val newStarts = lineStarts(newText)
        val selected = if (whole) null else selectedLines(oldLines, oldStarts, result.blocks, ranges, oldText.length)
        val keep = protectedLines?.let { protectedBlocks(it, result.blocks) }
        val edits = ArrayList<Edit>()
        fun allowed(c: Diff.Change): Boolean {
            val insideKept = keep != null && if (c.deleted > 0) touches(keep, c) else c.line0 > 0 && keep.get(c.line0 - 1) && keep.get(c.line0)
            return (selected == null || touches(selected, c)) && !insideKept
        }
        if (whitespaceOnly) {
            // lines are paired by their text without whitespace, so content Studio adds or removes elsewhere (front matter, say)
            // does not hide the whitespace changes next to it: each pair that differs, and each change that is all whitespace
            val keys = try {
                Diff.buildChanges(oldLines.map(::withoutWhitespace).toTypedArray(), newLines.map(::withoutWhitespace).toTypedArray())
            } catch (_: FilesTooBigForDiffException) {
                return emptyList()
            }
            var o = 0
            var n = 0
            var k: Diff.Change? = keys
            while (true) {
                val until = k?.line0 ?: oldLines.size
                while (o < until && n < newLines.size) {
                    val line = Diff.Change(o, n, 1, 1, null)
                    if (oldLines[o] != newLines[n] && allowed(line)) edit(oldText, newText, oldStarts, newStarts, line, true)?.let { edits += it }
                    o++
                    n++
                }
                val current = k ?: break
                if (allowed(current)) edit(oldText, newText, oldStarts, newStarts, current, true)?.let { edits += it }
                o = current.line0 + current.deleted
                n = current.line1 + current.inserted
                k = current.link
            }
            return edits
        }
        var c: Diff.Change? = change
        while (c != null) {
            if (allowed(c)) {
                if (c.deleted == c.inserted && c.deleted > 1) {
                    // line for line, so text between the changed parts of the lines (and markers on it) stays
                    for (k in 0 until c.deleted) {
                        val line = Diff.Change(c.line0 + k, c.line1 + k, 1, 1, null)
                        edit(oldText, newText, oldStarts, newStarts, line, whitespaceOnly)?.let { edits += it }
                    }
                } else {
                    edit(oldText, newText, oldStarts, newStarts, c, whitespaceOnly)?.let { edits += it }
                }
            }
            c = c.link
        }
        return edits
    }

    /** Whether [change] replaces a line of [lines], or (an insertion) sits next to one. */
    private fun touches(lines: BitSet, change: Diff.Change): Boolean {
        val first = change.line0
        if (change.deleted == 0) return first > 0 && lines.get(first - 1) || lines.get(first)
        val next = lines.nextSetBit(first)
        return next >= 0 && next < first + change.deleted
    }

    /** Lines of the blocks touched by [ranges], with the blank lines around them and the lines of the ranges themselves. */
    private fun selectedLines(lines: List<String>, lineStarts: IntArray, blocks: List<IntRange>, ranges: List<TextRange>, length: Int): BitSet {
        val starts = lineStarts.copyOf(lines.size)
        val affected = ComarkFormattingService.affectedLines(starts, length, ranges)
        val set = BitSet()
        for (line in affected) set.set(line)
        for (block in blocks) {
            if (affected.any { it in block }) set.set(block.first, block.last + 1)
        }
        // blank lines next to a selected block belong to it (separation between blocks)
        val withBlanks = set.clone() as BitSet
        var line = set.nextSetBit(0)
        while (line >= 0) {
            var up = line - 1
            while (up >= 0 && isBlank(lines[up]) && !set.get(up)) withBlanks.set(up--)
            var down = line + 1
            while (down < lines.size && isBlank(lines[down]) && !set.get(down)) withBlanks.set(down++)
            line = set.nextSetBit(line + 1)
        }
        // the final line break of the document follows its last line
        if (withBlanks.get(lines.size - 2)) withBlanks.set(lines.size - 1)
        return withBlanks
    }

    /** [protected] lines and every block that contains one. */
    private fun protectedBlocks(protected: BitSet, blocks: List<IntRange>): BitSet {
        val set = protected.clone() as BitSet
        for (block in blocks) {
            val next = protected.nextSetBit(block.first)
            if (next >= 0 && next <= block.last) set.set(block.first, block.last + 1)
        }
        return set
    }

    /** `lineStarts[i]` is where line i starts; one more entry, one past the end, marks the end of the last line's break. */
    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        starts += text.length + 1
        return starts.toIntArray()
    }

    /**
     * The edit for one change: old lines `[line0, line0 + deleted)` become new lines `[line1, line1 + inserted)`. Both texts are
     * read as if they ended with a line break, which is then taken off again, so changes at the end of the text come out right.
     */
    private fun edit(oldText: String, newText: String, oldStarts: IntArray, newStarts: IntArray, c: Diff.Change, whitespaceOnly: Boolean): Edit? {
        var start = oldStarts[c.line0]
        var end = oldStarts[c.line0 + c.deleted]
        var replacement = (newText + "\n").substring(newStarts[c.line1], newStarts[c.line1 + c.inserted])
        if (start > oldText.length) {
            // an insertion after the last line, which has no line break
            start = oldText.length
            end = oldText.length
            replacement = "\n" + replacement.dropLast(1)
        } else if (end > oldText.length) {
            // the change reaches the end: the replacement ends with the new text's (virtual) final line break, or, when it is
            // empty, the line break before [start] becomes the last one, so that one goes instead of the virtual one
            end = oldText.length
            if (replacement.isNotEmpty()) {
                replacement = replacement.dropLast(1)
            } else if (start > 0) {
                start--
            }
        }
        // trim to what differs
        val old = oldText.substring(start, end)
        val prefix = old.commonPrefixWith(replacement).length
        var suffix = 0
        while (suffix < old.length - prefix && suffix < replacement.length - prefix &&
            old[old.length - 1 - suffix] == replacement[replacement.length - 1 - suffix]
        ) suffix++
        val from = start + prefix
        val to = end - suffix
        val text = replacement.substring(prefix, replacement.length - suffix)
        if (from == to && text.isEmpty()) return null
        if (whitespaceOnly && withoutWhitespace(oldText.substring(from, to)) != withoutWhitespace(text)) return null
        return Edit(from, to, text)
    }

    private fun isBlank(line: String) = line.all { it == ' ' || it == '\t' }

    private fun withoutWhitespace(s: String) = s.filterNot { it == ' ' || it == '\t' || it == '\n' }

    // ---- line separators and BOM ------------------------------------------------------------------------------------------

    /**
     * Nuxt Studio writes files with `\n` line breaks and no byte order mark. After a whole-file reformat in a Studio based style,
     * a file with other line separators or a BOM is converted, after the reformat command, the way *File › File Properties ›
     * Line Separators* and *Remove BOM* do it (each an undoable command of its own that saves the file). Partial reformats leave
     * the file's encoding alone.
     */
    fun scheduleFileNormalization(project: Project, file: VirtualFile) {
        if (!needsNormalization(file)) return
        ApplicationManager.getApplication().invokeLater({ normalizeFile(project, file) }, ModalityState.nonModal(), project.disposed)
    }

    fun needsNormalization(file: VirtualFile): Boolean {
        val separator = file.detectedLineSeparator
        return file.isWritable && file.isInLocalFileSystemOrTemp() && (separator != null && separator != "\n" || file.bom != null)
    }

    private fun VirtualFile.isInLocalFileSystemOrTemp() = isInLocalFileSystem || fileSystem.protocol == "temp"

    /** Converts [file] to `\n` line separators and removes its BOM. */
    fun normalizeFile(project: Project, file: VirtualFile) {
        if (project.isDisposed || !file.isValid) return
        val separator = file.detectedLineSeparator
        if (separator != null && separator != "\n") AbstractConvertLineSeparatorsAction.changeLineSeparators(project, file, "\n")
        val bom = file.bom ?: return
        WriteCommandAction.writeCommandAction(project).withName("Remove BOM").run<IOException> {
            FileDocumentManager.getInstance().getCachedDocument(file)?.let { FileDocumentManager.getInstance().saveDocument(it) }
            val content = file.contentsToByteArray()
            if (content.size >= bom.size && content.copyOfRange(0, bom.size).contentEquals(bom)) {
                file.bom = null
                file.setBinaryContent(content.copyOfRange(bom.size, content.size))
            } else {
                file.bom = null
            }
        }
    }
}
