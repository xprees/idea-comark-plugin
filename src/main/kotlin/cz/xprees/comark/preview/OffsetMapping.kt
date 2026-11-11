package cz.xprees.comark.preview

/**
 * Maps offsets of a generated text back to the text it was generated from.
 *
 * The preview renders Markdown snippets that are rewritten copies of parts of the document (indentation removed, inline
 * Comark syntax replaced by HTML). The Markdown renderer marks every element with its source range (`md-src-pos`), which the
 * preview uses to keep editor and preview scrolled to the same place, so those ranges have to be translated back to document
 * offsets. Copied pieces map one to one; generated pieces map to the position of the syntax they replace.
 */
internal class OffsetMapping {
    private var targetStarts = IntArray(8)
    private var sourceStarts = IntArray(8)
    private var lengths = IntArray(8)
    private var copied = BooleanArray(8)
    private var size = 0

    /** Length of the generated text described so far. */
    var length = 0
        private set

    /** The next [count] generated characters are a copy of the source starting at [sourceStart]. */
    fun copy(sourceStart: Int, count: Int) {
        if (count > 0) add(sourceStart, count, copied = true)
    }

    /** The next [count] generated characters don't come from the source; they map to [sourceAnchor]. */
    fun generated(sourceAnchor: Int, count: Int) {
        if (count > 0) add(sourceAnchor, count, copied = false)
    }

    private fun add(sourceStart: Int, count: Int, copied: Boolean) {
        if (size == targetStarts.size) {
            val capacity = size * 2
            targetStarts = targetStarts.copyOf(capacity)
            sourceStarts = sourceStarts.copyOf(capacity)
            lengths = lengths.copyOf(capacity)
            this.copied = this.copied.copyOf(capacity)
        }
        targetStarts[size] = length
        sourceStarts[size] = sourceStart
        lengths[size] = count
        this.copied[size] = copied
        size++
        length += count
    }

    /** Source offset of the generated offset [offset] (a start offset). */
    fun source(offset: Int): Int {
        if (size == 0) return 0
        if (offset >= length) return sourceEndOf(size - 1)
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (targetStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return if (copied[lo]) sourceStarts[lo] + (offset - targetStarts[lo]) else sourceStarts[lo]
    }

    /** Source offset of the end offset [offset]: just after the source of the character before it. */
    fun sourceEnd(offset: Int): Int = if (offset <= 0) source(0) else source(offset - 1) + 1

    private fun sourceEndOf(segment: Int) = if (copied[segment]) sourceStarts[segment] + lengths[segment] else sourceStarts[segment]
}
