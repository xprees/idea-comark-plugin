package cz.xprees.comark.format.studio

import cz.xprees.comark.format.JsText

/**
 * Helpers of markdown-exit 1.1.0-beta.2 (a markdown-it 14 fork) and the libraries it uses: `utils` (escapes, entities,
 * character classes), `mdurl` 2 (`parse`, `format`, `encode`, `decode`), `punycode.js` 2 and the link normalizers.
 */
internal object StudioMdUtil {
    fun isSpace(c: Int) = c == 9 || c == 32

    fun isWhiteSpace(c: Int): Boolean {
        if (c in 0x2000..0x200A) return true
        return when (c) {
            9, 10, 11, 12, 13, 32, 0xA0, 0x1680, 0x202F, 0x205F, 0x3000 -> true
            else -> false
        }
    }

    /** uc.micro `P` (punctuation) or `S` (symbols) */
    fun isPunctCharCode(cp: Int): Boolean {
        if (cp < 0) return false
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
            -> true
            else -> false
        }
    }

    fun isMdAsciiPunct(c: Int) = c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126

    fun isValidEntityCode(c: Long): Boolean {
        if (c in 0xD800..0xDFFF) return false
        if (c in 0xFDD0..0xFDEF) return false
        if ((c and 0xFFFF) == 0xFFFFL || (c and 0xFFFF) == 0xFFFEL) return false
        if (c in 0..8) return false
        if (c == 11L) return false
        if (c in 14..31) return false
        if (c in 127..159) return false
        if (c > 0x10FFFF) return false
        return true
    }

    fun fromCodePoint(c: Int): String = String(Character.toChars(c))

    private val UNESCAPE_ALL = Regex("\\\\([!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~])|&([a-zA-Z#][a-zA-Z0-9]{1,31});")
    private val DIGITAL_ENTITY_TEST = Regex("^#([xX][a-fA-F0-9]{1,8}|\\d{1,8})\\z")

    private fun replaceEntityPattern(match: String, name: String): String {
        if (name[0] == '#' && DIGITAL_ENTITY_TEST.matches(name)) {
            val code = if (name[1].lowercaseChar() == 'x') name.substring(2).toLong(16) else name.substring(1).toLong()
            if (isValidEntityCode(code)) return fromCodePoint(code.toInt())
            return match
        }
        val decoded = StudioEntities.decodeHtml(match)
        return if (decoded != match) decoded else match
    }

    fun unescapeAll(str: String): String {
        if (!str.contains('\\') && !str.contains('&')) return str
        return UNESCAPE_ALL.replace(str) { m ->
            val escaped = m.groups[1]
            if (escaped != null) escaped.value else replaceEntityPattern(m.value, m.groupValues[2])
        }
    }

    fun normalizeReference(s: String): String {
        val collapsed = StudioJs.trim(s).let { t ->
            val sb = StringBuilder()
            var i = 0
            while (i < t.length) {
                if (JsText.isWhitespace(t[i])) {
                    sb.append(' ')
                    while (i < t.length && JsText.isWhitespace(t[i])) i++
                } else {
                    sb.append(t[i]); i++
                }
            }
            sb.toString()
        }
        return collapsed.lowercase(java.util.Locale.ROOT).uppercase(java.util.Locale.ROOT)
    }

    fun asciiTrim(s: String): String {
        fun t(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'
        var start = 0
        while (start < s.length && t(s[start])) start++
        var end = s.length - 1
        while (end >= start && t(s[end])) end--
        return s.substring(start, end + 1)
    }

    // ---- links ------------------------------------------------------------------------------------------------------

    private val BAD_PROTO = Regex("^(vbscript|javascript|file|data):")
    private val GOOD_DATA = Regex("^data:image/(gif|png|jpeg|webp);")

    fun validateLink(url: String): Boolean {
        val str = StudioJs.trim(url).lowercase(java.util.Locale.ROOT)
        return if (BAD_PROTO.containsMatchIn(str)) GOOD_DATA.containsMatchIn(str) else true
    }

    private val RECODE_HOSTNAME_FOR = setOf("http:", "https:", "mailto:")

    fun normalizeLink(url: String): String {
        val parsed = Url.parse(url, true)
        val host = parsed.hostname
        if (!host.isNullOrEmpty()) {
            if (parsed.protocol == null || parsed.protocol in RECODE_HOSTNAME_FOR) {
                try {
                    parsed.hostname = Punycode.toAscii(host)
                } catch (_: Exception) {
                }
            }
        }
        return encode(parsed.format())
    }

    fun normalizeLinkText(url: String): String {
        val parsed = Url.parse(url, true)
        val host = parsed.hostname
        if (!host.isNullOrEmpty()) {
            if (parsed.protocol == null || parsed.protocol in RECODE_HOSTNAME_FOR) {
                try {
                    parsed.hostname = Punycode.toUnicode(host)
                } catch (_: Exception) {
                }
            }
        }
        return decode(parsed.format(), ";/?:@&=+$,#%")
    }

    private const val ENCODE_DEFAULT = ";/?:@&=+$,-_.!~*'()#"

    fun encode(s: String, exclude: String = ENCODE_DEFAULT, keepEscaped: Boolean = true): String {
        val sb = StringBuilder()
        var i = 0
        val l = s.length
        fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        while (i < l) {
            val code = s[i].code
            if (keepEscaped && code == 0x25 && i + 2 < l && isHex(s[i + 1]) && isHex(s[i + 2])) {
                sb.append(s, i, i + 3)
                i += 3
                continue
            }
            if (code < 128) {
                val ch = s[i]
                if (ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || exclude.indexOf(ch) >= 0) {
                    sb.append(ch)
                } else {
                    sb.append('%').append(String.format("%02X", code))
                }
                i++
                continue
            }
            if (code in 0xD800..0xDFFF) {
                if (code <= 0xDBFF && i + 1 < l && s[i + 1].code in 0xDC00..0xDFFF) {
                    appendUtf8(sb, s.substring(i, i + 2))
                    i += 2
                    continue
                }
                sb.append("%EF%BF%BD")
                i++
                continue
            }
            appendUtf8(sb, s[i].toString())
            i++
        }
        return sb.toString()
    }

    private fun appendUtf8(sb: StringBuilder, s: String) {
        for (b in s.toByteArray(Charsets.UTF_8)) sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
    }

    private val PERCENT_SEQ = Regex("(%[a-fA-F0-9]{2})+")

    fun decode(s: String, exclude: String): String = PERCENT_SEQ.replace(s) { m ->
        val seq = m.value
        val result = StringBuilder()
        var i = 0
        val l = seq.length
        fun byteAt(j: Int) = seq.substring(j + 1, j + 3).toInt(16)
        while (i < l) {
            val b1 = byteAt(i)
            if (b1 < 0x80) {
                val ch = b1.toChar()
                if (exclude.indexOf(ch) >= 0) result.append('%').append(String.format("%02X", b1)) else result.append(ch)
                i += 3
                continue
            }
            if ((b1 and 0xE0) == 0xC0 && i + 3 < l) {
                val b2 = byteAt(i + 3)
                if ((b2 and 0xC0) == 0x80) {
                    val chr = ((b1 shl 6) and 0x7C0) or (b2 and 0x3F)
                    if (chr < 0x80) result.append("��") else result.append(chr.toChar())
                    i += 6
                    continue
                }
            }
            if ((b1 and 0xF0) == 0xE0 && i + 6 < l) {
                val b2 = byteAt(i + 3)
                val b3 = byteAt(i + 6)
                if ((b2 and 0xC0) == 0x80 && (b3 and 0xC0) == 0x80) {
                    val chr = ((b1 shl 12) and 0xF000) or ((b2 shl 6) and 0xFC0) or (b3 and 0x3F)
                    if (chr < 0x800 || chr in 0xD800..0xDFFF) result.append("���") else result.append(chr.toChar())
                    i += 9
                    continue
                }
            }
            if ((b1 and 0xF8) == 0xF0 && i + 9 < l) {
                val b2 = byteAt(i + 3)
                val b3 = byteAt(i + 6)
                val b4 = byteAt(i + 9)
                if ((b2 and 0xC0) == 0x80 && (b3 and 0xC0) == 0x80 && (b4 and 0xC0) == 0x80) {
                    var chr = ((b1 shl 18) and 0x1C0000) or ((b2 shl 12) and 0x3F000) or ((b3 shl 6) and 0xFC0) or (b4 and 0x3F)
                    if (chr < 0x10000 || chr > 0x10FFFF) {
                        result.append("����")
                    } else {
                        chr -= 0x10000
                        result.append((0xD800 + (chr shr 10)).toChar()).append((0xDC00 + (chr and 0x3FF)).toChar())
                    }
                    i += 12
                    continue
                }
            }
            result.append('�')
            i += 3
        }
        Regex.escapeReplacement(result.toString())
    }

    /** mdurl `Url` (a trimmed-down Node `url.parse`) */
    class Url {
        var protocol: String? = null
        var slashes = false
        var auth: String? = null
        var port: String? = null
        var hostname: String? = null
        var hash: String? = null
        var search: String? = null
        var pathname: String? = null

        fun format(): String {
            val sb = StringBuilder()
            sb.append(protocol ?: "")
            if (slashes) sb.append("//")
            if (!auth.isNullOrEmpty()) sb.append(auth).append('@')
            val h = hostname
            if (!h.isNullOrEmpty() && h.contains(':')) sb.append('[').append(h).append(']') else sb.append(h ?: "")
            if (!port.isNullOrEmpty()) sb.append(':').append(port)
            sb.append(pathname ?: "").append(search ?: "").append(hash ?: "")
            return sb.toString()
        }

        companion object {
            private val protocolPattern = Regex("^([a-zA-Z0-9.+-]+:)")
            private val portPattern = Regex(":[0-9]*\\z")
            private val simplePathPattern = Regex("^(//?(?!/)[^?${StudioJs.RE_S_CHARS}]*)(\\?[^${StudioJs.RE_S_CHARS}]*)?\\z")
            private val autoEscape = listOf('\'', '{', '}', '|', '\\', '^', '`', '<', '>', '"', ' ', '\r', '\n', '\t')
            private val nonHostChars = listOf('%', '/', '?', ';', '#') + autoEscape
            private val hostEndingChars = listOf('/', '?', '#')
            private const val HOSTNAME_MAX_LEN = 255
            private val hostnamePartPattern = Regex("^[+a-z0-9A-Z_-]{0,63}\\z")
            private val hostnamePartStart = Regex("^([+a-z0-9A-Z_-]{0,63})(${StudioJs.RE_DOT}*)\\z")
            private val hostlessProtocol = setOf("javascript", "javascript:")
            private val slashedProtocol = setOf("http", "https", "ftp", "gopher", "file", "http:", "https:", "ftp:", "gopher:", "file:")
            private val authPattern = Regex("^//[^@/]+@[^@/]+")

            fun parse(url: String, slashesDenoteHost: Boolean): Url {
                val u = Url()
                var rest = StudioJs.trim(url)
                if (!slashesDenoteHost && !url.contains('#')) {
                    val simple = simplePathPattern.matchEntire(rest)
                    if (simple != null) {
                        u.pathname = simple.groupValues[1]
                        if (simple.groupValues[2].isNotEmpty()) u.search = simple.groupValues[2]
                        return u
                    }
                }
                var lowerProto: String? = null
                var proto: String? = null
                protocolPattern.find(rest)?.let {
                    proto = it.value
                    lowerProto = it.value.lowercase(java.util.Locale.ROOT)
                    u.protocol = it.value
                    rest = rest.substring(it.value.length)
                }
                var slashes = false
                if (slashesDenoteHost || proto != null || authPattern.containsMatchIn(rest)) {
                    slashes = rest.startsWith("//")
                    if (slashes && !(proto != null && proto in hostlessProtocol)) {
                        rest = rest.substring(2)
                        u.slashes = true
                    }
                }
                if (!(proto != null && proto in hostlessProtocol) && (slashes || (proto != null && proto !in slashedProtocol))) {
                    var hostEnd = -1
                    for (c in hostEndingChars) {
                        val hec = rest.indexOf(c)
                        if (hec != -1 && (hostEnd == -1 || hec < hostEnd)) hostEnd = hec
                    }
                    val atSign = if (hostEnd == -1) rest.lastIndexOf('@') else rest.lastIndexOf('@', hostEnd)
                    if (atSign != -1) {
                        u.auth = rest.substring(0, atSign)
                        rest = rest.substring(atSign + 1)
                    }
                    hostEnd = -1
                    for (c in nonHostChars) {
                        val hec = rest.indexOf(c)
                        if (hec != -1 && (hostEnd == -1 || hec < hostEnd)) hostEnd = hec
                    }
                    if (hostEnd == -1) hostEnd = rest.length
                    if (hostEnd > 0 && rest[hostEnd - 1] == ':') hostEnd--
                    var host = rest.substring(0, hostEnd)
                    rest = rest.substring(hostEnd)
                    portPattern.find(host)?.let { m ->
                        if (m.value != ":") u.port = m.value.substring(1)
                        host = host.substring(0, host.length - m.value.length)
                    }
                    if (host.isNotEmpty()) u.hostname = host
                    var hostname = u.hostname ?: ""
                    val ipv6 = hostname.startsWith("[") && hostname.endsWith("]")
                    if (!ipv6) {
                        val parts = hostname.split('.')
                        for (i in parts.indices) {
                            val part = parts[i]
                            if (part.isEmpty()) continue
                            if (!hostnamePartPattern.matches(part)) {
                                val newpart = part.map { if (it.code > 127) 'x' else it }.joinToString("")
                                if (!hostnamePartPattern.matches(newpart)) {
                                    val validParts = parts.subList(0, i).toMutableList()
                                    val notHost = parts.subList(i + 1, parts.size).toMutableList()
                                    val bit = hostnamePartStart.matchEntire(part)
                                    if (bit != null) {
                                        validParts += bit.groupValues[1]
                                        notHost.add(0, bit.groupValues[2])
                                    }
                                    if (notHost.isNotEmpty()) rest = notHost.joinToString(".") + rest
                                    hostname = validParts.joinToString(".")
                                    break
                                }
                            }
                        }
                    }
                    if (hostname.length > HOSTNAME_MAX_LEN) hostname = ""
                    if (ipv6) hostname = hostname.substring(1, hostname.length - 1)
                    u.hostname = hostname
                }
                val hash = rest.indexOf('#')
                if (hash != -1) {
                    u.hash = rest.substring(hash)
                    rest = rest.substring(0, hash)
                }
                val qm = rest.indexOf('?')
                if (qm != -1) {
                    u.search = rest.substring(qm)
                    rest = rest.substring(0, qm)
                }
                if (rest.isNotEmpty()) u.pathname = rest
                if (lowerProto != null && lowerProto in slashedProtocol && !u.hostname.isNullOrEmpty() && u.pathname.isNullOrEmpty()) {
                    u.pathname = ""
                }
                return u
            }
        }
    }

    /** punycode.js 2 `toASCII` / `toUnicode` */
    object Punycode {
        private const val BASE = 36
        private const val TMIN = 1
        private const val TMAX = 26
        private const val SKEW = 38
        private const val DAMP = 700
        private const val INITIAL_BIAS = 72
        private const val INITIAL_N = 128
        private val separators = Regex("[\\x2E\\u3002\\uFF0E\\uFF61]")

        private fun mapDomain(domain: String, fn: (String) -> String): String {
            val at = domain.split('@')
            var result = ""
            var d = domain
            if (at.size > 1) {
                result = at[0] + "@"
                d = at[1]
            }
            d = separators.replace(d, ".")
            return result + d.split('.').joinToString(".") { fn(it) }
        }

        private fun adapt(deltaIn: Int, numPoints: Int, firstTime: Boolean): Int {
            var delta = if (firstTime) deltaIn / DAMP else deltaIn shr 1
            delta += delta / numPoints
            var k = 0
            while (delta > ((BASE - TMIN) * TMAX) shr 1) {
                delta /= BASE - TMIN
                k += BASE
            }
            return k + (BASE - TMIN + 1) * delta / (delta + SKEW)
        }

        private fun digitToBasic(d: Int): Char = (d + 22 + 75 * (if (d < 26) 1 else 0)).toChar()

        private fun basicToDigit(cp: Int): Int = when {
            cp in 0x30..0x39 -> 26 + (cp - 0x30)
            cp in 0x41..0x5A -> cp - 0x41
            cp in 0x61..0x7A -> cp - 0x61
            else -> BASE
        }

        fun encode(input: String): String {
            val cps = input.codePoints().toArray()
            val output = StringBuilder()
            for (c in cps) if (c < 0x80) output.append(c.toChar())
            val basicLength = output.length
            var handled = basicLength
            if (basicLength > 0) output.append('-')
            var n = INITIAL_N
            var delta = 0L
            var bias = INITIAL_BIAS
            while (handled < cps.size) {
                var m = Int.MAX_VALUE
                for (c in cps) if (c >= n && c < m) m = c
                delta += (m - n).toLong() * (handled + 1)
                if (delta > Int.MAX_VALUE) throw IllegalArgumentException("overflow")
                n = m
                for (c in cps) {
                    if (c < n) {
                        delta++
                        if (delta > Int.MAX_VALUE) throw IllegalArgumentException("overflow")
                    }
                    if (c == n) {
                        var q = delta.toInt()
                        var k = BASE
                        while (true) {
                            val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                            if (q < t) break
                            val qMinusT = q - t
                            val baseMinusT = BASE - t
                            output.append(digitToBasic(t + qMinusT % baseMinusT))
                            q = qMinusT / baseMinusT
                            k += BASE
                        }
                        output.append(digitToBasic(q))
                        bias = adapt(delta.toInt(), handled + 1, handled == basicLength)
                        delta = 0
                        handled++
                    }
                }
                delta++
                n++
            }
            return output.toString()
        }

        fun decode(input: String): String {
            val output = ArrayList<Int>()
            var basic = input.lastIndexOf('-')
            if (basic < 0) basic = 0
            for (j in 0 until basic) {
                if (input[j].code >= 0x80) throw IllegalArgumentException("not-basic")
                output += input[j].code
            }
            var i = 0L
            var n = INITIAL_N
            var bias = INITIAL_BIAS
            var index = if (basic > 0) basic + 1 else 0
            while (index < input.length) {
                val oldi = i
                var w = 1L
                var k = BASE
                while (true) {
                    if (index >= input.length) throw IllegalArgumentException("invalid-input")
                    val digit = basicToDigit(input[index++].code)
                    if (digit >= BASE) throw IllegalArgumentException("invalid-input")
                    if (digit > (Int.MAX_VALUE - i) / w) throw IllegalArgumentException("overflow")
                    i += digit * w
                    val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                    if (digit < t) break
                    val baseMinusT = BASE - t
                    if (w > Int.MAX_VALUE / baseMinusT) throw IllegalArgumentException("overflow")
                    w *= baseMinusT
                    k += BASE
                }
                val out = output.size + 1
                bias = adapt((i - oldi).toInt(), out, oldi == 0L)
                if (i / out > Int.MAX_VALUE - n) throw IllegalArgumentException("overflow")
                n += (i / out).toInt()
                i %= out
                output.add(i.toInt(), n)
                i++
            }
            val sb = StringBuilder()
            for (cp in output) sb.appendCodePoint(cp)
            return sb.toString()
        }

        fun toAscii(domain: String): String = mapDomain(domain) { s ->
            if (s.any { it.code > 0x7F }) "xn--" + encode(s) else s
        }

        fun toUnicode(domain: String): String = mapDomain(domain) { s ->
            if (s.startsWith("xn--")) decode(s.substring(4).lowercase(java.util.Locale.ROOT)) else s
        }
    }
}
