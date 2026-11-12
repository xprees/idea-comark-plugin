package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioDbPath.Reason
import cz.xprees.comark.format.studio.StudioDbPath.UnsupportedException
import cz.xprees.comark.format.studio.StudioRenderer.ComponentStyle
import java.util.IdentityHashMap

/**
 * The body Nuxt Studio 1.8.0's database path renders for a text-path-normal document (DB-PATH.md section 3), computed from
 * the text path's own tree of the same document.
 *
 * On the database path the body is parsed by Nuxt Content (`remark-mdc`, `remark-gfm`, `remark-rehype`, the rehype plugins
 * of `@nuxtjs/mdc`, `compileHast`, minimark) and converted back by Studio's `mdcToComark`; on the text path by comark. For
 * the documents this object accepts, both parsers read the same structure, and the trees differ only by the systematic
 * transformations applied here:
 *
 * - attributes: remark-mdc's names (`bindData`: non-string YAML props become `:key` with their JSON),
 *   `rehype-sort-attributes` (every element's properties ordered by how often the property name occurs on elements with
 *   the same tag in the whole document), hast-util-raw (`data-x` → `dataX`, `class` → `className`, non-string values
 *   stringified), `validateProps` (`on*`, unsafe URLs, `name` beside `:name`), and Studio's `propsMDCToComark` (`:key`
 *   JSON unbinding, `class` moved last, `rel` removed, arrays of primitives joined);
 * - links: Nuxt Content's link handler rewrites relative `.md` links;
 * - text: trailing spaces and tabs at line ends are dropped;
 * - list items: `compileHast` unwraps every paragraph of an item, with a `br` before each paragraph after the first; in a
 *   tight list the text before a nested block keeps the `"\n"` `remark-rehype` puts there; `normalizeMdcChildren` wraps the
 *   inline run in a paragraph again when the item also has blocks;
 * - slots: remark-mdc unwraps a `#default` slot;
 * - code blocks: `@nuxtjs/mdc`'s info string parse (`parseThematicBlock`), `detab` (4-column tab stops), highlights joined
 *   to a string;
 * - table cells lose their alignment.
 *
 * Everything else is checked first ([Checker]): a construct the two parsers read differently, or that this port does not
 * follow, makes the document unsupported ([UnsupportedException]) instead of guessing.
 */
internal object StudioDbBody {
    /** Tags comark and Nuxt Content give Markdown syntax; every other element is a component. */
    private val MARKDOWN_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "pre", "code", "table", "thead", "tbody",
        "tr", "th", "td", "hr", "br", "a", "img", "em", "strong", "del", "span", "template", "input",
    )

    /** Tag names parse5 knows (`TAG_NAMES`); hast-util-raw's tree construction may move or close such components. */
    private val PARSE5_TAGS = (
        "a address annotation-xml applet area article aside b base basefont bgsound big blockquote body br button caption " +
            "center code col colgroup dd desc details dialog dir div dl dt em embed fieldset figcaption figure font footer " +
            "foreignobject form frame frameset h1 h2 h3 h4 h5 h6 head header hgroup hr html i img image input iframe keygen " +
            "label li link listing main malignmark marquee math menu meta mglyph mi mo mn ms mtext nav nobr noframes noembed " +
            "noscript object ol optgroup option p param plaintext pre rb rp rt rtc ruby s script search section select source " +
            "small span strike strong style sub summary sup table tbody template textarea tfoot td th thead title tr track tt " +
            "u ul svg var wbr xmp"
        ).split(" ").toSet()

    /** `legacy.js` HTML_BLOCK_TAGS / HTML_INLINE_TAGS, for `normalizeMdcChildren`. */
    private val HTML_BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "details", "dialog", "dd", "div", "dl", "dt", "fieldset", "figcaption",
        "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "iframe", "li", "main", "nav",
        "ol", "p", "pre", "section", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul", "video", "template",
    )
    private val HTML_INLINE_TAGS = setOf(
        "a", "abbr", "b", "bdi", "bdo", "br", "cite", "code", "data", "dfn", "em", "i", "img", "kbd", "mark", "q", "s", "samp",
        "small", "span", "strong", "sub", "sup", "time", "u", "var", "wbr", "del", "ins",
    )
    private val BLOCK_HTML_COMPONENTS = setOf(
        "address", "article", "aside", "center", "details", "dialog", "dir", "div", "fieldset", "figcaption", "figure", "footer",
        "header", "hgroup", "main", "menu", "nav", "search", "section", "summary",
    )
    private val AUTOLINK_PARENTS = setOf("p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "td", "th")
    private val TEXT_BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "td", "th")
    private val INLINE_MARKUP = setOf("strong", "em", "del", "a", "span", "code")

    fun isComponent(tag: String) = tag !in MARKDOWN_TAGS

    /**
     * The database path's tree for the text path's [nodes]. [styles] says how the renderer wrote each component of [nodes]
     * (the same instances), which decides how remark-mdc reads its props back. Throws [UnsupportedException] outside the
     * supported domain.
     */
    class Body(val nodes: List<StudioNode>, val compiledRoot: List<StudioNode>)

    fun transform(input: List<StudioNode>, textStyles: Map<StudioElement, ComponentStyle>, listTightness: List<Boolean?>): Body {
        Checker(textStyles).children(input, null, false)
        val styles = IdentityHashMap(textStyles)
        val nodes = input.map { autolinks(it, styles) }
        val raw = IdentityHashMap<StudioElement, LinkedHashMap<String, Any?>>()
        val counts = HashMap<String, HashMap<String, Int>>()
        fun collect(n: StudioNode) {
            if (n !is StudioElement) return
            if (hasProps(n.tag)) {
                val props = rawProps(n, styles[n])
                raw[n] = props
                val m = counts.getOrPut(n.tag) { HashMap() }
                for (k in props.keys) m[k] = (m[k] ?: 0) + 1
            }
            n.children.forEach { collect(it) }
        }
        nodes.forEach { collect(it) }
        val converter = Converter(raw, counts, styles, listTightness)
        val root = converter.children(nodes, normalize = false)
        if (converter.lists != listTightness.size) unsupported(Reason.LIST, "lists the text path's tokens do not match")
        return Body(normalizeMdcChildren(root), root)
    }

    /** remark-gfm's literal autolinks ([StudioDbAutolink]) in the own text of paragraphs, list items, headings and cells. */
    private fun autolinks(node: StudioNode, styles: IdentityHashMap<StudioElement, ComponentStyle>): StudioNode {
        if (node !is StudioElement || node.tag == "a" || node.tag == "pre" || node.tag == "code") return node
        val children = node.children.map { autolinks(it, styles) }
        var out: List<StudioNode> = children
        if (node.tag in AUTOLINK_PARENTS && children.any { it is StudioText }) {
            val merged = mergeText(children)
            val result = ArrayList<StudioNode>()
            for ((i, c) in merged.withIndex()) {
                if (c !is StudioText) {
                    result += c; continue
                }
                val atStart = i == 0 || merged[i - 1].let { it is StudioElement && it.tag == "br" }
                val atEnd = i == merged.size - 1 || merged[i + 1].let { it is StudioElement && it.tag == "br" }
                val segments = StudioDbAutolink.split(c.text, atStart, atEnd)
                    ?: unsupported(Reason.INLINE_SYNTAX, "a bare URL or e-mail address remark-gfm may link differently")
                for (seg in segments) {
                    result += when (seg) {
                        is StudioDbAutolink.Text -> StudioText(seg.text)
                        is StudioDbAutolink.Link -> StudioElement("a", mapOf("href" to seg.href), listOf(StudioText(seg.text)))
                    }
                }
            }
            out = result
        }
        if (out.size == node.children.size && out.indices.all { out[it] === node.children[it] }) return node
        val copy = StudioElement(node.tag, node.attrs, out)
        styles[node]?.let { styles[copy] = it }
        return copy
    }

    private fun hasProps(tag: String) = tag == "a" || tag == "img" || tag == "span" || isComponent(tag)

    private fun unsupported(reason: Reason, detail: String): Nothing = throw UnsupportedException(reason, detail)

    // ---- domain ---------------------------------------------------------------------------------------------------------

    private val EMOJI_SHORTCODE = Regex(":[\\w+-]+:")
    private val SAFE_ALT = Regex("^[^\\[\\]{}*_`\\\\&<>:~!|^\\n]*\\z")
    private val RAW_CELL_TEXT = Regex("[~{}\\[\\]`<>&\\\\*!]|(?<![A-Za-z0-9])_|_(?![A-Za-z0-9])|:[A-Za-z$]")
    private val SLOT_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*\\z")
    private val TABLE_DELIMITER = Regex("^[ \\t]*[-:| \\t]*-[-:| \\t]*\\z")
    private val WHITESPACE_ONLY = Regex("^${StudioJs.RE_S}*\\z")
    private val WWW = Regex("www\\.", RegexOption.IGNORE_CASE)
    private val COMPONENT_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_-]*\\z")
    private val DATA_ATTRIBUTE = Regex("^data-[a-z0-9]+(?:-[a-z0-9]+)*\\z")

    private class Checker(val styles: Map<StudioElement, ComponentStyle>) {
        fun children(
            nodes: List<StudioNode>, parent: StudioElement?, inLink: Boolean, inComponent: Boolean = false, inLabel: Boolean = false,
            inListItem: Boolean = false,
        ) {
            for ((i, n) in nodes.withIndex()) node(n, parent, nodes, i, inLink, inComponent, inLabel, inListItem)
        }

        fun hasAttributeSyntax(el: StudioElement): Boolean = when {
            el.tag == "a" -> el.attrs.keys.any { it != "href" }
            el.tag == "img" -> el.attrs.keys.any { it != "src" && it != "alt" && it != "title" }
            el.tag == "span" || isComponent(el.tag) -> el.attrs.isNotEmpty()
            else -> false
        }

        fun node(
            node: StudioNode, parent: StudioElement?, siblings: List<StudioNode>, index: Int, inLink: Boolean, inComponent: Boolean,
            inLabel: Boolean, inListItem: Boolean,
        ) {
            when (node) {
                is StudioComment -> unsupported(Reason.HTML_COMMENT, "HTML comment")
                is StudioText -> if (parent?.tag != "code" && parent?.tag != "pre") {
                    text(node.text, inLink, parent)
                    // the renderer writes a table cell's own text without escaping it
                    if ((parent?.tag == "td" || parent?.tag == "th") && RAW_CELL_TEXT.containsMatchIn(node.text)) {
                        unsupported(Reason.INLINE_SYNTAX, "unescaped Markdown punctuation in a table cell")
                    }
                    val previous = siblings.getOrNull(index - 1)
                    if (previous is StudioElement && previous.tag == "br" && Regex("^#[^ \\t\\n#]").containsMatchIn(node.text)) {
                        unsupported(Reason.SLOT, "a #slot line after a hard break")
                    }
                    val next = siblings.getOrNull(index + 1)
                    if (next is StudioElement && next.tag == "br" && node.text.lastOrNull().let { it == ' ' || it == '\t' }) {
                        unsupported(Reason.INLINE_SYNTAX, "whitespace before a hard break")
                    }
                }
                is StudioElement -> {
                    if ("\$" in node.attrs) unsupported(Reason.RAW_HTML, "<${node.tag}>")
                    if (inLink && node.tag == "a") unsupported(Reason.INLINE_SYNTAX, "a link inside a link")
                    if (inLabel && hasAttributeSyntax(node)) unsupported(Reason.INLINE_SYNTAX, "{attributes} inside a span or component label")
                    element(node, parent, siblings, index, inComponent || inListItem)
                    if (isComponent(node.tag) || node.tag == "template") closingMarkers(node)
                    if (inComponent && node.tag == "pre") {
                        val code = (node.children.singleOrNull() as? StudioElement)?.children?.singleOrNull() as? StudioText
                        if (code != null && code.text.split('\n').any { Regex("^[ \t]*(:{2,}|```|~~~)").containsMatchIn(it) }) {
                            unsupported(Reason.BLOCK_SYNTAX, "component markers inside a code block inside a component")
                        }
                    }
                    if (inComponent && node.tag == "li" && node.children.any { isBlock(it) } &&
                        node.children.any { c -> c is StudioText && '\n' in c.text || c is StudioElement && c.tag == "p" && c.children.any { it is StudioText && '\n' in it.text } }
                    ) {
                        unsupported(Reason.LIST, "lazy list item continuation before a nested block inside a component")
                    }
                    val label = node.tag == "span" || isComponent(node.tag) && styles[node] == ComponentStyle.INLINE
                    if (isComponent(node.tag) && styles[node] != ComponentStyle.INLINE) nestedComponentIndent(node)
                    if (node.tag != "pre") {
                        children(node.children, node, inLink || node.tag == "a", inComponent || isComponent(node.tag), inLabel || label, inListItem || node.tag == "li")
                    }
                }
            }
        }

        /**
         * After a nested block component (written indented), remark-mdc can strip that indentation from the container's later
         * lines, which flattens nested lists and code indentation.
         */
        fun nestedComponentIndent(container: StudioElement) {
            val first = container.children.indexOfFirst { it is StudioElement && isComponent(it.tag) && styles[it] != ComponentStyle.INLINE }
            if (first < 0) return
            if (container.children.drop(first + 1).any { indentationSensitive(it) }) {
                unsupported(Reason.BLOCK_SYNTAX, "an indented list or code line after a nested component")
            }
        }

        /** A list item with more than inline content, or a code line that starts with whitespace, at any depth. */
        fun indentationSensitive(n: StudioNode): Boolean {
            if (n !is StudioElement) return false
            if (n.tag == "li" && n.children.any { it is StudioElement && !isInline(it) && it.tag != "input" }) return true
            if (n.tag == "pre") {
                val code = (n.children.singleOrNull() as? StudioElement)?.children?.singleOrNull() as? StudioText ?: return true
                return code.text.split('\n').any { it.startsWith(" ") || it.startsWith("\t") }
            }
            return n.children.any { indentationSensitive(it) }
        }

        /** Paragraphs remark-mdc's container repair (`repairMdcRoot`) reads as closing markers. */
        fun closingMarkers(container: StudioElement) {
            val paragraphs = if (container.children.isNotEmpty() && container.children.all { isInline(it) }) listOf(container.children)
            else container.children.filter { it is StudioElement && it.tag == "p" }.map { (it as StudioElement).children }
            for (p in paragraphs) {
                val only = p.singleOrNull()
                if (only is StudioText && Regex("^${StudioJs.RE_S}*:{2,}(${StudioJs.RE_S}*\n${StudioJs.RE_S}*:{2,})*${StudioJs.RE_S}*\\z").matches(only.text)) {
                    unsupported(Reason.BLOCK_SYNTAX, "a paragraph of colons in a component (a closing marker for Studio)")
                }
                val last = p.lastOrNull()
                if (last is StudioText && Regex("(?:\n[ \t]*:{2,}[ \t]*)+\\z").containsMatchIn(last.text)) {
                    unsupported(Reason.BLOCK_SYNTAX, "a line of colons ending a paragraph in a component (a closing marker for Studio)")
                }
            }
        }

        fun text(text: String, inLink: Boolean, parent: StudioElement?) {
            if (!inLink && ("://" in text || '@' in text || WWW.containsMatchIn(text)) && parent?.tag !in AUTOLINK_PARENTS) {
                unsupported(Reason.INLINE_SYNTAX, "a bare URL or e-mail address (remark-gfm links it) outside a paragraph's own text")
            }
            if ("{{" in text) unsupported(Reason.INLINE_SYNTAX, "{{ }} binding")
            if ("[^" in text) unsupported(Reason.INLINE_SYNTAX, "footnote syntax")
            // CommonMark reads `<!x` (any case) and `<?` as HTML; markdown-exit only `<!` with an uppercase letter
            if ("<!" in text || "<?" in text) unsupported(Reason.RAW_HTML, "an HTML declaration or processing instruction opener")
            if (Regex("\\(:[a-zA-Z]").containsMatchIn(text)) unsupported(Reason.INLINE_SYNTAX, "(:name, an inline component for remark-mdc")
            if ("**" in text || "__" in text || "~~" in text) unsupported(Reason.INLINE_SYNTAX, "a literal run of emphasis delimiters")
            if (EMOJI_SHORTCODE.containsMatchIn(text)) unsupported(Reason.INLINE_SYNTAX, "an emoji shortcode remark-emoji converts")
            if (text.split('\n').any { TABLE_DELIMITER.matches(it) }) unsupported(Reason.BLOCK_SYNTAX, "a line like a table delimiter row")
            if (Regex("\n#[^ \\t\\n#]").containsMatchIn(text)) unsupported(Reason.SLOT, "a #slot line inside a paragraph")
        }

        fun element(el: StudioElement, parent: StudioElement?, siblings: List<StudioNode>, index: Int, indented: Boolean) {
            val tag = el.tag
            val attrs = el.attrs
            when {
                tag == "span" -> {
                    if (attrs.isNotEmpty()) attributes(el)
                    span(el, parent, siblings, index)
                }
                tag == "blockquote" -> {
                    if (attrs.isNotEmpty()) unsupported(Reason.BLOCK_ATTRIBUTES, "attributes on <$tag>")
                    if (containsComponent(el)) unsupported(Reason.BLOCK_SYNTAX, "a component inside a blockquote")
                }
                (tag == "strong" || tag == "em" || tag == "del") && emphasisDepth(el) >= 3 ->
                    unsupported(Reason.INLINE_SYNTAX, "deeply nested emphasis")
                tag in setOf("p", "hr", "strong", "em", "del", "table", "thead", "tbody", "tr") ->
                    if (attrs.isNotEmpty()) unsupported(Reason.BLOCK_ATTRIBUTES, "attributes on <$tag>")
                tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' ->
                    if (attrs.keys.any { it != "id" }) unsupported(Reason.BLOCK_ATTRIBUTES, "attributes on <$tag>")
                tag == "th" || tag == "td" ->
                    if (attrs.keys.any { it != "style" } || attrs["style"]?.let { it !is String || !it.startsWith("text-align:") } == true) {
                        unsupported(Reason.BLOCK_ATTRIBUTES, "attributes on <$tag>")
                    }
                tag == "code" -> if (attrs.isNotEmpty()) unsupported(Reason.BLOCK_ATTRIBUTES, "attributes on inline code")
                tag == "template" -> {
                    if (attrs.keys != setOf("name") || (attrs["name"] as? String)?.let { SLOT_NAME.matches(it) } != true) {
                        unsupported(Reason.SLOT, "slot attributes or name")
                    }
                    // remark-mdc closes an open list section first, which can hand the slot to an outer component
                    if (siblings.getOrNull(index - 1).let { it is StudioElement && (it.tag == "ul" || it.tag == "ol") }) {
                        unsupported(Reason.SLOT, "a slot right after a list")
                    }
                }
                tag == "li" -> {
                    if (attrs.keys.any { it != "class" } || attrs["class"].let { it != null && it != "task-list-item" }) {
                        unsupported(Reason.BLOCK_ATTRIBUTES, "attributes on a list item")
                    }
                    // `- 42. x`: a list that starts on its parent item's line
                    if (el.children.firstOrNull().let { it is StudioElement && (it.tag == "ul" || it.tag == "ol") }) {
                        unsupported(Reason.LIST, "a list on its parent item's first line")
                    }
                    // `- [x]{...}`: remark-mdc takes a bracket at the start of an item for a task checkbox, not a span
                    val first = el.children.firstOrNull()
                    val firstInline = if (first is StudioElement && first.tag == "p") first.children.firstOrNull() else first
                    if (firstInline is StudioElement && firstInline.tag == "span") unsupported(Reason.BRACKET_SPAN, "a [span] starting a list item")
                    // inputs other than the task checkbox (an `:input` component) share the checkbox's attribute counts
                    if (el.children.withIndex().any { (i, c) -> c is StudioElement && c.tag == "input" && (i > 0 || attrs["class"] != "task-list-item") } ||
                        el.children.any { it is StudioElement && it.tag != "input" && containsTag(it, "input") }
                    ) {
                        unsupported(Reason.LIST, "an input element besides the task checkbox")
                    }
                }
                tag == "ul" -> if (attrs.keys.any { it != "class" } || attrs["class"].let { it != null && it != "contains-task-list" }) {
                    unsupported(Reason.BLOCK_ATTRIBUTES, "attributes on a list")
                }
                // the renderer writes an ordered task list's class as ::ol{.contains-task-list}, a component for remark-mdc
                tag == "ol" -> if (attrs.keys.any { it != "start" }) {
                    unsupported(Reason.BLOCK_ATTRIBUTES, "attributes on a list")
                }
                tag == "input" || tag == "pre" -> {}
                tag == "br" -> if (index == 0 || attrs.isNotEmpty() || parent != null && (parent.tag == "span" || isComponent(parent.tag))) {
                    unsupported(Reason.INLINE_SYNTAX, "a hard break at the start of inline content or in a span")
                }
                tag == "a" || tag == "img" -> attributes(el)
                else -> {
                    attributes(el)
                    component(el, parent, siblings, index, indented)
                }
            }
        }

        /** remark-mdc's span (`tokenizeSpan`) where comark has one: not after or before another bracket, nor before `(`. */
        fun span(el: StudioElement, parent: StudioElement?, siblings: List<StudioNode>, index: Int) {
            if (parent == null || parent.tag !in TEXT_BLOCKS && parent.tag !in INLINE_MARKUP && !(isComponent(parent.tag) && styles[parent] == ComponentStyle.INLINE)) {
                unsupported(Reason.BRACKET_SPAN, "[text] span outside inline content")
            }
            val prev = siblings.getOrNull(index - 1)
            val next = siblings.getOrNull(index + 1)
            val first = el.children.firstOrNull()
            val bad = prev == null && (parent.tag == "span" || parent.tag == "a" || isComponent(parent.tag)) ||
                prev is StudioText && (prev.text.endsWith("[") || prev.text.endsWith("!")) ||
                textOf(el).contains('\n') && (parent.tag !in TEXT_BLOCKS || hasElements(el)) ||
                prev is StudioElement && prev.tag == "input" ||
                prev == null && parent.tag == "li" ||
                codeWithBrackets(el) ||
                first is StudioElement && (first.tag == "span" || first.tag == "a" || first.tag == "img") ||
                first is StudioText && first.text.startsWith("[") ||
                el.attrs.isEmpty() && (next is StudioText && (next.text.startsWith("(") || next.text.startsWith("[") || next.text.startsWith("{")) ||
                    next is StudioElement && (next.tag == "span" || next.tag == "a" || isComponent(next.tag))) ||
                depth(el) > 2 || textLength(el) > 900
            if (bad) unsupported(Reason.BRACKET_SPAN, "a [span] remark-mdc reads differently")
        }

        fun emphasisDepth(el: StudioElement): Int =
            (if (el.tag == "strong" || el.tag == "em" || el.tag == "del") 1 else 0) +
                (el.children.filterIsInstance<StudioElement>().maxOfOrNull { emphasisDepth(it) } ?: 0)

        /** remark-mdc's label ends at the first unbalanced `]`, also inside inline code. */
        fun codeWithBrackets(el: StudioElement): Boolean = el.children.any {
            it is StudioElement && (it.tag == "code" && textOf(it).any { c -> c == '[' || c == ']' } || codeWithBrackets(it))
        }

        fun containsTag(el: StudioElement, tag: String): Boolean = el.children.any { it is StudioElement && (it.tag == tag || containsTag(it, tag)) }

        fun hasElements(el: StudioElement): Boolean = el.children.any { it is StudioElement }

        fun containsComponent(el: StudioElement): Boolean = el.children.any { it is StudioElement && (isComponent(it.tag) || containsComponent(it)) }

        fun textOf(n: StudioNode): String = when (n) {
            is StudioText -> n.text
            is StudioElement -> n.children.joinToString("") { textOf(it) }
            else -> ""
        }

        fun depth(el: StudioElement): Int = 1 + (el.children.filterIsInstance<StudioElement>().maxOfOrNull { depth(it) } ?: 0)

        fun textLength(n: StudioNode): Int = when (n) {
            is StudioText -> n.text.length
            is StudioElement -> n.children.sumOf { textLength(it) } + 8
            else -> 0
        }

        fun component(el: StudioElement, parent: StudioElement?, siblings: List<StudioNode>, index: Int, indented: Boolean) {
            val tag = el.tag
            if (tag in PARSE5_TAGS) {
                // hast-util-raw inserts these like any element, except that they close an open paragraph (there is none
                // around a block component) or, for button, an open button
                val block = styles[el].let { it != null && it != ComponentStyle.INLINE }
                val allowed = block && (tag in BLOCK_HTML_COMPONENTS || tag == "button" && !containsTag(el, "button"))
                if (!allowed) unsupported(Reason.COMPONENT_NAME, "component named like an HTML element: $tag")
            }
            if (!COMPONENT_NAME.matches(tag) || tag.endsWith("_") || kebabCase(tag) != tag) unsupported(Reason.COMPONENT_NAME, "component name $tag")
            if (el.children.firstOrNull().let { it is StudioElement && it.tag == "hr" }) {
                unsupported(Reason.COMPONENT_HR, "a component that starts with ---")
            }
            val style = styles[el] ?: unsupported(Reason.COMPONENT_NAME, "component the renderer did not report")
            if (style != ComponentStyle.INLINE) return
            // remark-mdc reads a label line by line
            if (textOf(el).contains('\n') && hasElements(el)) unsupported(Reason.INLINE_SYNTAX, "a multi-line inline component label with markup")
            if (codeWithBrackets(el)) unsupported(Reason.INLINE_SYNTAX, "inline code with brackets in a component label")
            // remark-mdc's inline component: `:` after nothing, a space or line ending, `[`, `*`, `_` or `(`
            val prev = siblings.getOrNull(index - 1)
            val prevOk = when {
                prev == null -> parent == null || parent.tag in TEXT_BLOCKS || parent.tag in setOf("strong", "em", "a", "span") ||
                    isComponent(parent.tag)
                prev is StudioText -> prev.text.lastOrNull().let { it == null || it == ' ' || it == '\t' || it == '\n' || it in "[*_(" }
                prev is StudioElement -> prev.tag == "strong" || prev.tag == "em"
                else -> false
            }
            if (!prevOk) unsupported(Reason.INLINE_SYNTAX, "inline component after a character remark-mdc does not accept")
            val next = siblings.getOrNull(index + 1)
            // what follows the component's own syntax must not continue it: its name, a label `[...]` or attributes `{...}`
            val nextChar: Char? = when (next) {
                is StudioText -> next.text.firstOrNull()
                is StudioElement -> when {
                    next.tag == "span" || next.tag == "a" -> '['
                    next.tag == "img" -> '!'
                    next.tag == "strong" || next.tag == "em" -> '*'
                    next.tag == "code" -> '`'
                    next.tag == "del" -> '~'
                    next.tag == "br" -> ' '
                    else -> ':'
                }
                else -> null
            }
            val continues = when {
                nextChar == null -> false
                el.children.isEmpty() && el.attrs.isEmpty() -> nextChar.isLetterOrDigit() || nextChar in "-_:[{"
                el.children.isEmpty() -> nextChar == '['
                el.attrs.isEmpty() -> nextChar == '{'
                else -> false
            }
            if (continues) unsupported(Reason.INLINE_SYNTAX, "inline component followed by characters that continue it")
            // alone on its line in flow content: remark-mdc reads a block component
            val atLineStart = prev == null || prev is StudioText && prev.text.endsWith("\n") || prev is StudioElement && prev.tag == "br"
            val atLineEnd = next == null || next is StudioText && next.text.startsWith("\n") || next is StudioElement && next.tag == "br"
            val flowParent = parent == null || parent.tag !in setOf("h1", "h2", "h3", "h4", "h5", "h6", "td", "th") && parent.tag !in INLINE_MARKUP && !isComponent(parent.tag) ||
                isComponent(parent.tag) && styles[parent] != ComponentStyle.INLINE
            if (atLineStart && atLineEnd && flowParent) unsupported(Reason.INLINE_SYNTAX, "inline component alone on a line (a block component for remark-mdc)")
            // an indented line starting with a component is a container for remark-mdc (tokenizeContainerIndented)
            if (atLineStart && indented) unsupported(Reason.INLINE_SYNTAX, "inline component at the start of an indented line")
        }

        fun attributes(el: StudioElement) {
            for ((key, value) in el.attrs) {
                val name = key.removePrefix(":")
                if (name.isEmpty() || name.startsWith(":") || name.startsWith("v-") || name.startsWith("@") || name.startsWith("#") || name.startsWith(".")) {
                    unsupported(Reason.ATTRIBUTE_NAME, "attribute $key")
                }
                if (!Regex("^[A-Za-z][A-Za-z0-9_-]*\\z").matches(name)) unsupported(Reason.ATTRIBUTE_NAME, "attribute name $key")
                // remark-mdc's bound attribute names take letters, digits and dashes only
                if (key.startsWith(":") && '_' in name) unsupported(Reason.ATTRIBUTE_NAME, "bound attribute name $key")
                if (key == "class" && value !is String) unsupported(Reason.ATTRIBUTE_NAME, "class value")
            }
        }
    }

    // ---- remark-mdc props ------------------------------------------------------------------------------------------------

    /** A hast property name hast-util-raw keeps (or renames as this port follows: `class`, `data-*`). */
    private fun checkRawName(raw: String) {
        val name = raw.removePrefix(":")
        if (!Regex("^[A-Za-z][A-Za-z0-9-]*\\z").matches(name)) unsupported(Reason.ATTRIBUTE_NAME, "attribute name $raw")
        if (raw == "class") return
        val info = StudioHastProperties.normal[name.lowercase()]
        if (info != null && !info.plain) unsupported(Reason.ATTRIBUTE_NAME, "HTML attribute $raw")
        if (info == null && name.length > 4 && name.substring(0, 4).lowercase() == "data" && (raw.startsWith(":") || !DATA_ATTRIBUTE.matches(name))) {
            unsupported(Reason.ATTRIBUTE_NAME, "data attribute $raw")
        }
    }

    /** A string attribute value remark-mdc's attribute syntax reads back as written. */
    private fun checkInlineValue(s: String) {
        if (s.any { it == '\\' || it == '&' || it == '\n' || it == '\r' } || '"' in s && '\'' in s) {
            unsupported(Reason.ATTRIBUTE_VALUE, "attribute value with characters remark-mdc reads differently")
        }
    }

    /**
     * The hast properties remark-mdc and remark-rehype give an element, in no particular order (they are sorted later), as
     * the database path reads the renderer's output for it: `[content](href){attrs}` for links, `![alt](src "title"){attrs}`
     * for images, `{attrs}` for spans and components, or a YAML props block ([style] [ComponentStyle.YAML]).
     */
    private fun rawProps(el: StudioElement, style: ComponentStyle?): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (style == ComponentStyle.YAML) {
            // comarkYamlAttributes' normalization, then js-yaml dump → yaml parse → flat.unflatten → bindData
            val normalized = LinkedHashMap<String, Any?>()
            for ((key, value) in el.attrs) {
                if (key.startsWith(":")) {
                    if (value is String) {
                        try {
                            normalized[key.substring(1)] = StudioJs.jsonParse(value)
                        } catch (_: StudioJs.JsonSyntaxError) {
                            normalized[key] = value
                        }
                    } else normalized[key.substring(1)] = value
                } else normalized[key] = when (value) {
                    "true" -> true
                    "false" -> false
                    else -> value
                }
            }
            StudioDbPath.checkYaml12(normalized)
            if (!StudioFlatCheck.isIdentity(normalized)) unsupported(Reason.COMPONENT_PROPS, "props that flat.unflatten changes")
            for ((key, value) in normalized) {
                when {
                    key.startsWith(":") -> out[key] = value
                    value is String -> out[key] = value
                    else -> out[":$key"] = StudioJs.jsonStringify(value)
                }
            }
            out.keys.forEach { checkRawName(it) }
            return out
        }
        for ((key, value) in el.attrs) {
            when {
                el.tag == "a" && key == "href" || el.tag == "img" && (key == "src" || key == "alt" || key == "title") -> {
                    if (value !is String) unsupported(Reason.ATTRIBUTE_VALUE, "non-string $key")
                    if (key == "alt" || key == "title") checkInlineValue(value)
                    if (key == "alt" && !SAFE_ALT.matches(value)) unsupported(Reason.ATTRIBUTE_VALUE, "image alt text with inline syntax")
                    out[key] = value
                }
                key == "id" -> {
                    if (value !is String || !Regex("^[A-Za-z0-9_-]+\\z").matches(value)) unsupported(Reason.ATTRIBUTE_VALUE, "id value")
                    out[key] = value
                }
                key == "class" -> {
                    val classes = (value as String).split(' ').filter { it.isNotEmpty() }
                    if (classes.any { !Regex("^[A-Za-z0-9_-]+\\z").matches(it) }) unsupported(Reason.ATTRIBUTE_VALUE, "class value")
                    out[key] = classes.joinToString(" ")
                }
                // a bare `{name}`: remark-mdc binds it (`:name="true"`) on spans and components, an empty string on links and images
                key.startsWith(":") && value == "true" -> if (el.tag == "a" || el.tag == "img") out[key.substring(1)] = "" else out[key] = value
                // `name=""`: the same, except on a block component's opening line
                value == "" -> if (key.startsWith(":") || el.tag == "a" || el.tag == "img" || style == ComponentStyle.BLOCK) out[key] = "" else out[":$key"] = "true"
                value is String -> {
                    checkInlineValue(value)
                    out[key] = value
                }
                else -> unsupported(Reason.ATTRIBUTE_VALUE, "non-string attribute in attribute syntax")
            }
        }
        // remark-mdc's attribute syntax kebab-cases every name (scule kebabCase): `myProp` → `my-prop`, `:A_b` → `:a-b`
        val named = LinkedHashMap<String, Any?>()
        for ((key, value) in out) {
            val markdownProp = el.tag == "a" && key == "href" || el.tag == "img" && (key == "src" || key == "alt" || key == "title")
            val name = if (markdownProp) key else kebabCase(key)
            if (name in named || named.keys.any { it.removePrefix(":") == name.removePrefix(":") }) {
                unsupported(Reason.ATTRIBUTE_NAME, "attributes remark-mdc reads under the same name")
            }
            checkRawName(name)
            named[name] = value
        }
        return named
    }

    // ---- conversion -----------------------------------------------------------------------------------------------------

    private class Converter(
        val raw: Map<StudioElement, LinkedHashMap<String, Any?>>,
        val counts: Map<String, Map<String, Int>>,
        val styles: Map<StudioElement, ComponentStyle>,
        val tightness: List<Boolean?>,
    ) {
        /** Lists converted so far, in document order (the order of [tightness]). */
        var lists = 0

        fun children(nodes: List<StudioNode>, normalize: Boolean = true): List<StudioNode> {
            val out = ArrayList<StudioNode>()
            for (n in nodes) {
                when {
                    // remark-mdc unwraps a default slot without attributes
                    n is StudioElement && n.tag == "template" && n.attrs["name"] == "default" -> out += children(paragraphed(n))
                    else -> out += convert(n)
                }
            }
            return if (normalize) normalizeMdcChildren(compiled(out)) else compiled(out)
        }

        /**
         * comark's auto-unwrap left the inline content of a lone paragraph directly in a block component, slot or blockquote;
         * remark-mdc keeps the paragraph.
         */
        fun paragraphed(el: StudioElement): List<StudioNode> {
            val wraps = el.tag == "template" || el.tag == "blockquote" || isComponent(el.tag) && styles[el] != ComponentStyle.INLINE
            if (!wraps || el.children.isEmpty() || !el.children.all { isInline(it) }) return el.children
            if (el.children.none { it is StudioElement || it is StudioText && StudioJs.trim(it.text).isNotEmpty() }) return el.children
            return listOf(StudioElement("p", emptyMap(), el.children))
        }

        fun convert(node: StudioNode): StudioNode {
            if (node !is StudioElement) return node
            val tag = node.tag
            return when {
                tag == "pre" -> pre(node)
                tag == "code" -> node
                tag == "ul" || tag == "ol" -> list(node)
                tag == "th" || tag == "td" -> StudioElement(tag, emptyMap(), children(node.children))
                hasProps(tag) -> StudioElement(tag, props(node), children(paragraphed(node)))
                else -> StudioElement(tag, node.attrs, children(paragraphed(node)))
            }
        }

        /**
         * An element's attributes as the database path ends up with them: hast properties ordered by document-wide frequency,
         * renamed by hast-util-raw, filtered by `validateProps`, then Studio's `propsMDCToComark`.
         */
        fun props(el: StudioElement): Map<String, Any?> {
            val props = raw.getValue(el)
            if (el.tag == "a") props["href"]?.let { props["href"] = normaliseLink(it as String) }
            val count = counts.getValue(el.tag)
            val order = props.keys.sortedWith { a, b ->
                val d = (count[b] ?: 0) - (count[a] ?: 0)
                if (d != 0) d else compareNames(a, b)
            }
            // hast-util-raw: hast → parse5 attributes → hast (property-information); the names are checked to be plain
            val hast = LinkedHashMap<String, Any?>()
            for (name in order) {
                val value = attributeValue(props[name]) ?: continue
                if (name == "class") hast["className"] = value.split(Regex("[ \\t\\n\\u000C\\r]+")).filter { it.isNotEmpty() }
                else if (DATA_ATTRIBUTE.matches(name)) {
                    val rest = name.substring(5).replace(Regex("-[a-z]")) { it.value.substring(1).uppercase() }
                    hast["data" + rest.take(1).uppercase() + rest.drop(1)] = value
                } else hast[name] = value
            }
            // compileHast validateProps
            val valid = LinkedHashMap<String, Any?>()
            for ((name, value) in hast) {
                if (StudioJs.truthy(hast[":$name"])) continue
                if (!validProp(name, value)) continue
                valid[name] = value
            }
            return propsMDCToComark(valid)
        }

        fun list(el: StudioElement): StudioNode {
            // looseness as markdown-it saw it in the text (the CommonMark rule micromark follows too)
            val loose = tightness.getOrNull(lists++) == false
            val items = el.children.map { li ->
                if (li !is StudioElement || li.tag != "li") unsupported(Reason.LIST, "list child that is not an item")
                item(li, loose)
            }
            return StudioElement(el.tag, el.attrs, items)
        }

        fun item(li: StudioElement, loose: Boolean): StudioNode {
            val converted = li.children.map { convert(it) }
            // paragraphs (a p element, or a run of inline nodes) and blocks
            val parts = ArrayList<Pair<Boolean, List<StudioNode>>>()
            var run = ArrayList<StudioNode>()
            fun flush() {
                if (run.isNotEmpty()) parts += true to run
                run = ArrayList()
            }
            var input: StudioNode? = null
            for ((i, c) in converted.withIndex()) {
                when {
                    i == 0 && c is StudioElement && c.tag == "input" -> input = c
                    c is StudioElement && c.tag == "p" -> {
                        flush(); parts += true to c.children
                    }
                    isInline(c) && !(c is StudioElement && isComponent(c.tag) && styles[c] != ComponentStyle.INLINE) -> run += c
                    else -> {
                        flush(); parts += false to listOf(c)
                    }
                }
            }
            flush()
            if (parts.any { !it.first && it.second.single().let { n -> n is StudioElement && isComponent(n.tag) } }) {
                unsupported(Reason.LIST, "component in a list item")
            }
            val out = ArrayList<StudioNode>()
            input?.let { out += it }
            if (loose) {
                var seenParagraph = false
                for ((isParagraph, nodes) in parts) {
                    if (isParagraph) {
                        if (seenParagraph) out += StudioElement("br")
                        out += nodes
                        seenParagraph = true
                    } else out += nodes
                }
            } else {
                for ((index, part) in parts.withIndex()) {
                    val (isParagraph, nodes) = part
                    if (isParagraph && index < parts.size - 1 && nodes.lastOrNull() is StudioText) {
                        out += nodes.dropLast(1)
                        out += StudioText((nodes.last() as StudioText).text + "\n")
                    } else out += nodes
                }
            }
            return StudioElement("li", li.attrs, normalizeMdcChildren(compiled(out)))
        }
    }

    /** remark: spaces and tabs before a line ending inside a paragraph are not part of the text. */
    private fun trimLineEnds(text: String): String = if ('\n' !in text) text else text.replace(Regex("[ \\t]+\n"), "\n")

    /** rehype-sort-attributes `compare`: by UTF-16 code unit, a shorter prefix first. */
    private fun compareNames(a: String, b: String): Int {
        var i = 0
        while (true) {
            val ca = if (i < a.length) a[i].code else 0
            val cb = if (i < b.length) b[i].code else 0
            if (ca != cb) return ca - cb
            if (i >= a.length && i >= b.length) return 0
            i++
        }
    }

    /** hast-util-to-parse5 `createProperty` for a name without schema information: the attribute value, `null` when dropped. */
    private fun attributeValue(v: Any?): String? = when (v) {
        null, false, JsUndefined -> null
        true -> ""
        is Double -> if (v.isNaN()) null else StudioJs.numberToString(v)
        is List<*> -> StudioJs.trim(v.joinToString(" ") { if (it == null || it === JsUndefined) "" else StudioJs.str(it) })
        else -> StudioJs.str(v)
    }

    private val COMPONENT_SELECTORS = setOf("as", "is", "tag")
    private val COMPONENT_OPTION_KEYS = setOf("template", "render", "ssrrender", "setup", "extends", "mixins")
    private val UNSAFE_ATTRIBUTES = setOf("srcdoc", "formaction", "innerhtml", "outerhtml")

    private fun normalizePropName(attribute: String): String =
        attribute.lowercase().replace(Regex("^(?::|v-bind:)?[.^]?"), "").replace(Regex("^(@|v-on:)"), "on")

    /** `validateProp` (`@nuxtjs/mdc` `parser/utils/props.js`). */
    private fun validProp(attribute: String, value: Any?): Boolean {
        val decoded = if ((attribute.startsWith(":") || attribute.startsWith("v-bind:")) && value is String) {
            try {
                StudioJs.jsonParse(value)
            } catch (_: StudioJs.JsonSyntaxError) {
                value
            }
        } else value
        if (componentPropViolation(attribute, decoded)) return false
        val normalized = normalizePropName(attribute)
        if (normalized.startsWith("on") || normalized in UNSAFE_ATTRIBUTES) return false
        if (normalized == "href" || normalized == "src" || normalized == "xlinkhref" || normalized == "action") return anchorLinkAllowed(value)
        return true
    }

    private fun componentPropViolation(attribute: String, value: Any?): Boolean {
        if (normalizePropName(attribute) in COMPONENT_SELECTORS && value !is String) return true
        val pending = ArrayDeque<Any?>()
        pending += value
        val seen = IdentityHashMap<Any, Boolean>()
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current !is Map<*, *> && current !is List<*>) continue
            if (seen.put(current, true) != null) continue
            if (current is Map<*, *>) {
                for ((k, item) in current) {
                    val name = (k as String).lowercase()
                    if (name in COMPONENT_OPTION_KEYS || name.startsWith("__v_")) return true
                    pending += item
                }
            } else {
                for (item in current as List<*>) pending += item
            }
        }
        return false
    }

    private val SAFE_URL = Regex("^[A-Za-z0-9;/?:@&=+$,\\-_.!~*'#]*\\z")
    private val HTTP_URL = Regex("^https?://(?!xn--)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.(?!xn--)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*(?::[0-9]{1,4})?(?:[/?#][A-Za-z0-9;/?:@&=+$,\\-_.!~*'#]*)?\\z")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val LAST_LABEL_HAS_LETTER = Regex("^https?://(?:[^/?#:]*\\.)?[A-Za-z0-9-]*[A-Za-z][A-Za-z0-9-]*(?::[0-9]+)?(?:[/?#]|\\z)")

    /** `isAnchorLinkAllowed`, for the URLs this port accepts (others are unsupported). */
    private fun anchorLinkAllowed(value: Any?): Boolean {
        if (value !is String) unsupported(Reason.URL, "non-string URL")
        if (!SAFE_URL.matches(value) || value.contains('&')) unsupported(Reason.URL, "URL with characters the parsers encode or decode differently")
        if (value.startsWith("//")) unsupported(Reason.URL, "protocol-relative URL")
        val scheme = SCHEME.find(value)?.value?.lowercase() ?: return true
        return when (scheme) {
            // the WHATWG URL parser reads a host whose last label is numeric as IPv4
            "http:", "https:" -> if (HTTP_URL.matches(value) && LAST_LABEL_HAS_LETTER.containsMatchIn(value)) true else unsupported(Reason.URL, "absolute URL")
            "mailto:", "tel:" -> true
            else -> unsupported(Reason.URL, "URL scheme $scheme")
        }
    }

    // ---- Nuxt Content's link handler (module.mjs normaliseLink / generatePath) ------------------------------------------

    private fun normaliseLink(link: String): String {
        val hash = Regex("#${StudioJs.RE_DOT}+\\z").find(link)?.value ?: ""
        val withoutHash = Regex("#${StudioJs.RE_DOT}+\\z").replaceFirst(link, "")
        val relative = Regex("^\\.{1,2}(/|\\z)").containsMatchIn(link)
        if (withoutHash.endsWith(".md") && (relative || !link.startsWith("http") && !link.startsWith("/"))) {
            val path = link.replaceFirst(".md$hash", "")
            return path.split('/').joinToString("/") { slugify(StudioDbFrontMatter.refineUrlPart(it)) } + hash
        }
        return link
    }

    /**
     * slugify 1.6 with default options, for the characters this port accepts in URLs: `-` is the replacement character, so
     * it becomes a space, spaces are trimmed and every run of them becomes one `-`.
     */
    private fun slugify(s: String): String {
        if (!Regex("^[A-Za-z0-9._~-]*\\z").matches(s)) unsupported(Reason.URL, ".md link with characters slugify replaces")
        return s.replace('-', ' ').trim(' ').replace(Regex(" +"), "-")
    }

    /** `propsMDCToComark` with `unbindMDCProps` (Studio `legacy.js`). */
    private fun propsMDCToComark(props: Map<String, Any?>): Map<String, Any?> {
        val next = LinkedHashMap<String, Any?>()
        val unbound = HashSet<String>()
        for ((rawKey, value) in props) {
            if (rawKey.startsWith(":") && value is String) {
                try {
                    val parsed = StudioJs.jsonParse(value)
                    if (parsed is Boolean) {
                        next[rawKey] = value
                    } else {
                        val key = rawKey.substring(1)
                        next[key] = parsed
                        unbound += key
                    }
                } catch (_: StudioJs.JsonSyntaxError) {
                    next[rawKey] = value
                }
            } else {
                next[rawKey] = value
            }
        }
        if ("className" in next) {
            val className = next.remove("className")
            val classStr = if (className is List<*>) className.joinToString(" ") { StudioJs.str(it) } else StudioJs.str(className ?: "")
            if (classStr.isNotEmpty()) {
                val existing = next["class"]
                next["class"] = if (existing is String) "$existing $classStr" else classStr
            }
        }
        next.remove("rel")
        for (key in next.keys.toList()) {
            if (key in unbound) continue
            val value = next[key]
            if (value is List<*> && value.all { it is String || it is Double || it is Boolean }) next[key] = value.joinToString(" ") { StudioJs.str(it) }
        }
        return next
    }

    // ---- code blocks ----------------------------------------------------------------------------------------------------

    /** A text-path `pre` as the database path gives it to the renderer. */
    private fun pre(el: StudioElement): StudioNode {
        val a = el.attrs
        val codeEl = el.children.singleOrNull() as? StudioElement
        val code = (codeEl?.children?.singleOrNull() as? StudioText)?.text ?: if (codeEl != null && codeEl.children.isEmpty()) "" else null
        if (codeEl?.tag != "code" || code == null) unsupported(Reason.CODE_BLOCK, "pre without a single code text")
        if (a.keys.any { it !in setOf("language", "filename", "highlights", "meta", "class") } || a["class"].let { it != null && it != "shiki" }) {
            unsupported(Reason.CODE_BLOCK, "code block attributes")
        }
        if (code.isEmpty()) unsupported(Reason.CODE_BLOCK, "empty code block")
        // the info string as the renderer wrote it, read the way micromark and the code handler do
        val rendered = StudioRenderer.renderNodes(listOf(el))
        val firstLine = rendered.substringBefore('\n')
        val info = firstLine.trimStart(firstLine.firstOrNull() ?: '`')
        if ('\\' in info || '&' in info) unsupported(Reason.CODE_BLOCK, "info string with escapes or references")
        val trimmed = info.trim(' ', '\t')
        val langWord = trimmed.takeWhile { it != ' ' && it != '\t' }
        val metaPart = trimmed.substring(langWord.length).trimStart(' ', '\t')
        val thematic = parseThematicBlock(langWord + " " + metaPart)
        val attrs = LinkedHashMap<String, Any?>()
        val bare = thematic == null
        if (thematic != null) {
            thematic.filename?.let { attrs["filename"] = it }
            thematic.highlights?.let { h -> attrs["highlights"] = h.joinToString(" ") { StudioJs.numberToString(parseIntOf(it)) } }
            attrs["language"] = thematic.language ?: "text"
            if (thematic.meta.isNotEmpty()) attrs["meta"] = thematic.meta
        }
        if (bare) attrs.remove("language")
        return StudioElement("pre", attrs, listOf(StudioElement("code", mapOf("__ignoreMap" to ""), listOf(StudioText(detab(code))))))
    }

    /** `Number.parseInt(String(n))`, as `validateProps` reads the highlights back. */
    private fun parseIntOf(n: Double): Double {
        val m = Regex("^[ \\t\\n]*([+-]?[0-9]+)").find(StudioJs.numberToString(n)) ?: return Double.NaN
        return m.groupValues[1].toDouble()
    }

    private class Thematic(val language: String?, val highlights: List<Double>?, val filename: String?, val meta: String)

    /** `parseThematicBlock(lang)` (`@nuxtjs/mdc` `parser/handlers/utils.js`); `null` when every field is undefined. */
    private fun parseThematicBlock(lang: String): Thematic? {
        if (StudioJs.trim(lang).isEmpty()) return null
        val languageMatch = Regex("^[^ \\t]+(?=[ \\t]|\\z)").find(Regex("[{|\\[](${StudioJs.RE_DOT}+)").replaceFirst(lang, ""))?.value
        val highlightMatch = Regex("\\{([^}]*)\\}").find(lang)
        val filenameMatch = Regex("\\[(${StudioJs.RE_DOT}*)\\]").find(lang)
        val meta = StudioJs.trim(
            lang.replaceFirst(languageMatch ?: "", "").replaceFirst(highlightMatch?.value ?: "", "").replaceFirst(filenameMatch?.value ?: "", ""),
        )
        val filename = filenameMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            ?.replace(Regex("\\\\([\\[\\]{}().*+?^$|])"), "$1")
        return Thematic(languageMatch?.takeIf { it.isNotEmpty() }, parseHighlightedLines(highlightMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }), filename, meta)
    }

    private fun parseHighlightedLines(lines: String?): List<Double>? {
        val out = ArrayList<Double>()
        for (line in (lines ?: "").split(',').filter { it.isNotEmpty() }) {
            val parts = StudioJs.trim(line).split('-').map { StudioJs.toNumber(StudioJs.trim(it)) }
            val start = parts[0]
            val end = parts.getOrNull(1)
            fun or0(v: Double?) = if (v == null || v.isNaN() || v == 0.0) null else v
            val s = or0(start) ?: 0.0
            val length = (or0(end) ?: or0(start) ?: 0.0) - s + 1
            if (length > 10_000) unsupported(Reason.CODE_BLOCK, "highlight range")
            var i = 0
            while (i < length) {
                out += s + i
                i++
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** `detab(value)` with the default 4-column tab stops. */
    fun detab(value: String): String {
        if ('\t' !in value) return value
        val sb = StringBuilder()
        var column = 0
        for (c in value) {
            when (c) {
                '\t' -> {
                    val add = 4 - column % 4
                    repeat(add) { sb.append(' ') }
                    column += add
                }
                '\n', '\r' -> {
                    sb.append(c); column = 0
                }
                else -> {
                    sb.append(c); column++
                }
            }
        }
        return sb.toString()
    }

    // ---- shared helpers -------------------------------------------------------------------------------------------------

    private fun isInline(n: StudioNode) = n is StudioText || n is StudioElement && n.tag in HTML_INLINE_TAGS
    private fun isBlock(n: StudioNode) = n is StudioElement && n.tag in HTML_BLOCK_TAGS

    /**
     * hast-util-raw merges adjacent text nodes; compileHast then drops text nodes of line endings only, and paragraphs of
     * whitespace only (JavaScript's `\s`, which includes Unicode spaces markdown-exit keeps as text).
     */
    private fun compiled(nodes: List<StudioNode>): List<StudioNode> = mergeText(nodes).map { if (it is StudioText) StudioText(trimLineEnds(it.text)) else it }.filter { n ->
        !(n is StudioText && n.text.isNotEmpty() && n.text.all { it == '\n' }) &&
            !(n is StudioElement && n.tag == "p" && n.children.all { it is StudioText && WHITESPACE_ONLY.matches(it.text) })
    }

    private fun mergeText(nodes: List<StudioNode>): List<StudioNode> {
        val out = ArrayList<StudioNode>()
        for (n in nodes) {
            val last = out.lastOrNull()
            if (n is StudioText && last is StudioText) out[out.size - 1] = StudioText(last.text + n.text) else out += n
        }
        return out
    }

    /** scule `kebabCase`. */
    fun kebabCase(s: String): String = StudioDbFrontMatter.splitByCase(s).joinToString("-") { it.lowercase(java.util.Locale.ROOT) }

    /** `normalizeMdcChildren` (Studio `legacy.js`): inline runs beside blocks are wrapped in paragraphs. */
    fun normalizeMdcChildren(children: List<StudioNode>): List<StudioNode> {
        if (children.none { isBlock(it) } || children.none { isInline(it) }) return children
        val result = ArrayList<StudioNode>()
        var buf = ArrayList<StudioNode>()
        fun flush() {
            if (buf.isEmpty()) return
            if (buf.any { it is StudioElement || it is StudioText && StudioJs.trim(it.text).isNotEmpty() }) result += StudioElement("p", emptyMap(), buf)
            buf = ArrayList()
        }
        for (c in children) {
            if (isInline(c)) buf += c else {
                flush(); result += c
            }
        }
        flush()
        return result
    }
}

/** Whether `flat.unflatten` (remark-mdc's YAML props) leaves props as they are. */
internal object StudioFlatCheck {
    fun isIdentity(values: Map<String, Any?>): Boolean = try {
        deepEquals(StudioFlat.unflatten(values), values)
    } catch (_: StudioDbPath.UnsupportedException) {
        false
    }

    fun deepEquals(a: Any?, b: Any?): Boolean = when {
        a is Map<*, *> && b is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val ka = StudioJs.keys(a as Map<String, *>)
            @Suppress("UNCHECKED_CAST")
            ka == StudioJs.keys(b as Map<String, *>) && ka.all { deepEquals(a[it], b[it]) }
        }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { deepEquals(a[it], b[it]) }
        else -> a == b
    }
}
