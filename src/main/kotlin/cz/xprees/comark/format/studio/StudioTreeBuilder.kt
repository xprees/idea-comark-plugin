package cz.xprees.comark.format.studio

/** Studio's text path cannot produce a document: comark, js-yaml or Studio itself would throw on this input. */
class StudioTextPathException(message: String) : RuntimeException(message)

/** The input is valid, but leads to a Studio code path this port does not reproduce (it would not throw there). */
class StudioUnsupportedException(message: String) : RuntimeException(message)

/**
 * Builds the comark tree that Nuxt Studio 1.8.0's text path hands to `renderMarkdown`: `documentFromMarkdownContent`
 * (comark 0.7.0 `parseMarkdown` with `autoClose: false, autoUnwrap: true, linkify: false` and the emoji, shiki and toc
 * plugins), then `contentFromMarkdownDocument`'s `comarkTreeFromLegacyDocument` + `unbindComarkTree`.
 *
 * comark parses with markdown-exit (a markdown-it fork), its own markdown-it plugins, htmlparser2 and js-yaml; this is a
 * port of exactly that chain ([StudioMdBlock], [StudioMdInline], [StudioHtml], [StudioYamlLoader]) followed by comark's
 * token-to-tree conversion (`internal/parse/token-processor.js`), `autoUnwrap`, the alert and shiki post-processors and
 * Studio's unbinding. The IDE's own Comark parser is deliberately not used: its block structure follows intellij-markdown
 * (CommonMark via a different algorithm), and byte-identical output needs markdown-it's own decisions on lazy
 * continuation, emphasis, HTML blocks and list tightness.
 */
object StudioTreeBuilder {
    /** The tree for [text]; throws [StudioTextPathException] where Studio's text path throws. */
    fun build(text: String): StudioDocument {
        try {
            return buildChecked(text)
        } catch (e: StudioYamlException) {
            throw StudioTextPathException("YAML: ${e.message}")
        } catch (e: StudioParseException) {
            throw StudioTextPathException(e.message ?: "parse error")
        } catch (e: StackOverflowError) {
            throw StudioUnsupportedException("nesting too deep for this port")
        }
    }

    private fun buildChecked(text: String): StudioDocument {
        val (content, data) = parseFrontmatter(text)
        val tokens = StudioMarkdownIt.parse(content)
        val nodes = postProcess(TokenProcessor().document(tokens))
        val frontmatter = documentFrontmatter(data)
        return StudioDocument(frontmatter = frontmatter, nodes = nodes, frontmatterValue = data)
    }

    /** `autoUnwrap`, the alert and shiki plugins, then Studio's `unbindComarkTree`; one output node per input node. */
    private fun postProcess(input: List<StudioNode>): List<StudioNode> {
        var nodes = input.map { autoUnwrap(it) }
        nodes = nodes.map { alert(it) }
        nodes = shiki(nodes)
        return nodes.map { unbind(it) }
    }

    /**
     * The same parse as [build], split into the document's top-level blocks: the front matter, and each top-level
     * markdown-it block with the comark nodes made from it. Not part of Studio; [StudioSafety] and
     * [StudioFormatter.formatPreserving] use it to keep single blocks verbatim.
     *
     * Invalid YAML does not throw here: front matter that does not load is reported in [StudioSegmentedDocument.frontmatter]
     * and read as empty, and a component props block that does not load is read as if empty and reported by line.
     * Other parse failures still throw [StudioTextPathException].
     */
    internal fun buildSegmented(text: String): StudioSegmentedDocument {
        try {
            return buildSegmentedChecked(text)
        } catch (e: StudioYamlException) {
            throw StudioTextPathException("YAML: ${e.message}")
        } catch (e: StudioParseException) {
            throw StudioTextPathException(e.message ?: "parse error")
        } catch (e: StackOverflowError) {
            throw StudioUnsupportedException("nesting too deep for this port")
        }
    }

    private fun buildSegmentedChecked(text: String): StudioSegmentedDocument {
        val split = splitFrontmatter(text)
        var frontmatter: StudioFrontmatterBlock? = null
        if (split.raw != null) {
            var data: Any? = null
            var error: String? = null
            try {
                val parsed = StudioYamlLoader.parseYaml(split.yaml!!)
                data = if (parsed == null || parsed === JsUndefined) LinkedHashMap<String, Any?>() else parsed
                if (data is Map<*, *> && data.containsKey("body")) error = "front matter has a body key, which replaces the document body"
            } catch (e: StudioYamlException) {
                error = "YAML: ${e.message}"
            }
            frontmatter = StudioFrontmatterBlock(split.raw, split.yaml, data, error, split.contentLine)
        }
        val content = normalizeNewlines(split.content)
        val env = MdEnv().apply { lenientPropsYaml = true }
        val tokens = StudioMarkdownIt.parse(content, env)
        val groups = TokenProcessor().documentGroups(tokens)
        val nodes = postProcess(groups.flatMap { it.nodes })
        val lines = content.split("\n")
        val segments = ArrayList<StudioSegment>()
        var at = 0
        for ((index, group) in groups.withIndex()) {
            val groupTokens = tokens.subList(group.start, group.end)
            val start = groupTokens.mapNotNull { it.map?.get(0) }.minOrNull()
                ?: throw StudioUnsupportedException("a top-level block without a source map")
            val nextStart = groups.getOrNull(index + 1)?.let { g -> tokens.subList(g.start, g.end).mapNotNull { it.map?.get(0) }.minOrNull() }
                ?: lines.size
            var end = maxOf(nextStart, start + 1)
            while (end > start + 1 && StudioJs.trim(lines.getOrElse(end - 1) { "" }).isEmpty()) end--
            val groupNodes = nodes.subList(at, at + group.nodes.size)
            at += group.nodes.size
            segments += StudioSegment(start, end, lines.subList(start, minOf(end, lines.size)).joinToString("\n"), groupTokens, groupNodes)
        }
        return StudioSegmentedDocument(text, frontmatter, content, segments, env.propsYamlErrors.toList())
    }

    private fun normalizeNewlines(s: String): String {
        var src = s
        if (src.contains('\r')) src = src.replace(Regex("\r\n?"), "\n")
        if (src.contains('\u0000')) src = src.replace('\u0000', '\uFFFD')
        return src
    }

    // ---- front matter (internal/frontmatter.js) -----------------------------------------------------------------------

    /** Where comark's `parseFrontmatter` splits [input]: the raw block with its delimiters, the YAML, and the content. */
    private class FrontmatterSplit(val raw: String?, val yaml: String?, val content: String, val contentLine: Int)

    private fun splitFrontmatter(input: String): FrontmatterSplit {
        if (input.startsWith("---")) {
            val idx = input.indexOf("\n---")
            if (idx != -1) {
                val hasCarriageReturn = idx > 0 && input[idx - 1] == '\r'
                val end = idx - (if (hasCarriageReturn) 1 else 0)
                val frontmatter = if (end > 4) input.substring(4, end) else ""
                if (frontmatter.isNotEmpty()) {
                    // comark skips one more character after a `\r\n---` (the closing line's `\r`)
                    val contentStart = minOf(input.length, idx + 4 + (if (hasCarriageReturn) 1 else 0))
                    val raw = input.substring(0, idx + 4)
                    val contentLine = normalizeNewlines(raw).count { it == '\n' }
                    return FrontmatterSplit(raw, frontmatter, input.substring(contentStart), contentLine)
                }
            }
        }
        return FrontmatterSplit(null, null, input, 0)
    }

    private fun parseFrontmatter(input: String): Pair<String, Any?> {
        val split = splitFrontmatter(input)
        if (split.yaml == null) return input to LinkedHashMap<String, Any?>()
        val parsed = StudioYamlLoader.parseYaml(split.yaml)
        val data = if (parsed == null || parsed === JsUndefined) LinkedHashMap<String, Any?>() else parsed
        return split.content to data
    }

    /**
     * Studio spreads the front matter into the document (`{ id, meta, extension, stem, body: tree, ...tree.frontmatter }`)
     * and renders `tree.frontmatter`. Only plain objects without a `body` key keep the tree as the body.
     */
    @Suppress("UNCHECKED_CAST")
    private fun documentFrontmatter(data: Any?): Map<String, Any?> {
        if (data !is Map<*, *>) return emptyMap()
        if (data.containsKey("body")) throw StudioUnsupportedException("front matter has a body key, which replaces the document body")
        return data as Map<String, Any?>
    }

    // ---- token processor (internal/parse/token-processor.js) ----------------------------------------------------------

    private val WRAPPER_TAGS = setOf("ul", "ol", "table", "blockquote", "pre")
    private val BLOCK_TAG_MAP = mapOf(
        "blockquote_open" to "blockquote", "ordered_list_open" to "ol", "bullet_list_open" to "ul", "list_item_open" to "li",
        "paragraph_open" to "p", "table_open" to "table", "thead_open" to "thead", "tbody_open" to "tbody", "tr_open" to "tr",
        "th_open" to "th", "td_open" to "td",
    )
    private val INLINE_TAG_MAP = mapOf("strong_open" to "strong", "em_open" to "em", "s_open" to "del", "sub_open" to "sub", "sup_open" to "sup")

    private class Result(val node: StudioNode?, val next: Int)
    private class Group(val start: Int, val end: Int, val nodes: List<StudioNode>)
    private class Many(val nodes: List<StudioNode>, val next: Int)

    private class TokenProcessor {
        val headingSlugCounts = HashMap<String, Int>()
        val headingStack = ArrayList<Pair<Int, String>>()

        fun document(tokens: List<MdToken>): List<StudioNode> = documentGroups(tokens).flatMap { it.nodes }

        /** The top-level token groups (`tokens[start, end)`) and the nodes each one becomes. */
        fun documentGroups(tokens: List<MdToken>): List<Group> {
            val groups = ArrayList<Group>()
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                if (token.type == "html_block") {
                    groups += Group(i, i + 1, StudioHtml.htmlToNodes(token.content))
                    i++
                    continue
                }
                val r = blockToken(tokens, i)
                groups += Group(i, r.next, listOfNotNull(r.node))
                i = r.next
            }
            return groups
        }

        fun blockToken(tokens: List<MdToken>, start: Int): Result {
            val token = tokens[start]
            when (token.type) {
                "hr" -> return Result(StudioElement("hr"), start + 1)
                "html_block" -> return Result(StudioHtml.htmlToNodes(token.content).firstOrNull(), start + 1)
                "mdc_block_open" -> {
                    val name = token.tag.ifEmpty { "component" }
                    val attrs = processAttributes(token.attrs)
                    val children = blockChildrenWithSlots(tokens, start + 1, "mdc_block_close")
                    val only = children.nodes.singleOrNull()
                    if (name in WRAPPER_TAGS && only is StudioElement && only.tag == name) {
                        val merged = LinkedHashMap(only.attrs)
                        merged.putAll(attrs)
                        return Result(StudioElement(name, merged, only.children), children.next + 1)
                    }
                    return Result(StudioElement(name, attrs, children.nodes), children.next + 1)
                }
                "mdc_block_shorthand" -> {
                    var next = start + 1
                    val name = token.tag.ifEmpty { "component" }
                    val attrs = processAttributes(token.attrs, handleJson = false)
                    val children = ArrayList<StudioNode>()
                    if (token.nesting == 1) {
                        while (next < tokens.size) {
                            val child = tokens[next]
                            next++
                            if (child.type == "mdc_block_shorthand" && child.nesting == -1) break
                            if (child.type == "inline") children += inlineTokens(child.children ?: emptyList(), false)
                        }
                    }
                    return Result(StudioElement(name, attrs, children), next)
                }
                "fence", "code_block" -> {
                    val content = token.content
                    val pre = parseCodeblockInfo(token.info)
                    val codeAttrs = LinkedHashMap<String, Any?>()
                    val language = pre["language"] as String?
                    if (language != null && StudioJs.trim(language).isNotEmpty()) {
                        pre["language"] = language
                        codeAttrs["class"] = "language-$language"
                    }
                    val code = StudioElement("code", codeAttrs, listOf(StudioText(if (content.endsWith("\n")) content.dropLast(1) else content)))
                    return Result(StudioElement("pre", pre, listOf(code)), start + 1)
                }
                "heading_open" -> {
                    val level = token.tag.removePrefix("h").toIntOrNull() ?: 0
                    val userAttrs = processAttributes(token.attrs, handleJson = false)
                    val children = blockChildren(tokens, start + 1, "heading_close", inlineOnly = true, inHeading = true)
                    if (children.nodes.isNotEmpty()) {
                        val text = children.nodes.joinToString("") { textContent(it) }
                        val id = uniqueSlug(slugify(text), level)
                        val attrs = LinkedHashMap<String, Any?>()
                        attrs["id"] = id
                        attrs.putAll(userAttrs)
                        return Result(StudioElement("h$level", attrs, children.nodes), children.next + 1)
                    }
                    return Result(null, children.next + 1)
                }
                "list_item_open" -> {
                    val attrs = processAttributes(token.attrs, handleJson = false)
                    val children = blockChildren(tokens, start + 1, "list_item_close", inlineOnly = false, inHeading = false)
                    if (children.nodes.isNotEmpty()) return Result(StudioElement("li", attrs, children.nodes), children.next + 1)
                    return Result(null, children.next + 1)
                }
            }
            val tagName = BLOCK_TAG_MAP[token.type]
            if (tagName != null) {
                val attrs = processAttributes(token.attrs, handleJson = false)
                val closeType = token.type.replace("_open", "_close")
                val children = blockChildren(tokens, start + 1, closeType, inlineOnly = false, inHeading = false)
                return Result(StudioElement(tagName, attrs, children.nodes), children.next + 1)
            }
            val name = token.tag.ifEmpty { "component" }
            return Result(StudioElement(name, processAttributes(token.attrs, handleJson = false)), start + 1)
        }

        fun blockChildrenWithSlots(tokens: List<MdToken>, startIndex: Int, closeType: String): Many {
            val nodes = ArrayList<StudioNode>()
            var i = startIndex
            var slotName: String? = null
            var slotAttrs: Map<String, Any?> = emptyMap()
            var slotChildren = ArrayList<StudioNode>()
            fun template(): StudioElement {
                val attrs = LinkedHashMap<String, Any?>()
                attrs["name"] = slotName
                attrs.putAll(slotAttrs)
                return StudioElement("template", attrs, slotChildren)
            }
            while (i < tokens.size && tokens[i].type != closeType) {
                val token = tokens[i]
                if (token.type == "html_block") {
                    val html = StudioHtml.htmlToNodes(token.content)
                    if (slotName != null) slotChildren.addAll(html) else nodes.addAll(html)
                    i++
                    continue
                }
                if (token.type == "mdc_block_slot") {
                    val attrs = token.attrs
                    if (!attrs.isNullOrEmpty()) {
                        val slotKey = attrs[0].name
                        if (slotKey.startsWith("#")) {
                            val name = slotKey.substring(1)
                            val newAttrs = processAttributes(attrs.subList(1, attrs.size))
                            if (slotName != null && slotChildren.isNotEmpty()) {
                                nodes += template()
                                slotChildren = ArrayList()
                            }
                            slotName = name
                            slotAttrs = newAttrs
                            i++
                            continue
                        }
                    }
                    i++
                    continue
                }
                val r = blockToken(tokens, i)
                i = r.next
                r.node?.let { if (slotName != null) slotChildren += it else nodes += it }
            }
            if (slotName != null && slotChildren.isNotEmpty()) nodes += template()
            return Many(nodes, i)
        }

        fun blockChildren(tokens: List<MdToken>, startIndex: Int, closeType: String, inlineOnly: Boolean, inHeading: Boolean): Many {
            val nodes = ArrayList<StudioNode>()
            var i = startIndex
            while (i < tokens.size && tokens[i].type != closeType) {
                val token = tokens[i]
                when {
                    token.type == "html_block" -> {
                        nodes += StudioHtml.htmlToNodes(token.content)
                        i++
                    }
                    token.type == "inline" -> {
                        nodes += inlineTokens(token.children ?: emptyList(), inHeading)
                        i++
                    }
                    token.type == "hardbreak" || token.type == "hard_break" -> {
                        nodes += StudioElement("br"); i++
                    }
                    token.type == "softbreak" -> {
                        nodes += StudioText("\n"); i++
                    }
                    inlineOnly && (token.type == "text" || token.type == "code_inline") -> {
                        if (token.content.isNotEmpty()) nodes += StudioText(token.content)
                        i++
                    }
                    else -> {
                        val r = blockToken(tokens, i)
                        i = r.next
                        r.node?.let { nodes += it }
                    }
                }
            }
            return Many(mergeText(nodes), i)
        }

        fun uniqueSlug(slugIn: String, level: Int): String {
            var slug = slugIn
            while (headingStack.isNotEmpty() && headingStack.last().first >= level) headingStack.removeAt(headingStack.size - 1)
            if (headingStack.isNotEmpty()) {
                val parent = headingStack.last()
                if (parent.first >= 2) slug = parent.second + "-" + slug
            }
            headingStack += level to slug
            val count = headingSlugCounts[slug] ?: 0
            headingSlugCounts[slug] = count + 1
            return if (count == 0) slug else "$slug-$count"
        }

        fun inlineTokens(tokens: List<MdToken>, inHeading: Boolean): List<StudioNode> {
            val nodes = ArrayList<StudioNode>()
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                if (token.type == "mdc_inline_props" && token.hidden) {
                    i++
                    continue
                }
                val r = inlineToken(tokens, i, inHeading, 0)
                i = r.next
                r.node?.let { nodes += it }
            }
            return mergeText(nodes)
        }

        fun extractAttributes(tokens: List<MdToken>, startIndex: Int, skipEmptyText: Boolean = true): Pair<Map<String, Any?>, Int> {
            var propsIndex = startIndex
            if (skipEmptyText) {
                while (propsIndex < tokens.size && tokens[propsIndex].type == "text" && StudioJs.trim(tokens[propsIndex].content).isEmpty()) propsIndex++
            }
            if (propsIndex < tokens.size && tokens[propsIndex].type == "mdc_inline_props") {
                return processAttributes(tokens[propsIndex].attrs) to propsIndex + 1
            }
            return emptyMap<String, Any?>() to startIndex
        }

        fun inlineToken(tokens: List<MdToken>, start: Int, inHeading: Boolean, htmlDepth: Int): Result {
            val token = tokens[start]
            when (token.type) {
                "text", "emoji" -> return Result(token.content.takeIf { it.isNotEmpty() }?.let { StudioText(it) }, start + 1)
                "html_inline" -> {
                    val content = token.content
                    val info = StudioHtml.parseInlineHtmlTag(content)
                        ?: return Result(content.takeIf { it.isNotEmpty() }?.let { StudioText(it) }, start + 1)
                    if (info.isClose) return Result(null, start + 1)
                    if (info.isVoid) return Result(StudioElement(info.tag, info.attrs), start + 1)
                    if (htmlDepth >= 100) return Result(content.takeIf { it.isNotEmpty() }?.let { StudioText(it) }, start + 1)
                    val children = ArrayList<StudioNode>()
                    var j = start + 1
                    while (j < tokens.size) {
                        val next = tokens[j]
                        if (next.type == "html_inline") {
                            val nextInfo = StudioHtml.parseInlineHtmlTag(next.content)
                            if (nextInfo != null && nextInfo.isClose && nextInfo.tag == info.tag) {
                                j++
                                break
                            }
                        }
                        val r = inlineToken(tokens, j, inHeading, htmlDepth + 1)
                        j = r.next
                        r.node?.let { children += it }
                    }
                    return Result(StudioElement(info.tag, info.attrs, children), j)
                }
                "mdc_inline_span" -> {
                    if (token.nesting == 1) {
                        var i = start + 1
                        val nodes = ArrayList<StudioNode>()
                        while (i < tokens.size) {
                            val child = tokens[i]
                            if (child.type == "mdc_inline_span" && child.nesting == -1) break
                            if (child.type == "text" && StudioJs.trim(child.content).isEmpty()) {
                                i++
                                continue
                            }
                            val r = inlineToken(tokens, i, inHeading, htmlDepth)
                            i = r.next
                            r.node?.let { nodes += it }
                        }
                        val (spanAttrs, next) = extractAttributes(tokens, i + 1)
                        if (nodes.isNotEmpty() || spanAttrs.isNotEmpty()) return Result(StudioElement("span", LinkedHashMap(spanAttrs), nodes), next)
                        return Result(null, next)
                    }
                    if (token.nesting == -1) return Result(null, start + 1)
                }
                "code_inline" -> {
                    val (attrs, next) = extractAttributes(tokens, start + 1)
                    if (token.content.isNotEmpty()) return Result(StudioElement("code", attrs, listOf(StudioText(token.content))), next)
                    return Result(null, next)
                }
                "hardbreak", "hard_break" -> return Result(StudioElement("br"), start + 1)
                "softbreak" -> return Result(StudioText("\n"), start + 1)
                "mdc_inline_component" -> {
                    val name = token.tag.ifEmpty { "component" }
                    if (token.nesting == 1) {
                        val children = ArrayList<StudioNode>()
                        var i = start + 1
                        while (i < tokens.size) {
                            val child = tokens[i]
                            if (child.type == "mdc_inline_component" && child.nesting == -1) {
                                val (attrs, next) = extractAttributes(tokens, i + 1, false)
                                return Result(StudioElement(name, attrs, children), next)
                            }
                            val r = inlineToken(tokens, i, inHeading, htmlDepth)
                            i = r.next
                            r.node?.let { children += it }
                        }
                        return Result(StudioElement(name, emptyMap(), children), i)
                    } else if (token.nesting == -1) {
                        return Result(null, start + 1)
                    } else {
                        val attrs = LinkedHashMap<String, Any?>()
                        val (componentAttrs, propsNext) = extractAttributes(tokens, start + 1, false)
                        attrs.putAll(componentAttrs)
                        attrs.putAll(processAttributes(token.attrs))
                        val next = if (componentAttrs.isNotEmpty()) propsNext else start + 1
                        return Result(StudioElement(name, attrs), next)
                    }
                }
                "image" -> {
                    val attrs = processAttributes(token.attrs, handleJson = false, filterEmpty = true)
                    if (token.content.isNotEmpty()) attrs["alt"] = token.content
                    val (imageAttrs, next) = extractAttributes(tokens, start + 1)
                    attrs.putAll(imageAttrs)
                    return Result(StudioElement("img", attrs), next)
                }
                "link_open" -> {
                    val attrs = processAttributes(token.attrs, handleJson = false)
                    val children = inlineChildren(tokens, start + 1, "link_close", inHeading)
                    val (linkAttrs, next) = extractAttributes(tokens, children.next + 1)
                    attrs.putAll(linkAttrs)
                    if (children.nodes.isNotEmpty()) return Result(StudioElement("a", attrs, children.nodes), next)
                    return Result(null, next)
                }
            }
            val tagName = INLINE_TAG_MAP[token.type]
            if (tagName != null) {
                val closeType = token.type.replace("_open", "_close")
                val children = inlineChildren(tokens, start + 1, closeType, inHeading)
                val (attrs, next) = extractAttributes(tokens, children.next + 1)
                if (children.nodes.isNotEmpty()) return Result(StudioElement(tagName, attrs, children.nodes), next)
                return Result(null, next)
            }
            val kids = token.children
            if (kids != null) {
                val nested = inlineTokens(kids, inHeading)
                return Result(if (nested.size == 1) nested[0] else null, start + 1)
            }
            return Result(null, start + 1)
        }

        fun inlineChildren(tokens: List<MdToken>, startIndex: Int, closeType: String, inHeading: Boolean): Many {
            val nodes = ArrayList<StudioNode>()
            var i = startIndex
            while (i < tokens.size) {
                val token = tokens[i]
                if (token.type == closeType) {
                    if (closeType == "mdc_inline_span") {
                        if (token.nesting == -1) break
                    } else {
                        break
                    }
                }
                if (token.type == "mdc_inline_props" && token.hidden) {
                    i++
                    continue
                }
                if (token.type == "mdc_inline_component" && inHeading) {
                    val name = token.tag.ifEmpty { "component" }
                    val (componentAttrs, next) = extractAttributes(tokens, i + 1, false)
                    i = if (componentAttrs.isNotEmpty()) next else i + 1
                    nodes += StudioElement(name, LinkedHashMap(componentAttrs))
                    continue
                }
                val r = inlineToken(tokens, i, inHeading, 0)
                i = r.next
                r.node?.let { nodes += it }
            }
            return Many(mergeText(nodes), i)
        }
    }

    private fun mergeText(nodes: List<StudioNode>): List<StudioNode> {
        val merged = ArrayList<StudioNode>()
        for (node in nodes) {
            val last = merged.lastOrNull()
            if (node is StudioText && last is StudioText) merged[merged.size - 1] = StudioText(last.text + node.text) else merged += node
        }
        return merged
    }

    /** comark `processAttributes` */
    private fun processAttributes(attrs: List<MdAttr>?, handleJson: Boolean = true, filterEmpty: Boolean = false): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (attrs == null) return out
        for (attr in attrs) {
            val key = attr.name
            var value = attr.value
            if (filterEmpty && (value == "" || value == null || value === JsUndefined)) continue
            if (handleJson && value is String) {
                if ((value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"))) {
                    try {
                        value = StudioJs.jsonParse(value)
                    } catch (_: StudioJs.JsonSyntaxError) {
                    }
                }
            }
            val existing = out[key]
            if (key == "class" && existing is String) out[key] = "$existing ${StudioJs.str(value)}" else out[key] = value
        }
        return out
    }

    private val LANGUAGE_RE = Regex("^([^\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff\\[{}\"'<>`]+)")
    private val HIGHLIGHTS_RE = Regex("^\\{([^}]+)}")

    /** comark `parseInt(s, 10)`: leading sign and digits after leading whitespace, or NaN (null). */
    private fun jsParseInt(s: String): Double? {
        val t = StudioJs.trimStart(s)
        val m = Regex("^[+-]?[0-9]+").find(t) ?: return null
        return m.value.toBigDecimal().toDouble()
    }

    internal fun parseCodeblockInfo(info: String): LinkedHashMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        if (info.isEmpty()) return result
        var remaining = StudioJs.trim(info)
        LANGUAGE_RE.find(remaining)?.let {
            result["language"] = it.groupValues[1]
            remaining = StudioJs.trim(remaining.substring(it.groupValues[1].length))
        }
        while (remaining.isNotEmpty() && (remaining.startsWith("{") || remaining.startsWith("["))) {
            if (remaining.startsWith("{")) {
                val m = HIGHLIGHTS_RE.find(remaining) ?: break
                remaining = StudioJs.trim(remaining.substring(m.value.length))
                val highlights = ArrayList<Any?>()
                for (part in m.groupValues[1].split(',')) {
                    val trimmed = StudioJs.trim(part)
                    if (trimmed.contains('-')) {
                        val pieces = trimmed.split('-')
                        val start = jsParseInt(StudioJs.trim(pieces[0]))
                        val end = jsParseInt(StudioJs.trim(pieces.getOrElse(1) { "" }))
                        if (start != null && end != null && end - start <= 1000) {
                            var i = start
                            while (i <= end && highlights.size < 1000) {
                                highlights += i
                                i += 1.0
                            }
                        }
                    } else {
                        val num = jsParseInt(trimmed)
                        if (num != null && highlights.size < 1000) highlights += num
                    }
                }
                if (highlights.isNotEmpty()) result["highlights"] = highlights
            } else {
                var depth = 0
                var i = 0
                while (i < remaining.length) {
                    if (remaining[i] == '[') {
                        depth++
                    } else if (remaining[i] == ']') {
                        depth--
                        if (depth == 0) {
                            result["filename"] = remaining.substring(1, i).replace("\\\\", "")
                            remaining = StudioJs.trim(remaining.substring(i + 1))
                            break
                        }
                    }
                    i++
                }
                if (depth != 0) break
            }
        }
        if (remaining.isNotEmpty()) result["meta"] = remaining
        return result
    }

    private fun textContent(node: StudioNode): String = when (node) {
        is StudioText -> node.text
        is StudioElement -> node.children.joinToString("") { textContent(it) }
        is StudioComment -> node.text ?: "undefined"
    }

    private val WS_RUN = Regex("[\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")
    private val NON_WORD = Regex("[^A-Za-z0-9_-]+")
    private val DASHES = Regex("-{2,}")
    private val EDGE_DASHES = Regex("^-+|-+\\z")

    private fun slugify(text: String): String {
        var slug = StudioJs.trim(text.lowercase(java.util.Locale.ROOT))
        slug = WS_RUN.replace(slug, "-")
        slug = NON_WORD.replace(slug, "")
        slug = DASHES.replace(slug, "-")
        slug = EDGE_DASHES.replace(slug, "")
        if (slug.isNotEmpty() && slug[0] in '0'..'9') slug = "_$slug"
        return slug
    }

    // ---- autoUnwrap (internal/parse/auto-unwrap.js) --------------------------------------------------------------------

    private fun autoUnwrap(node: StudioNode): StudioNode {
        if (node !is StudioElement) return node
        val children = node.children
        val nonEmpty = children.filter { it !is StudioText || StudioJs.trim(it.text).isNotEmpty() }
        if (nonEmpty.isEmpty()) return node
        val first = nonEmpty[0]
        if (nonEmpty.size > 1 || first !is StudioElement || first.tag != "p") {
            return StudioElement(node.tag, node.attrs, children.map { autoUnwrap(it) })
        }
        val merged = if (first.attrs.isNotEmpty()) LinkedHashMap(first.attrs).also { it.putAll(node.attrs) } else node.attrs
        return StudioElement(node.tag, merged, first.children)
    }

    // ---- alert (plugins/alert.js) ----------------------------------------------------------------------------------------

    private val ALERT_TYPES = mapOf("!TIP" to "tip", "!NOTE" to "note", "!IMPORTANT" to "important", "!WARNING" to "warning", "!CAUTION" to "caution")

    /** `String(node)` of a comark tuple: arrays join with `,`, objects are `[object Object]`. */
    private fun jsString(node: StudioNode?): String = when (node) {
        null -> "undefined"
        is StudioText -> node.text
        is StudioElement -> (listOf(node.tag, "[object Object]") + node.children.map { jsString(it) }).joinToString(",")
        is StudioComment -> listOf("", "[object Object]", node.text ?: "").joinToString(",")
    }

    private fun alertMarker(span: StudioNode?): String? {
        if (span !is StudioElement || span.tag != "span") return null
        return ALERT_TYPES[jsString(span.children.firstOrNull()).uppercase(java.util.Locale.ROOT)]
    }

    private fun stripMarker(children: List<StudioNode>): List<StudioNode> {
        val rest = children.drop(1).toMutableList()
        val second = rest.firstOrNull()
        if (second is StudioText) rest[0] = StudioText(StudioJs.trimStart(second.text))
        return rest
    }

    private fun alert(node: StudioNode): StudioNode {
        if (node !is StudioElement) return node
        var current = node
        if (node.tag == "blockquote") {
            val first = node.children.firstOrNull()
            val type = alertMarker(first)
            if (type != null) {
                current = StudioElement(node.tag, LinkedHashMap(node.attrs).also { it["as"] = type }, stripMarker(node.children))
            } else if (first is StudioElement && first.tag == "p") {
                val pType = alertMarker(first.children.firstOrNull())
                if (pType != null) {
                    val p = StudioElement("p", first.attrs, stripMarker(first.children))
                    current = StudioElement(node.tag, LinkedHashMap(node.attrs).also { it["as"] = pType }, listOf(p) + node.children.drop(1))
                }
            }
        }
        return StudioElement(current.tag, current.attrs, current.children.map { alert(it) })
    }

    // ---- shiki (internal/shiki.js highlightCodeBlocks, with highlighting failing) ----------------------------------------

    /**
     * Studio passes `github-light`/`github-dark` theme names that comark's shiki plugin never loads, so every
     * `codeToTokens` call throws and the catch branch only marks the `<pre>` with `class: "shiki"` (or `shiki . user`) and
     * rebuilds it around the code element's first text. The code blocks are collected first and then written back by
     * their index path into copies of the tree, exactly like `highlightCodeBlocks`, which matters when a `<pre>` holds
     * another one. Nodes are handled as JavaScript-like arrays here (`[tag, attrs, ...children]`, strings for text).
     */
    @Suppress("UNCHECKED_CAST")
    private fun shiki(nodes: List<StudioNode>): List<StudioNode> {
        fun toJs(n: StudioNode): Any = when (n) {
            is StudioText -> n.text
            is StudioElement -> ArrayList<Any?>(n.children.size + 2).also { a -> a += n.tag; a += n.attrs; n.children.forEach { a += toJs(it) } }
            is StudioComment -> ArrayList<Any?>().also { a -> a += null; a += n.attrs; if (n.text != null) a += n.text }
        }

        fun fromJs(v: Any?): StudioNode = when (v) {
            is String -> StudioText(v)
            is List<*> -> if (v[0] == null) {
                StudioComment(v.getOrNull(2) as String?, v[1] as Map<String, Any?>)
            } else {
                StudioElement(v[0] as String, v[1] as Map<String, Any?>, v.drop(2).map { fromJs(it) })
            }
            else -> throw StudioUnsupportedException("shiki rebuilt a code block into a malformed tree")
        }

        fun isCodeBlock(n: List<*>): Boolean {
            val code = n.getOrNull(2)
            return n[0] == "pre" && code is List<*> && code[0] == "code" && code.getOrNull(2) is String
        }

        val tree = nodes.map { toJs(it) }.toMutableList<Any?>()
        val blocks = ArrayList<Pair<List<Any?>, List<Int>>>()
        val path = ArrayList<Int>()
        fun walkChildren(el: List<Any?>) {
            for (i in 2 until el.size) {
                val child = el[i]
                if (child !is List<*> || child.size < 3) continue
                path += i - 2
                if (isCodeBlock(child)) blocks += (child as List<Any?>) to path.toList()
                walkChildren(child as List<Any?>)
                path.removeAt(path.size - 1)
            }
        }
        for ((i, node) in tree.withIndex()) {
            if (node !is List<*> || node.size < 3) continue
            if (isCodeBlock(node)) blocks += (node as List<Any?>) to listOf(i)
            path.clear()
            path += i
            walkChildren(node as List<Any?>)
        }
        if (blocks.isEmpty()) return nodes
        for ((node, at) in blocks) {
            val codeEl = node[2] as List<Any?>
            val code = codeEl[2] as String
            val preAttrs = node[1] as Map<String, Any?>
            val userClass = (preAttrs["class"] as? String)?.let { StudioJs.trim(it) } ?: ""
            val newPreAttrs = LinkedHashMap(preAttrs)
            newPreAttrs["class"] = if (userClass.isNotEmpty()) "shiki . $userClass" else "shiki"
            val newPre = arrayListOf<Any?>("pre", newPreAttrs, arrayListOf<Any?>("code", codeEl[1] ?: emptyMap<String, Any?>(), code))
            if (at.size == 1) {
                tree[at[0]] = newPre
                continue
            }
            fun copy(v: Any?): ArrayList<Any?> = (v as? List<Any?>)?.let { ArrayList(it) }
                ?: throw StudioUnsupportedException("shiki rewrote a nested code block through a text node")
            var current = copy(tree[at[0]])
            tree[at[0]] = current
            for (j in 1 until at.size - 1) {
                val slot = at[j] + 2
                val next = copy(current.getOrNull(slot))
                current[slot] = next
                current = next
            }
            val slot = at.last() + 2
            when {
                slot < current.size -> current[slot] = newPre
                slot == current.size -> current.add(newPre)
                else -> throw StudioUnsupportedException("shiki wrote a nested code block past the end of its parent")
            }
        }
        return tree.map { fromJs(it) }
    }

    // ---- Studio unbindComarkTree (utils/document/legacy.js) --------------------------------------------------------------

    private fun unbind(node: StudioNode): StudioNode {
        if (node !is StudioElement) return node
        val next = LinkedHashMap<String, Any?>()
        @Suppress("UNCHECKED_CAST")
        for ((rawKey, value) in StudioJs.entries(node.attrs)) {
            if (rawKey.startsWith(":") && value is String) {
                val parsed = try {
                    StudioJs.jsonParse(value)
                } catch (_: StudioJs.JsonSyntaxError) {
                    JsUndefined
                }
                if (parsed is Map<*, *> || parsed is List<*>) {
                    next[rawKey.substring(1)] = parsed
                    continue
                }
            }
            next[rawKey] = value
        }
        return StudioElement(node.tag, next, node.children.map { unbind(it) })
    }
}

/** markdown-exit's `Parser#parse` with comark's core rules and parse hook: tokens for one Markdown document. */
internal object StudioMarkdownIt {
    private val TASK_RE = Regex("^(\\[[ xX]])[\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

    fun parse(input: String, env: MdEnv = MdEnv()): List<MdToken> {
        var src = input
        if (src.contains('\r')) src = src.replace(Regex("\r\n?"), "\n")
        if (src.contains('\u0000')) src = src.replace('\u0000', '�')
        val tokens = ArrayList<MdToken>()
        if (src.isNotEmpty()) {
            val state = MdStateBlock(src, env, tokens)
            StudioMdBlock.tokenize(state, state.line, state.lineMax)
        }
        taskListsPre(tokens)
        for (t in tokens) if (t.type == "inline") StudioMdInline.parse(t.content, env, t.children!!)
        taskListsPost(tokens)
        textJoin(tokens)
        inlinePropsHook(tokens)
        return tokens
    }

    private fun taskListsPre(tokens: List<MdToken>) {
        val openItems = ArrayList<Int>()
        val openItemLists = ArrayList<Int>()
        val openLists = ArrayList<Int>()
        fun attrSet(t: MdToken, name: String, value: String) {
            val idx = t.attrIndex(name)
            if (idx < 0) t.attrPush(MdAttr(name, value)) else t.attrs!![idx] = MdAttr(name, value)
        }
        for ((i, token) in tokens.withIndex()) {
            when (token.type) {
                "bullet_list_open", "ordered_list_open" -> openLists += i
                "bullet_list_close", "ordered_list_close" -> if (openLists.isNotEmpty()) openLists.removeAt(openLists.size - 1)
                "list_item_open" -> {
                    openItems += i
                    openItemLists += if (openLists.isNotEmpty()) openLists.last() else -1
                }
                "list_item_close" -> {
                    if (openItems.isNotEmpty()) openItems.removeAt(openItems.size - 1)
                    if (openItemLists.isNotEmpty()) openItemLists.removeAt(openItemLists.size - 1)
                }
                "inline" -> if (token.content.isNotEmpty() && openItems.isNotEmpty()) {
                    val m = TASK_RE.find(token.content)
                    if (m != null) {
                        val checked = m.groupValues[1].lowercase() == "[x]"
                        attrSet(tokens[openItems.last()], "class", "task-list-item")
                        val listIdx = openItemLists.last()
                        if (listIdx >= 0) attrSet(tokens[listIdx], "class", "contains-task-list")
                        token.content = "TASK_CHECKBOX_${if (checked) "CHECKED" else "UNCHECKED"} " + token.content.substring(m.value.length)
                    }
                }
            }
        }
    }

    private fun taskListsPost(tokens: List<MdToken>) {
        for (token in tokens) {
            val children = token.children ?: continue
            if (token.type != "inline") continue
            var j = 0
            while (j < children.size) {
                val child = children[j]
                if (child.type == "text" && child.content.isNotEmpty()) {
                    val checked = child.content.startsWith("TASK_CHECKBOX_CHECKED")
                    val unchecked = child.content.startsWith("TASK_CHECKBOX_UNCHECKED")
                    if (checked || unchecked) {
                        val checkbox = MdToken("mdc_inline_component", "input", 0)
                        checkbox.attrs = mutableListOf(MdAttr("class", "task-list-item-checkbox"), MdAttr("type", "checkbox"), MdAttr(":disabled", "true"))
                        if (checked) checkbox.attrs!! += MdAttr(":checked", "true")
                        child.content = child.content.removePrefix(if (checked) "TASK_CHECKBOX_CHECKED" else "TASK_CHECKBOX_UNCHECKED")
                        children.add(j, checkbox)
                        j++
                    }
                }
                j++
            }
        }
    }

    private fun textJoin(tokens: List<MdToken>) {
        for (block in tokens) {
            if (block.type != "inline") continue
            val children = block.children ?: continue
            for (t in children) if (t.type == "text_special") t.type = "text"
            var last = 0
            var curr = 0
            val max = children.size
            while (curr < max) {
                if (children[curr].type == "text" && curr + 1 < max && children[curr + 1].type == "text") {
                    children[curr + 1].content = children[curr].content + children[curr + 1].content
                } else {
                    if (curr != last) children[last] = children[curr]
                    last++
                }
                curr++
            }
            while (children.size > last) children.removeAt(children.size - 1)
        }
    }

    private val TRAILING_BLANKS = Regex("[ \\t]+\\z")

    /** comark `attributes` plugin's `md.parse` wrapper: trailing `{props}` of a heading, paragraph or list item apply to it. */
    private fun inlinePropsHook(tokens: List<MdToken>) {
        for (index in tokens.indices) {
            var token = tokens[index]
            val prev = tokens.getOrNull(index - 1) ?: continue
            val next = tokens.getOrNull(index + 1)
            if (prev.type !in setOf("heading_open", "paragraph_open", "list_item_open") || prev.hidden) continue
            if (token.hidden && next?.type == "inline") token = next
            val children = token.children
            if (token.type != "inline" || children.isNullOrEmpty()) continue
            val last = children.last()
            if (last.type != "mdc_inline_props") continue
            var beforeIdx = children.size - 2
            while (beforeIdx >= 0) {
                val child = children[beforeIdx]
                if (child.type == "text" && child.content.isEmpty()) {
                    beforeIdx--
                    continue
                }
                break
            }
            val beforeProps = if (beforeIdx >= 0) children[beforeIdx] else null
            if (beforeProps == null || beforeProps.type != "text") continue
            beforeProps.content = TRAILING_BLANKS.replace(beforeProps.content, "")
            val props = last.attrs
            val newLength = if (beforeProps.content.isNotEmpty()) beforeIdx + 1 else beforeIdx
            while (children.size > newLength) children.removeAt(children.size - 1)
            props?.forEach { if (it.name == "class") prev.attrJoin("class", it.value) else prev.attrSet(it.name, it.value) }
        }
    }
}

/**
 * The front matter block of a [StudioSegmentedDocument]: [raw] is the source up to the end of the closing `---`, and
 * [contentLine] the source line the content starts on (the rest of the closing line).
 */
internal class StudioFrontmatterBlock(val raw: String, val yaml: String?, val data: Any?, val error: String?, val contentLine: Int)

/**
 * One top-level block: content lines `[startLine, endLine)` (trailing blank lines excluded), their [source] text, the
 * markdown-it tokens of the block and the comark nodes made from it (possibly none).
 */
internal class StudioSegment(val startLine: Int, val endLine: Int, val source: String, val tokens: List<MdToken>, val nodes: List<StudioNode>)

/**
 * [StudioTreeBuilder.buildSegmented]: the [content] after the front matter (newlines normalized to `\n` as markdown-it
 * does), its [segments], and the content lines of props blocks whose YAML did not load.
 */
internal class StudioSegmentedDocument(
    val text: String,
    val frontmatter: StudioFrontmatterBlock?,
    val content: String,
    val segments: List<StudioSegment>,
    val propsYamlErrorLines: List<Int>,
) {
    /** Content line [line] as a line of [text] (counting `\r\n`, `\r` and `\n` as one line break each). */
    fun sourceLine(line: Int): Int = line + (frontmatter?.contentLine ?: 0)
}
