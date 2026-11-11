package cz.xprees.comark.format.studio

import java.util.EnumSet

/**
 * Why a top-level block is kept verbatim in Nuxt Studio style: Studio (on its text path, its database path, or both) loses
 * content of the block, or its rewrite of the block does not converge (a second save changes it again).
 */
enum class StudioRewriteReason(val description: String) {
    INVALID_YAML("YAML that does not load; Studio's code editor cannot save the document"),
    FRONTMATTER_BODY_KEY("a front matter `body` key, which replaces the document body in Studio"),
    FRONTMATTER_COMMENT("front matter with YAML comments, which Studio's re-dump drops"),
    PROPS_COMMENT("component props YAML with comments, which Studio's re-dump drops"),
    COMPONENT_IN_LIST_ITEM("a block component inside a list item, which Studio's database path collapses and moves out of the list"),
    HTML_COMMENT("an HTML comment, which Studio's database path drops"),
    RAW_HTML("raw HTML, which Studio's database path converts to components"),
    LINK_REFERENCE_DEFINITION("a link reference definition, which Studio deletes (its text path writes an empty ::component)"),
    REFERENCE_LINK("a reference-style link, which Studio inlines (and deletes its definition)"),
    FOOTNOTE("a footnote, which Studio's database path expands into components"),
    TABLE_ALIGNMENT("table column alignment, which Studio's database path drops"),
    FENCE_HIGHLIGHTS("code fence line highlights {…}, which Studio's database path mangles further on every save"),
    HEADING_ID("a heading {#id}, which Studio drops (text path) or escapes into the heading text (database path)"),
    ESCAPED_BRACE("an escaped \\{ or \\}, which Studio unescapes and then drops as an empty attribute set on the next save"),
    INLINE_CODE_WITH_BACKTICK("inline code containing a backtick, which Studio does not write back intact"),
    FENCE_FILENAME_BRACKET("a code fence filename containing ], which Studio does not write back intact"),
    INLINE_COMPONENT_OBJECT_PROPS("an inline component with object or array props, which Studio writes as a block component inside the text"),
    MULTILINE_PROP("a component prop spanning several lines, which Studio writes inline where it no longer parses"),
    MULTILINE_HEADING("a setext heading spanning several lines, which Studio writes as a one-line heading followed by the other lines"),
    NESTED_TASK_LIST("a task list item holding another task list, whose own text Studio drops"),
    FENCE_IN_LIST_ITEM("a code fence opening a list item, which Studio writes without the item's indentation, so it leaves the list"),
    ADJACENT_LISTS("neighbouring lists of the same kind with different markers, which Studio merges into one list on the next save"),
    COMPONENT_NAMED_A("a component named a, which Studio writes as a link to undefined"),
    INDENTED_COMPONENT("a component indented as code (4+ spaces or a tab), which Studio's text path turns into a code block"),
    UNCLOSED_ATTRIBUTES("an attribute list { with no closing }, which Studio reads to the end of the text, turning words into attributes and dropping others"),
    NESTING_LIMIT("nesting past markdown-it's limit of 100 levels, whose content Studio drops, or a tree too deep to render safely"),
    DROPPED_BLOCK("a block Studio writes nothing for, so it is lost and the blocks around it may merge"),
    NOT_IDEMPOTENT("Studio's rewrite of this block changes again on the next save"),
}

/** The front matter, or one top-level block of the body. */
enum class StudioBlockKind { FRONTMATTER, BLOCK }

/**
 * One top-level block of a document: lines `[startLine, endLine)` of the source (a line break being `\r\n`, `\r` or `\n`),
 * its [source] text (newlines normalized to `\n`, trailing blank lines dropped), the reasons it must be kept verbatim
 * (empty when Studio's rewrite is safe), and [studioOutput], what Studio writes for it on its own (trimmed; empty when Studio
 * drops the block; `null` for front matter Studio cannot load, and for a block too deeply nested to render).
 */
data class StudioBlock(
    val kind: StudioBlockKind,
    val startLine: Int,
    val endLine: Int,
    val source: String,
    val reasons: Set<StudioRewriteReason>,
    val studioOutput: String?,
) {
    val kept: Boolean get() = reasons.isNotEmpty()
}

/**
 * Classifies the top-level blocks of a document by whether Nuxt Studio 1.8.0 damages them or does not converge on them
 * (see [StudioRewriteReason]). Syntactic checks name the constructs the research found; a dynamic check renders each
 * remaining block like Studio, parses the result again and flags the block when a second rendering differs.
 */
object StudioSafety {
    /**
     * All top-level blocks of [text] with their reasons, or `null` when the document cannot be parsed at all.
     * [StudioFormatter.formatPreserving] may keep more blocks than this reports: blocks whose output only fails to converge
     * next to the blocks around it.
     */
    fun classify(text: String): List<StudioBlock>? = analyze(text)?.blocks?.map { it.block }

    /** A block and, for a body block, its rendering: the concatenated output of its nodes, untrimmed. */
    internal class AnalyzedBlock(val block: StudioBlock, val rendered: String?)

    internal class Analysis(val doc: StudioSegmentedDocument, val blocks: List<AnalyzedBlock>)

    internal fun analyze(text: String): Analysis? {
        val doc = try {
            StudioTreeBuilder.buildSegmented(text)
        } catch (_: StudioTextPathException) {
            return null
        } catch (_: StudioUnsupportedException) {
            return null
        }
        val blocks = ArrayList<AnalyzedBlock>()
        doc.frontmatter?.let { fm ->
            val reasons: MutableSet<StudioRewriteReason> = EnumSet.noneOf(StudioRewriteReason::class.java)
            when {
                fm.error?.startsWith("YAML") == true -> reasons += StudioRewriteReason.INVALID_YAML
                fm.error != null -> reasons += StudioRewriteReason.FRONTMATTER_BODY_KEY
            }
            if (fm.yaml != null && StudioYamlLoader.hasComment(fm.yaml)) reasons += StudioRewriteReason.FRONTMATTER_COMMENT
            val yaml = if (fm.error != null) null else StudioRenderer.frontmatterYaml(fm.data)
            if (reasons.isEmpty() && yaml != null && !frontmatterConverges(yaml)) reasons += StudioRewriteReason.NOT_IDEMPOTENT
            val raw = fm.raw.replace(Regex("\r\n?"), "\n")
            val output = if (fm.error != null) null else yaml?.let { "---\n$it\n---" } ?: ""
            blocks += AnalyzedBlock(StudioBlock(StudioBlockKind.FRONTMATTER, 0, fm.contentLine + 1, raw, reasons, output), null)
        }
        val segments = doc.segments
        for ((i, segment) in segments.withIndex()) {
            val reasons: MutableSet<StudioRewriteReason> = EnumSet.noneOf(StudioRewriteReason::class.java)
            classifyTokens(segment.tokens, reasons)
            for (node in segment.nodes) classifyNode(node, null, false, reasons)
            fun inSegment(lines: List<Int>) = lines.any { it >= segment.startLine && it < segment.endLine }
            if (inSegment(doc.propsYamlErrorLines)) reasons += StudioRewriteReason.INVALID_YAML
            if (inSegment(doc.propsYamlCommentLines)) reasons += StudioRewriteReason.PROPS_COMMENT
            if (inSegment(doc.nestingLimitLines)) reasons += StudioRewriteReason.NESTING_LIMIT
            val kind = listKind(segment)
            if (kind != null && (listKind(segments.getOrNull(i - 1)) == kind || listKind(segments.getOrNull(i + 1)) == kind)) {
                reasons += StudioRewriteReason.ADJACENT_LISTS
            }
            val tooDeep = StudioTreeBuilder.treeDepth(segment.nodes) > StudioTreeBuilder.MAX_TREE_DEPTH
            if (tooDeep) reasons += StudioRewriteReason.NESTING_LIMIT
            val rendered = if (tooDeep) null else StudioRenderer.renderNodes(segment.nodes)
            if (rendered != null && StudioJs.trim(rendered).isEmpty() && StudioJs.trim(segment.source).isNotEmpty()) {
                // a dropped block: the blocks around it may then merge, which only the next save would show
                reasons += StudioRewriteReason.DROPPED_BLOCK
            }
            if (reasons.isEmpty() && rendered != null && !converges(StudioJs.trimEnd(rendered))) {
                reasons += StudioRewriteReason.NOT_IDEMPOTENT
            }
            val block = StudioBlock(
                StudioBlockKind.BLOCK, doc.sourceLine(segment.startLine), doc.sourceLine(segment.endLine), segment.source, reasons,
                rendered?.let { StudioPostProcess.padBlankLinesInComponents(StudioJs.trim(it)) },
            )
            blocks += AnalyzedBlock(block, rendered)
        }
        return Analysis(doc, blocks)
    }

    /**
     * Studio's text path leaves the block it wrote unchanged on the next save. [piece] is the block's output as it stands in
     * the document, after a blank line; checked alone, with no front matter.
     */
    private fun converges(piece: String): Boolean {
        val written = StudioPostProcess.padBlankLinesInComponents(piece)
        return StudioFormatter.format("\n" + written + "\n") == written + "\n"
    }

    private fun frontmatterConverges(yaml: String): Boolean {
        val text = "---\n$yaml\n---\n\nx\n"
        return StudioFormatter.format(text) == text
    }

    private fun listKind(segment: StudioSegment?): String? = segment?.tokens?.firstOrNull()?.type?.takeIf { it == "bullet_list_open" || it == "ordered_list_open" }

    private val INDENTED_COMPONENT_LINE = Regex("^[ \\t]*:{2,}[a-zA-Z]", RegexOption.MULTILINE)
    /** `[^label]`, a footnote reference or (followed by `:`) definition; markdown-it reads it as text or a reference. */
    private val FOOTNOTE_SYNTAX = Regex("\\[\\^[^\\]\\s]+]")
    private val HTML_COMMENT = Regex("<!--[\\s\\S]*?(-->|$)")

    private fun classifyTokens(tokens: List<MdToken>, reasons: MutableSet<StudioRewriteReason>) {
        var listItemDepth = 0
        var previous: MdToken? = null
        val taskItems = ArrayList<Boolean>()
        fun visit(token: MdToken) {
            when (token.type) {
                "list_item_open" -> {
                    listItemDepth++
                    val task = (token.attrs?.firstOrNull { it.name == "class" }?.value as? String)?.contains("task-list-item") == true
                    if (task && taskItems.any { it }) reasons += StudioRewriteReason.NESTED_TASK_LIST
                    taskItems += task
                }
                "list_item_close" -> {
                    listItemDepth--
                    if (taskItems.isNotEmpty()) taskItems.removeAt(taskItems.size - 1)
                }
                "mdc_block_open", "mdc_block_shorthand" -> {
                    if (listItemDepth > 0 && token.nesting >= 0) reasons += StudioRewriteReason.COMPONENT_IN_LIST_ITEM
                    if (token.tag == "a") reasons += StudioRewriteReason.COMPONENT_NAMED_A
                }
                "mdc_inline_component" -> if (token.tag == "a") reasons += StudioRewriteReason.COMPONENT_NAMED_A
                "mdc_inline_props" -> if (token.unclosedProps) reasons += StudioRewriteReason.UNCLOSED_ATTRIBUTES
                "html_block", "html_inline" -> {
                    if (token.content.contains("<!--")) reasons += StudioRewriteReason.HTML_COMMENT
                    if (StudioJs.trim(token.content.replace(HTML_COMMENT, "")).isNotEmpty()) reasons += StudioRewriteReason.RAW_HTML
                }
                "reference" -> reasons += if (token.info.startsWith("^")) StudioRewriteReason.FOOTNOTE else StudioRewriteReason.LINK_REFERENCE_DEFINITION
                "link_open", "image" -> token.reference?.let {
                    reasons += if (it.startsWith("^")) StudioRewriteReason.FOOTNOTE else StudioRewriteReason.REFERENCE_LINK
                }
                "th_open", "td_open" -> if (token.attrIndex("style") >= 0) reasons += StudioRewriteReason.TABLE_ALIGNMENT
                "heading_open" -> {
                    if (token.attrIndex("id") >= 0) reasons += StudioRewriteReason.HEADING_ID
                    val inline = tokens.getOrNull(tokens.indexOf(token) + 1)
                    if (token.markup.let { it == "=" || it == "-" } && inline?.content?.contains('\n') == true) {
                        reasons += StudioRewriteReason.MULTILINE_HEADING
                    }
                }
                "fence" -> {
                    if (previous?.type == "list_item_open") reasons += StudioRewriteReason.FENCE_IN_LIST_ITEM
                    val info = StudioTreeBuilder.parseCodeblockInfo(token.info)
                    if (info.containsKey("highlights")) reasons += StudioRewriteReason.FENCE_HIGHLIGHTS
                    if ((info["filename"] as? String)?.contains(']') == true) reasons += StudioRewriteReason.FENCE_FILENAME_BRACKET
                }
                "code_block" -> if (INDENTED_COMPONENT_LINE.containsMatchIn(token.content)) reasons += StudioRewriteReason.INDENTED_COMPONENT
                "code_inline" -> if (token.content.contains('`')) reasons += StudioRewriteReason.INLINE_CODE_WITH_BACKTICK
                "inline" -> {
                    if (token.content.contains("\\{") || token.content.contains("\\}")) reasons += StudioRewriteReason.ESCAPED_BRACE
                    if (FOOTNOTE_SYNTAX.containsMatchIn(token.content)) reasons += StudioRewriteReason.FOOTNOTE
                }
            }
            previous = token
            token.children?.forEach { visit(it) }
        }
        tokens.forEach { visit(it) }
    }

    private val INLINE_ELEMENTS = setOf("a", "strong", "em", "del", "span", "code", "img", "br")
    private val BLOCK_HANDLED = setOf(
        "pre", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "li", "blockquote", "table", "thead", "tbody", "tr",
        "th", "td", "template", "html", "comment", "math", "mermaid",
    )

    /**
     * Component props the renderer cannot write back: an object prop forces an inline component into a block, and a
     * multi-line string prop written inline (`{a="…\n…"}`, three props or fewer) no longer parses.
     */
    private fun classifyNode(node: StudioNode, parent: StudioElement?, inInline: Boolean, reasons: MutableSet<StudioRewriteReason>) {
        if (node !is StudioElement) return
        val isComponent = node.tag !in BLOCK_HANDLED && node.tag !in INLINE_ELEMENTS && (node.attrs["\$"] as? Map<*, *>)?.get("html") == null
        val inline = inInline || parent?.children?.any { it is StudioText } == true || parent?.tag in setOf("a", "strong", "em", "span")
        if (isComponent) {
            val entries = node.attrs.filterKeys { it != "\$" }
            val hasObject = entries.values.any { StudioJs.isObject(it) }
            if (hasObject && inline) reasons += StudioRewriteReason.INLINE_COMPONENT_OBJECT_PROPS
            if (!hasObject && (inline || entries.size <= 3) && entries.values.any { it is String && it.contains('\n') }) {
                reasons += StudioRewriteReason.MULTILINE_PROP
            }
        }
        // like the renderer's `state.context.inline`: set for the children of a component written inline
        val childInInline = inInline || (isComponent && inline && node.attrs.filterKeys { it != "\$" }.values.none { StudioJs.isObject(it) })
        for (child in node.children) classifyNode(child, node, childInInline, reasons)
    }
}
