@file:OptIn(org.intellij.markdown.ExperimentalApi::class)

package cz.xprees.comark.parser

import com.intellij.openapi.progress.ProgressManager
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.ASTNodeImpl
import org.intellij.markdown.ast.CompositeASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.IElementType as MdType

/**
 * Builds one intellij-markdown AST for a whole Comark document.
 *
 * The block structure (components, `---` props, `#slots`) is found line by line, following the reference parser
 * (comark `components.ts`). Everything between those structural lines is regular Markdown: each such region is handed to the
 * intellij-markdown parser with the component's own indentation removed (comark strips each component's indent column from its
 * children, so `  text` inside an indented component is a paragraph, not a code block). The region's tree is then mapped back to
 * original offsets, with the removed indentation becoming whitespace leaves, and spliced into the document tree.
 *
 * The result covers every character of the input, so the lexer can take its leaves as tokens and the PSI parser its
 * composites as markers, exactly as the Markdown plugin does with its own tree.
 */
class ComarkTreeBuilder(private val text: CharSequence, private val flavour: MarkdownFlavourDescriptor) {
    private val lines: List<Line> = splitLines(text)

    /** A physical line: [start, end) is the content without the line break, [next] the start of the following line. */
    class Line(val start: Int, val end: Int, val next: Int, val contentStart: Int, val indent: Int) {
        val isBlank get() = contentStart >= end
    }

    fun build(): ASTNode {
        val nodes = ArrayList<ASTNode>()
        // Front matter at the very top is Markdown's business (the Markdown flavour parses it and injects YAML into it).
        val frontMatterEnd = frontMatterEnd()
        if (frontMatterEnd > 0) nodes += markdownRegion(0, frontMatterEnd, IntArray(frontMatterEnd))
        parseSequence(frontMatterEnd, lines.size, Container.TOP, allowSlots = false).flatMapTo(nodes) { it.toAst() }
        return assemble(MarkdownElementTypes.MARKDOWN_FILE, 0, text.length, nodes)
    }

    // ---- Structure --------------------------------------------------------------------------------------------------

    /** Where nested content lives: [indent] is the opener's column, children have up to that much indentation stripped. */
    private class Container(val indent: Int) {
        companion object {
            val TOP = Container(0)
        }
    }

    private sealed class Block {
        abstract fun toAst(): List<ASTNode>
    }

    private inner class MarkdownBlock(val from: Int, val to: Int, val strip: IntArray) : Block() {
        override fun toAst(): List<ASTNode> = markdownRegion(from, to, strip)
    }

    private inner class ComponentBlock(
        val openLine: Int,
        val opener: ComarkSyntax.Opener,
        val closeLine: Int, // -1 when unclosed
        val props: PropsBlock?,
        val children: List<Block>,
    ) : Block() {
        override fun toAst(): List<ASTNode> {
            val line = lines[openLine]
            val parts = ArrayList<ASTNode>()
            parts += header(opener, line)
            props?.let { parts += it.toAst() }
            children.flatMapTo(parts) { it.toAst() }
            val end: Int
            if (closeLine >= 0) {
                val close = lines[closeLine]
                val markerEnd = close.contentStart + ComarkSyntax.colonRun(text, close.contentStart, close.end)
                parts += LeafASTNode(ComarkTypes.COMPONENT_CLOSE_PAIR.first, close.contentStart, markerEnd)
                end = markerEnd
            } else {
                end = parts.lastOrNull()?.endOffset ?: line.end
            }
            return listOf(assemble(ComarkTypes.BLOCK_COMPONENT_PAIR.first, line.contentStart, maxOf(end, opener.markerEnd), parts))
        }
    }

    private inner class PropsBlock(val openLine: Int, val closeLine: Int) : Block() {
        override fun toAst(): List<ASTNode> {
            val open = lines[openLine]
            val close = lines[closeLine]
            val parts = ArrayList<ASTNode>()
            parts += LeafASTNode(ComarkTypes.PROPS_FENCE_PAIR.first, open.contentStart, open.contentStart + 3)
            val contentStart = open.next
            val contentEnd = close.start
            if (contentEnd > contentStart) parts += LeafASTNode(ComarkTypes.PROPS_CONTENT_PAIR.first, contentStart, contentEnd)
            parts += LeafASTNode(ComarkTypes.PROPS_FENCE_PAIR.first, close.contentStart, close.contentStart + 3)
            return listOf(assemble(ComarkTypes.PROPS_BLOCK_PAIR.first, open.contentStart, close.contentStart + 3, parts))
        }
    }

    private inner class SlotBlock(val line: Int, val slot: ComarkSyntax.SlotLine, val children: List<Block>) : Block() {
        override fun toAst(): List<ASTNode> {
            val parts = ArrayList<ASTNode>()
            parts += LeafASTNode(ComarkTypes.SLOT_MARKER_PAIR.first, slot.hashStart, slot.nameStart)
            parts += LeafASTNode(ComarkTypes.SLOT_NAME_PAIR.first, slot.nameStart, slot.nameEnd)
            slot.attributes?.let { parts += attributes(it) }
            children.flatMapTo(parts) { it.toAst() }
            val end = maxOf(parts.last().endOffset, slot.nameEnd)
            return listOf(assemble(ComarkTypes.SLOT_PAIR.first, slot.hashStart, end, parts))
        }
    }

    /**
     * Splits lines [from, to) into Markdown regions and structural blocks. Lines indented four or more columns past the container
     * are never structure (they are code or a paragraph continuation in Markdown), and nothing inside a code fence is.
     */
    private fun parseSequence(from: Int, to: Int, container: Container, allowSlots: Boolean): List<Block> {
        val blocks = ArrayList<Block>()
        var regionStart = from
        var fenceChar = 0.toChar()
        var fenceLength = 0
        var l = from

        fun flush(until: Int) {
            if (until > regionStart) blocks += MarkdownBlock(regionStart, until, stripFor(regionStart, until, container))
        }

        while (l < to) {
            ProgressManager.checkCanceled()
            val line = lines[l]
            if (fenceLength > 0) {
                if (ComarkSyntax.isFenceClose(text, line.contentStart, line.end, fenceChar, fenceLength)) fenceLength = 0
                l++
                continue
            }
            val relative = line.indent - minOf(container.indent, line.indent)
            if (!line.isBlank && relative < 4) {
                val fence = ComarkSyntax.fenceOpen(text, line.contentStart, line.end)
                if (fence != null) {
                    fenceChar = fence.first
                    fenceLength = fence.second
                    l++
                    continue
                }
                val opener = ComarkSyntax.parseOpener(text, line.contentStart, line.end)
                if (opener != null) {
                    flush(l)
                    val component = parseComponent(l, opener, to)
                    blocks += component
                    l = if (component.closeLine >= 0) component.closeLine + 1 else to
                    regionStart = l
                    continue
                }
                if (allowSlots) {
                    val slot = ComarkSyntax.parseSlot(text, line.contentStart, line.end)
                    if (slot != null) {
                        flush(l)
                        val end = slotEnd(l, to)
                        blocks += SlotBlock(l, slot, parseSequence(l + 1, end, container, allowSlots = false))
                        l = end
                        regionStart = l
                        continue
                    }
                }
            }
            l++
        }
        flush(to)
        return blocks
    }

    private fun parseComponent(openLine: Int, opener: ComarkSyntax.Opener, limit: Int): ComponentBlock {
        val markers = opener.markerCount
        val closeLine = findClose(openLine, markers, limit)
        val bodyEnd = if (closeLine >= 0) closeLine else limit
        val container = Container(lines[openLine].indent)
        var bodyStart = openLine + 1
        var props: PropsBlock? = null
        if (bodyStart < bodyEnd && isPropsFence(lines[bodyStart])) {
            for (j in bodyStart + 1 until bodyEnd) {
                if (isPropsFence(lines[j])) {
                    props = PropsBlock(bodyStart, j)
                    bodyStart = j + 1
                    break
                }
            }
        }
        val children = parseSequence(bodyStart, bodyEnd, container, allowSlots = true)
        return ComponentBlock(openLine, opener, closeLine, props, children)
    }

    private fun isPropsFence(line: Line) = line.end - line.contentStart >= 3 &&
        text[line.contentStart] == '-' && text[line.contentStart + 1] == '-' && text[line.contentStart + 2] == '-' &&
        ComarkSyntax.isBlank(text, line.contentStart + 3, line.end)

    /** Closing line of the component opened at [openLine], or -1 (comark's `comark_block` loop: fences and same-depth nesting). */
    private fun findClose(openLine: Int, markers: Int, limit: Int): Int {
        var depth = 0
        var fenceChar = 0.toChar()
        var fenceLength = 0
        for (l in openLine + 1 until limit) {
            val line = lines[l]
            if (line.isBlank) continue
            val start = line.contentStart
            if (fenceLength > 0) {
                if (ComarkSyntax.isFenceClose(text, start, line.end, fenceChar, fenceLength)) fenceLength = 0
                continue
            }
            val fence = ComarkSyntax.fenceOpen(text, start, line.end)
            if (fence != null) {
                fenceChar = fence.first
                fenceLength = fence.second
                continue
            }
            if (text[start] != ':') continue
            if (ComarkSyntax.colonRun(text, start, line.end) != markers) continue
            if (!ComarkSyntax.isBlank(text, start + markers, line.end)) {
                depth++
                continue
            }
            if (depth > 0) {
                depth--
                continue
            }
            return l
        }
        return -1
    }

    /** A slot runs until the next slot line of the same component (not inside a fence or a nested component). */
    private fun slotEnd(slotLine: Int, limit: Int): Int {
        var fenceChar = 0.toChar()
        var fenceLength = 0
        var l = slotLine + 1
        while (l < limit) {
            val line = lines[l]
            if (!line.isBlank) {
                val start = line.contentStart
                if (fenceLength > 0) {
                    if (ComarkSyntax.isFenceClose(text, start, line.end, fenceChar, fenceLength)) fenceLength = 0
                } else {
                    val fence = ComarkSyntax.fenceOpen(text, start, line.end)
                    val opener = if (fence == null) ComarkSyntax.parseOpener(text, start, line.end) else null
                    when {
                        fence != null -> {
                            fenceChar = fence.first
                            fenceLength = fence.second
                        }

                        opener != null -> {
                            val close = findClose(l, opener.markerCount, limit)
                            if (close < 0) return limit
                            l = close
                        }

                        ComarkSyntax.parseSlot(text, start, line.end) != null -> return l
                    }
                }
            }
            l++
        }
        return limit
    }

    /** Columns to strip from each line of [from, to): up to the container's indent, keeping code fence bodies aligned. */
    private fun stripFor(from: Int, to: Int, container: Container): IntArray {
        val strip = IntArray(to - from)
        if (container.indent == 0) return strip
        var fenceChar = 0.toChar()
        var fenceLength = 0
        var fenceStrip = 0
        for (l in from until to) {
            val line = lines[l]
            val own = minOf(container.indent, line.indent)
            if (fenceLength > 0) {
                strip[l - from] = minOf(fenceStrip, line.indent)
                if (!line.isBlank && ComarkSyntax.isFenceClose(text, line.contentStart, line.end, fenceChar, fenceLength)) {
                    fenceLength = 0
                    strip[l - from] = own
                }
                continue
            }
            strip[l - from] = own
            if (!line.isBlank) {
                val fence = ComarkSyntax.fenceOpen(text, line.contentStart, line.end)
                if (fence != null) {
                    fenceChar = fence.first
                    fenceLength = fence.second
                    fenceStrip = own
                }
            }
        }
        return strip
    }

    private fun frontMatterEnd(): Int {
        if (lines.isEmpty()) return 0
        val first = lines[0]
        if (first.contentStart != 0 || !isPropsFence(first)) return 0
        for (l in 1 until lines.size) {
            val line = lines[l]
            if (line.contentStart == line.start && (isPropsFence(line) || text.subSequence(line.start, line.end).trimEnd() == "...")) {
                return l + 1
            }
        }
        return 0
    }

    // ---- AST pieces -------------------------------------------------------------------------------------------------

    private fun header(opener: ComarkSyntax.Opener, line: Line): ASTNode {
        val parts = ArrayList<ASTNode>()
        parts += LeafASTNode(ComarkTypes.COMPONENT_MARKER_PAIR.first, opener.markerStart, opener.markerEnd)
        parts += LeafASTNode(ComarkTypes.COMPONENT_NAME_PAIR.first, opener.nameStart, opener.nameEnd)
        if (opener.contentOpen >= 0) {
            parts += LeafASTNode(ComarkTypes.CONTENT_LBRACKET_PAIR.first, opener.contentOpen, opener.contentOpen + 1)
            if (opener.contentClose > opener.contentOpen + 1) {
                parts += LeafASTNode(ComarkTypes.INLINE_CONTENT_PAIR.first, opener.contentOpen + 1, opener.contentClose)
            }
            parts += LeafASTNode(ComarkTypes.CONTENT_RBRACKET_PAIR.first, opener.contentClose, opener.contentClose + 1)
        }
        opener.attributes?.let { parts += attributes(it) }
        if (opener.restEnd > opener.restStart) {
            parts += LeafASTNode(ComarkTypes.BAD_CHARACTER_PAIR.first, opener.restStart, opener.restEnd)
        }
        var end = parts.last().endOffset
        // trailing whitespace stays outside the header
        if (end > line.end) end = line.end
        return assemble(ComarkTypes.COMPONENT_HEADER_PAIR.first, opener.markerStart, end, parts)
    }

    private fun attributes(scan: ComarkSyntax.AttrScan): ASTNode {
        val parts = ArrayList<ASTNode>()
        var i = 0
        val tokens = scan.tokens
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.part == ComarkSyntax.AttrPart.NAME) {
                // name [= value] becomes one ATTRIBUTE node
                val group = ArrayList<ASTNode>()
                group += leaf(token)
                var j = i + 1
                while (j < tokens.size && (tokens[j].part == ComarkSyntax.AttrPart.EQ || tokens[j].part == ComarkSyntax.AttrPart.VALUE)) {
                    group += leaf(tokens[j])
                    j++
                }
                parts += assemble(ComarkTypes.ATTRIBUTE_PAIR.first, group.first().startOffset, group.last().endOffset, group)
                i = j
            } else {
                parts += leaf(token)
                i++
            }
        }
        val start = tokens.first().start
        return assemble(ComarkTypes.ATTRIBUTES_PAIR.first, start, maxOf(scan.end, parts.last().endOffset), parts)
    }

    private fun leaf(token: ComarkSyntax.AttrToken): ASTNode {
        val type = when (token.part) {
            ComarkSyntax.AttrPart.LBRACE -> ComarkTypes.ATTR_LBRACE_PAIR.first
            ComarkSyntax.AttrPart.RBRACE -> ComarkTypes.ATTR_RBRACE_PAIR.first
            ComarkSyntax.AttrPart.CLASS -> ComarkTypes.ATTR_CLASS_PAIR.first
            ComarkSyntax.AttrPart.ID -> ComarkTypes.ATTR_ID_PAIR.first
            ComarkSyntax.AttrPart.NAME -> ComarkTypes.ATTR_NAME_PAIR.first
            ComarkSyntax.AttrPart.EQ -> ComarkTypes.ATTR_EQ_PAIR.first
            ComarkSyntax.AttrPart.VALUE -> ComarkTypes.ATTR_VALUE_PAIR.first
            ComarkSyntax.AttrPart.TEXT -> MarkdownTokenTypes.TEXT
        }
        return LeafASTNode(type, token.start, token.end)
    }

    /**
     * Parses lines [from, to) as Markdown with [strip] columns removed from each line and maps the tree back to the original
     * offsets. Returns the region's top-level nodes (the region root itself is dropped).
     */
    private fun markdownRegion(from: Int, to: Int, strip: IntArray): List<ASTNode> {
        val regionStart = lines[from].start
        val regionEnd = lines[to - 1].next
        if (regionEnd <= regionStart) return emptyList()
        val mapping = DedentMapping(to - from)
        val dedented = StringBuilder(regionEnd - regionStart + 1)
        // The Markdown parser takes a delimiter line at the very start of its text for front matter. Only the region at the top of
        // the document can have front matter, so any other region starting with one is parsed after a guard line break (dropped
        // again below): its `---` stays a thematic break, as in the reference parser.
        val guard = from > 0 && startsLikeFrontMatter(lines[from], strip[0])
        if (guard) dedented.append('\n')
        for (l in from until to) {
            val line = lines[l]
            val skip = charsToStrip(line, strip[l - from])
            mapping.add(dedented.length, line.start + skip)
            dedented.append(text, line.start + skip, line.next)
        }
        val parsed = MarkdownParser(flavour, false, CANCELLATION).parse(MarkdownElementTypes.MARKDOWN_FILE, dedented.toString(), false)
        val tree = if (guard) CompositeASTNode(parsed.type, parsed.children.filter { it.endOffset > 1 }) else parsed
        if (mapping.isConstantShift()) {
            // every line lost the same number of characters (usually none): offsets only need shifting
            val shift = mapping.shift(0)
            return tree.children.map { shiftNode(it, shift) }
        }
        return tree.children.map { remap(it, mapping) }
    }

    /** True when [line], with [columns] of indentation stripped, could open front matter (`---` or `+++`, at least three). */
    private fun startsLikeFrontMatter(line: Line, columns: Int): Boolean {
        val start = line.start + charsToStrip(line, columns)
        val content = text.subSequence(start, line.end).trimEnd()
        return content.length >= 3 && (content.all { it == '-' } || content.all { it == '+' })
    }

    /** Characters making up the first [columns] columns of indentation (tabs count to the next multiple of 4). */
    private fun charsToStrip(line: Line, columns: Int): Int {
        var col = 0
        var i = line.start
        while (i < line.end && col < columns) {
            val c = text[i]
            val width = when (c) {
                ' ' -> 1
                '\t' -> 4 - col % 4
                else -> break
            }
            if (col + width > columns) break // a tab across the boundary stays
            col += width
            i++
        }
        return i - line.start
    }

    /** Monotonic map from offsets in the dedented region text to document offsets. */
    private class DedentMapping(capacity: Int) {
        private val dedentedStarts = IntArray(capacity)
        private val originalStarts = IntArray(capacity)
        private var size = 0

        fun add(dedented: Int, original: Int) {
            dedentedStarts[size] = dedented
            originalStarts[size] = original
            size++
        }

        /** True when no line other than the first lost characters, so a single shift maps every offset. */
        fun isConstantShift(): Boolean {
            for (k in 1 until size) if (shift(k) != shift(0)) return false
            return true
        }

        fun shift(line: Int): Int = originalStarts[line] - dedentedStarts[line]

        fun start(offset: Int): Int {
            var lo = 0
            var hi = size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (dedentedStarts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return originalStarts[lo] + (offset - dedentedStarts[lo])
        }

        /** An end offset maps to just after the character before it, so a node never swallows the next line's indentation. */
        fun end(offset: Int): Int = if (offset <= 0) start(0) else start(offset - 1) + 1
    }

    private fun shiftNode(node: ASTNode, shift: Int): ASTNode {
        if (shift == 0) return node
        val children = node.children
        if (children.isEmpty()) {
            return if (node is LeafASTNode) {
                mappedLeaf(node.type, node.startOffset + shift, node.endOffset + shift)
            } else {
                EmptyCompositeNode(node.type, node.startOffset + shift)
            }
        }
        return CompositeASTNode(node.type, children.map { shiftNode(it, shift) })
    }

    /**
     * A leaf of a region tree at its document offsets. A closing code fence takes in the indentation stripped from its line, as the
     * Markdown parser does for fences in list items: the Markdown plugin's fence injection (and its literal text escaper) assume the
     * content ends at the line break before `CODE_FENCE_END`, and a separate whitespace leaf there made the injected range overrun the
     * content ("After patch" errors for code fences indented inside components).
     */
    private fun mappedLeaf(type: MdType, start: Int, end: Int): ASTNode {
        if (type != MarkdownTokenTypes.CODE_FENCE_END) return LeafASTNode(type, start, end)
        var s = start
        while (s > 0 && (text[s - 1] == ' ' || text[s - 1] == '\t')) s--
        return LeafASTNode(type, s, end)
    }

    private fun remap(node: ASTNode, mapping: DedentMapping): ASTNode {
        val children = node.children
        val start = mapping.start(node.startOffset)
        if (children.isEmpty()) {
            if (node.startOffset == node.endOffset) {
                return if (node is LeafASTNode) LeafASTNode(node.type, start, start) else EmptyCompositeNode(node.type, start)
            }
            val end = mapping.end(node.endOffset)
            return if (node is LeafASTNode) mappedLeaf(node.type, start, end) else EmptyCompositeNode(node.type, start)
        }
        val mapped = children.map { remap(it, mapping) }
        return assemble(node.type, minOf(start, mapped.first().startOffset), maxOf(mapping.end(node.endOffset), mapped.last().endOffset), mapped)
    }

    /**
     * A composite of [type] covering [start, end), with [children] in document order and whatever lies between them (removed
     * indentation, line breaks of structural lines) filled in as whitespace / line break leaves.
     */
    private fun assemble(type: MdType, start: Int, end: Int, children: List<ASTNode>): ASTNode {
        val filled = ArrayList<ASTNode>(children.size + 4)
        var pos = start
        for (child in children) {
            if (child.startOffset < pos) continue // never overlap (defensive)
            if (child.startOffset > pos) fillGap(pos, child.startOffset, filled)
            filled += child
            pos = child.endOffset
        }
        if (end > pos) fillGap(pos, end, filled)
        if (filled.isEmpty()) return EmptyCompositeNode(type, start)
        return CompositeASTNode(type, filled)
    }

    private fun fillGap(from: Int, to: Int, into: MutableList<ASTNode>) {
        var i = from
        while (i < to) {
            val c = text[i]
            val s = i
            when (c) {
                '\n' -> {
                    i++
                    into += LeafASTNode(MarkdownTokenTypes.EOL, s, i)
                }

                ' ', '\t', '\r' -> {
                    while (i < to && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
                    into += LeafASTNode(MarkdownTokenTypes.WHITE_SPACE, s, i)
                }

                else -> {
                    while (i < to && text[i] != '\n' && text[i] != ' ' && text[i] != '\t') i++
                    into += LeafASTNode(MarkdownTokenTypes.TEXT, s, i)
                }
            }
        }
    }

    /** A composite node without children (an empty table cell, say), which [CompositeASTNode] can't express. */
    private class EmptyCompositeNode(type: MdType, offset: Int) : ASTNodeImpl(type, offset, offset) {
        override val children: List<ASTNode> get() = emptyList()
    }

    companion object {
        private val CANCELLATION = object : CancellationToken {
            override fun checkCancelled() = ProgressManager.checkCanceled()
        }

        fun splitLines(text: CharSequence): List<Line> {
            val result = ArrayList<Line>()
            val length = text.length
            var start = 0
            while (start < length) {
                var end = start
                while (end < length && text[end] != '\n') end++
                val next = if (end < length) end + 1 else end
                var content = start
                var indent = 0
                while (content < end && (text[content] == ' ' || text[content] == '\t')) {
                    indent += if (text[content] == '\t') 4 - indent % 4 else 1
                    content++
                }
                result += Line(start, end, next, content, indent)
                start = next
            }
            return result
        }
    }
}
