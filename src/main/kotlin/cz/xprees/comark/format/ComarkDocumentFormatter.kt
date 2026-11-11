package cz.xprees.comark.format

import cz.xprees.comark.format.FormatterExpressions.isComponentEnd
import cz.xprees.comark.format.FormatterExpressions.isComponentStart

/**
 * What Reformat Code, Auto-Indent Lines and Enter apply to a Comark document: [ComarkFormatter] (the port of the VS Code
 * extension's formatter, monarch-mdc) with a few deliberate deviations where the original changes what the document *says*, not
 * only how it is laid out. Everywhere else the result is byte-identical to the original's; `ComarkFormattingPipelineTest` checks
 * that on the recorded differential corpus, skipping exactly the inputs that hit one of the deviations below.
 *
 * The deviations, each applied only where monarch-mdc would change the document's content or structure:
 * 1. **Components inside list items** ([DocumentStructure.ListItemComponent]): monarch-mdc indents a component opener by
 *    component depth only, so `- item\n  ::block\n  text\n  ::` comes out as `- item\n::block\ntext\n::`, which moves the
 *    component out of the list item (Comark spec: `component-block-in-list-item`). Such a component is formatted as a document
 *    of its own and placed at the list item's content column (its opener keeps its offset from the list item's marker line).
 *    The rest of the document is formatted with the component's lines blank, so the list around it is not interrupted either.
 * 2. **Indented code blocks** ([DocumentStructure.CodeBlock]): monarch-mdc re-indents every line to the component's content
 *    indentation, which turns a four-space indented code block into paragraph text. Code block lines keep their indentation
 *    relative to the enclosing component's opener (unchanged at the top level), and their text, trailing whitespace included.
 * 3. **Tabs in code fences** ([DocumentStructure.Fence]): monarch-mdc counts a tab as one column and re-emits spaces, so a
 *    Makefile's `\tgo build` becomes ` go build`. A fence content line whose indentation beyond the fence's own contains
 *    anything but spaces keeps that part as it is; only the fence's indentation is changed.
 * 4. **Hard line breaks**: monarch-mdc strips all trailing whitespace, including the two or more spaces that make a Markdown
 *    hard line break. Two or more trailing spaces are kept when the next line continues the text (it is not blank and not a
 *    component marker).
 *
 * Like the original, indentation is always written with spaces: Markdown counts a leading tab as four columns, so a nested
 * component indented with a tab would become an indented code block.
 *
 * Every input line still maps to the output line with the same number (a final empty line may be added), which callers rely on.
 */
internal object ComarkDocumentFormatter {
    /**
     * Formats [text] and returns its lines (one more than [text] has when a final line break is added).
     *
     * @throws ComarkFormatterException where monarch-mdc throws (for a component inside a list item, where it would throw on
     *   that component formatted on its own)
     */
    fun format(
        text: String,
        tabSize: Int,
        structure: DocumentStructure = DocumentStructure.EMPTY,
        checkCanceled: () -> Unit = {},
    ): List<String> {
        val original = text.split('\n')
        val formatted = formatBlock(original, 0, structure.listItemComponents, tabSize, checkCanceled)
        val codeLines = HashMap<Int, DocumentStructure.CodeBlock>()
        for (block in structure.codeBlocks) for (line in block.lines) if (line in original.indices) codeLines[line] = block
        val fenceLines = HashMap<Int, DocumentStructure.Fence>()
        for (fence in structure.fences) for (line in fence.contentLines) if (line in original.indices) fenceLines[line] = fence

        val result = ArrayList<String>(formatted.size)
        for (i in formatted.indices) {
            checkCanceled()
            val code = if (i < original.size) codeLines[i] else null
            val fence = if (i < original.size) fenceLines[i] else null
            result += when {
                i >= original.size -> formatted[i]
                code != null -> codeBlockLine(original, formatted, i, code.anchorLine)
                fence != null -> keepHardLineBreak(original, i, fenceContentLine(original, formatted, i, fence.openLine))
                else -> keepHardLineBreak(original, i, formatted[i])
            }
        }
        return result
    }

    /**
     * Deviation 1: formats [lines] (document lines from [offset] on) with the components among [components] that lie in them
     * formatted on their own.
     */
    private fun formatBlock(
        lines: List<String>,
        offset: Int,
        components: List<DocumentStructure.ListItemComponent>,
        tabSize: Int,
        checkCanceled: () -> Unit,
    ): MutableList<String> {
        val inBlock = components.filter { it.itemLine >= offset && it.openLine > it.itemLine && it.closeLine < offset + lines.size }
        val outermost = inBlock.filter { c -> inBlock.none { it !== c && it.openLine <= c.openLine && c.closeLine <= it.closeLine } }
        val input = if (outermost.isEmpty()) lines else lines.toMutableList().also { blanked ->
            for (c in outermost) for (l in c.openLine..c.closeLine) blanked[l - offset] = ""
        }
        val out = formatLines(input, offset, tabSize, checkCanceled).toMutableList()
        for (c in outermost) {
            val open = c.openLine - offset
            val dedent = JsText.leadingWhitespace(lines[open])
            val own = (open..c.closeLine - offset).map { l -> lines[l].substring(minOf(dedent, JsText.leadingWhitespace(lines[l]))) }
            val inner = inBlock.filter { it !== c && it.openLine > c.openLine && it.closeLine <= c.closeLine }
            val ownOut = formatBlock(own, c.openLine, inner, tabSize, checkCanceled)
            val item = c.itemLine - offset
            val column = maxOf(0, leadingSpaces(out[item]) + dedent - JsText.leadingWhitespace(lines[item]))
            for (k in own.indices) out[open + k] = if (ownOut[k].isEmpty()) "" else " ".repeat(column) + ownOut[k]
        }
        return out
    }

    /** [ComarkFormatter.format] on [lines], with failures reported at document line numbers ([offset] is the first line's). */
    private fun formatLines(lines: List<String>, offset: Int, tabSize: Int, checkCanceled: () -> Unit): List<String> = try {
        ComarkFormatter.format(lines.joinToString("\n"), tabSize, isFormatOnType = false, checkCanceled = checkCanceled).split('\n')
    } catch (e: ComarkFormatterException) {
        if (offset == 0) throw e
        throw ComarkFormatterException(e.line + offset, e.indent)
    }

    /** Deviation 2: the code block line [i], moved with its component's opener [anchor] (left alone at the top level). */
    private fun codeBlockLine(original: List<String>, formatted: List<String>, i: Int, anchor: Int): String {
        val line = original[i]
        if (anchor !in original.indices) return line
        if (JsText.trim(line).isEmpty()) return ""
        val anchorIndent = indentOf(original[anchor])
        if (!line.startsWith(anchorIndent)) return line
        return " ".repeat(leadingSpaces(formatted[anchor])) + line.substring(anchorIndent.length)
    }

    /**
     * Deviation 3: the code fence content line [i] of the fence opened on line [open]: the formatter's result, unless the
     * indentation beyond the fence's own holds something other than spaces.
     */
    private fun fenceContentLine(original: List<String>, formatted: List<String>, i: Int, open: Int): String {
        val line = original[i]
        val content = JsText.trim(line)
        if (content.isEmpty() || open !in original.indices) return formatted[i]
        val indent = indentOf(line)
        val fenceIndent = indentOf(original[open])
        if (!indent.startsWith(fenceIndent)) return formatted[i]
        val relative = indent.substring(fenceIndent.length)
        if (relative.all { it == ' ' }) return formatted[i]
        return " ".repeat(leadingSpaces(formatted[open])) + relative + content
    }

    /** Deviation 4: [line] (the result for line [i]) with the original's hard line break, if it had one the result lost. */
    private fun keepHardLineBreak(original: List<String>, i: Int, line: String): String {
        val source = original[i]
        val content = JsText.trim(source)
        if (content.isEmpty() || i + 1 >= original.size) return line
        val trailing = source.substring(JsText.leadingWhitespace(source) + content.length)
        if (trailing.length < 2 || trailing.any { it != ' ' }) return line
        val next = original[i + 1]
        if (JsText.trim(next).isEmpty() || isComponentStart(next) || isComponentEnd(next)) return line
        return if (line.endsWith(content)) line + trailing else line
    }

    private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.length

    private fun indentOf(line: String): String = line.substring(0, JsText.leadingWhitespace(line))
}

/**
 * Facts about a Comark document that [ComarkDocumentFormatter] needs and the text based [ComarkFormatter] cannot see, taken from
 * the PSI ([ComarkDocumentStructure.of]). Line numbers are 0-based document lines.
 */
internal class DocumentStructure(
    val listItemComponents: List<ListItemComponent> = emptyList(),
    val codeBlocks: List<CodeBlock> = emptyList(),
    val fences: List<Fence> = emptyList(),
) {
    /** A closed block component directly inside a list item: lines [openLine]..[closeLine]; the item's marker is on [itemLine]. */
    class ListItemComponent(val openLine: Int, val closeLine: Int, val itemLine: Int)

    /** An indented code block on [lines]; [anchorLine] is the opener line of the innermost enclosing component, or -1. */
    class CodeBlock(val lines: IntRange, val anchorLine: Int)

    /** A code fence opened on [openLine], with content on [contentLines]. */
    class Fence(val openLine: Int, val contentLines: IntRange)

    companion object {
        val EMPTY = DocumentStructure()
    }
}
