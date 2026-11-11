package cz.xprees.comark.format.studio

/**
 * comark 0.7.0's Comark syntax helpers (`internal/parse/syntax/{block-params,brackets,props}.js` and `utils` `kebabCase`),
 * ported as written, including their quirks (a value scan may run past the closing brace, a `\` skips two characters).
 */
internal object StudioComarkSyntax {
    class BlockParams(val name: String, val content: String? = null, val props: List<Pair<String, String>>? = null, val remaining: String? = null)

    private val RE_BLOCK_NAME = Regex("^[a-zA-Z$][$\\w.-]*")

    fun parseBlockParams(input: String): BlockParams {
        val str = StudioJs.trim(input)
        if (str.isEmpty()) return BlockParams("")
        val name = RE_BLOCK_NAME.find(str)?.value ?: throw StudioParseException("Invalid block params: $str")
        var remaining = StudioJs.trim(str.substring(name.length))
        var content: String? = null
        var props: List<Pair<String, String>>? = null
        var unparsed: String? = null
        if (remaining.startsWith("[")) {
            val r = parseBracketContent(remaining, 0)
            if (r != null) {
                content = r.first
                remaining = StudioJs.trim(remaining.substring(r.second))
            }
        }
        if (remaining.startsWith("{")) {
            val p = searchProps(remaining, 0)
            if (p != null) {
                props = p.first
                val after = StudioJs.trim(remaining.substring(minOf(p.second, remaining.length)))
                if (after.isNotEmpty()) unparsed = after
            }
        } else if (remaining.isNotEmpty()) {
            unparsed = remaining
        }
        return BlockParams(kebabCase(name), content, props, unparsed)
    }

    fun findClosingBracket(str: String, openIndex: Int): Int {
        if (openIndex >= str.length || str[openIndex] != '[') return -1
        var index = openIndex + 1
        var depth = 0
        while (index < str.length) {
            if (str[index] == '\\' && index + 1 < str.length) {
                index += 2
                continue
            }
            if (str[index] == '[') {
                depth++
            } else if (str[index] == ']') {
                if (depth == 0) return index
                depth--
            }
            index++
        }
        return -1
    }

    /** `{content, endIndex}` as a pair, or null */
    fun parseBracketContent(str: String, startIndex: Int): Pair<String, Int>? {
        val close = findClosingBracket(str, startIndex)
        if (close == -1) return null
        return str.substring(startIndex + 1, close) to close + 1
    }

    private val BRACKETS = mapOf('[' to ']', '{' to '}', '(' to ')')
    private val QUOTES = mapOf('\'' to '\'', '"' to '"', '`' to '`')
    private val KEY_RE = Regex("^:?[a-zA-Z_][a-zA-Z0-9_-]*\\z")
    private val QUOTED_RE = Regex("^(['\"`]).*\\1\\z")

    /**
     * comark `searchProps(content, index)`: the props and the index after them, or null for `{{`. The scan may end past
     * the end of [content] (JavaScript indexes past the end read `undefined`); callers clamp.
     */
    fun searchProps(content: String, start: Int): Pair<List<Pair<String, String>>, Int>? {
        fun at(i: Int): Char? = if (i >= 0 && i < content.length) content[i] else null
        if (at(start) != '{') throw StudioParseException("Invalid props, expected `{` but got '${at(start)}'")
        val props = ArrayList<Pair<String, String>>()
        if (at(start + 1) == '{') return null
        var index = start + 1

        fun jsSlice(a: Int, b: Int): String {
            val s = a.coerceIn(0, content.length)
            val e = b.coerceIn(0, content.length)
            return if (s >= e) "" else content.substring(s, e)
        }

        fun searchUntil(stops: String): String {
            val from = index
            while (index < content.length) {
                index += 1
                if (at(index) == '\\') index += 2
                val c = at(index)
                if (c == null) {
                    // `str.includes(undefined)` is false
                } else if (stops.indexOf(c) >= 0) {
                    break
                }
            }
            return jsSlice(from, index)
        }

        fun searchString(end: Char) {
            searchUntil(end.toString())
        }

        fun searchBracket(end: Char) {
            while (index < content.length) {
                index++
                val c = at(index)
                if (c != null && c in QUOTES) {
                    searchString(QUOTES.getValue(c))
                } else if (c != null && c in BRACKETS) {
                    searchBracket(BRACKETS.getValue(c))
                } else if (c == end) {
                    return
                }
            }
        }

        fun searchValue(): String {
            val from = index
            val c = at(index)
            return if (c != null && c in BRACKETS) {
                searchBracket(BRACKETS.getValue(c))
                index += 1
                jsSlice(from, index)
            } else if (c != null && c in QUOTES) {
                searchString(QUOTES.getValue(c))
                index += 1
                jsSlice(from, index)
            } else {
                searchUntil(" }")
            }
        }

        while (index < content.length) {
            val c = content[index]
            if (c == '\\') {
                index += 2
            } else if (c == '}') {
                index += 1
                break
            } else if (c == ' ') {
                index += 1
            } else if (c == '.') {
                index += 1
                props += "class" to searchUntil(" #.}")
            } else if (c == '#') {
                index += 1
                props += "id" to searchUntil(" #.}")
            } else {
                val from = index
                while (index < content.length) {
                    index += 1
                    val n = at(index)
                    if (n == null || " }=".indexOf(n) >= 0) {
                        // `' }='.includes(undefined)` is false, but the loop then ends anyway
                        if (n != null) break
                    }
                }
                val ch = at(index)
                if (from != index) {
                    var key = StudioJs.trim(jsSlice(from, index))
                    var value: String
                    if (ch == '=') {
                        index += 1
                        value = searchValue()
                    } else {
                        key = if (key.startsWith(":")) key else ":$key"
                        value = "true"
                    }
                    if (KEY_RE.matches(key)) props += key to value
                }
            }
        }
        val unquoted = props.map { (k, v) ->
            if (QUOTED_RE.matches(v) && v.length >= 2) k to v.substring(1, v.length - 1) else k to v
        }
        return unquoted to index
    }

    /** comark `kebabCase` (`splitByCase`, then lower-case and join with `-`) */
    fun kebabCase(str: String): String {
        if (str.isEmpty()) return ""
        val parts = ArrayList<String>()
        var buff = StringBuilder()
        var previousUpper: Boolean? = null
        var previousSplitter: Boolean? = null
        for (char in str) {
            val isSplitter = char == '-' || char == '_' || char == '/' || char == '.'
            if (isSplitter) {
                parts += buff.toString()
                buff = StringBuilder()
                previousUpper = null
                continue
            }
            val code = char.code
            val isNumber = code in 48..57
            val isUpper: Boolean? = if (isNumber) null else code in 65..90
            if (previousSplitter == false) {
                if (previousUpper == false && isUpper == true) {
                    parts += buff.toString()
                    buff = StringBuilder().append(char)
                    previousUpper = isUpper
                    continue
                }
                if (previousUpper == true && isUpper == false && buff.length > 1) {
                    val lastChar = buff[buff.length - 1]
                    parts += buff.substring(0, buff.length - 1)
                    buff = StringBuilder().append(lastChar).append(char)
                    previousUpper = isUpper
                    continue
                }
            }
            buff.append(char)
            previousUpper = isUpper
            previousSplitter = isSplitter
        }
        parts += buff.toString()
        return parts.joinToString("-") { it.lowercase(java.util.Locale.ROOT) }
    }
}
