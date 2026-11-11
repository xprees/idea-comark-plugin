package cz.xprees.comark.format.studio

import java.util.Locale

/**
 * A byte-exact port of comark 0.7.0 `renderMarkdown(document, { blockAttributesStyle: 'frontmatter' })`: `render.js`,
 * `internal/stringify/state.js`, `attributes.js`, `fence.js`, `internal/frontmatter.js` and every handler in
 * `internal/stringify/handlers/`, with no user components, `maxInlineAttributes` 3 and block separator `\n\n`.
 *
 * Comark's quirks are kept on purpose (table widths in UTF-16 code units, adjacent lists, `` `` `` for inline code that
 * contains a backtick, `&` escaping in HTML attributes, ...). The sources this follows are in comark's published
 * `dist/` folder; the function names match theirs.
 */
object StudioRenderer {
    private const val BLOCK_SEPARATOR = "\n\n"
    private const val MAX_INLINE_ATTRIBUTES = 3

    /** `renderMarkdown(document, { blockAttributesStyle: 'frontmatter' })`. */
    fun renderMarkdown(document: StudioDocument): String {
        val content = render(document)
        return renderFrontmatter(document.frontmatterValue, content)
    }

    /** `render(document, ...)`: the body, trimmed and ending with one `\n`. */
    fun render(document: StudioDocument): String {
        val state = State(Context(), 0)
        val sb = StringBuilder()
        for (child in document.nodes) sb.append(one(child, state, null))
        return StudioJs.trim(sb.toString()) + "\n"
    }

    /** `renderFrontmatter(data, content)` (`internal/frontmatter.js`). */
    fun renderFrontmatter(data: Any?, content: String): String {
        val noKeys = when (data) {
            is Map<*, *> -> data.isEmpty()
            is List<*> -> data.isEmpty()
            is String -> data.isEmpty()
            else -> true // Object.keys of null, booleans and numbers is empty
        }
        if (!StudioJs.truthy(data) || noKeys) return StudioJs.trim(content)
        val fm = StudioJs.trim(StudioYaml.stringify(data))
        if (content.isNotEmpty()) return "---\n$fm\n---\n\n" + StudioJs.trim(content)
        return fm
    }

    // ---- state ----------------------------------------------------------------------------------------------------------

    /** The mutable `state.context` keys handlers set through `applyContext`. `order` 0 stands for `false`/`undefined`. */
    private class Context {
        var inline: Boolean? = null
        var list: Boolean? = null
        var order: Double = 0.0
        var listIndent: Int? = null

        /** `applyContext({ list, order, listIndent })` as `ul`/`ol` call it; returns the revert values. */
        fun applyList(order: Double, listIndent: Int): ListSnapshot {
            val revert = ListSnapshot(list, this.order, this.listIndent)
            list = true; this.order = order; this.listIndent = listIndent
            return revert
        }

        fun restore(s: ListSnapshot) {
            list = s.list; order = s.order; listIndent = s.listIndent
        }
    }

    private data class ListSnapshot(val list: Boolean?, val order: Double, val listIndent: Int?)

    /** comark's `state`: the shared context plus `nodeDepthInTree` (which `mdc` copies the state to increase). */
    private class State(val ctx: Context, val depth: Int)

    private val HANDLER_NAMES = setOf(
        "code", "pre", "hr", "br", "h1", "h2", "h3", "h4", "h5", "h6", "p", "a", "ul", "ol", "li", "html", "strong", "em",
        "blockquote", "img", "del", "mdc", "template", "table", "thead", "tbody", "tr", "th", "td", "comment", "math", "mermaid",
    )

    /** What `tag in state.handlers` also finds on the object's prototype. */
    private val OBJECT_PROTOTYPE_KEYS = setOf(
        "constructor", "hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable", "toLocaleString", "toString", "valueOf",
        "__proto__", "__defineGetter__", "__defineSetter__", "__lookupGetter__", "__lookupSetter__",
    )

    private fun tagOf(node: StudioNode): String? = when (node) {
        is StudioElement -> node.tag
        is StudioComment -> null
        is StudioText -> null
    }

    private fun dollar(attrs: Map<String, Any?>, key: String): Any? = (attrs["\$"] as? Map<*, *>)?.get(key)

    private fun isOne(v: Any?) = v is Number && v.toDouble() == 1.0
    private fun isZero(v: Any?) = v is Number && v.toDouble() == 0.0

    private fun one(node: StudioNode, state: State, parent: StudioElement?, atLineStart: Boolean = false): String = when (node) {
        is StudioText -> if (parent != null && isOne(dollar(parent.attrs, "html")) && isOne(dollar(parent.attrs, "block"))) {
            node.text
        } else {
            escapeTextNode(node.text, atLineStart)
        }
        is StudioComment -> "<!--${node.text ?: "undefined"}-->" + BLOCK_SEPARATOR
        is StudioElement -> element(node, state, parent)
    }

    private fun element(node: StudioElement, state: State, parent: StudioElement?): String {
        if (isOne(dollar(node.attrs, "html"))) return html(node, state, parent)
        return when (node.tag) {
            "code" -> code(node)
            "pre" -> pre(node)
            "hr" -> if (parent?.tag == "p") ":hr" else "---$BLOCK_SEPARATOR"
            "br" -> "  \n"
            "h1", "h2", "h3", "h4", "h5", "h6" -> heading(node, state)
            "p" -> p(node, state, parent)
            "a" -> a(node, state)
            "ul" -> ul(node, state)
            "ol" -> ol(node, state)
            "li" -> li(node, state)
            "html" -> html(node, state, parent)
            "strong" -> wrapInline(node, state, "**")
            "em" -> wrapInline(node, state, "*")
            "del" -> wrapInline(node, state, "~~")
            "blockquote" -> blockquote(node, state)
            "img" -> img(node)
            "mdc" -> mdc(node, state, parent)
            "template" -> template(node, state, parent)
            "table" -> table(node, state)
            "thead", "tbody", "tr", "th", "td", "comment" -> ""
            "math" -> math(node, parent)
            "mermaid" -> mermaid(node)
            else -> mdc(node, state, parent)
        }
    }

    private fun flow(node: StudioElement, state: State): String {
        val sb = StringBuilder()
        for (child in node.children) sb.append(one(child, state, node))
        return sb.toString()
    }

    // ---- text escaping (state.js) -----------------------------------------------------------------------------------------

    private fun escapeTextNode(text: String, atLineStart: Boolean): String {
        val escaped = escapeInline(text)
        if (!atLineStart && '\n' !in escaped) return escaped
        return escaped.split("\n").mapIndexed { index, line -> if (index > 0 || atLineStart) escapeLeadingBlock(line) else line }
            .joinToString("\n")
    }

    private fun isAsciiAlphaNumeric(c: Char?) = c != null && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9')

    private fun escapeInline(source: String): String {
        val sb = StringBuilder(source.length + 8)
        var nextGt = -2 // lazily: index of the last '>' in source, -1 when there is none
        for (offset in source.indices) {
            val ch = source[offset]
            if (ch !in "\\`*_<&~[]{:") {
                sb.append(ch); continue
            }
            val prev = source.getOrNull(offset - 1)
            val next = source.getOrNull(offset + 1)
            val keep = when (ch) {
                '_' -> isAsciiAlphaNumeric(prev) && isAsciiAlphaNumeric(next)
                '<' -> {
                    val tagStart = next != null && (next in 'A'..'Z' || next in 'a'..'z' || next == '!' || next == '?' || next == '/')
                    if (!tagStart) {
                        true
                    } else {
                        if (nextGt == -2) nextGt = source.lastIndexOf('>')
                        nextGt < offset + 1
                    }
                }
                '&' -> !entityAt(source, offset)
                ':' -> {
                    val prevAllowed = prev == null || prev in " \t\n*_["
                    !(prevAllowed && next != null && (next in 'a'..'z' || next in 'A'..'Z' || next == '$'))
                }
                '{' -> if (prev == '{' || prev == '$') true else !attributeBraceAt(source, offset)
                else -> false
            }
            if (!keep) sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    /** `/^&#?[a-zA-Z0-9]+;/.test(source.slice(offset))` */
    private fun entityAt(s: String, offset: Int): Boolean {
        var i = offset + 1
        if (i < s.length && s[i] == '#') i++
        val start = i
        while (i < s.length && (s[i] in 'a'..'z' || s[i] in 'A'..'Z' || s[i] in '0'..'9')) i++
        return i > start && i < s.length && s[i] == ';'
    }

    /** `/^\{[ \t]{0,3}[.#:a-zA-Z_]/.test(source.slice(offset, offset + 6))` */
    private fun attributeBraceAt(s: String, offset: Int): Boolean {
        val end = minOf(s.length, offset + 6)
        var i = offset + 1
        var spaces = 0
        while (i < end && (s[i] == ' ' || s[i] == '\t') && spaces < 3) { i++; spaces++ }
        if (i >= end) return false
        val c = s[i]
        return c == '.' || c == '#' || c == ':' || c == '_' || c in 'a'..'z' || c in 'A'..'Z'
    }

    private fun escapeLeadingBlock(line: String): String {
        if (line.isEmpty()) return line
        // /^#{1,6}([ \t]|$)/
        var h = 0
        while (h < line.length && line[h] == '#') h++
        if (h in 1..6 && (h == line.length || line[h] == ' ' || line[h] == '\t')) return "\\$line"
        if (line[0] == '>') return "\\$line"
        // /^(\d{1,9})[.)]([ \t]|$)/
        var d = 0
        while (d < line.length && line[d] in '0'..'9') d++
        if (d in 1..9 && d < line.length && (line[d] == '.' || line[d] == ')') &&
            (d + 1 == line.length || line[d + 1] == ' ' || line[d + 1] == '\t')
        ) {
            return line.substring(0, d) + "\\" + line.substring(d)
        }
        // /^-([ \t-]|$)/
        if (line[0] == '-' && (line.length == 1 || line[1] == ' ' || line[1] == '\t' || line[1] == '-')) return "\\$line"
        // /^\+([ \t]|$)/
        if (line[0] == '+' && (line.length == 1 || line[1] == ' ' || line[1] == '\t')) return "\\$line"
        // /^=+[ \t]*$/
        if (line[0] == '=') {
            var i = 0
            while (i < line.length && line[i] == '=') i++
            while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
            if (i == line.length) return "\\$line"
        }
        if (line[0] == ':') return "\\$line"
        return line
    }

    // ---- utils -----------------------------------------------------------------------------------------------------------

    /** `indent(text, { ignoreFirstLine, level, width })` from `utils/index.js`. */
    private fun indent(text: String, level: Int = 1, width: Int? = null, ignoreFirstLine: Boolean = false): String {
        val pad = if (width != null && width != 0) " ".repeat(width) else "  ".repeat(level)
        return text.split("\n").mapIndexed { index, line ->
            if (ignoreFirstLine && index == 0) line else if (line.isNotEmpty()) pad + line else line
        }.joinToString("\n")
    }

    private fun textContent(node: StudioNode): String = when (node) {
        is StudioText -> node.text
        // utils `textContent` concatenates the comment's items after the attributes: nothing when it has no text.
        is StudioComment -> node.text ?: ""
        is StudioElement -> node.children.joinToString("") { textContent(it) }
    }

    /** `pickFence(content)` (`fence.js`). */
    fun pickFence(content: String): String {
        var maxBackticks = 0
        var maxTildes = 0
        for (line in content.split("\n")) {
            var i = 0
            while (i < 3 && i < line.length && line[i] == ' ') i++
            if (i >= line.length) continue
            val c = line[i]
            if (c != '`' && c != '~') continue
            var j = i
            while (j < line.length && line[j] == c) j++
            val run = j - i
            if (c == '`') {
                if (run > maxBackticks) maxBackticks = run
            } else if (run > maxTildes) {
                maxTildes = run
            }
        }
        val char = if (maxBackticks <= maxTildes) '`' else '~'
        return char.toString().repeat(maxOf(3, (if (char == '`') maxBackticks else maxTildes) + 1))
    }

    // ---- attributes.js --------------------------------------------------------------------------------------------------

    private fun without(attrs: Map<String, Any?>, vararg keys: String): Map<String, Any?> =
        LinkedHashMap(attrs).apply { keys.forEach { remove(it) } }

    /** `comarkAttributes(attributes)` */
    private fun comarkAttributes(attributes: Map<String, Any?>): String {
        val attrs = StudioJs.entries(attributes).joinToString(" ") { (key, value) ->
            when {
                key.startsWith(":") && value == "true" -> key.substring(1)
                key == "id" -> "#" + StudioJs.str(value)
                key == "class" -> {
                    val classValue = if (value is List<*>) {
                        value.joinToString(" ") { if (it == null || it == JsUndefined) "" else StudioJs.str(it) }
                    } else {
                        StudioJs.str(value)
                    }
                    classValue.split(" ").filter { it.isNotEmpty() }.joinToString("") { ".$it" }
                }
                StudioJs.isObject(value) -> "$key=\"" + (StudioJs.jsonStringify(value) ?: "undefined").replace("\"", "\\\"") + "\""
                else -> {
                    val s = StudioJs.str(value)
                    if ('"' in s && '\'' !in s) "$key='$s'" else "$key=\"" + s.replace("\"", "\\\"") + "\""
                }
            }
        }
        return if (attrs.isNotEmpty()) "{$attrs}" else ""
    }

    private val SAFE_ATTR_NAME = Regex("[a-zA-Z_:][a-zA-Z0-9_:.-]*")

    /** `htmlAttributes(attributes)` */
    private fun htmlAttributes(attributes: Map<String, Any?>): String {
        val parts = ArrayList<String>()
        for ((rawKey, value) in StudioJs.entries(attributes)) {
            val key = if (rawKey.startsWith(":")) rawKey.substring(1) else rawKey
            if (!SAFE_ATTR_NAME.matches(key)) continue
            if (rawKey.startsWith(":")) {
                when {
                    value == "true" -> parts += key
                    value is Map<*, *> || value is List<*> || value is JsDate ->
                        parts += "$key=\"" + escapeHtml(StudioJs.jsonStringify(value) ?: "undefined") + "\""
                    else -> parts += "$key=\"" + escapeHtml(StudioJs.str(value)) + "\""
                }
                continue
            }
            when {
                value == true || value == "true" -> parts += key
                value == false || value == null || value == JsUndefined -> {}
                StudioJs.isObject(value) -> parts += "$key=\"" + escapeHtml(StudioJs.jsonStringify(value) ?: "undefined") + "\""
                else -> parts += "$key=\"" + escapeHtml(StudioJs.str(value)) + "\""
            }
        }
        return parts.joinToString(" ")
    }

    private val HTML_ESCAPED = Regex("^&[a-zA-Z][a-zA-Z0-9]*;|#[0-9]+;|#x[0-9a-fA-F]+;")

    /** `escapeHtml(value)` from `utils/index.js`, including its `&` check that also looks for `#123;` anywhere after. */
    private fun escapeHtml(value: String): String {
        val sb = StringBuilder()
        for ((index, c) in value.withIndex()) {
            when (c) {
                '&' -> if (HTML_ESCAPED.containsMatchIn(value.substring(index))) sb.append('&') else sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private class ImplicitRule(val drop: List<String> = emptyList(), val classBlocklist: List<String>? = null)

    private val IMPLICIT_ATTRS = mapOf(
        "blockquote" to ImplicitRule(drop = listOf("as")),
        "ol" to ImplicitRule(drop = listOf("start")),
        "ul" to ImplicitRule(classBlocklist = listOf("contains-task-list")),
        "li" to ImplicitRule(classBlocklist = listOf("task-list-item")),
        "pre" to ImplicitRule(drop = listOf("language", "filename", "highlights", "meta", "style")),
    )

    /** `userBlockAttrs(tag, attributes)` */
    private fun userBlockAttrs(tag: String, attributes: Map<String, Any?>): Map<String, Any?> {
        val rule = IMPLICIT_ATTRS[tag] ?: return attributes
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in StudioJs.entries(attributes)) {
            if (key in rule.drop) continue
            if (key == "class" && rule.classBlocklist != null && value is String) {
                val remaining = StudioJs.splitWhitespace(value).filter { it.isNotEmpty() && it !in rule.classBlocklist }.joinToString(" ")
                if (remaining.isNotEmpty()) result[key] = remaining
                continue
            }
            if (key == "class" && tag == "pre" && value is String && (value.startsWith("shiki") || value.startsWith("shj"))) {
                val tokens = StudioJs.splitWhitespace(value)
                val cutoff = tokens.indexOf(".")
                val userClass = if (cutoff >= 0) tokens.subList(cutoff + 1, tokens.size).joinToString(" ") else ""
                if (userClass.isNotEmpty()) result[key] = userClass
                continue
            }
            result[key] = value
        }
        return result
    }

    /** `comarkYamlAttributes(attributes, 'frontmatter')` */
    private fun comarkYamlAttributes(attributes: Map<String, Any?>): String {
        val normalized = LinkedHashMap<String, Any?>()
        for ((key, value) in StudioJs.entries(attributes)) {
            if (key.startsWith(":")) {
                if (value is String) {
                    try {
                        normalized[key.substring(1)] = StudioJs.jsonParse(value)
                    } catch (_: StudioJs.JsonSyntaxError) {
                        normalized[key] = value
                    }
                } else {
                    normalized[key.substring(1)] = value
                }
            } else {
                normalized[key] = when (value) {
                    "true" -> true
                    "false" -> false
                    else -> value
                }
            }
        }
        val yamlContent = StudioJs.trim(StudioYaml.stringify(normalized))
        return "---\n$yamlContent\n---"
    }

    // ---- handlers -------------------------------------------------------------------------------------------------------

    private fun code(node: StudioElement): String {
        val attrsString = if (node.attrs.isNotEmpty()) comarkAttributes(node.attrs) else ""
        val content = textContent(node)
        val fence = if ('`' in content) "``" else "`"
        return "$fence$content$fence$attrsString"
    }

    private fun pre(node: StudioElement): String {
        val attributes = node.attrs
        val first = node.children.firstOrNull()
        val codeClasses = when (first) {
            is StudioElement -> first.attrs["class"]
            is StudioComment -> first.attrs["class"]
            else -> null
        }
        val language = attributes["language"].takeIf { StudioJs.truthy(it) }?.let { StudioJs.str(it) }
            ?: (codeClasses as? String)?.split(" ")?.firstOrNull { it.startsWith("language-") }?.substring(9)?.takeIf { it.isNotEmpty() }
            ?: ""
        val filename = attributes["filename"].takeIf { StudioJs.truthy(it) }
            ?.let { " [" + StudioJs.str(it).split("]").joinToString("\\\\]") + "]" } ?: ""
        val highlights = attributes["highlights"].takeIf { StudioJs.truthy(it) }?.let { " {" + formatHighlights(it) + "}" } ?: ""
        val meta = attributes["meta"].takeIf { StudioJs.truthy(it) }?.let { " " + StudioJs.str(it) } ?: ""
        val codeAttr = attributes["code"]
        val code = StudioJs.trimEnd(if (StudioJs.truthy(codeAttr)) StudioJs.str(codeAttr) else textContent(node))
        val fence = pickFence(code)
        val fenceBlock = fence + language + filename + highlights + meta + "\n" + code + "\n" + fence
        val attrs = comarkAttributes(userBlockAttrs("pre", attributes))
        if (attrs.isNotEmpty()) return "::pre$attrs\n$fenceBlock\n::$BLOCK_SEPARATOR"
        return fenceBlock + BLOCK_SEPARATOR
    }

    /**
     * `formatHighlights(highlights)` from `handlers/pre.js`, with JavaScript semantics for any value:
     * `[...highlights].sort((a, b) => a - b)`, ranges merged where `sorted[i] === end + 1`, and every bound written with
     * `String()`. A string is spread into its UTF-16 units. That matters on Studio's database path, where
     * `propsMDCToComark` (legacy.js) turns `highlights: [2, 4, 5]` into the string `"2 4 5"`, so Studio writes `{ , ,2,4,5}`.
     *
     * comark throws for a truthy value that is neither iterable nor has a `length`: a number, an object or a boolean. The
     * port returns `""` there (the fence then ends in ` {}`), which no tree Studio produces reaches.
     */
    private fun formatHighlights(highlights: Any?): String {
        val items: List<Any?> = when (highlights) {
            is List<*> -> highlights
            is String -> highlights.map { it.toString() }
            else -> return ""
        }
        if (items.isEmpty()) return ""
        val sorted = jsSortByDifference(items)
        val ranges = ArrayList<String>()
        var start = sorted[0]
        var end = sorted[0]
        for (i in 1..sorted.size) {
            if (i < sorted.size && strictEquals(sorted[i], plusOne(end))) {
                end = sorted[i]
            } else {
                ranges += if (strictEquals(start, end)) StudioJs.str(start) else StudioJs.str(start) + "-" + StudioJs.str(end)
                if (i < sorted.size) {
                    start = sorted[i]
                    end = sorted[i]
                }
            }
        }
        return ranges.joinToString(",")
    }

    /**
     * `Array.prototype.sort((a, b) => a - b)` as V8 runs it: `undefined` items go last without being compared, and the
     * rest are sorted stably with a comparator whose `NaN` result counts as `0`. V8 and the JDK both use TimSort (binary
     * insertion sort for short arrays), so they compare the same pairs in the same order.
     */
    private fun jsSortByDifference(items: List<Any?>): List<Any?> {
        val defined = items.filter { it !== JsUndefined }
        val undefinedCount = items.size - defined.size
        val sorted = defined.sortedWith { a, b ->
            val d = StudioJs.toNumber(a) - StudioJs.toNumber(b)
            when {
                d.isNaN() || d == 0.0 -> 0
                d < 0 -> -1
                else -> 1
            }
        }
        return sorted + List(undefinedCount) { JsUndefined }
    }

    /** `v + 1`: string concatenation when `v` is (or converts to) a string, numeric addition otherwise. */
    private fun plusOne(v: Any?): Any = when (v) {
        is String, is List<*>, is Map<*, *>, is JsDate -> StudioJs.str(v) + "1"
        else -> StudioJs.toNumber(v) + 1.0
    }

    /** `a === b` (`NaN` is not equal to itself; objects compare by identity). */
    private fun strictEquals(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is String && b is String -> a == b
        a is Boolean && b is Boolean -> a == b
        else -> a === b
    }

    private fun heading(node: StudioElement, state: State): String {
        val level = node.tag.substring(1).toInt()
        val content = flow(node, state)
        val attrs = comarkAttributes(without(node.attrs, "id"))
        val suffix = if (attrs.isNotEmpty()) " $attrs" else ""
        return "#".repeat(level) + " " + content + suffix + BLOCK_SEPARATOR
    }

    private fun stripTrailingSpaceTab(s: String): String {
        var e = s.length
        while (e > 0 && (s[e - 1] == ' ' || s[e - 1] == '\t')) e--
        return s.substring(0, e)
    }

    private fun p(node: StudioElement, state: State, parent: StudioElement?): String {
        val sb = StringBuilder()
        for (child in node.children) sb.append(one(child, state, node, sb.isEmpty() || sb.endsWith("\n")))
        var result = sb.toString()
        val attrs = comarkAttributes(node.attrs)
        if (attrs.isNotEmpty()) result = stripTrailingSpaceTab(result) + " " + attrs
        if (parent?.tag == "li") return result
        return result + BLOCK_SEPARATOR
    }

    private fun a(node: StudioElement, state: State): String {
        val href = node.attrs["href"] ?: if ("href" in node.attrs) null else JsUndefined
        val rest = without(node.attrs, "href")
        val attrsString = if (rest.isNotEmpty()) comarkAttributes(rest) else ""
        val content = flow(node, state)
        if (href is String && content == href && attrsString.isEmpty()) return "<$href>"
        return "[$content](${StudioJs.str(href)})$attrsString"
    }

    private fun wrapInline(node: StudioElement, state: State, marker: String): String {
        val sb = StringBuilder()
        for (child in node.children) sb.append(one(child, state, node))
        val content = StudioJs.trim(sb.toString())
        val attrsString = if (node.attrs.isNotEmpty()) comarkAttributes(node.attrs) else ""
        return "$marker$content$marker$attrsString"
    }

    private fun blockquote(node: StudioElement, state: State): String {
        val sb = StringBuilder()
        for (child in node.children) sb.append(one(child, state, node, sb.isEmpty() || sb.endsWith("\n")))
        var childResult = sb.toString()
        val attrs = comarkAttributes(userBlockAttrs("blockquote", node.attrs))
        val hasBlockChildren = node.children.any { it !is StudioText }
        fun quote(text: String) = StudioJs.trim(text).split("\n").joinToString("\n") { if (it.isNotEmpty()) "> $it" else ">" }
        if (attrs.isNotEmpty() && hasBlockChildren) {
            return "::blockquote$attrs\n${quote(childResult)}\n::$BLOCK_SEPARATOR"
        }
        if (attrs.isNotEmpty()) childResult = stripTrailingSpaceTab(childResult) + " " + attrs
        val content = quote(childResult)
        val asValue = node.attrs["as"]
        if (StudioJs.truthy(asValue)) {
            return "> [!${StudioJs.str(asValue).uppercase(Locale.ROOT)}]\n" + content + BLOCK_SEPARATOR
        }
        return content + BLOCK_SEPARATOR
    }

    private fun img(node: StudioElement): String {
        val attrs = node.attrs
        val title = attrs["title"]
        val src = if ("src" in attrs) attrs["src"] else JsUndefined
        val alt = if ("alt" in attrs && attrs["alt"] != JsUndefined) attrs["alt"] else ""
        val rest = without(attrs, "title", "src", "alt")
        val attrsString = if (rest.isNotEmpty()) comarkAttributes(rest) else ""
        val link = if (StudioJs.truthy(title)) {
            "![${StudioJs.str(alt)}](${StudioJs.str(src)} \"${StudioJs.str(title)}\")"
        } else {
            "![${StudioJs.str(alt)}](${StudioJs.str(src)})"
        }
        return link + attrsString
    }

    private fun ul(node: StudioElement, state: State): String {
        val revert = state.ctx.applyList(0.0, 2)
        val sb = StringBuilder()
        for (child in node.children) sb.append(one(child, state, null))
        var result = StudioJs.trim(sb.toString())
        state.ctx.restore(revert)
        val attrs = comarkAttributes(userBlockAttrs("ul", node.attrs))
        val nested = revert.list == true
        val outerIndent = revert.listIndent?.takeIf { it != 0 } ?: 2
        if (attrs.isNotEmpty()) {
            if (nested) return "\n" + indent("::ul$attrs\n$result\n::", width = outerIndent)
            return "::ul$attrs\n$result\n::$BLOCK_SEPARATOR"
        }
        result = if (nested) "\n" + indent(result, width = outerIndent) else result + BLOCK_SEPARATOR
        return result
    }

    private fun ol(node: StudioElement, state: State): String {
        val start = StudioJs.toNumber(node.attrs["start"] ?: if ("start" in node.attrs) null else JsUndefined)
        val order = if (!start.isNaN() && !start.isInfinite() && start == Math.floor(start) && start >= 1) start else 1.0
        val revert = state.ctx.applyList(order, 3)
        val sb = StringBuilder()
        for (child in node.children) sb.append(one(child, state, null))
        var result = StudioJs.trim(sb.toString())
        state.ctx.restore(revert)
        val attrs = comarkAttributes(userBlockAttrs("ol", node.attrs))
        val nested = revert.list == true
        val outerIndent = revert.listIndent?.takeIf { it != 0 } ?: 2
        if (attrs.isNotEmpty()) {
            if (nested) return "\n" + indent("::ol$attrs\n$result\n::", width = outerIndent)
            return "::ol$attrs\n$result\n::$BLOCK_SEPARATOR"
        }
        result = if (nested) "\n" + indent(result, width = outerIndent) else result + BLOCK_SEPARATOR
        return result
    }

    private val LI_BLOCK_ELEMENTS = setOf("pre", "blockquote", "table")

    private fun li(node: StudioElement, state: State): String {
        val children = node.children.toMutableList()
        val order = state.ctx.order
        var prefix = if (order != 0.0) StudioJs.numberToString(order) + ". " else "- "
        val classNameAttr = node.attrs["className"]
        val className = if (classNameAttr is List<*>) {
            classNameAttr.joinToString(" ") { if (it == null || it == JsUndefined) "" else StudioJs.str(it) }
        } else {
            StudioJs.str(
                if (StudioJs.truthy(classNameAttr)) classNameAttr else node.attrs["class"] ?: if ("class" in node.attrs) null else JsUndefined,
            )
        }
        if ("task-list-item" in className) {
            val inputAttrs = when (val input = children.removeAt(0)) {
                is StudioElement -> input.attrs
                is StudioComment -> input.attrs
                is StudioText -> emptyMap()
            }
            val checked = StudioJs.truthy(inputAttrs["checked"]) || StudioJs.truthy(inputAttrs[":checked"])
            prefix += if (checked) "[x] " else "[ ] "
        }
        val prefixWidth = prefix.length
        val hasInlineContent = children.any { it is StudioText }
        var result = ""
        for (child in children) {
            if (child !is StudioText) {
                val tag = tagOf(child)
                if (result.isNotEmpty() && tag in LI_BLOCK_ELEMENTS) {
                    val indented = indent(one(child, state, node), width = prefixWidth)
                    result = StudioJs.trimEnd(result) + "\n" + StudioJs.trimEnd(indented) + "\n"
                    continue
                }
                if (result.isNotEmpty() && tag == "p") {
                    val indented = indent(one(child, state, node), width = prefixWidth)
                    result = StudioJs.trimEnd(result) + "\n\n" + StudioJs.trimEnd(indented) + "\n"
                    continue
                }
                val tagName = tag ?: "null"
                if (!hasInlineContent && tagName !in HANDLER_NAMES && tagName !in OBJECT_PROTOTYPE_KEYS) {
                    val indented = indent(one(child, state, null), width = prefixWidth, ignoreFirstLine = result.isEmpty())
                    result = if (result.isNotEmpty()) {
                        StudioJs.trimEnd(result) + "\n" + StudioJs.trimEnd(indented) + "\n"
                    } else {
                        StudioJs.trimEnd(indented) + "\n"
                    }
                    continue
                }
            }
            result += one(child, state, node, result.isEmpty() || result.endsWith("\n"))
        }
        result = StudioJs.trim(result)
        val attrs = comarkAttributes(userBlockAttrs("li", node.attrs))
        val suffix = if (attrs.isNotEmpty()) " $attrs" else ""
        if (order != 0.0) state.ctx.order = order + 1
        return "$prefix$result$suffix\n"
    }

    private val TEXT_BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "td", "th")
    private val SELF_CLOSE_TAGS = setOf("br", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    private val INLINE_TAGS = setOf("strong", "em", "del", "code", "a", "br", "span", "img")
    private val BLOCK_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "blockquote", "hr", "table", "td", "th")

    /** `String(child?.[0])` */
    private fun tagString(node: StudioNode): String = when (node) {
        is StudioElement -> node.tag
        is StudioComment -> "null"
        is StudioText -> "undefined" // a string's [0] is its first character; only used where the node is not a string
    }

    private fun html(node: StudioElement, state: State, parent: StudioElement?): String {
        val tag = node.tag
        val dollarAttr = node.attrs["\$"] as? Map<*, *>
        val attributes = without(node.attrs, "\$")
        val children = node.children
        val hasOnlyTextChildren = children.all { it is StudioText || tagString(it) in INLINE_TAGS }
        val hasTextSibling = children.any { it is StudioText }
        val isBlock = tag in TEXT_BLOCKS
        val blockZero = isZero(dollarAttr?.get("block"))
        val isInline = tag in INLINE_TAGS && blockZero
        var oneLiner = isBlock && hasOnlyTextChildren
        if (!oneLiner && tag in INLINE_TAGS && hasOnlyTextChildren) oneLiner = true
        if (tag == "pre") oneLiner = true
        if (parent?.tag == "p" || state.ctx.inline == true) oneLiner = true
        if (blockZero) oneLiner = true
        val isSelfClose = tag in SELF_CLOSE_TAGS
        val revert = state.ctx.inline
        state.ctx.inline = oneLiner
        val childrenContent = children.map { one(it, state, node) }
        val childSeparator = if (oneLiner) "" else "\n"
        val sb = StringBuilder()
        var isPrevBlock = true
        for (i in children.indices) {
            val child = children[i]
            val childIsBlock = child !is StudioText &&
                (tagString(child) in BLOCK_TAGS || (tagString(child) !in INLINE_TAGS && !hasTextSibling))
            if (i > 0 && !isPrevBlock && childIsBlock) sb.append(childSeparator)
            sb.append(childrenContent[i])
            isPrevBlock = childIsBlock
            if (childIsBlock && i < children.size - 1) sb.append(childSeparator)
        }
        state.ctx.inline = revert
        var content = sb.toString()
        val attrs = if (attributes.isNotEmpty()) " " + htmlAttributes(attributes) else ""
        val separator = if (parent == null && !isInline) BLOCK_SEPARATOR else ""
        if (isSelfClose) return "<$tag$attrs>$separator"
        if (!oneLiner && content.isNotEmpty()) content = "\n" + StudioJs.trimEnd(content) + "\n"
        return "<$tag$attrs>$content</$tag>$separator"
    }

    private val INLINE_HTML_ELEMENTS = setOf("a", "strong", "em", "span")

    private fun mdc(node: StudioElement, state: State, parent: StudioElement?): String {
        val tag = node.tag
        val attributes = without(node.attrs, "\$")
        if (tag == "table") return html(node, state, null)
        val attributeEntries = StudioJs.entries(attributes)
        val hasObjectAttributes = attributeEntries.any { StudioJs.isObject(it.second) }
        val hasTextSiblings = parent?.children?.any { it is StudioText } ?: false
        val insideInlineElement = parent != null && parent.tag in INLINE_HTML_ELEMENTS
        var inline = hasTextSiblings || insideInlineElement || state.ctx.inline == true
        if (hasObjectAttributes) inline = false
        val revert = state.ctx.inline
        if (inline) state.ctx.inline = true
        val childState = State(state.ctx, state.depth + 1)
        val sb = StringBuilder()
        for (child in node.children) sb.append(one(child, childState, node))
        val content = StudioJs.trimEnd(sb.toString())
        if (inline) state.ctx.inline = revert
        var attrs = if (attributeEntries.isNotEmpty()) comarkAttributes(attributes) else ""
        if (tag == "span") {
            if (attrs.isEmpty() && parent?.tag == "a") attrs = "{}"
            return "[$content]$attrs" + if (inline) "" else BLOCK_SEPARATOR
        }
        val fence = ":".repeat(state.depth + 2)
        var result = ":$tag" + (if (content.isNotEmpty()) "[$content]" else "") + attrs + if (parent == null) BLOCK_SEPARATOR else ""
        if (!inline) {
            val useYaml = hasObjectAttributes || attributeEntries.size > MAX_INLINE_ATTRIBUTES
            val body = if (content.isNotEmpty()) "\n$content" else ""
            result = if (useYaml) {
                "$fence$tag\n${comarkYamlAttributes(attributes)}$body\n$fence$BLOCK_SEPARATOR"
            } else {
                "$fence$tag$attrs$body\n$fence$BLOCK_SEPARATOR"
            }
        }
        return if (inline) result else indent(result, level = if (parent != null) 1 else 0)
    }

    private fun template(node: StudioElement, state: State, parent: StudioElement?): String {
        val attrs = node.attrs
        val content = StudioJs.trimEnd(flow(node, state))
        val name = if ("name" in attrs) attrs["name"] else JsUndefined
        if (name == "default") {
            val templateCount = parent?.children?.count { it is StudioElement && it.tag == "template" } ?: 0
            if (templateCount == 1) return content + BLOCK_SEPARATOR
        }
        val extraAttrs = comarkAttributes(without(attrs, "name", "\$"))
        return "#${StudioJs.str(name)}$extraAttrs\n$content$BLOCK_SEPARATOR"
    }

    private fun math(node: StudioElement, parent: StudioElement?): String {
        val content = textContent(node)
        val className = node.attrs["class"]
        val classes = if (className is String) className.split(" ") else emptyList()
        val hasInlineSiblings = parent?.children?.any { it is StudioText } ?: false
        val isInline = "inline" in classes || ("block" !in classes && hasInlineSiblings)
        if (isInline) return "$$content$"
        return "$$\n$content\n$$$BLOCK_SEPARATOR"
    }

    private fun mermaid(node: StudioElement): String {
        val content = node.attrs["content"]
        val attrs = comarkAttributes(without(node.attrs, "content"))
        val raw = if (content == null || content == JsUndefined) "" else StudioJs.str(content)
        val body = if (raw.endsWith("\n")) raw.substring(0, raw.length - 1) else raw
        val fence = pickFence(body)
        return "${fence}mermaid${if (attrs.isNotEmpty()) " $attrs" else ""}\n$body\n$fence$BLOCK_SEPARATOR"
    }

    // ---- table.js -------------------------------------------------------------------------------------------------------

    private fun alignment(attributes: Map<String, Any?>): String? {
        val style = attributes["style"] as? String ?: return null
        val normalized = style.lowercase(Locale.ROOT).replace(" ", "").replace("\t", "")
        return when {
            "text-align:left" in normalized -> "left"
            "text-align:center" in normalized -> "center"
            "text-align:right" in normalized -> "right"
            else -> null
        }
    }

    private fun cellContent(cell: StudioElement, state: State): String {
        val sb = StringBuilder()
        for (child in cell.children) {
            if (child is StudioText) sb.append(child.text) else sb.append(one(child, state, cell))
        }
        return escapePipes(StudioJs.trim(sb.toString()))
    }

    private fun escapePipes(text: String) = text.split("\n").joinToString(" ").split("|").joinToString("\\|")

    private fun rows(element: StudioNode): List<StudioElement> {
        if (element !is StudioElement) return emptyList()
        if (element.tag == "tr") return listOf(element)
        if (element.tag == "thead" || element.tag == "tbody") return element.children.filterIsInstance<StudioElement>().filter { it.tag == "tr" }
        return emptyList()
    }

    private fun cells(row: StudioElement): List<StudioElement> =
        row.children.filterIsInstance<StudioElement>().filter { it.tag == "th" || it.tag == "td" }

    private fun table(node: StudioElement, state: State): String {
        var headerRows = mutableListOf<StudioElement>()
        var bodyRows = mutableListOf<StudioElement>()
        for (child in node.children) {
            if (child is StudioText) continue
            when (tagOf(child)) {
                "thead" -> headerRows = rows(child).toMutableList()
                "tbody" -> bodyRows = rows(child).toMutableList()
                "tr" -> {
                    val c = cells(child as StudioElement)
                    if (c.isNotEmpty() && c[0].tag == "th") headerRows += child else bodyRows += child
                }
            }
        }
        if (headerRows.isEmpty() && bodyRows.isNotEmpty()) {
            val headerCells = cells(bodyRows[0]).mapIndexed { i, _ -> StudioElement("th", emptyMap(), listOf(StudioText("Column ${i + 1}"))) }
            headerRows = mutableListOf(StudioElement("tr", emptyMap(), headerCells))
        }
        if (headerRows.isEmpty()) return ""
        val headerCells = cells(headerRows[0])
        val headerContent = headerCells.map { cellContent(it, state) }
        val alignments = headerCells.map { alignment(it.attrs) }
        val columnWidths = headerContent.map { maxOf(3, it.length) }.toMutableList()
        for (row in bodyRows) {
            val c = cells(row)
            for (i in c.indices) {
                if (i < columnWidths.size) columnWidths[i] = maxOf(columnWidths[i], cellContent(c[i], state).length)
            }
        }
        val sb = StringBuilder("| ")
        sb.append(headerContent.mapIndexed { i, content -> content.padEnd(columnWidths[i]) }.joinToString(" | "))
        sb.append(" |\n| ")
        sb.append(
            columnWidths.mapIndexed { i, width ->
                when (alignments[i]) {
                    "left" -> ":" + "-".repeat(maxOf(0, width - 1))
                    "center" -> ":" + "-".repeat(maxOf(0, width - 2)) + ":"
                    "right" -> "-".repeat(maxOf(0, width - 1)) + ":"
                    else -> "-".repeat(width)
                }
            }.joinToString(" | "),
        )
        sb.append(" |\n")
        for (row in bodyRows) {
            val c = cells(row)
            val cellContents = c.mapIndexed { i, cell -> cellContent(cell, state).padEnd(columnWidths.getOrElse(i) { 0 }) }.toMutableList()
            while (cellContents.size < columnWidths.size) cellContents += "".padEnd(columnWidths[cellContents.size])
            sb.append("| ").append(cellContents.joinToString(" | ")).append(" |\n")
        }
        val result = sb.toString()
        val attrs = comarkAttributes(userBlockAttrs("table", node.attrs))
        if (attrs.isNotEmpty()) return "::table$attrs\n${StudioJs.trimEnd(result)}\n::\n\n"
        return result + "\n"
    }
}
