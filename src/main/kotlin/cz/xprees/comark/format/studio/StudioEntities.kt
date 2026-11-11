package cz.xprees.comark.format.studio

/**
 * HTML character references as the `entities` package (7 for markdown-exit, 8 for htmlparser2) decodes them. The tables
 * ([StudioEntityData]) hold the 2125 named references; 106 "legacy" ones may also appear without the trailing semicolon.
 */
internal object StudioEntities {
    val named: Map<String, String> by lazy {
        val m = HashMap<String, String>(4096)
        for (entry in StudioEntityData.NAMED.split(',')) {
            val eq = entry.indexOf('=')
            val sb = StringBuilder()
            for (hex in entry.substring(eq + 1).split(' ')) sb.appendCodePoint(hex.toInt(16))
            m[entry.substring(0, eq)] = sb.toString()
        }
        m
    }

    val legacy: Set<String> by lazy { StudioEntityData.LEGACY.split(',').toHashSet() }

    /** Every prefix of every name (the paths of the `entities` decode trie). */
    private val prefixes: Set<String> by lazy {
        val s = HashSet<String>(16384)
        for (name in named.keys) for (i in 1..name.length) s += name.substring(0, i)
        s
    }

    enum class Mode { LEGACY, STRICT, ATTRIBUTE }

    /** One decoded reference: the text and how many characters it took, counting the `&`. */
    class Decoded(val text: String, val consumed: Int)

    /** windows-1252 replacements for C1 controls, as `entities` `replaceCodePoint` applies them. */
    private val C1 = mapOf(
        0x80 to 0x20AC, 0x82 to 0x201A, 0x83 to 0x0192, 0x84 to 0x201E, 0x85 to 0x2026, 0x86 to 0x2020, 0x87 to 0x2021,
        0x88 to 0x02C6, 0x89 to 0x2030, 0x8A to 0x0160, 0x8B to 0x2039, 0x8C to 0x0152, 0x8E to 0x017D, 0x91 to 0x2018,
        0x92 to 0x2019, 0x93 to 0x201C, 0x94 to 0x201D, 0x95 to 0x2022, 0x96 to 0x2013, 0x97 to 0x2014, 0x98 to 0x02DC,
        0x99 to 0x2122, 0x9A to 0x0161, 0x9B to 0x203A, 0x9C to 0x0153, 0x9E to 0x017E, 0x9F to 0x0178,
    )

    fun replaceCodePoint(cp: Long): Int {
        if (cp in 0xD800..0xDFFF || cp > 0x10FFFF) return 0xFFFD
        if (cp == 0L) return 0xFFFD
        return C1[cp.toInt()] ?: cp.toInt()
    }

    private fun isAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /**
     * Decodes the reference whose `&` is at [amp], like `entities`' `EntityDecoder` fed the whole remaining input; `null`
     * when nothing is decoded there.
     */
    fun decodeAt(input: String, amp: Int, mode: Mode): Decoded? {
        var p = amp + 1
        if (p < input.length && input[p] == '#') {
            p++
            var hex = false
            if (p < input.length && (input[p] == 'x' || input[p] == 'X')) {
                hex = true; p++
            }
            val digitsStart = p
            var value = 0L
            while (p < input.length) {
                val ch = input[p]
                val d = when {
                    ch in '0'..'9' -> ch - '0'
                    hex && ch.lowercaseChar() in 'a'..'f' -> ch.lowercaseChar() - 'a' + 10
                    else -> -1
                }
                if (d < 0) break
                value = minOf(value * (if (hex) 16 else 10) + d, 0x7FFFFFFFL)
                p++
            }
            if (p == digitsStart) return null
            var consumed = p - amp
            if (p < input.length && input[p] == ';') {
                consumed++
            } else if (mode == Mode.STRICT) {
                return null
            }
            return Decoded(String(Character.toChars(replaceCodePoint(value))), consumed)
        }
        var k = 0
        var legacyLen = -1
        while (true) {
            val prefix = input.substring(p, p + k)
            val next = if (p + k < input.length) input[p + k] else null
            if (k > 0 && named.containsKey(prefix)) {
                if (mode != Mode.STRICT && prefix in legacy) legacyLen = k
                if (next == ';') return Decoded(named.getValue(prefix), k + 2)
            }
            if (next != null && (prefix + next) in prefixes) {
                k++
                continue
            }
            if (legacyLen < 0) return null
            if (mode == Mode.ATTRIBUTE) {
                if (legacyLen != k) return null
                if (next != null && (next == '=' || isAlnum(next))) return null
            }
            val name = input.substring(p, p + legacyLen)
            return Decoded(named.getValue(name), legacyLen + 1)
        }
    }

    /** `entities` `decodeHTML` (legacy mode) of a whole string. */
    fun decodeHtml(s: String): String = decodeAll(s, Mode.LEGACY)

    /** `entities` `decodeHTMLStrict` of a whole string. */
    fun decodeHtmlStrict(s: String): String = decodeAll(s, Mode.STRICT)

    private fun decodeAll(s: String, mode: Mode): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '&') {
                val d = decodeAt(s, i, mode)
                if (d != null) {
                    sb.append(d.text); i += d.consumed; continue
                }
            }
            sb.append(s[i]); i++
        }
        return sb.toString()
    }
}
