package cz.xprees.comark.format.studio

import cz.xprees.comark.format.JsText
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The JavaScript semantics comark's renderer and js-yaml rely on, for the value model of [StudioNode]: `String(v)`,
 * truthiness, `typeof v === 'object'`, own-key order, `Number#toString`, `JSON.stringify`, `JSON.parse` and the
 * `trim` family.
 */
internal object StudioJs {
    /** `typeof v === 'object'` (true for `null`, arrays, plain objects and dates). */
    fun isObject(v: Any?): Boolean = v == null || v is Map<*, *> || v is List<*> || v is JsDate

    /** JavaScript truthiness. */
    fun truthy(v: Any?): Boolean = when (v) {
        null, JsUndefined -> false
        is Boolean -> v
        is Double -> !(v == 0.0 || v.isNaN())
        is Number -> v.toDouble().let { !(it == 0.0 || it.isNaN()) }
        is String -> v.isNotEmpty()
        else -> true
    }

    /** `String(v)`, which is also what a template literal `${v}` produces. */
    fun str(v: Any?): String = when (v) {
        null -> "null"
        JsUndefined -> "undefined"
        is String -> v
        is Boolean -> v.toString()
        is Double -> numberToString(v)
        is Number -> numberToString(v.toDouble())
        is List<*> -> v.joinToString(",") { if (it == null || it == JsUndefined) "" else str(it) }
        is Map<*, *> -> "[object Object]"
        is JsDate -> dateToString(v)
        else -> v.toString()
    }

    private fun dateToString(d: JsDate): String {
        val iso = d.iso ?: return "Invalid Date"
        val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return "Invalid Date"
        return DateTimeFormatter.ofPattern("EEE MMM dd uuuu HH:mm:ss", Locale.ROOT).format(instant.atOffset(ZoneOffset.UTC)) +
            " GMT+0000 (Coordinated Universal Time)"
    }

    /** `Number(v)` for the values comark coerces (`Number(node[1].start)`). */
    fun toNumber(v: Any?): Double = when (v) {
        null -> 0.0
        JsUndefined -> Double.NaN
        is Boolean -> if (v) 1.0 else 0.0
        is Double -> v
        is Number -> v.toDouble()
        is String -> stringToNumber(v)
        is List<*> -> if (v.isEmpty()) 0.0 else if (v.size == 1) toNumber(str(v)) else Double.NaN
        else -> Double.NaN
    }

    private val DECIMAL = Regex("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    private fun stringToNumber(s: String): Double {
        val t = trim(s)
        if (t.isEmpty()) return 0.0
        if (t == "Infinity" || t == "+Infinity") return Double.POSITIVE_INFINITY
        if (t == "-Infinity") return Double.NEGATIVE_INFINITY
        if (t.length > 2 && t[0] == '0') {
            val radix = when (t[1]) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> 0
            }
            if (radix != 0) return t.substring(2).toBigIntegerOrNull(radix)?.toDouble() ?: Double.NaN
        }
        return if (DECIMAL.matches(t)) t.toDouble() else Double.NaN
    }

    /** Whether [key] is an array index, which JavaScript lists before the other own keys of an object, in ascending order. */
    private fun indexKey(key: String): Long? {
        if (key.isEmpty() || key.length > 10) return null
        if (key != "0" && key[0] == '0') return null
        if (!key.all { it in '0'..'9' }) return null
        return key.toLong().takeIf { it < 4294967295L }
    }

    /** `Object.keys(o)`: integer-like keys first (ascending), then the others in insertion order. */
    fun keys(o: Map<String, *>): List<String> {
        if (o.keys.none { indexKey(it) != null }) return o.keys.toList()
        val (indices, others) = o.keys.partition { indexKey(it) != null }
        return indices.sortedBy { indexKey(it) } + others
    }

    /** `Object.entries(o)`. */
    fun entries(o: Map<String, Any?>): List<Pair<String, Any?>> = keys(o).map { it to o[it] }

    /** `Number.prototype.toString()` (radix 10). */
    fun numberToString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == 0.0) return "0"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d < 0) return "-" + numberToString(-d)
        // Double.toString is the shortest representation that round-trips (JDK 19+), as JavaScript's is.
        var bd = BigDecimal(d.toString()).stripTrailingZeros()
        if (bd.precision() == 2) {
            // Java may pick a 2-digit decimal closer to d where a 1-digit one also round-trips (4.9E-324); JavaScript takes
            // the shortest, the closest of them on a tie in length.
            val exact = BigDecimal(d)
            val down = bd.round(java.math.MathContext(1, java.math.RoundingMode.FLOOR))
            val up = bd.round(java.math.MathContext(1, java.math.RoundingMode.CEILING))
            val shortest = listOf(down, up).filter { it.toDouble() == d }
                .minWithOrNull(compareBy<BigDecimal> { it.subtract(exact).abs() }.thenBy { it.unscaledValue().toInt() % 2 })
            if (shortest != null) bd = shortest.stripTrailingZeros()
        }
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        val n = k - bd.scale() // value = 0.digits * 10^n
        return when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val exp = (if (e >= 0) "+" else "-") + kotlin.math.abs(e)
                if (k == 1) digits + "e" + exp else digits[0] + "." + digits.substring(1) + "e" + exp
            }
        }
    }

    /** `JSON.stringify(v)`, or `null` where JavaScript returns `undefined`. */
    fun jsonStringify(v: Any?): String? = when (v) {
        JsUndefined -> null
        null -> "null"
        is Boolean -> v.toString()
        is Double -> if (v.isNaN() || v.isInfinite()) "null" else numberToString(v)
        is Number -> jsonStringify(v.toDouble())
        is String -> quoteJson(v)
        is JsDate -> v.iso?.let { quoteJson(it) } ?: "null"
        is List<*> -> v.joinToString(",", "[", "]") { jsonStringify(it) ?: "null" }
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val m = v as Map<String, Any?>
            entries(m).mapNotNull { (k, x) -> jsonStringify(x)?.let { quoteJson(k) + ":" + it } }.joinToString(",", "{", "}")
        }
        else -> quoteJson(v.toString())
    }

    private fun quoteJson(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1]); i++
                }
                Character.isSurrogate(c) -> sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
            i++
        }
        return sb.append('"').toString()
    }

    class JsonSyntaxError(message: String) : RuntimeException(message)

    /** `JSON.parse(s)` (strict ECMA-404 JSON); throws [JsonSyntaxError] where JavaScript throws a `SyntaxError`. */
    fun jsonParse(s: String): Any? {
        val p = JsonReader(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) throw JsonSyntaxError("unexpected token at ${p.i}")
        return v
    }

    private class JsonReader(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun fail(): Nothing = throw JsonSyntaxError("unexpected token at $i")

        fun value(): Any? {
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> number()
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                fail()
            }
        }

        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l += value()
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                fail()
            }
        }

        fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail()
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    c < ' ' -> fail()
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) fail()
            if (s[i] == '0') i++
            else if (s[i] in '1'..'9') while (i < s.length && s[i] in '0'..'9') i++
            else fail()
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || s[i] !in '0'..'9') fail()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') fail()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            return s.substring(start, i).toDouble()
        }
    }

    fun trim(s: String): String = JsText.trim(s)

    fun trimEnd(s: String): String {
        var e = s.length
        while (e > 0 && JsText.isWhitespace(s[e - 1])) e--
        return s.substring(0, e)
    }

    fun trimStart(s: String): String {
        var b = 0
        while (b < s.length && JsText.isWhitespace(s[b])) b++
        return s.substring(b)
    }

    /** `s.split(/\s+/)` */
    fun splitWhitespace(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (JsText.isWhitespace(s[i])) {
                out += sb.toString(); sb.setLength(0)
                while (i < s.length && JsText.isWhitespace(s[i])) i++
            } else {
                sb.append(s[i]); i++
            }
        }
        out += sb.toString()
        return out
    }
}
