package cz.xprees.comark.preview

import com.intellij.openapi.progress.ProgressManager
import cz.xprees.comark.parser.ComarkParseCache
import cz.xprees.comark.parser.ComarkTypes
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * Renders a Comark document to the HTML `<body>` shown by the Markdown preview.
 *
 * The block structure comes from the same tree the editor uses ([ComarkParseCache], built by
 * [cz.xprees.comark.parser.ComarkTreeBuilder]), so the preview and the editor always agree on where a component, its props and
 * its slots are. Components, props and slots become styled `<div>`s (see `comark-preview.css`); everything between them is
 * regular Markdown, which is handed to [markdownToHtml] (the Markdown plugin's own renderer in the IDE) as a snippet with the
 * component's indentation removed and its inline Comark syntax rewritten by [InlineComarkRewriter]. That keeps every Markdown
 * preview feature (code highlighting, diagrams, math, local images, header anchors) working inside components. A component the
 * tree keeps inside a list item is rendered in its place within the rendered list (see [renderMarkdown]).
 *
 * Every element carries the `md-src-pos` source range the preview uses for scroll synchronisation; the ranges in the rendered
 * snippets are translated back to document offsets.
 *
 * @param markdownToHtml renders a Markdown snippet to `<body md-src-pos="..">...</body>`.
 */
internal class ComarkPreviewRenderer(private val text: String, private val markdownToHtml: (String) -> String) {
    private val out = StringBuilder(text.length * 2)

    private val tree: ASTNode = ComarkParseCache.parse(text)

    /**
     * The link reference definitions of the whole document, by normalised label. Snippets are rendered one by one, so
     * [InlineComarkRewriter] turns `[text][ref]` into an inline link to the definition's target, and a reference resolves in any
     * snippet without the definitions being added to it.
     */
    private val linkReferences: Map<String, String> =
        InlineComarkRewriter.linkReferences(StringBuilder().also { collectLinkDefinitions(tree, it) }.toString())

    /** Marks the place of a component embedded in a list item while the list around it is rendered; never found in [text]. */
    private val placeholderTag: String = generateSequence("comark-embedded-component") { "$it-" }.first { !text.contains(it) }

    /** The `<body>` element of the preview page, with the Comark styles at its start. */
    fun renderBody(): String {
        out.append("<body ").append(SRC_POS).append("=\"0..").append(text.length).append("\">")
        out.append("<style>").append(ComarkPreviewStyles.css).append("</style>")
        val bodyStart = out.length
        renderChildren(tree, indent = 0)
        val body = uniqueHeadingIds(out.substring(bodyStart))
        out.setLength(bodyStart)
        out.append(body).append("</body>")
        return out.toString()
    }

    // ---- Structure --------------------------------------------------------------------------------------------------

    /** Renders the children of the file, a component or a slot; [indent] is the column stripped from their Markdown lines. */
    private fun renderChildren(parent: ASTNode, indent: Int) {
        var runStart = -1
        var runEnd = -1
        var embedded = ArrayList<ASTNode>()
        for (child in parent.children) {
            ProgressManager.checkCanceled()
            when (child.type) {
                BLOCK_COMPONENT, SLOT -> {
                    if (runStart >= 0) renderMarkdown(runStart, runEnd, indent, embedded)
                    runStart = -1
                    embedded = ArrayList()
                    if (child.type == BLOCK_COMPONENT) renderComponent(child) else renderSlot(child, indent)
                }

                COMPONENT_HEADER, PROPS_BLOCK, COMPONENT_CLOSE, SLOT_MARKER, SLOT_NAME, ATTRIBUTES -> Unit
                MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE -> Unit
                else -> {
                    if (runStart < 0) runStart = child.startOffset
                    runEnd = child.endOffset
                    collectEmbeddedComponents(child, embedded)
                }
            }
        }
        if (runStart >= 0) renderMarkdown(runStart, runEnd, indent, embedded)
    }

    /**
     * The components inside the Markdown node [node], outermost only (their content is rendered by [renderComponent]): the tree
     * builder keeps a component that continues a list item inside that item.
     */
    private fun collectEmbeddedComponents(node: ASTNode, into: MutableList<ASTNode>) {
        if (node.type == BLOCK_COMPONENT) {
            into += node
            return
        }
        if (node.type in LEAF_HOLDERS) return
        for (child in node.children) collectEmbeddedComponents(child, into)
    }

    private fun renderComponent(node: ASTNode) {
        val header = node.child(COMPONENT_HEADER)
        val name = header?.child(COMPONENT_NAME)?.let(::textOf) ?: "component"
        out.append("<div class=\"comark-component\" data-component=\"").append(escape(name)).append("\" ").append(srcPos(node)).append('>')
        out.append("<div class=\"comark-component-header\"")
        if (header != null) out.append(' ').append(srcPos(header))
        out.append("><span class=\"comark-component-name\">").append(escape(name)).append("</span>")
        header?.child(ATTRIBUTES)?.let(::renderAttributes)
        out.append("</div>")
        node.child(PROPS_BLOCK)?.let(::renderProps)
        out.append("<div class=\"comark-component-body\">")
        header?.child(INLINE_CONTENT)?.let(::renderInlineContent)
        renderChildren(node, columnOf(node.startOffset))
        out.append("</div></div>")
    }

    private fun renderSlot(node: ASTNode, indent: Int) {
        val name = node.child(SLOT_NAME)?.let(::textOf) ?: ""
        out.append("<div class=\"comark-slot\" data-slot=\"").append(escape(name)).append("\" ").append(srcPos(node)).append('>')
        out.append("<div class=\"comark-slot-name\">#").append(escape(name))
        node.child(ATTRIBUTES)?.let(::renderAttributes)
        out.append("</div><div class=\"comark-slot-body\">")
        renderChildren(node, indent)
        out.append("</div></div>")
    }

    /** `{.class #id key="value" bool}` as one chip per attribute. */
    private fun renderAttributes(node: ASTNode) {
        out.append("<span class=\"comark-component-attrs\">")
        for (child in node.children) {
            val kind = when (child.type) {
                ATTRIBUTE -> "comark-attr"
                ATTR_CLASS -> "comark-attr comark-attr-class"
                ATTR_ID -> "comark-attr comark-attr-id"
                else -> continue
            }
            out.append("<span class=\"").append(kind).append("\">").append(escape(textOf(child))).append("</span>")
        }
        out.append("</span>")
    }

    /** The YAML between the `---` fences, shown as is. */
    private fun renderProps(node: ASTNode) {
        val content = node.child(PROPS_CONTENT) ?: return
        val yaml = textOf(content).trimIndent().trimEnd()
        if (yaml.isEmpty()) return
        out.append("<pre class=\"comark-component-props\" ").append(srcPos(content)).append("><code>")
            .append(escape(yaml)).append("</code></pre>")
    }

    /** The `[content]` of `::name[content]`, rendered as inline Markdown. */
    private fun renderInlineContent(node: ASTNode) {
        val html = renderSnippet(textOf(node), node.startOffset)
        out.append("<div class=\"comark-component-content\">").append(unwrapParagraph(html)).append("</div>")
    }

    // ---- Markdown snippets ------------------------------------------------------------------------------------------

    /**
     * Renders the Markdown of the lines covering [from, to) with [indent] columns of indentation removed from each line.
     *
     * The lines of each component in [embedded] (components inside list items, in document order) are replaced by a one-line HTML
     * comment at the opener's column. Like the empty code fence the tree builder parses in their place, an HTML block interrupts a
     * paragraph and stays in the list item, so the list renders as the editor parsed it; each comment is then replaced by the
     * component's HTML.
     */
    private fun renderMarkdown(from: Int, to: Int, indent: Int, embedded: List<ASTNode> = emptyList()) {
        val start = lineStart(from)
        val end = lineEnd(to)
        val dedented = StringBuilder(end - start)
        val mapping = OffsetMapping()
        var nextComponent = 0
        var line = start
        while (line < end) {
            var next = text.indexOf('\n', line).let { if (it < 0 || it >= end) end else it + 1 }
            if (next <= line) next = end
            val skip = indentationChars(line, next, indent)
            val component = embedded.getOrNull(nextComponent)
            if (component != null && lineStart(component.startOffset) == line) {
                // the opener line's indentation, the placeholder (mapped onto the component), then the line break after the closer
                dedented.append(text, line + skip, component.startOffset)
                mapping.copy(line + skip, component.startOffset - line - skip)
                val placeholder = placeholder(nextComponent++)
                dedented.append(placeholder)
                mapping.generated(component.startOffset, placeholder.length - 1)
                mapping.generated(maxOf(component.startOffset, component.endOffset - 1), 1)
                val after = lineEnd(maxOf(component.startOffset + 1, component.endOffset))
                if (text[after - 1] == '\n') {
                    dedented.append('\n')
                    mapping.copy(after - 1, 1)
                }
                line = maxOf(after, next)
                continue
            }
            dedented.append(text, line + skip, next)
            mapping.copy(line + skip, next - line - skip)
            line = next
        }
        var html = renderSnippet(dedented.toString(), mapping)
        for ((index, component) in embedded.withIndex()) {
            val mark = out.length
            renderComponent(component)
            val componentHtml = out.substring(mark)
            out.setLength(mark)
            val at = html.indexOf(placeholder(index))
            // the placeholder is always rendered as is; should it ever be lost, the component still shows, after the run
            html = if (at >= 0) html.replaceRange(at, at + placeholder(index).length, componentHtml) else html + componentHtml
        }
        out.append(html)
    }

    private fun placeholder(index: Int) = "<!--$placeholderTag-$index-->"

    private fun renderSnippet(snippet: String, sourceStart: Int): String =
        renderSnippet(snippet, OffsetMapping().apply { copy(sourceStart, snippet.length) })

    /** Renders [snippet] (whose offsets map to the document through [toDocument]) and returns the content of its `<body>`. */
    private fun renderSnippet(snippet: String, toDocument: OffsetMapping): String {
        val rewritten = InlineComarkRewriter(snippet, linkReferences).rewrite()
        val html = unwrapBody(markdownToHtml(rewritten.text))
        val renderedLength = rewritten.text.length
        return SRC_POS_VALUE.replace(html) { match ->
            val start = minOf(match.groupValues[2].toInt(), renderedLength)
            val end = minOf(match.groupValues[3].toInt(), renderedLength)
            val documentStart = toDocument.source(rewritten.mapping.source(start))
            val documentEnd = toDocument.sourceEnd(rewritten.mapping.sourceEnd(end))
            val quote = match.groupValues[1]
            "$SRC_POS=$quote$documentStart..${maxOf(documentStart, documentEnd)}$quote"
        }
    }

    private fun collectLinkDefinitions(node: ASTNode, into: StringBuilder) {
        if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
            into.append(textOf(node).lines().joinToString("\n") { it.trimStart() }).append('\n')
            return
        }
        if (node.type in LEAF_HOLDERS) return
        for (child in node.children) collectLinkDefinitions(child, into)
    }

    /**
     * [body] with repeated heading ids made unique across the whole page (`usage`, `usage-1`, `usage-2`): the Markdown renderer does
     * that within one snippet, and every snippet is rendered on its own. A repeated id that a snippet already numbered
     * (`usage-1`) is numbered again from its base (`usage`).
     */
    private fun uniqueHeadingIds(body: String): String {
        val seen = HashSet<String>()
        return HEADING_ID.replace(body) { match ->
            val id = match.groupValues[2]
            if (seen.add(id)) return@replace match.value
            val base = NUMBERED_ID.matchEntire(id)?.groupValues?.get(1)?.takeIf { it in seen } ?: id
            var suffix = 1
            while (!seen.add("$base-$suffix")) suffix++
            "${match.groupValues[1]}$base-$suffix\""
        }
    }

    // ---- Text helpers -----------------------------------------------------------------------------------------------

    private fun textOf(node: ASTNode) = text.substring(node.startOffset, node.endOffset)

    private fun ASTNode.child(type: org.intellij.markdown.IElementType): ASTNode? = children.firstOrNull { it.type == type }

    private fun srcPos(node: ASTNode) = "$SRC_POS=\"${node.startOffset}..${node.endOffset}\""

    private fun lineStart(offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

    private fun lineEnd(offset: Int): Int {
        if (offset > 0 && offset <= text.length && text[offset - 1] == '\n') return offset
        val end = text.indexOf('\n', offset)
        return if (end < 0) text.length else end + 1
    }

    /** Column of [offset] within its line (tabs to the next multiple of 4, as the parser counts them). */
    private fun columnOf(offset: Int): Int {
        var column = 0
        for (i in lineStart(offset) until offset) column += if (text[i] == '\t') 4 - column % 4 else 1
        return column
    }

    /** Number of characters making up the first [columns] columns of indentation of the line [lineStart, lineEnd). */
    private fun indentationChars(lineStart: Int, lineEnd: Int, columns: Int): Int {
        var column = 0
        var i = lineStart
        while (i < lineEnd && column < columns) {
            val width = when (text[i]) {
                ' ' -> 1
                '\t' -> 4 - column % 4
                else -> break
            }
            if (column + width > columns) break
            column += width
            i++
        }
        return i - lineStart
    }

    companion object {
        private const val SRC_POS = "md-src-pos"
        /** The Markdown renderer writes the attribute with double quotes, and with single quotes on text spans. */
        private val SRC_POS_VALUE = Regex("""md-src-pos=(["'])(\d+)\.\.(\d+)\1""")
        private val BODY_OPEN = Regex("""^\s*<body[^>]*>""")
        private val BODY_CLOSE = Regex("""</body>\s*$""")
        /** The `id` the Markdown renderer gives a heading, as its first attribute. */
        private val HEADING_ID = Regex("""(<h[1-6] id=")([^"]*)"""")
        private val NUMBERED_ID = Regex("""(.+)-\d+""")
        private val SINGLE_PARAGRAPH = Regex("""^\s*<p[^>]*>(.*)</p>\s*$""", RegexOption.DOT_MATCHES_ALL)

        private val BLOCK_COMPONENT = ComarkTypes.BLOCK_COMPONENT_PAIR.first
        private val COMPONENT_HEADER = ComarkTypes.COMPONENT_HEADER_PAIR.first
        private val COMPONENT_NAME = ComarkTypes.COMPONENT_NAME_PAIR.first
        private val COMPONENT_CLOSE = ComarkTypes.COMPONENT_CLOSE_PAIR.first
        private val INLINE_CONTENT = ComarkTypes.INLINE_CONTENT_PAIR.first
        private val PROPS_BLOCK = ComarkTypes.PROPS_BLOCK_PAIR.first
        private val PROPS_CONTENT = ComarkTypes.PROPS_CONTENT_PAIR.first
        private val SLOT = ComarkTypes.SLOT_PAIR.first
        private val SLOT_MARKER = ComarkTypes.SLOT_MARKER_PAIR.first
        private val SLOT_NAME = ComarkTypes.SLOT_NAME_PAIR.first
        private val ATTRIBUTES = ComarkTypes.ATTRIBUTES_PAIR.first
        private val ATTRIBUTE = ComarkTypes.ATTRIBUTE_PAIR.first
        private val ATTR_CLASS = ComarkTypes.ATTR_CLASS_PAIR.first
        private val ATTR_ID = ComarkTypes.ATTR_ID_PAIR.first

        /** Nodes that can't contain link definitions (the tree's paragraphs are unparsed leaves). */
        private val LEAF_HOLDERS = setOf(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK)

        private fun escape(value: String) = InlineComarkRewriter.escape(value)

        private fun unwrapBody(html: String): String = html.replace(BODY_OPEN, "").replace(BODY_CLOSE, "")

        private fun unwrapParagraph(html: String): String = SINGLE_PARAGRAPH.matchEntire(html)?.groupValues?.get(1) ?: html
    }
}
