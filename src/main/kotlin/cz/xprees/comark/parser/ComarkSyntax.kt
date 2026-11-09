package cz.xprees.comark.parser

/**
 * Low level Comark syntax helpers shared by the parser, the highlighting annotator, completion and the formatter.
 * Everything here works on plain text, mirroring the reference implementation (comark `components.ts`, `props.ts`).
 */
object ComarkSyntax {
    /** Block component / slot names: `[a-z$][$\w.-]*` (case-insensitive), as in comark's `RE_BLOCK_NAME`. */
    private val BLOCK_NAME = Regex("[a-zA-Z$][\\w$.-]*")

    fun isNameStart(c: Char) = c.isLetter() && c.code < 128 || c == '$'

    fun isNamePart(c: Char) = c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '$' || c == '-'

    fun isBlockNamePart(c: Char) = isNamePart(c) || c == '.'

    /** Length of a block component name starting at [from], or 0 when there is none. */
    fun blockNameLength(text: CharSequence, from: Int, to: Int = text.length): Int {
        if (from >= to || !isNameStart(text[from])) return 0
        var i = from + 1
        while (i < to && isBlockNamePart(text[i])) i++
        return i - from
    }

    /** Length of an inline component name (`:name`), which allows `[\w$-]` only. */
    fun inlineNameLength(text: CharSequence, from: Int, to: Int = text.length): Int {
        if (from >= to || !isNameStart(text[from])) return 0
        var i = from + 1
        while (i < to && isNamePart(text[i])) i++
        return i - from
    }

    fun isValidName(name: CharSequence) = BLOCK_NAME.matches(name)

    /** Number of leading `:` characters at [from]. */
    fun colonRun(text: CharSequence, from: Int, to: Int): Int {
        var i = from
        while (i < to && text[i] == ':') i++
        return i - from
    }

    fun isBlank(text: CharSequence, from: Int, to: Int): Boolean {
        for (i in from until to) if (text[i] != ' ' && text[i] != '\t') return false
        return true
    }

    fun skipSpaces(text: CharSequence, from: Int, to: Int): Int {
        var i = from
        while (i < to && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    /** Index of the `]` closing the `[` at [open] (backslash escapes and nesting honoured), or -1. */
    fun findClosingBracket(text: CharSequence, open: Int, to: Int = text.length): Int {
        if (open >= to || text[open] != '[') return -1
        var i = open + 1
        var depth = 0
        while (i < to) {
            when (text[i]) {
                '\\' -> if (i + 1 < to) {
                    i += 2
                    continue
                }

                '[' -> depth++
                ']' -> if (depth == 0) return i else depth--
            }
            i++
        }
        return -1
    }

    // ---- Attributes (`{.class #id key="value" bool}`) -------------------------------------------------------------

    enum class AttrPart { LBRACE, RBRACE, CLASS, ID, NAME, EQ, VALUE, TEXT }

    class AttrToken(val part: AttrPart, val start: Int, val end: Int)

    /** Result of scanning a `{...}` group: its tokens and the index just past the group. */
    class AttrScan(val tokens: List<AttrToken>, val end: Int, val closed: Boolean)

    /**
     * Scans an attribute group starting at the `{` at [start], mirroring comark's `searchProps`. Returns null when there is no
     * group there (not a `{`, or a Vue mustache `{{`). The group may be unterminated before [limit]; then [AttrScan.closed] is
     * false. Scanning never crosses a line break.
     */
    fun scanAttributes(text: CharSequence, start: Int, limit: Int = text.length): AttrScan? {
        if (start >= limit || text[start] != '{') return null
        if (start + 1 < limit && text[start + 1] == '{') return null
        var end = start
        while (end < limit && text[end] != '\n') end++
        return AttrScanner(text, start, end).scan()
    }

    private class AttrScanner(private val text: CharSequence, private val start: Int, private val limit: Int) {
        private var i = start
        private val tokens = ArrayList<AttrToken>()

        fun scan(): AttrScan {
            tokens += AttrToken(AttrPart.LBRACE, i, i + 1)
            i++
            var closed = false
            while (i < limit) {
                val c = text[i]
                when {
                    c == '\\' -> {
                        val e = minOf(i + 2, limit)
                        tokens += AttrToken(AttrPart.TEXT, i, e)
                        i = e
                    }

                    c == '}' -> {
                        tokens += AttrToken(AttrPart.RBRACE, i, i + 1)
                        i++
                        closed = true
                        break
                    }

                    c == ' ' || c == '\t' -> i++
                    c == '.' -> {
                        val s = i
                        searchUntil(" \t#.}", bracketAware = true)
                        tokens += AttrToken(AttrPart.CLASS, s, i)
                    }

                    c == '#' -> {
                        val s = i
                        searchUntil(" \t#.}", bracketAware = false)
                        tokens += AttrToken(AttrPart.ID, s, i)
                    }

                    else -> property()
                }
            }
            return AttrScan(tokens, i, closed)
        }

        private fun property() {
            val s = i
            while (i < limit && text[i] !in " \t}=") i++
            if (i == s) {
                i++
                return
            }
            tokens += AttrToken(AttrPart.NAME, s, i)
            if (i >= limit || text[i] != '=') return
            tokens += AttrToken(AttrPart.EQ, i, i + 1)
            i++
            val vs = i
            if (i >= limit) return
            val v = text[i]
            when (v) {
                in BRACKET_PAIRS -> {
                    searchBracket(BRACKET_PAIRS.getValue(v))
                    i = minOf(i + 1, limit)
                }

                in QUOTES -> {
                    searchUntil(v.toString(), bracketAware = false)
                    i = minOf(i + 1, limit)
                }

                else -> {
                    while (i < limit && text[i] !in " \t}") {
                        if (text[i] == '\\') i++
                        i++
                    }
                    if (i > limit) i = limit
                }
            }
            if (i > vs) tokens += AttrToken(AttrPart.VALUE, vs, i)
        }

        /** Advances past the current character, then up to (not past) the first character in [stops]. */
        private fun searchUntil(stops: String, bracketAware: Boolean) {
            while (i < limit) {
                i++
                if (i < limit && text[i] == '\\') {
                    i++
                    continue
                }
                if (i >= limit) break
                if (bracketAware && text[i] in BRACKET_PAIRS) {
                    val open = i
                    if (searchBracket(BRACKET_PAIRS.getValue(text[open]))) continue
                    i = open
                }
                if (text[i] in stops) break
            }
            if (i > limit) i = limit
        }

        /** With [i] on an opening bracket, moves to its matching [end]; returns false (at [limit]) when unclosed. */
        private fun searchBracket(end: Char): Boolean {
            while (i < limit) {
                i++
                if (i >= limit) return false
                val c = text[i]
                if (c in QUOTES) {
                    searchUntil(c.toString(), bracketAware = false)
                } else if (c in BRACKET_PAIRS) {
                    if (!searchBracket(BRACKET_PAIRS.getValue(c))) return false
                } else if (c == end) {
                    return true
                }
            }
            return false
        }
    }

    private val BRACKET_PAIRS = mapOf('[' to ']', '{' to '}', '(' to ')')
    private val QUOTES = setOf('\'', '"', '`')

    // ---- Opener line --------------------------------------------------------------------------------------------

    /** Parts of a block component opener line (`::name[content]{attrs}`), all offsets absolute. */
    class Opener(
        val markerStart: Int,
        val markerEnd: Int,
        val nameStart: Int,
        val nameEnd: Int,
        val contentOpen: Int,
        val contentClose: Int,
        val attributes: AttrScan?,
        val restStart: Int,
        val restEnd: Int,
    ) {
        val markerCount get() = markerEnd - markerStart
    }

    /**
     * Parses a component opener: two or more colons, optional spaces, a valid name, then optionally `[content]` and `{attrs}`.
     * [from] is the first non-blank character of the line, [to] its end (without the line break).
     */
    fun parseOpener(text: CharSequence, from: Int, to: Int): Opener? {
        val colons = colonRun(text, from, to)
        if (colons < 2) return null
        val markerEnd = from + colons
        val nameStart = skipSpaces(text, markerEnd, to)
        val nameLength = blockNameLength(text, nameStart, to)
        if (nameLength == 0) return null
        val nameEnd = nameStart + nameLength
        var i = skipSpaces(text, nameEnd, to)
        var contentOpen = -1
        var contentClose = -1
        if (i < to && text[i] == '[') {
            val close = findClosingBracket(text, i, to)
            if (close >= 0) {
                contentOpen = i
                contentClose = close
                i = skipSpaces(text, close + 1, to)
            }
        }
        var attrs: AttrScan? = null
        if (i < to && text[i] == '{') {
            attrs = scanAttributes(text, i, to)
            if (attrs != null) i = skipSpaces(text, attrs.end, to)
        }
        var restEnd = to
        while (restEnd > i && (text[restEnd - 1] == ' ' || text[restEnd - 1] == '\t')) restEnd--
        return Opener(from, markerEnd, nameStart, nameEnd, contentOpen, contentClose, attrs, i, restEnd)
    }

    /** True when the line content `[from, to)` is a closing marker: exactly [markers] colons and nothing else. */
    fun isCloser(text: CharSequence, from: Int, to: Int, markers: Int): Boolean =
        colonRun(text, from, to) == markers && isBlank(text, from + markers, to)

    /** True when the line content is any closing marker (`::`, `:::`, ...). */
    fun closerMarkers(text: CharSequence, from: Int, to: Int): Int {
        val n = colonRun(text, from, to)
        return if (n >= 2 && isBlank(text, from + n, to)) n else 0
    }

    /** Parts of a slot line (`#name{attrs}`). */
    class SlotLine(val hashStart: Int, val nameStart: Int, val nameEnd: Int, val attributes: AttrScan?, val end: Int)

    fun parseSlot(text: CharSequence, from: Int, to: Int): SlotLine? {
        if (from + 1 >= to || text[from] != '#') return null
        val next = text[from + 1]
        if (next == ' ' || next == '#') return null
        val nameStart = from + 1
        val nameLength = blockNameLength(text, nameStart, to)
        if (nameLength == 0) return null
        val nameEnd = nameStart + nameLength
        var i = skipSpaces(text, nameEnd, to)
        var attrs: AttrScan? = null
        if (i < to && text[i] == '{') {
            attrs = scanAttributes(text, i, to) ?: return null
            i = skipSpaces(text, attrs.end, to)
        }
        // Anything else after the name means this is not a slot (comark: `remaining` must be empty)
        if (i < to) return null
        return SlotLine(from, nameStart, nameEnd, attrs, to)
    }

    /** Code fence opener at [from]: returns fence char and length, or null. */
    fun fenceOpen(text: CharSequence, from: Int, to: Int): Pair<Char, Int>? {
        if (from >= to) return null
        val c = text[from]
        if (c != '`' && c != '~') return null
        var i = from
        while (i < to && text[i] == c) i++
        val n = i - from
        if (n < 3) return null
        // a backtick fence's info string can't contain backticks
        if (c == '`') for (j in i until to) if (text[j] == '`') return null
        return c to n
    }

    fun isFenceClose(text: CharSequence, from: Int, to: Int, fenceChar: Char, fenceLength: Int): Boolean {
        var i = from
        while (i < to && text[i] == fenceChar) i++
        return i - from >= fenceLength && isBlank(text, i, to)
    }
}
