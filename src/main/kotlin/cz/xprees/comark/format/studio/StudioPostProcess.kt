package cz.xprees.comark.format.studio

/**
 * A port of nuxt-studio 1.8.0 `padBlankLinesInComponents` (`dist/module/runtime/utils/document/legacy.js`), which Studio
 * runs on comark's output before saving.
 *
 * Only an outermost `::component` block that contains an indented code fence (one in a list item or a nested component) is
 * changed: each of its blank lines becomes the indentation of the innermost component around it, or, inside a fence, the
 * fence's indentation, so that the fence keeps its content. Lines between the props delimiters `---` right after an opening
 * line are skipped.
 */
object StudioPostProcess {
    private val FENCE_OPENING = Regex("^( *(?:(?:[-*+]|[0-9]{1,9}[.)]) +)?)(`{3,}|~{3,})")
    private val COMPONENT_OPENING = Regex("^ *:{2,}[a-zA-Z]")
    private val COMPONENT_CLOSING = Regex(" *:{2,} *")
    private val PROPS_DELIMITER = Regex(" *--- *")

    private class Fence(val marker: Char, val length: Int, val indent: String) {
        /** `^ *\<marker>{<length>,} *$` */
        fun closes(line: String): Boolean {
            var i = 0
            while (i < line.length && line[i] == ' ') i++
            var run = 0
            while (i < line.length && line[i] == marker) { i++; run++ }
            if (run < length) return false
            while (i < line.length && line[i] == ' ') i++
            return i == line.length
        }
    }

    private class Blank(val index: Int, val indent: String)

    fun padBlankLinesInComponents(markdown: String): String {
        val lines = markdown.split("\n").toMutableList()
        val components = ArrayList<String>()
        var blanks = ArrayList<Blank>()
        var hasIndentedFence = false
        var fence: Fence? = null
        var inProps = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val blank = StudioJs.trim(line).isEmpty()
            if (fence != null) {
                if (fence.closes(line)) fence = null
                else if (blank && components.isNotEmpty()) blanks += Blank(i, fence.indent)
                i++
                continue
            }
            if (inProps) {
                inProps = !PROPS_DELIMITER.matches(line)
                i++
                continue
            }
            val opening = FENCE_OPENING.find(line)
            if (opening != null) {
                val marker = opening.groupValues[2]
                fence = Fence(marker[0], marker.length, " ".repeat(opening.groupValues[1].length))
                hasIndentedFence = hasIndentedFence || (components.isNotEmpty() && opening.groupValues[1].isNotEmpty())
                i++
                continue
            }
            if (COMPONENT_OPENING.containsMatchIn(line)) {
                components += line.takeWhile { it == ' ' }
                if (PROPS_DELIMITER.matches(lines.getOrElse(i + 1) { "" })) {
                    inProps = true
                    i++
                }
                i++
                continue
            }
            if (COMPONENT_CLOSING.matches(line) && components.isNotEmpty()) {
                components.removeAt(components.size - 1)
                if (components.isNotEmpty()) {
                    i++
                    continue
                }
                if (hasIndentedFence) for (b in blanks) lines[b.index] = b.indent
                blanks = ArrayList()
                hasIndentedFence = false
                i++
                continue
            }
            if (blank && components.isNotEmpty()) blanks += Blank(i, components.last())
            i++
        }
        return lines.joinToString("\n")
    }
}
