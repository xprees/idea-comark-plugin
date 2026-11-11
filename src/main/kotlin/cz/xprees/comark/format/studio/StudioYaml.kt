package cz.xprees.comark.format.studio

import cz.xprees.comark.format.JsText
import java.math.BigInteger
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/**
 * A port of js-yaml 5.5.0 `dump(value, { indent: 2, lineWidth: -1 })` with every other option at its default
 * (`DUMP_SCHEMA`, `quoteStyle: 'single'`, `seqInlineFirst`, no `flowLevel`, no key sorting, `noRefs: false`), and of
 * comark 0.7.0's `stringifyYaml` (`internal/yaml.js`), which runs that dump and then unquotes keys of the form `':key':`.
 *
 * Values are the JavaScript values of [StudioNode]. Repeated references to one object (js-yaml's `&ref_0` anchors) cannot
 * occur in this model, so they are not produced.
 */
object StudioYaml {
    /** comark's `stringifyYaml(data)`. */
    fun stringify(data: Any?): String {
        val lines = dump(data).split("\n").toMutableList()
        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = StudioJs.trimStart(line)
            if (trimmed.isNotEmpty() && (trimmed[0] == '\'' || trimmed[0] == '"')) {
                val quote = trimmed[0]
                if (trimmed.length > 1 && trimmed[1] == ':') {
                    val quoteEnd = trimmed.indexOf(quote, 1)
                    if (quoteEnd > 1 && quoteEnd + 1 < trimmed.length && trimmed[quoteEnd + 1] == ':') {
                        val indent = line.length - trimmed.length
                        lines[i] = " ".repeat(indent) + trimmed.substring(1, quoteEnd) + trimmed.substring(quoteEnd + 1)
                    }
                }
            }
        }
        return lines.joinToString("\n")
    }

    /** js-yaml `dump(data, { indent: 2, lineWidth: -1 })`. */
    fun dump(data: Any?): String {
        val root = build(data) ?: return ""
        val state = Presenter()
        var result = state.writeNode(0, root, null, block = true, compact = true, isKey = false) + "\n"
        if (state.openEnded) result += "...\n"
        return result
    }

    // ---- representation (jsToAst) -------------------------------------------------------------------------------------

    private const val TAG_STR = "tag:yaml.org,2002:str"
    private const val TAG_NULL = "tag:yaml.org,2002:null"
    private const val TAG_BOOL = "tag:yaml.org,2002:bool"
    private const val TAG_INT = "tag:yaml.org,2002:int"
    private const val TAG_FLOAT = "tag:yaml.org,2002:float"
    private const val TAG_TIMESTAMP = "tag:yaml.org,2002:timestamp"
    private const val TAG_MERGE = "tag:yaml.org,2002:merge"

    private sealed class Node
    private class Scalar(val tag: String, val value: String) : Node()
    private class Sequence(val items: List<Node>) : Node()
    private class Mapping(val items: List<Pair<Node, Node>>) : Node()

    /** `build`; `null` is js-yaml's `INVALID` (an `undefined`, which is skipped). */
    private fun build(v: Any?): Node? = when (v) {
        null -> Scalar(TAG_NULL, "null")
        JsUndefined -> null
        is Boolean -> Scalar(TAG_BOOL, if (v) "true" else "false")
        is Number -> number(v.toDouble())
        is String -> Scalar(TAG_STR, v)
        is JsDate -> Scalar(TAG_TIMESTAMP, v.iso ?: throw IllegalArgumentException("Invalid time value"))
        is List<*> -> Sequence(v.map { build(it) ?: Scalar(TAG_NULL, "null") })
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val m = v as Map<String, Any?>
            Mapping(StudioJs.entries(m).mapNotNull { (k, x) -> build(x)?.let { Scalar(TAG_STR, k) to it } })
        }
        else -> throw IllegalArgumentException("unacceptable kind of an object to dump ${v::class.java.name}")
    }

    private fun number(d: Double): Scalar {
        val s = StudioJs.numberToString(d)
        val isInt = !d.isNaN() && !d.isInfinite() && d == Math.floor(d) && !(d == 0.0 && 1.0 / d < 0) && 'e' !in s
        if (isInt) return Scalar(TAG_INT, s)
        val repr = when {
            d.isNaN() -> ".nan"
            d == Double.POSITIVE_INFINITY -> ".inf"
            d == Double.NEGATIVE_INFINITY -> "-.inf"
            d == 0.0 -> "-0.0"
            Regex("^[-+]?[0-9]+e").containsMatchIn(s) -> s.replaceFirst("e", ".e")
            else -> s
        }
        return Scalar(TAG_FLOAT, repr)
    }

    // ---- implicit tag resolution (DUMP_SCHEMA.resolveImplicitScalarTag) ------------------------------------------------

    private val NULL_VALUES = setOf("", "~", "null", "Null", "NULL")
    private val BOOL_VALUES = setOf(
        "true", "True", "TRUE", "y", "Y", "yes", "Yes", "YES", "on", "On", "ON",
        "false", "False", "FALSE", "n", "N", "no", "No", "NO", "off", "Off", "OFF",
    )
    private val INT_YAML11 = Regex(
        "(?:[-+]?0b[0-1_]+|[-+]?0[0-7_]+|[-+]?0x[0-9a-fA-F_]+|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+|[-+]?(?:0|[1-9][0-9_]*))",
    )
    private val INT_CORE = Regex("(?:0o[0-7]+|0x[0-9a-fA-F]+|[-+]?[0-9]+)")
    private val FLOAT_YAML11 = Regex(
        "(?:[-+]?(?:(?:[0-9][0-9_]*)?\\.[0-9_]*)(?:[eE][-+][0-9]+)?|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\\.[0-9_]*" +
            "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))",
    )
    private val FLOAT_CORE = Regex(
        "(?:[-+]?[0-9]+(?:\\.[0-9]*)?(?:[eE][-+]?[0-9]+)?|[-+]?\\.[0-9]+(?:[eE][-+]?[0-9]+)?|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))",
    )
    private val FLOAT_SPECIAL = Regex("(?:[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))")
    private val DATE = Regex("([0-9][0-9][0-9][0-9])-([0-9][0-9])-([0-9][0-9])")
    private val TIMESTAMP = Regex(
        "([0-9][0-9][0-9][0-9])-([0-9][0-9]?)-([0-9][0-9]?)(?:[Tt]|[ \\t]+)([0-9][0-9]?):([0-9][0-9]):([0-9][0-9])" +
            "(?:\\.([0-9]*))?(?:[ \\t]*(Z|([-+])([0-9][0-9]?)(?::([0-9][0-9]))?))?",
    )

    /** The tag a plain scalar [s] would resolve to. */
    private fun resolveTag(s: String): String {
        val c = if (s.isEmpty()) "" else s.substring(0, 1)
        val nullFirst = c == "" || c == "~" || c == "n" || c == "N"
        val boolFirst = c.length == 1 && c[0] in "yYnNtTfFoO"
        val digit = c.length == 1 && c[0] in '0'..'9'
        val intFirst = digit || c == "-" || c == "+"
        val floatFirst = intFirst || c == "."
        if (nullFirst && s in NULL_VALUES) return TAG_NULL
        if (boolFirst && s in BOOL_VALUES) return TAG_BOOL
        if (intFirst && resolvesInt(s)) return TAG_INT
        if (floatFirst && resolvesFloat(s)) return TAG_FLOAT
        if (digit && resolvesTimestamp(s)) return TAG_TIMESTAMP
        if (c == "<" && s == "<<") return TAG_MERGE
        return TAG_STR
    }

    private fun parseIntFinite(digits: String, radix: Int): Boolean =
        digits.isNotEmpty() && BigInteger(digits, radix).toDouble().isFinite()

    private fun resolvesInt(s: String): Boolean {
        if (INT_YAML11.matches(s)) {
            var v = s.replace("_", "")
            if (v.startsWith("-") || v.startsWith("+")) v = v.substring(1)
            val finite = when {
                v.startsWith("0b") -> parseIntFinite(v.substring(2), 2)
                v.startsWith("0x") -> parseIntFinite(v.substring(2), 16)
                ':' in v -> v.split(":").fold(0.0) { acc, part -> acc * 60 + part.toDouble() }.isFinite()
                v != "0" && v.startsWith("0") -> parseIntFinite(v, 8)
                else -> parseIntFinite(v, 10)
            }
            if (finite) return true
        }
        if (INT_CORE.matches(s)) {
            var v = s
            if (v.startsWith("-") || v.startsWith("+")) v = v.substring(1)
            return when {
                v.startsWith("0b") -> parseIntFinite(v.substring(2), 2)
                v.startsWith("0o") -> parseIntFinite(v.substring(2), 8)
                v.startsWith("0x") -> parseIntFinite(v.substring(2), 16)
                else -> parseIntFinite(v, 10)
            }
        }
        return false
    }

    /** `parseFloat` of a string already matched by one of the float patterns (sign stripped, lower case). */
    private fun parseFloatMatched(v: String): Double {
        val mantissa = v.substringBefore('e')
        if (mantissa.none { it in '0'..'9' }) return Double.NaN
        return v.toDouble()
    }

    private fun resolvesFloat(s: String): Boolean {
        if (FLOAT_YAML11.matches(s)) {
            var v = s.lowercase(Locale.ROOT).replace("_", "")
            if (v.isNotEmpty() && v[0] in "+-") v = v.substring(1)
            if (v == ".inf" || v == ".nan") return true
            val result = if (':' in v) {
                v.split(":").fold(0.0) { acc, part -> acc * 60 + (if (part.isEmpty()) 0.0 else part.toDouble()) }
            } else {
                parseFloatMatched(v)
            }
            if (result.isFinite() || FLOAT_SPECIAL.matches(s)) return true
        }
        if (FLOAT_CORE.matches(s)) {
            var v = s.lowercase(Locale.ROOT)
            if (v[0] in "+-") v = v.substring(1)
            if (v == ".inf" || v == ".nan") return true
            return parseFloatMatched(v).isFinite() || FLOAT_SPECIAL.matches(s)
        }
        return false
    }

    private fun resolvesTimestamp(s: String): Boolean {
        val m = DATE.matchEntire(s) ?: TIMESTAMP.matchEntire(s) ?: return false
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        if (!validDate(year, month, day)) return false
        if (g.size <= 4 || g[4].isEmpty()) return true
        if (g[4].toInt() > 23 || g[5].toInt() > 59 || g[6].toInt() > 59) return false
        if (g[9].isNotEmpty()) {
            if (g[10].toInt() > 23 || (g[11].ifEmpty { "0" }).toInt() > 59) return false
        }
        return true
    }

    private fun validDate(year: Int, month: Int, day: Int): Boolean = try {
        LocalDate.of(year, month, day); true
    } catch (_: DateTimeException) {
        false
    }

    // ---- scalar styling (styler_defaults + scalar_styler) ---------------------------------------------------------------

    private enum class Style { PLAIN, SINGLE_QUOTED, DOUBLE_QUOTED, LITERAL_BLOCK, FOLDED_BLOCK }

    private const val INDENT = 2

    /** c-printable, as a code point. */
    private fun isPrintable(cp: Int): Boolean = cp == 0x09 || cp == 0x0A || cp == 0x0D || cp in 0x20..0x7E || cp == 0x85 ||
        cp in 0xA0..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF

    /** nb-char: printable, not a line break, not the byte order mark. */
    private fun isNbChar(cp: Int): Boolean = isPrintable(cp) && cp != 0x0A && cp != 0x0D && cp != 0xFEFF

    /** ns-char: nb-char that is not a space or tab. */
    private fun isNsChar(cp: Int): Boolean = isNbChar(cp) && cp != 0x20 && cp != 0x09

    private fun isIndicator(cp: Int): Boolean = cp < 0x80 && cp.toChar() in "-?:,[]{}#&*!|>'\"%@`"

    private fun isFlowIndicator(cp: Int): Boolean = cp < 0x80 && cp.toChar() in ",[]{}"

    private fun isSafe(cp: Int, flowIn: Boolean): Boolean = isNsChar(cp) && !(flowIn && isFlowIndicator(cp))

    /** Code points of [s], with lone surrogates kept as their own (non-printable) code point. */
    private fun codePoints(s: String): IntArray = s.codePoints().toArray()

    /**
     * Whether [s] matches js-yaml's `NS_PLAIN_*` pattern: ns-plain-multi-line (or, for keys, ns-plain-one-line) in the
     * flow-out ([flowIn] false) or flow-in context.
     */
    private fun matchesPlain(s: String, flowIn: Boolean, oneLine: Boolean): Boolean {
        val cps = codePoints(s)
        val n = cps.size
        if (n == 0) return false
        fun at(i: Int) = if (i < n) cps[i] else -1

        /** plain-char at [i] (not counting the `#*` that may follow); returns the index after it or -1. */
        fun plainChar(i: Int): Int {
            val c = at(i)
            if (c == -1) return -1
            if (c == ':'.code) return if (at(i + 1) != -1 && isSafe(at(i + 1), flowIn)) i + 1 else -1
            if (c == '#'.code) return -1
            return if (isSafe(c, flowIn)) i + 1 else -1
        }

        fun hashes(i: Int): Int {
            var j = i
            while (at(j) == '#'.code) j++
            return j
        }

        /** nb-ns-plain-in-line from [i]; returns the index where it stops (at a line break or the end), or -1. */
        fun inLine(start: Int): Int {
            var i = start
            while (true) {
                if (i >= n || cps[i] == '\n'.code) return i
                var j = i
                while (at(j) == ' '.code || at(j) == '\t'.code) j++
                val k = plainChar(j)
                if (k == -1) return -1
                i = hashes(k)
            }
        }

        // ns-plain-first
        val c0 = cps[0]
        var i = if (isNsChar(c0) && !isIndicator(c0) && !(flowIn && isFlowIndicator(c0))) {
            1
        } else if ((c0 == '?'.code || c0 == ':'.code || c0 == '-'.code) && at(1) != -1 && isSafe(at(1), flowIn)) {
            1
        } else {
            return false
        }
        i = hashes(i)
        i = inLine(i)
        if (i == -1) return false
        while (i < n) {
            if (oneLine) return false
            // s-ns-plain-next-line: \n+ ns-plain-char nb-ns-plain-in-line
            while (at(i) == '\n'.code) i++
            val k = plainChar(i)
            if (k == -1) return false
            i = inLine(hashes(k))
            if (i == -1) return false
        }
        return true
    }

    private val FORBIDDEN_FIRST_LINE = Regex("^(?:---|\\.\\.\\.)(?=\\z|[ \\t\\n\\r])")

    private fun forbiddenContent(s: String): Boolean {
        // JavaScript `/^(?:---|\.\.\.)(?=$|[ \t\n\r])/m`: `^` and `$` at line boundaries (\n, \r,  ,  ).
        var lineStart = 0
        while (true) {
            if (s.startsWith("---", lineStart) || s.startsWith("...", lineStart)) {
                val after = lineStart + 3
                if (after >= s.length || s[after] in " \t\n\r  ") return true
            }
            var j = lineStart
            while (j < s.length && s[j] !in "\n\r  ") j++
            if (j >= s.length) return false
            lineStart = j + 1
        }
    }

    private class Layout(
        val node: Scalar,
        val isKey: Boolean,
        val flowOnly: Boolean,
        val shiftOfParent: Int,
        val shiftOfContent: Int,
        val shiftOfFirstLine: Int,
    ) {
        var allowed = mutableSetOf(Style.DOUBLE_QUOTED)
        var style = Style.PLAIN
    }

    private fun canUsePlain(l: Layout): Boolean {
        val str = l.node.value
        if (str != "") {
            if (!matchesPlain(str, flowIn = l.flowOnly, oneLine = l.isKey)) return false
            if (l.shiftOfFirstLine == 0 && FORBIDDEN_FIRST_LINE.containsMatchIn(str)) return false
            if (l.shiftOfContent == 0) {
                val br = str.indexOf('\n')
                if (br != -1 && forbiddenContent(str.substring(br + 1))) return false
            }
        }
        val resolved = resolveTag(str)
        if (resolved != l.node.tag) return false
        if (str == "=" && resolved == TAG_STR) return false
        return true
    }

    private fun canUseSingleQuoted(l: Layout): Boolean {
        val str = l.node.value
        for (cp in codePoints(str)) {
            val ok = cp == 0x09 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFF || cp in 0x10000..0x10FFFF || (!l.isKey && cp == 0x0A)
            if (!ok) return false
        }
        for (i in 0 until str.length - 1) {
            val a = str[i]
            val b = str[i + 1]
            if ((a == ' ' || a == '\t') && b == '\n') return false
            if (a == '\n' && (b == ' ' || b == '\t')) return false
        }
        if (!l.isKey && l.shiftOfContent == 0) {
            val br = str.indexOf('\n')
            if (br != -1 && forbiddenContent(str.substring(br + 1))) return false
        }
        return true
    }

    private fun canUseBlock(l: Layout): Boolean {
        if (l.flowOnly) return false
        if (!codePoints(l.node.value).all { it == 0x0A || isNbChar(it) }) return false
        val contentIndent = l.shiftOfContent - l.shiftOfParent
        if (contentIndent < 1) return false
        if (contentIndent > 9 && Regex("^\\n* ").containsMatchIn(l.node.value)) return false
        if (l.shiftOfContent == 0 && forbiddenContent(l.node.value)) return false
        return true
    }

    private fun isInvisible(c: Char): Boolean =
        c == '\t' || c in '\u007F'..' ' || c == ' ' || c == ' ' || c == '﻿' || c == '￾' || c == '￿'

    private fun chooseStyle(l: Layout) {
        if (canUsePlain(l)) l.allowed += Style.PLAIN
        if (canUseSingleQuoted(l)) l.allowed += Style.SINGLE_QUOTED
        if (canUseBlock(l)) {
            l.allowed += Style.LITERAL_BLOCK
            l.allowed += Style.FOLDED_BLOCK
        }
        val value = l.node.value
        // doubleQuoteForInvisibles
        if (l.style == Style.PLAIN && value.any { isInvisible(it) }) l.style = Style.DOUBLE_QUOTED
        // doubleQuoteWhitespaceOnly
        if (l.style == Style.PLAIN && value.isNotEmpty() && value.all { JsText.isWhitespace(it) }) l.style = Style.DOUBLE_QUOTED
        // tryLongOrMultilineAsBlock (lineWidth -1)
        if (l.style == Style.PLAIN && !l.isKey) {
            val multiline = '\n' in value
            if (Style.LITERAL_BLOCK !in l.allowed) {
                if (multiline) l.style = Style.DOUBLE_QUOTED
            } else if (multiline) {
                l.style = Style.LITERAL_BLOCK
            }
        }
        // quoteInvalidPlain
        if (l.style == Style.PLAIN && Style.PLAIN !in l.allowed) {
            l.style = if (Style.SINGLE_QUOTED in l.allowed) Style.SINGLE_QUOTED else Style.DOUBLE_QUOTED
        }
        // fallbackToDoubleQuoted
        if (l.style !in l.allowed) l.style = Style.DOUBLE_QUOTED
    }

    private fun renderScalar(l: Layout): String {
        val value = l.node.value
        return when (l.style) {
            Style.PLAIN -> encodeFlowBreaks(value, l.shiftOfContent)
            Style.SINGLE_QUOTED -> "'" + encodeFlowBreaks(value, l.shiftOfContent).replace("'", "''") + "'"
            Style.LITERAL_BLOCK -> "|" + blockHeader(value, l.shiftOfParent, l.shiftOfContent) +
                dropEndingNewline(indentString(value, l.shiftOfContent))
            Style.FOLDED_BLOCK -> throw IllegalStateException("folded scalars need a line width")
            Style.DOUBLE_QUOTED -> "\"" + escapeString(value) + "\""
        }
    }

    private fun encodeFlowBreaks(string: String, shiftOfContent: Int): String {
        val first = string.indexOf('\n')
        if (first == -1) return string
        val pad = " ".repeat(shiftOfContent)
        val sb = StringBuilder(string.substring(0, first))
        var i = first
        while (i < string.length) {
            var breaks = 0
            while (i < string.length && string[i] == '\n') { breaks++; i++ }
            val end = string.indexOf('\n', i).let { if (it == -1) string.length else it }
            sb.append("\n".repeat(breaks + 1)).append(pad).append(string, i, end)
            i = end
        }
        return sb.toString()
    }

    private fun indentString(string: String, spaces: Int): String {
        val indent = " ".repeat(spaces)
        val sb = StringBuilder()
        var position = 0
        while (position < string.length) {
            val next = string.indexOf('\n', position)
            val line = if (next == -1) string.substring(position) else string.substring(position, next + 1)
            position = if (next == -1) string.length else next + 1
            if (line.isNotEmpty() && line != "\n") sb.append(indent)
            sb.append(line)
        }
        return sb.toString()
    }

    private fun blockHeader(string: String, shiftOfParent: Int, shiftOfContent: Int): String {
        var i = 0
        while (i < string.length && string[i] == '\n') i++
        val indentIndicator = if (i < string.length && string[i] == ' ') (shiftOfContent - shiftOfParent).toString() else ""
        val clip = string.endsWith("\n")
        val chomp = when {
            clip && (string == "\n" || (string.length >= 2 && string[string.length - 2] == '\n')) -> "+"
            clip -> ""
            else -> "-"
        }
        return "$indentIndicator$chomp\n"
    }

    private fun dropEndingNewline(s: String) = if (s.endsWith("\n")) s.substring(0, s.length - 1) else s

    private fun escapeString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                sb.append(c).append(s[i + 1]); i += 2; continue
            }
            val escape = c == '"' || c == '\\' || c <= '\u001F' || c in '\u007F'..' ' || c == ' ' || c == ' ' ||
                Character.isSurrogate(c) || c == '﻿' || c == '￾' || c == '￿'
            if (!escape) {
                sb.append(c); i++; continue
            }
            sb.append(
                when (c) {
                    '\u0000' -> "\\0"
                    '\u0007' -> "\\a"
                    '\b' -> "\\b"
                    '\t' -> "\\t"
                    '\n' -> "\\n"
                    '\u000B' -> "\\v"
                    '\u000C' -> "\\f"
                    '\r' -> "\\r"
                    '\u001B' -> "\\e"
                    '"' -> "\\\""
                    '\\' -> "\\\\"
                    '\u0085' -> "\\N"
                    ' ' -> "\\_"
                    ' ' -> "\\L"
                    ' ' -> "\\P"
                    else -> {
                        val hex = Integer.toHexString(c.code).uppercase(Locale.ROOT)
                        if (c.code <= 255) "\\x" + hex.padStart(2, '0') else "\\u" + hex.padStart(4, '0')
                    }
                },
            )
            i++
        }
        return sb.toString()
    }

    private fun tagShort(tag: String) = if (tag.startsWith("tag:yaml.org,2002:")) "!!" + tag.substring(18) else "!<$tag>"

    // ---- presenter ----------------------------------------------------------------------------------------------------

    private class Presenter {
        var openEnded = false

        fun nextLine(level: Int) = "\n" + " ".repeat(INDENT * level)

        fun writeNode(level: Int, node: Node, parent: Node?, block: Boolean, compact: Boolean, isKey: Boolean): String {
            return when (node) {
                is Mapping -> if (block && node.items.isNotEmpty()) {
                    writeBlockMapping(level, node, compact)
                } else {
                    openEnded = false
                    writeFlowMapping(level, node)
                }
                is Sequence -> if (block && node.items.isNotEmpty()) {
                    writeBlockSequence(level, node, compact)
                } else {
                    openEnded = false
                    writeFlowSequence(level, node)
                }
                is Scalar -> {
                    val layout = Layout(
                        node = node,
                        isKey = isKey,
                        flowOnly = !block,
                        shiftOfParent = if (level == 0) -1 else INDENT * (level - 1),
                        shiftOfContent = INDENT * maxOf(1, level),
                        shiftOfFirstLine = if (level == 0) 0 else INDENT * level,
                    )
                    chooseStyle(layout)
                    val body = renderScalar(layout)
                    openEnded = (layout.style == Style.LITERAL_BLOCK || layout.style == Style.FOLDED_BLOCK) &&
                        (node.value == "\n" || node.value.endsWith("\n\n"))
                    val printTag = (body == "" && layout.flowOnly && parent is Sequence) ||
                        (layout.style != Style.PLAIN && node.tag != TAG_STR)
                    if (printTag) {
                        val sep = if (body == "" || body[0] == '\n') "" else " "
                        tagShort(node.tag) + sep + body
                    } else {
                        body
                    }
                }
            }
        }

        fun writeFlowSequence(level: Int, node: Sequence): String =
            node.items.joinToString(", ", "[", "]") { writeNode(level, it, node, block = false, compact = false, isKey = false) }

        fun writeFlowMapping(level: Int, node: Mapping): String = node.items.joinToString(", ", "{", "}") { (k, v) ->
            val keyText = writeNode(level, k, node, block = false, compact = false, isKey = true)
            val valueText = writeNode(level, v, node, block = false, compact = false, isKey = false)
            "$keyText:${if (valueText == "") "" else " "}$valueText"
        }

        fun writeBlockSequence(level: Int, node: Sequence, compact: Boolean): String {
            val sb = StringBuilder()
            for (item in node.items) {
                val text = writeNode(level + 1, item, node, block = true, compact = true, isKey = false)
                if (!compact || sb.isNotEmpty()) sb.append(nextLine(level))
                sb.append(if (text == "" || text[0] == '\n') "-" else "- ")
                sb.append(text)
            }
            return sb.toString()
        }

        fun writeBlockMapping(level: Int, node: Mapping, compact: Boolean): String {
            val sb = StringBuilder()
            for ((key, value) in node.items) {
                if (!compact || sb.isNotEmpty()) sb.append(nextLine(level))
                val keyText = writeNode(level + 1, key, node, block = true, compact = true, isKey = true)
                val keyHasLineBreak = key is Scalar && '\n' in key.value
                val keyIsTooLong = keyText.length > 1024 && keyText.codePointCount(0, keyText.length) > 1024
                val explicitPair = keyHasLineBreak || keyIsTooLong
                if (explicitPair) sb.append(if (keyText.isNotEmpty() && keyText[0] == '\n') "?" else "? ")
                sb.append(keyText)
                if (explicitPair) sb.append(nextLine(level))
                val valueText = writeNode(level + 1, value, node, block = true, compact = explicitPair, isKey = false)
                sb.append(if (valueText == "" || valueText[0] == '\n') ":" else ": ")
                sb.append(valueText)
            }
            return sb.toString()
        }
    }
}
