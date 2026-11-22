package cz.xprees.comark.format.studio

/**
 * comark 0.9.2's side of the parse that nuxt-studio 1.8.2's text path runs (`parseMarkdown` with `autoUnwrap: true` and the
 * frontmatter, html, alert, task-list, components, attributes, emoji, shiki and toc plugins): the `html` plugin's
 * `markdownItPost` ([htmlBlocksToParagraphs]), the token-to-tree conversion (`internal/parse/tree.js`, [tokenListToTree]),
 * `applyAutoUnwrap` (`internal/parse/utils.js`) and the alert plugin's `visit` (which also drops whatever lies 50 levels
 * deep).
 *
 * The tree is built as comark builds it, as JavaScript-like arrays (`[tag, attrs, ...children]` in an [ArrayList], text as
 * [String], a comment as `[null, attrs, text]`), because comark mutates nodes in place (`node[1] = Object.assign(...)`,
 * `splice`) and the result depends on that; [toNodes] turns it into [StudioNode]s at the end.
 */
internal object StudioTreeBuilder09 {
    private val INLINE_TAGS = setOf("strong", "em", "del", "code", "a", "span", "sub", "sup", "kbd")
    private val WRAPPER_TAGS = setOf("ul", "ol", "table", "blockquote", "pre")

    /** `TREE_WALK_MAX_DEPTH` (`comark/utils`) */
    private const val TREE_WALK_MAX_DEPTH = 50

    private class Frame(
        val tag: String?,
        val attrs: MutableMap<String, Any?>,
        val children: ArrayList<Any?>,
        var block: Boolean,
        var pendingInline: ArrayList<Any?> = ArrayList(),
    )

    private class HeadingIds {
        val slugCounts = HashMap<String, Int>()
        val usedIds = HashSet<String>()
    }

    private class State {
        val headingIds = HeadingIds()
        var htmlStack = ArrayList<Frame>()
    }

    /** A processor's `{ nextIndex, node, siblingOfHtmlStack }`; a `null` [node] is JavaScript's `undefined`. */
    private class Res(var node: Any?, var nextIndex: Int, val siblingOfHtmlStack: Boolean = false)

    private class Children(val children: ArrayList<Any?>, val nextIndex: Int)

    private fun el(tag: String?, attrs: MutableMap<String, Any?>, children: List<Any?> = emptyList()): ArrayList<Any?> =
        ArrayList<Any?>(children.size + 2).also { a ->
            a.add(tag)
            a.add(attrs)
            a.addAll(children)
        }

    private fun tagOf(node: Any?): Any? = (node as? List<*>)?.getOrNull(0)

    @Suppress("UNCHECKED_CAST")
    private fun attrsOf(node: Any?): MutableMap<String, Any?>? = (node as? List<*>)?.getOrNull(1) as? MutableMap<String, Any?>

    private fun makeHtmlAttrs(attrs: Map<String, Any?>, block: Boolean): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["\$"] = linkedMapOf<String, Any?>("html" to 1.0, "block" to if (block) 1.0 else 0.0)
        out.putAll(attrs)
        return out
    }

    private fun pushNode(nodes: MutableList<Any?>, node: Any?) {
        if (node is List<*> && node[0] == "fragment") nodes.addAll(node.subList(2, node.size)) else nodes.add(node)
    }

    private fun isInlineish(node: Any?): Boolean {
        if (node is String) return true
        if (node !is List<*>) return false
        val tag = node[0]
        return tag == null || tag in INLINE_TAGS
    }

    private fun flushPendingInline(frame: Frame) {
        if (frame.pendingInline.isEmpty()) return
        val pending = frame.pendingInline
        frame.pendingInline = ArrayList()
        frame.children.add(el("p", LinkedHashMap(), pending))
    }

    private fun flattenText(nodes: List<Any?>): String {
        val sb = StringBuilder()
        for (n in nodes) {
            if (n is String) sb.append(n) else if (n is List<*>) sb.append(flattenText(n.subList(minOf(2, n.size), n.size)))
        }
        return sb.toString()
    }

    private fun frameToNode(frame: Frame): ArrayList<Any?> {
        flushPendingInline(frame)
        if (frame.tag == null) {
            val chunks = frame.children.map { c ->
                if (c is String) c else flattenText(if (c is List<*> && c[0] == "p") c.subList(2, c.size) else listOf(c))
            }
            val body = if (chunks.isNotEmpty()) "\n\n${chunks.joinToString("\n\n")}\n\n" else ""
            return el(null, LinkedHashMap(), listOf(body))
        }
        return el(frame.tag, makeHtmlAttrs(frame.attrs, frame.block), frame.children)
    }

    private fun deliverBlock(state: State, node: Any?): Any? {
        if (node == null) return null
        if (state.htmlStack.isEmpty()) return node
        val top = state.htmlStack.last()
        flushPendingInline(top)
        top.children.add(node)
        return null
    }

    private fun deliverInline(state: State, node: Any?): Any? {
        if (node == null) return null
        if (state.htmlStack.isEmpty()) return node
        val top = state.htmlStack.last()
        if (top.block && isInlineish(node)) {
            top.pendingInline.add(node)
        } else {
            flushPendingInline(top)
            top.children.add(node)
        }
        return null
    }

    /** `tryCommentTag`: a comment opened in one token and closed in a later one; `null` when [content] is not part of one. */
    private fun tryCommentTag(content: String, state: State): Res? {
        val top = state.htmlStack.lastOrNull()
        if (top != null && top.tag == null && content.contains("-->")) {
            val i = content.indexOf("-->")
            if (i > 0) deliverInline(state, content.substring(0, i))
            val frame = state.htmlStack.removeAt(state.htmlStack.size - 1)
            return Res(frameToNode(frame), -1)
        }
        if (content.startsWith("<!--") && !content.contains("-->")) {
            val body = content.substring(4)
            state.htmlStack += Frame(null, LinkedHashMap(), if (body.isNotEmpty()) arrayListOf(body) else ArrayList(), true)
            return Res(null, -1)
        }
        if (content.startsWith("<!--") && content.contains("-->")) {
            val end = content.indexOf("-->")
            return Res(el(null, LinkedHashMap(), listOf(jsSlice(content, 4, end))), -1)
        }
        return null
    }

    private fun jsSlice(s: String, start: Int, end: Int): String {
        val a = start.coerceIn(0, s.length)
        val b = end.coerceIn(0, s.length)
        return if (a >= b) "" else s.substring(a, b)
    }

    private fun processInline(inlineTokens: List<MdToken>, state: State, nestHtml: Boolean = true): ArrayList<Any?> {
        val nodes = ArrayList<Any?>()
        val depth = state.htmlStack.size
        var i = 0
        while (i < inlineTokens.size) {
            val result = processToken(inlineTokens, i, state)
            if (result == null) {
                i++
                continue
            }
            val node = if (nestHtml || state.htmlStack.size > depth) deliverInline(state, result.node) else result.node
            if (node != null) pushNode(nodes, node)
            i = result.nextIndex
        }
        return nodes
    }

    private fun processChildren(tokens: List<MdToken>, start: Int, closeType: String, state: State, nestHtml: Boolean = false): Children {
        val children = ArrayList<Any?>()
        val depth = state.htmlStack.size
        var i = start + 1
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.type == closeType) {
                if (!nestHtml) closeHtmlFrames(state, children, depth, false)
                return Children(children, i + 1)
            }
            if (token.type == "inline") {
                children.addAll(processInline(token.children ?: emptyList(), state, nestHtml))
                i++
                continue
            }
            val result = processToken(tokens, i, state)
            if (result != null) {
                val node = if (nestHtml || state.htmlStack.size > depth) deliverInline(state, result.node) else result.node
                if (node != null) pushNode(children, node)
                i = result.nextIndex
            } else {
                i++
            }
        }
        return Children(children, i)
    }

    private fun processPossibleAttributesSyntax(tokens: List<MdToken>, value: Res, skipEmptyText: Boolean = true): Res {
        val (attrs, next) = extractAttributes(tokens, value.nextIndex, skipEmptyText)
        if (value.nextIndex < next) {
            @Suppress("UNCHECKED_CAST")
            val node = value.node as? ArrayList<Any?>
                ?: throw StudioTextPathException("comark sets attributes on a text node (TypeError)")
            val current = node[1] as MutableMap<String, Any?>
            val currentClass = current["class"]
            val newClass = attrs["class"]
            if (currentClass is String && newClass is String) attrs["class"] = "$currentClass $newClass"
            current.putAll(attrs)
            value.nextIndex = next
        }
        return value
    }

    private fun processBlockChildrenWithSlots(tokens: List<MdToken>, start: Int, closeType: String, state: State): Children {
        val nodes = ArrayList<Any?>()
        var i = start + 1
        var currentSlot: Triple<String, MutableMap<String, Any?>, ArrayList<Any?>>? = null
        fun flushSlot() {
            closeHtmlFrames(state, currentSlot?.third ?: nodes)
            val slot = currentSlot ?: return
            nodes.add(el(slot.first, slot.second, mergeAdjacentTextNodes(slot.third)))
            currentSlot = null
        }
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.type == closeType) {
                flushSlot()
                return Children(nodes, i + 1)
            }
            if (token.type == "mdc_block_slot_open") {
                flushSlot()
                currentSlot = Triple(token.tag.ifEmpty { "template" }, processAttributes(token.attrs), ArrayList())
                i++
                continue
            }
            if (token.type == "mdc_block_slot_close") {
                i++
                continue
            }
            if (token.type == "inline") {
                val free = processInline(token.children ?: emptyList(), state)
                (currentSlot?.third ?: nodes).addAll(free)
                i++
                continue
            }
            val result = processToken(tokens, i, state)
            if (result != null) {
                if (result.node != null) pushNode(currentSlot?.third ?: nodes, result.node)
                i = result.nextIndex
            } else {
                i++
            }
        }
        flushSlot()
        return Children(nodes, i)
    }

    private fun processMdcBlock(tokens: List<MdToken>, start: Int, state: State): Res {
        val open = tokens[start]
        val r = withHtmlScope(state) { processBlockChildrenWithSlots(tokens, start, "mdc_block_close", state) }
        val attrs = processAttributes(open.attrs)
        var node = el(open.tag.ifEmpty { "div" }, attrs, mergeAdjacentTextNodes(r.children))
        if (node[0] in WRAPPER_TAGS) {
            val only = r.children.singleOrNull()
            if (only is ArrayList<*> && only[0] == node[0]) {
                @Suppress("UNCHECKED_CAST")
                node = only as ArrayList<Any?>
                val merged = LinkedHashMap(node[1] as Map<String, Any?>)
                merged.putAll(attrs)
                node[1] = merged
            }
        }
        return processPossibleAttributesSyntax(tokens, Res(node, r.nextIndex))
    }

    private fun codeBlock(tokens: List<MdToken>, start: Int): Res {
        val token = tokens[start]
        val content = token.content
        val preAttrs = StudioTreeBuilder.parseCodeblockInfo(token.info)
        val codeAttrs = LinkedHashMap<String, Any?>()
        val language = preAttrs["language"] as String?
        if (!language.isNullOrEmpty() && StudioJs.trim(language).isNotEmpty()) {
            preAttrs["language"] = language
            codeAttrs["class"] = "language-$language"
        }
        val code = if (content.endsWith("\n")) content.dropLast(1) else content
        return Res(el("pre", preAttrs, listOf(el("code", codeAttrs, listOf(code)))), start + 1)
    }

    private fun singleToken(tokens: List<MdToken>, start: Int, node: Any?): Res = processPossibleAttributesSyntax(tokens, Res(node, start + 1))

    private fun <T : Children> withHtmlScope(state: State, fn: () -> T): T {
        val outerStack = state.htmlStack
        state.htmlStack = ArrayList()
        try {
            val result = fn()
            closeHtmlFrames(state, result.children)
            return result
        } finally {
            state.htmlStack = outerStack
        }
    }

    private fun closeHtmlFrames(state: State, children: MutableList<Any?>, depth: Int = 0, block: Boolean = true) {
        while (state.htmlStack.size > depth) {
            val frame = state.htmlStack.removeAt(state.htmlStack.size - 1)
            if (block) frame.block = true
            val node = frameToNode(frame)
            if (state.htmlStack.size > depth) state.htmlStack.last().children.add(node) else children.add(node)
        }
    }

    private fun openCloseToken(tokens: List<MdToken>, start: Int, state: State, closeType: String, tag: String = "", nest: Boolean = false): Res {
        val open = tokens[start]
        val r = if (nest) withHtmlScope(state) { processChildren(tokens, start, closeType, state, true) } else processChildren(tokens, start, closeType, state)
        val attrs = processAttributes(open.attrs)
        return processPossibleAttributesSyntax(tokens, Res(el(tag.ifEmpty { open.tag }, attrs, mergeAdjacentTextNodes(r.children)), r.nextIndex))
    }

    private fun processToken(tokens: List<MdToken>, i: Int, state: State): Res? {
        val token = tokens[i]
        return when (token.type) {
            "mdc_block_slot_open" -> openCloseToken(tokens, i, state, "mdc_block_slot_close")
            "mdc_inline_span_open" -> openCloseToken(tokens, i, state, "mdc_inline_span_close")
            "mdc_block_shorthand_open" -> openCloseToken(tokens, i, state, "mdc_block_shorthand_close")
            "mdc_inline_component_open" -> openCloseToken(tokens, i, state, "mdc_inline_component_close")
            "blockquote_open" -> openCloseToken(tokens, i, state, "blockquote_close", nest = true)
            "bullet_list_open" -> openCloseToken(tokens, i, state, "bullet_list_close", nest = true)
            "ordered_list_open" -> openCloseToken(tokens, i, state, "ordered_list_close", nest = true)
            "list_item_open" -> openCloseToken(tokens, i, state, "list_item_close", nest = true)
            "strong_open" -> openCloseToken(tokens, i, state, "strong_close")
            "link_open" -> openCloseToken(tokens, i, state, "link_close")
            "em_open" -> openCloseToken(tokens, i, state, "em_close")
            "table_open" -> openCloseToken(tokens, i, state, "table_close", nest = true)
            "thead_open" -> openCloseToken(tokens, i, state, "thead_close", nest = true)
            "tbody_open" -> openCloseToken(tokens, i, state, "tbody_close", nest = true)
            "tr_open" -> openCloseToken(tokens, i, state, "tr_close", nest = true)
            "th_open" -> openCloseToken(tokens, i, state, "th_close", nest = true)
            "td_open" -> openCloseToken(tokens, i, state, "td_close", nest = true)
            "sub_open" -> openCloseToken(tokens, i, state, "sub_close")
            "sup_open" -> openCloseToken(tokens, i, state, "sup_close")
            "s_open" -> openCloseToken(tokens, i, state, "s_close", "del")
            "mdc_block_open" -> processMdcBlock(tokens, i, state)
            "code_inline" -> singleToken(tokens, i, el("code", LinkedHashMap(), listOf(token.content)))
            "emoji" -> singleToken(tokens, i, token.content)
            "hr" -> singleToken(tokens, i, el("hr", LinkedHashMap()))
            "hardbreak" -> singleToken(tokens, i, el("br", LinkedHashMap()))
            "reference" -> Res(null, i + 1)
            "softbreak" -> {
                if (i == 0) throw StudioTextPathException("comark reads the token before a leading soft break (TypeError)")
                Res(if (state.htmlStack.isNotEmpty() || tokens[i - 1].type == "html_inline") null else "\n", i + 1)
            }
            "code_block", "fenced_code_block", "fence" -> codeBlock(tokens, i)
            "mdc_block_shorthand" -> singleToken(tokens, i, el(token.tag, processAttributes(token.attrs)))
            "heading_open" -> heading(tokens, i, state)
            "paragraph_open" -> paragraph(tokens, i, state)
            "mdc_inline_component" -> {
                val node = el(token.tag, LinkedHashMap(processAttributes(token.attrs)))
                processPossibleAttributesSyntax(tokens, Res(node, i + 1), false)
            }
            "image" -> {
                val attrs = processAttributes(token.attrs, handleJson = false, filterEmpty = true)
                if (token.content.isNotEmpty()) attrs["alt"] = token.content
                processPossibleAttributesSyntax(tokens, Res(el("img", attrs), i + 1))
            }
            "text" -> {
                val content = token.content
                if (content.isEmpty()) return Res(null, i + 1)
                val comment = tryCommentTag(content, state)
                if (comment != null) Res(comment.node, i + 1) else Res(content, i + 1)
            }
            "html_inline" -> htmlInline(tokens, i, state)
            else -> null
        }
    }

    private fun heading(tokens: List<MdToken>, start: Int, state: State): Res {
        val r = openCloseToken(tokens, start, state, "heading_close")
        @Suppress("UNCHECKED_CAST")
        val node = r.node as ArrayList<Any?>
        if (node.size == 2) return Res(null, r.nextIndex)
        val level = StudioJs.trimStart(tokens[start].tag.ifEmpty { "h1" }.replaceFirst("h", "")).let { t ->
            Regex("^[+-]?[0-9]+").find(t)?.value?.toBigDecimal()?.toInt()
        }.let { if (it == null || it == 0) 1 else it }
        val tracker = state.headingIds
        val headingId = nextHeadingId(textContent(node), level, tracker)
        @Suppress("UNCHECKED_CAST")
        val attrs = node[1] as MutableMap<String, Any?>
        val userId = attrs["id"]
        if (userId is String && userId.isNotEmpty()) tracker.usedIds += userId
        if (headingId != null) {
            val withId = LinkedHashMap<String, Any?>()
            withId["id"] = headingId
            withId.putAll(attrs)
            node[1] = withId
        }
        return Res(node, r.nextIndex)
    }

    private fun paragraph(tokens: List<MdToken>, start: Int, state: State): Res {
        val inline = tokens.getOrNull(start + 1)
        val depthBefore = state.htmlStack.size
        val r = processChildren(tokens, start, "paragraph_close", state, true)
        val children = r.children
        val nextIndex = r.nextIndex
        fun asParagraph(): Res = if (children.isEmpty()) {
            Res(null, nextIndex)
        } else {
            Res(el("p", processAttributes(tokens[start].attrs), mergeAdjacentTextNodes(children)), nextIndex)
        }
        if (depthBefore > 0 && state.htmlStack.isEmpty()) {
            if (children.size == 1 && children[0] is List<*>) return Res(children[0], nextIndex)
            return asParagraph()
        }
        if (state.htmlStack.isNotEmpty()) {
            for (frame in state.htmlStack) flushPendingInline(frame)
            if (children.isEmpty()) return Res(null, nextIndex)
            val node = if (children.size == 1) children[0] else el("fragment", LinkedHashMap(), children)
            return Res(node, nextIndex, siblingOfHtmlStack = true)
        }
        val empty = asParagraph()
        if (empty.node == null) return empty
        val result = processPossibleAttributesSyntax(tokens, Res(empty.node, nextIndex))
        @Suppress("UNCHECKED_CAST")
        val final = result.node as ArrayList<Any?>
        val first = final.getOrNull(2)
        val canUnwrap = final.size == 3 && first is ArrayList<*> &&
            inline?.type == "inline" && inline.children?.firstOrNull()?.type == "html_inline" &&
            (first[0] !in INLINE_TAGS || blockFlag(first) == true)
        if (canUnwrap) {
            @Suppress("UNCHECKED_CAST")
            val dollar = attrsOf(first)?.get("\$") as? MutableMap<String, Any?>
            if (dollar != null) dollar["block"] = 1.0
            return Res(first, result.nextIndex)
        }
        if (final.withIndex().all { (i, n) -> i < 2 || htmlFlag(n) }) final[0] = "fragment"
        return result
    }

    /** `Boolean(node[1]?.$?.block)` */
    private fun blockFlag(node: Any?): Boolean? = ((attrsOf(node)?.get("\$") as? Map<*, *>)?.get("block"))?.let { StudioJs.truthy(it) }

    /** `n?.[1]?.$?.html` truthy (a string's second character has no `$`) */
    private fun htmlFlag(n: Any?): Boolean = StudioJs.truthy(((attrsOf(n)?.get("\$")) as? Map<*, *>)?.get("html"))

    private fun htmlInline(tokens: List<MdToken>, start: Int, state: State): Res {
        val raw = tokens[start].content
        return when (val parsed = parseHtmlInline(raw)) {
            is HtmlInline.Comment -> Res(el(null, LinkedHashMap(), listOf(parsed.content)), start + 1)
            is HtmlInline.Other -> {
                val comment = tryCommentTag(raw, state)
                if (comment != null) Res(comment.node, start + 1) else Res(el(null, LinkedHashMap(), listOf(raw)), start + 1)
            }
            is HtmlInline.Close -> {
                val closeTag = parsed.tag.lowercase(java.util.Locale.ROOT)
                var idx = state.htmlStack.size - 1
                while (idx >= 0) {
                    val frameTag = state.htmlStack[idx].tag
                    if (frameTag != null && frameTag.lowercase(java.util.Locale.ROOT) == closeTag) break
                    idx--
                }
                if (idx < 0) {
                    val node = if (closeTag == "br") el("br", makeHtmlAttrs(emptyMap(), false)) else null
                    return Res(node, start + 1)
                }
                var closed: Any? = null
                while (state.htmlStack.size > idx) {
                    val node = frameToNode(state.htmlStack.removeAt(state.htmlStack.size - 1))
                    if (state.htmlStack.size > idx) deliverInline(state, node) else closed = node
                }
                Res(closed, start + 1)
            }
            is HtmlInline.Open -> {
                if (parsed.selfClosing) return Res(el(parsed.tag, makeHtmlAttrs(parsed.attrs, false)), start + 1)
                state.htmlStack += Frame(parsed.tag, parsed.attrs, ArrayList(), false)
                Res(null, start + 1)
            }
        }
    }

    /** `tokenListToTree(tokens, { headingIds: true })` */
    fun tokenListToTree(tokens: List<MdToken>): ArrayList<Any?> = ArrayList(treeGroups(tokens).flatMap { it.nodes })

    /**
     * Not comark: a top-level token group `tokens[start, end)` and the root nodes [tokenListToTree] makes of it. A group ends
     * where the HTML stack is empty again, so an HTML element left open in one block takes the blocks it swallows with it.
     */
    class Group(val start: Int, val end: Int, val nodes: List<Any?>)

    /** [tokenListToTree], split into its top-level [Group]s. */
    fun treeGroups(tokens: List<MdToken>): List<Group> {
        val state = State()
        val nodes = ArrayList<Any?>()
        val groups = ArrayList<Group>()
        var groupStart = 0
        var groupFrom = 0
        var i = 0
        fun closeGroup(end: Int) {
            groups += Group(groupStart, end, ArrayList(nodes.subList(groupFrom, nodes.size)))
            groupStart = end
            groupFrom = nodes.size
        }
        while (i < tokens.size) {
            if (i > groupStart && state.htmlStack.isEmpty()) closeGroup(i)
            val token = tokens[i]
            if (token.type == "inline") {
                val free = processInline(token.children ?: emptyList(), state)
                if (state.htmlStack.isNotEmpty()) state.htmlStack.last().children.addAll(free) else nodes.addAll(free)
                i++
                continue
            }
            val result = processToken(tokens, i, state)
            if (result != null) {
                for (frame in state.htmlStack) frame.block = true
                if (result.node != null) {
                    if (result.siblingOfHtmlStack) {
                        pushNode(nodes, result.node)
                    } else {
                        val free = deliverBlock(state, result.node)
                        if (free != null) pushNode(nodes, free)
                    }
                }
                i = result.nextIndex
            } else {
                nodes.add(el(token.tag.ifEmpty { "component" }, processAttributes(token.attrs, handleJson = false)))
                i++
            }
        }
        while (state.htmlStack.isNotEmpty()) {
            val frame = state.htmlStack.removeAt(state.htmlStack.size - 1)
            frame.block = true
            val node = frameToNode(frame)
            if (state.htmlStack.isNotEmpty()) state.htmlStack.last().children.add(node) else nodes.add(node)
        }
        if (groupStart < tokens.size || groupFrom < nodes.size) closeGroup(tokens.size)
        return groups
    }

    // ---- tree-utils.js ------------------------------------------------------------------------------------------------

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

    private fun extractAttributes(tokens: List<MdToken>, startIndex: Int, skipEmptyText: Boolean): Pair<LinkedHashMap<String, Any?>, Int> {
        var propsIndex = startIndex
        if (skipEmptyText) {
            while (propsIndex < tokens.size && tokens[propsIndex].type == "text" && StudioJs.trim(tokens[propsIndex].content).isEmpty()) propsIndex++
        }
        if (propsIndex < tokens.size && tokens[propsIndex].type == "mdc_inline_props") {
            return processAttributes(tokens[propsIndex].attrs) to propsIndex + 1
        }
        return LinkedHashMap<String, Any?>() to startIndex
    }

    private fun mergeAdjacentTextNodes(nodes: List<Any?>): ArrayList<Any?> {
        val merged = ArrayList<Any?>()
        for (node in nodes) {
            val last = merged.lastOrNull()
            if (node is String && last is String) merged[merged.size - 1] = last + node else merged.add(node)
        }
        return merged
    }

    /** `textContent` (`comark/utils`): the strings at and below [node]. */
    private fun textContent(node: Any?): String {
        if (node is String) return node
        val sb = StringBuilder()
        val list = node as List<*>
        for (i in 2 until list.size) sb.append(textContent(list[i]))
        return sb.toString()
    }

    // ---- heading-id.js ------------------------------------------------------------------------------------------------

    private val WS_RUN = Regex("${StudioJs.RE_S}+")
    private val NON_SLUG = Regex("[^\\p{L}\\p{M}\\p{Nd}\\p{Nl}_-]+")
    private val DASHES = Regex("-{2,}")
    private val EDGE = Regex("^(?:\\p{M}+|-+)+|-+\\z")

    fun slugify(text: String): String {
        var slug = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
        slug = StudioJs.trim(slug)
        slug = WS_RUN.replace(slug, "-")
        slug = NON_SLUG.replace(slug, "")
        slug = DASHES.replace(slug, "-")
        slug = EDGE.replace(slug, "")
        if (slug.isNotEmpty() && slug[0] in '0'..'9') slug = "_$slug"
        return slug
    }

    private fun nextHeadingId(text: String, @Suppress("UNUSED_PARAMETER") level: Int, tracker: HeadingIds): String? {
        val slug = slugify(text)
        if (slug.isEmpty()) return null
        var count = tracker.slugCounts[slug] ?: 0
        var id = if (count == 0) slug else "$slug-$count"
        while (id in tracker.usedIds) {
            count++
            id = "$slug-$count"
        }
        tracker.slugCounts[slug] = count + 1
        tracker.usedIds += id
        return id
    }

    // ---- utils.js: parseHtmlInline, parseHtmlAttributes ----------------------------------------------------------------

    private sealed interface HtmlInline {
        class Comment(val content: String) : HtmlInline
        class Close(val tag: String) : HtmlInline
        class Open(val tag: String, val attrs: LinkedHashMap<String, Any?>, val selfClosing: Boolean) : HtmlInline
        class Other(val content: String) : HtmlInline
    }

    private const val S = StudioJs.RE_S
    private val CLOSE_RE = Regex("^</$S*([A-Za-z][\\w:-]*)$S*>\\z")
    private val OPEN_RE = Regex("^<$S*([A-Za-z][\\w:-]*)((?:$S+[\\s\\S]*?)?)$S*(/?)>\\z")
    private val ATTR_RE = Regex("([:\\w.-]+)(?:$S*=$S*(?:\"([^\"]*)\"|'([^']*)'|([^${StudioJs.RE_S_CHARS}\"'=<>`]+)))?")
    private val VOID_ELEMENTS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    private fun normalizeTagName(tag: String): String = if (tag.any { it in 'a'..'z' }) tag else tag.lowercase(java.util.Locale.ROOT)

    private fun parseHtmlInline(content: String): HtmlInline {
        val trimmed = StudioJs.trim(content)
        if (trimmed.startsWith("<!--") && trimmed.endsWith("-->")) return HtmlInline.Comment(jsSlice(trimmed, 4, trimmed.length - 3))
        CLOSE_RE.find(trimmed)?.let { return HtmlInline.Close(normalizeTagName(it.groupValues[1])) }
        OPEN_RE.find(trimmed)?.let { m ->
            val tag = normalizeTagName(m.groupValues[1])
            val slash = m.groupValues[3] == "/"
            val selfClosing = slash || tag.lowercase(java.util.Locale.ROOT) in VOID_ELEMENTS
            return HtmlInline.Open(tag, parseHtmlAttributes(m.groupValues[2]), selfClosing)
        }
        return HtmlInline.Other(trimmed)
    }

    private fun parseHtmlAttributes(attrsStr: String): LinkedHashMap<String, Any?> {
        val attrs = LinkedHashMap<String, Any?>()
        if (attrsStr.isEmpty() || StudioJs.trim(attrsStr).isEmpty()) return attrs
        for (m in ATTR_RE.findAll(attrsStr)) {
            val key = m.groupValues[1].lowercase(java.util.Locale.ROOT)
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value
            attrs[key] = if (value == null) true else if (value.contains('&')) decodeHtmlAttribute(value) else value
        }
        return attrs
    }

    /** comark 0.9 `decodeHTMLAttribute` (`internal/entities.js`) */
    private fun decodeHtmlAttribute(input: String): String {
        var amp = input.indexOf('&')
        if (amp == -1) return input
        val out = StringBuilder()
        var last = 0
        val length = input.length
        fun code(i: Int) = if (i < length) input[i].code else -1
        fun isDigit(c: Int) = c in 48..57
        fun isHex(c: Int) = isDigit(c) || c in 65..70 || c in 97..102
        fun isAlnum(c: Int) = isDigit(c) || c in 65..90 || c in 97..122
        while (amp != -1) {
            var end = amp + 1
            var decoded: String? = null
            if (code(end) == 35) {
                end++
                val hex = (code(end) or 0x20) == 120
                if (hex) end++
                val start = end
                var value = 0L
                while (end < length && (if (hex) isHex(code(end)) else isDigit(code(end)))) {
                    value = minOf(value * (if (hex) 16 else 10) + input[end].digitToInt(16), 0x110000L)
                    end++
                }
                if (end > start) {
                    decoded = if (value == 0L || value > 0x10FFFF || value in 0xD800..0xDFFF) {
                        "�"
                    } else {
                        String(Character.toChars(C1[value.toInt()] ?: value.toInt()))
                    }
                    if (code(end) == 59) end++
                }
            } else {
                while (end < length && isAlnum(code(end))) end++
                val name = input.substring(amp + 1, end)
                val next = code(end)
                val named = StudioEntities.named[name]
                if (next == 59 && named != null) {
                    decoded = named
                    end++
                } else if (next != 61 && name in StudioEntities.legacy) {
                    decoded = named
                }
            }
            if (decoded != null) {
                out.append(input, last, amp).append(decoded)
                last = end
            }
            amp = input.indexOf('&', if (decoded == null) amp + 1 else end)
        }
        return out.append(input, last, length).toString()
    }

    private val C1 = mapOf(
        0x80 to 0x20AC, 0x82 to 0x201A, 0x83 to 0x0192, 0x84 to 0x201E, 0x85 to 0x2026, 0x86 to 0x2020, 0x87 to 0x2021,
        0x88 to 0x02C6, 0x89 to 0x2030, 0x8A to 0x0160, 0x8B to 0x2039, 0x8C to 0x0152, 0x8E to 0x017D, 0x91 to 0x2018,
        0x92 to 0x2019, 0x93 to 0x201C, 0x94 to 0x201D, 0x95 to 0x2022, 0x96 to 0x2013, 0x97 to 0x2014, 0x98 to 0x02DC,
        0x99 to 0x2122, 0x9A to 0x0161, 0x9B to 0x203A, 0x9C to 0x0153, 0x9E to 0x017E, 0x9F to 0x0178,
    )

    // ---- plugins/html.js markdownItPost ---------------------------------------------------------------------------------

    private val RAW_HTML_TAGS = setOf("style", "pre", "script", "textarea")

    private class Tag(val name: String, val close: Boolean, val after: Int)

    private fun readTag(src: String, pos: Int): Tag? {
        if (pos >= src.length || src[pos] != '<') return null
        var i = pos + 1
        val close = i < src.length && src[i] == '/'
        if (close) i++
        val start = i
        while (i < src.length && (src[i] in 'a'..'z' || src[i] in 'A'..'Z')) i++
        if (i == start) return null
        return Tag(src.substring(start, i).lowercase(java.util.Locale.ROOT), close, i)
    }

    private val ANY_NEWLINE = Regex("[\r\n]")
    private val LEADING_BLANK_LINE = Regex("^$S*[\r\n]")
    private val NON_SPACE = Regex("[^${StudioJs.RE_S_CHARS}]")
    private val TRAILING_NEWLINE = Regex("[ \t]*(?:\r\n|\n|\r)[ \t]*\\z")

    private fun literalText(content: String): String {
        if (!ANY_NEWLINE.containsMatchIn(content)) return content
        var start = 0
        var end = content.length
        if (LEADING_BLANK_LINE.containsMatchIn(content)) {
            val nonSpace = NON_SPACE.find(content)?.range?.first ?: return ""
            start = nonSpace
        }
        val trailing = TRAILING_NEWLINE.find(content)
        if (trailing != null && trailing.range.first >= start) end = trailing.range.first
        return if (start >= end) "" else content.substring(start, end)
    }

    private fun pushText(tokens: MutableList<MdToken>, content: String, verbatim: Boolean = false) {
        val text = if (verbatim) content else literalText(content)
        if (text.isEmpty()) return
        tokens += MdToken("text", "", 0).also { it.content = text }
    }

    private fun pushHtml(tokens: MutableList<MdToken>, content: String) {
        tokens += MdToken("html_inline", "", 0).also { it.content = content }
    }

    private fun rawCloserEnd(src: String, pos: Int, name: String): Int {
        val tag = readTag(src, pos)
        if (tag == null || !tag.close || tag.name != name) return -1
        var j = tag.after
        while (j < src.length && src[j].code <= 0x20) j++
        return if (j < src.length && src[j] == '>') j + 1 else -1
    }

    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** `htmlToTokens`: an HTML block's text as inline `text` and `html_inline` tokens. */
    private fun htmlToTokens(str: String): MutableList<MdToken> {
        val tokens = ArrayList<MdToken>()
        val len = str.length
        var pos = 0
        var textStart = 0
        while (pos < len) {
            if (str[pos] == '<' && pos + 1 < len) {
                val next = str[pos + 1]
                if (next == '!' || next == '?' || next == '/' || isLetter(next)) {
                    val end = StudioMdInline.matchHtmlTag(str, pos)
                    if (end >= 0) {
                        val match = str.substring(pos, end)
                        if (pos > textStart) pushText(tokens, str.substring(textStart, pos))
                        val raw = readTag(match, 0)
                        if (raw != null && !raw.close && raw.name in RAW_HTML_TAGS && match[match.length - 2] != '/') {
                            pushHtml(tokens, match)
                            val from = pos + match.length
                            var closerEnd = -1
                            var i = from
                            while (i < len) {
                                if (str[i] != '<') {
                                    i++
                                    continue
                                }
                                closerEnd = rawCloserEnd(str, i, raw.name)
                                if (closerEnd < 0) {
                                    i++
                                    continue
                                }
                                pushText(tokens, str.substring(from, i), true)
                                pushHtml(tokens, str.substring(i, closerEnd))
                                pos = closerEnd
                                break
                            }
                            if (closerEnd < 0) {
                                pushText(tokens, str.substring(from), true)
                                return tokens
                            }
                            textStart = pos
                            continue
                        }
                        pushHtml(tokens, match)
                        pos += match.length
                        textStart = pos
                        continue
                    }
                }
            }
            pos++
        }
        if (textStart < len) pushText(tokens, str.substring(textStart))
        return tokens
    }

    /** The `html` plugin's `markdownItPost`: every `html_block` becomes a paragraph of inline HTML tokens. */
    fun htmlBlocksToParagraphs(tokens: MutableList<MdToken>) {
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.type != "html_block") {
                i++
                continue
            }
            val open = MdToken("paragraph_open", "p", 1)
            val inline = MdToken("inline", "", 0)
            val close = MdToken("paragraph_close", "p", -1)
            inline.children = htmlToTokens(token.content)
            inline.content = token.content
            token.map?.let {
                open.map = it
                inline.map = it
            }
            tokens.removeAt(i)
            tokens.addAll(i, listOf(open, inline, close))
            i += 3
        }
    }

    // ---- utils.js applyAutoUnwrap, alert's visit ------------------------------------------------------------------------

    private fun isSpaceCode(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r'

    /** `applyAutoUnwrap` */
    fun applyAutoUnwrap(node: Any?, depth: Int = 0): Any? {
        if (depth >= TREE_WALK_MAX_DEPTH || node is String || node !is List<*> || node[0] == "p" || node[0] == null) return node
        val length = node.size
        if (length < 3) return node
        var significant: Any? = null
        var significantCount = 0
        for (i in 2 until length) {
            val child = node[i]
            if (child is String && child.all { isSpaceCode(it) }) continue
            significant = child
            if (++significantCount > 1) break
        }
        if (significantCount == 0) return node
        if (significantCount == 1 && significant is List<*> && significant[0] == "p") {
            @Suppress("UNCHECKED_CAST")
            val paragraphAttrs = significant[1] as Map<String, Any?>?
            @Suppress("UNCHECKED_CAST")
            var mergedProps = node[1] as Map<String, Any?>
            if (!paragraphAttrs.isNullOrEmpty()) mergedProps = LinkedHashMap(paragraphAttrs).also { it.putAll(mergedProps) }
            val unwrapped = ArrayList<Any?>(significant)
            unwrapped[0] = node[0]
            unwrapped[1] = mergedProps
            return unwrapped
        }
        var copy: ArrayList<Any?>? = null
        for (i in 2 until length) {
            val child = node[i]
            val next = applyAutoUnwrap(child, depth + 1)
            if (copy != null) {
                copy.add(next)
            } else if (next !== child) {
                copy = ArrayList(node.subList(0, i))
                copy.add(next)
            }
        }
        return copy ?: node
    }

    private val ALERT_TYPES = mapOf("!TIP" to "tip", "!NOTE" to "note", "!IMPORTANT" to "important", "!WARNING" to "warning", "!CAUTION" to "caution")

    /** `String(x)` of a value in a comark array tree */
    private fun jsString(v: Any?): String = when (v) {
        null -> "null"
        is String -> v
        is List<*> -> v.joinToString(",") { if (it == null) "" else jsString(it) }
        is Map<*, *> -> "[object Object]"
        else -> StudioJs.str(v)
    }

    private fun alertMarker(span: Any?): String? {
        if (span !is List<*> || span[0] != "span") return null
        val content = if (span.size > 2) jsString(span[2]) else "undefined"
        return ALERT_TYPES[content.uppercase(java.util.Locale.ROOT)]
    }

    /** The alert plugin's visitor on a `blockquote`. */
    @Suppress("UNCHECKED_CAST")
    private fun alertVisitor(element: ArrayList<Any?>) {
        val first = element.getOrNull(2)
        if (tagOf(first) == "span") {
            val type = alertMarker(first) ?: return
            if (element.getOrNull(3) is String) element[3] = StudioJs.trimStart(element[3] as String)
            element.removeAt(2)
            (element[1] as MutableMap<String, Any?>)["as"] = type
        } else if (tagOf(first) == "p") {
            val paragraph = first as ArrayList<Any?>
            if (tagOf(paragraph.getOrNull(2)) == "span") {
                val type = alertMarker(paragraph[2]) ?: return
                if (paragraph.getOrNull(3) is String) paragraph[3] = StudioJs.trimStart(paragraph[3] as String)
                paragraph.removeAt(2)
                (element[1] as MutableMap<String, Any?>)["as"] = type
            }
        }
    }

    /** `visit(tree, isBlockquote, alertVisitor)`, which removes every node [TREE_WALK_MAX_DEPTH] levels deep or deeper. */
    @Suppress("UNCHECKED_CAST")
    fun alert(nodes: MutableList<Any?>) {
        fun walk(node: Any?, parent: MutableList<Any?>, index: Int, depth: Int): Boolean {
            if (depth >= TREE_WALK_MAX_DEPTH) {
                parent.removeAt(index)
                return true
            }
            if (node is ArrayList<*> && node[0] == "blockquote") alertVisitor(node as ArrayList<Any?>)
            if (node is ArrayList<*> && node.size > 2) {
                val list = node as ArrayList<Any?>
                var i = 2
                while (i < list.size) {
                    if (walk(list[i], list, i, depth + 1)) continue
                    i++
                }
            }
            return false
        }
        var i = 0
        while (i < nodes.size) {
            if (walk(nodes[i], nodes, i, 0)) continue
            i++
        }
    }

    // ---- conversion --------------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun toNode(v: Any?): StudioNode = when (v) {
        is String -> StudioText(v)
        is List<*> -> if (v[0] == null) {
            StudioComment(v.getOrNull(2) as String?, (v[1] as Map<String, Any?>?) ?: emptyMap())
        } else {
            StudioElement(v[0] as String, (v[1] as Map<String, Any?>?) ?: emptyMap(), v.drop(2).map { toNode(it) })
        }
        else -> throw StudioUnsupportedException("comark built a malformed tree")
    }

    /** The comark 0.9 tree for markdown-it [tokens], before shiki and Studio's unbinding. */
    fun nodes(tokens: List<MdToken>): List<StudioNode> {
        val tree = tokenListToTree(tokens).map { applyAutoUnwrap(it) }.toMutableList()
        val nodes = tree.map { toNode(it) }
        if (StudioTreeBuilder.treeDepth(nodes) > StudioTreeBuilder.MAX_TREE_DEPTH) throw StudioUnsupportedException("nesting too deep for this port")
        alert(tree)
        return tree.map { toNode(it) }
    }

    /**
     * [nodes] split into the top-level token groups (`tokens[start, end)`) they come from. Every step after the token
     * conversion (`applyAutoUnwrap`, the alert plugin's walk) works on one root node at a time, so the nodes of all groups
     * are exactly [nodes]. Trees deeper than [StudioTreeBuilder.MAX_TREE_DEPTH] are not declined here: the caller checks
     * each group.
     */
    fun nodeGroups(tokens: List<MdToken>): List<Triple<Int, Int, List<StudioNode>>> =
        treeGroups(tokens).map { g ->
            val tree = g.nodes.map { applyAutoUnwrap(it) }.toMutableList()
            alert(tree)
            Triple(g.start, g.end, tree.map { toNode(it) })
        }
}
