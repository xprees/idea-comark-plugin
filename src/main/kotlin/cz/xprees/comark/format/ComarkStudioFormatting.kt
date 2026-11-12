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
 * - a partial reformat (a selection, "only VCS changed text", code inserted by completion) replaces the top-level blocks that
 *   intersect the requested ranges with their formatted lines (the engine's block mapping, [StudioStyleResult.blockMap]), and
 *   the lines between them and their neighbours with the formatted separation; other blocks, and front matter the style would
 *   add, are left alone;
 * - lines between formatter off/on markers are kept, and so is every block they touch: the engine keeps those blocks verbatim;
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
     *
     * [protectedLines] (formatter off/on regions) must already be verbatim in [result] (the engine was given them): a change
     * that would still touch them means the line diff paired lines oddly, and applying the other changes alone could duplicate
     * or lose text, so then nothing is applied.
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
        val newText = (if (whole) result.text else partialTarget(oldText, result, ranges)) ?: return emptyList()
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
        val keep = protectedLines?.let { protectedBlocks(it, result.blocks) }
        if (keep != null) {
            var c: Diff.Change? = change
            while (c != null) {
                val insideKept = if (c.deleted > 0) touches(keep, c) else c.line0 > 0 && keep.get(c.line0 - 1) && keep.get(c.line0)
                if (insideKept) {
                    LOG.debug("A Nuxt Studio style change touches lines between formatter off/on markers; nothing applied")
                    return emptyList()
                }
                c = c.link
            }
        }
        val edits = ArrayList<Edit>()
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
                    if (oldLines[o] != newLines[n]) edit(oldText, newText, oldStarts, newStarts, line, true)?.let { edits += it }
                    o++
                    n++
                }
                val current = k ?: break
                edit(oldText, newText, oldStarts, newStarts, current, true)?.let { edits += it }
                o = current.line0 + current.deleted
                n = current.line1 + current.inserted
                k = current.link
            }
            return edits
        }
        var c: Diff.Change? = change
        while (c != null) {
            if (c.deleted == c.inserted && c.deleted > 1) {
                // line for line, so text between the changed parts of the lines (and markers on it) stays
                for (k in 0 until c.deleted) {
                    val line = Diff.Change(c.line0 + k, c.line1 + k, 1, 1, null)
                    edit(oldText, newText, oldStarts, newStarts, line, false)?.let { edits += it }
                }
            } else {
                edit(oldText, newText, oldStarts, newStarts, c, false)?.let { edits += it }
            }
            c = c.link
        }
        return edits
    }

    /**
     * For a partial reformat: [old] with the top-level blocks that [ranges] touch replaced by their formatted lines, and the lines
     * between such a block and its neighbours replaced by the formatted separation; every other block stays as written. Front
     * matter the style adds is left out (it belongs to a whole-file reformat). `null` when the block mapping is unusable.
     */
    private fun partialTarget(old: String, result: StudioStyleResult, ranges: List<TextRange>): String? {
        val units = result.blockMap.filter { !it.input.isEmpty() && !it.output.isEmpty() }
        if (units.isEmpty()) return null
        for (k in 1 until units.size) {
            if (units[k].input.first <= units[k - 1].input.last || units[k].output.first <= units[k - 1].output.last) return null
        }
        val oldLines = old.split('\n')
        val newLines = result.text.split('\n')
        if (units.last().input.last >= oldLines.size || units.last().output.last >= newLines.size) return null
        val starts = lineStarts(old).copyOf(oldLines.size)
        val affected = ComarkFormattingService.affectedLines(starts, old.length, ranges).toHashSet()
        val selected = units.map { u -> u.input.any { it in affected } }
        if (selected.none { it }) return old
        val out = ArrayList<String>()
        fun copy(lines: List<String>, from: Int, until: Int) {
            for (i in from until until) out += lines[i]
        }
        copy(oldLines, 0, units[0].input.first)
        for ((k, u) in units.withIndex()) {
            if (k > 0) {
                val p = units[k - 1]
                if (selected[k] || selected[k - 1]) copy(newLines, p.output.last + 1, u.output.first) else copy(oldLines, p.input.last + 1, u.input.first)
            }
            if (selected[k]) copy(newLines, u.output.first, u.output.last + 1) else copy(oldLines, u.input.first, u.input.last + 1)
        }
        val last = units.last()
        if (selected.last()) copy(newLines, last.output.last + 1, newLines.size) else copy(oldLines, last.input.last + 1, oldLines.size)
        return out.joinToString("\n")
    }

    /** Whether [change] replaces a line of [lines], or (an insertion) sits next to one. */
    private fun touches(lines: BitSet, change: Diff.Change): Boolean {
        val first = change.line0
        if (change.deleted == 0) return first > 0 && lines.get(first - 1) || lines.get(first)
        val next = lines.nextSetBit(first)
        return next >= 0 && next < first + change.deleted
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
