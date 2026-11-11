package cz.xprees.comark.format

/**
 * The bits of JavaScript string semantics the monarch-mdc formatter relies on, replicated exactly so that [ComarkFormatter]
 * produces byte-identical output.
 *
 * Kotlin's own helpers differ in small but observable ways: `String.trim()` and `Char.isWhitespace()` treat `\u001C`-`\u001F`
 * as whitespace but not the BOM `\uFEFF`, Java's regex `\s` knows only ASCII whitespace, `$` also matches before a final line
 * terminator and `.` works on code points instead of UTF-16 code units. JavaScript (without the `u` flag) works on code units
 * and its `\s` / `String.prototype.trim` use the ECMAScript WhiteSpace + LineTerminator set, which is what [isWhitespace] is.
 */
internal object JsText {
    /** ECMAScript `WhiteSpace` and `LineTerminator` code points: what `\s` matches and `String.prototype.trim` removes. */
    fun isWhitespace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }

    /** ECMAScript `LineTerminator`: the code units the regex `.` does not match. */
    fun isLineTerminator(c: Char): Boolean = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

    /** `\w` without the `u` flag: `[A-Za-z0-9_]`. */
    fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    /** `[\w-]` */
    fun isWordOrDash(c: Char): Boolean = isWordChar(c) || c == '-'

    /** `\d` without the `u` flag. */
    fun isDigit(c: Char): Boolean = c in '0'..'9'

    /** Index of the first non-[isWhitespace] character at or after [from] (`s.length` when there is none). */
    fun skipWhitespace(s: CharSequence, from: Int = 0): Int {
        var i = from
        while (i < s.length && isWhitespace(s[i])) i++
        return i
    }

    /** Length of the match of `^\s*` (the formatter's `line.match(INDENT_REGEX)[0].length`). */
    fun leadingWhitespace(s: CharSequence): Int = skipWhitespace(s)

    /** `String.prototype.trim()` */
    fun trim(s: String): String {
        val start = skipWhitespace(s)
        var end = s.length
        while (end > start && isWhitespace(s[end - 1])) end--
        return if (start == 0 && end == s.length) s else s.substring(start, end)
    }
}
