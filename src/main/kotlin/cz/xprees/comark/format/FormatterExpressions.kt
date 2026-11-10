package cz.xprees.comark.format

import cz.xprees.comark.format.JsText.isDigit
import cz.xprees.comark.format.JsText.isLineTerminator
import cz.xprees.comark.format.JsText.isWhitespace
import cz.xprees.comark.format.JsText.isWordOrDash
import cz.xprees.comark.format.JsText.skipWhitespace

/**
 * The regular expressions of monarch-mdc's `expressions.ts` and the helpers of `formatter-utils.ts`, as hand-written matchers.
 *
 * Each function documents the JavaScript regex it replaces. They are written out by hand rather than translated to
 * `java.util.regex` because the two engines disagree on `\s`, `$`, `.` and surrogate pairs (see [JsText]); every pattern here is
 * anchored and has no ambiguous backtracking, so a linear scan reproduces the JavaScript result exactly.
 */
internal object FormatterExpressions {
    /** Index just past a run of `[\w-]` starting at [from]. */
    private fun skipWordOrDash(s: CharSequence, from: Int): Int {
        var i = from
        while (i < s.length && isWordOrDash(s[i])) i++
        return i
    }

    private fun skipColons(s: CharSequence, from: Int): Int {
        var i = from
        while (i < s.length && s[i] == ':') i++
        return i
    }

    /** `COMPONENT_START_REGEX = /^\s*:{2,}[\w-]+/` - block component opening tags like `::name`. */
    fun isComponentStart(s: CharSequence): Boolean {
        val colonsStart = skipWhitespace(s)
        val colonsEnd = skipColons(s, colonsStart)
        return colonsEnd - colonsStart >= 2 && colonsEnd < s.length && isWordOrDash(s[colonsEnd])
    }

    /** `COMPONENT_END_REGEX = /^\s*:{2,}\s*$/` - block component closing tags like `::`. */
    fun isComponentEnd(s: CharSequence): Boolean {
        val colonsStart = skipWhitespace(s)
        val colonsEnd = skipColons(s, colonsStart)
        return colonsEnd - colonsStart >= 2 && skipWhitespace(s, colonsEnd) == s.length
    }

    /** `MULTILINE_STRING_REGEX = /^[\w-]+:\s*[|>]/` - YAML multiline indicators `|` or `>`. */
    fun isMultilineStringStart(s: CharSequence): Boolean {
        val nameEnd = skipWordOrDash(s, 0)
        if (nameEnd == 0 || nameEnd >= s.length || s[nameEnd] != ':') return false
        val i = skipWhitespace(s, nameEnd + 1)
        return i < s.length && (s[i] == '|' || s[i] == '>')
    }

    /** ``CODE_BLOCK_REGEX = /^\s*(?:`{3,}|~{3,})/`` - Markdown code block fences. */
    fun isCodeBlockFence(s: CharSequence): Boolean {
        val i = skipWhitespace(s)
        if (i + 3 > s.length) return false
        val c = s[i]
        return (c == '`' || c == '~') && s[i + 1] == c && s[i + 2] == c
    }

    /** Index just past `^\s*[-*]\s+` (a list bullet and the whitespace after it), or -1 when [s] does not start with one. */
    private fun bulletEnd(s: CharSequence): Int {
        val i = skipWhitespace(s)
        if (i >= s.length || (s[i] != '-' && s[i] != '*')) return -1
        val end = skipWhitespace(s, i + 1)
        return if (end > i + 1) end else -1
    }

    /** `UNORDERED_LIST_REGEX = /^\s*[-*]\s+/` - unordered list items like `- item` or `* item`. */
    fun isUnorderedListItem(s: CharSequence): Boolean = bulletEnd(s) >= 0

    /** `ORDERED_LIST_REGEX = /^\s*\d+\.\s+/` - ordered list items like `1. item`. */
    fun isOrderedListItem(s: CharSequence): Boolean {
        val start = skipWhitespace(s)
        var i = start
        while (i < s.length && isDigit(s[i])) i++
        if (i == start || i >= s.length || s[i] != '.') return false
        return i + 1 < s.length && isWhitespace(s[i + 1])
    }

    /** `TASK_LIST_REGEX = /^\s*[-*]\s+\[.\]\s+/` - task list items like `- [ ] item` (`.` is one UTF-16 code unit). */
    fun isTaskListItem(s: CharSequence): Boolean {
        val i = bulletEnd(s)
        if (i < 0 || i + 3 >= s.length) return false
        return s[i] == '[' && !isLineTerminator(s[i + 1]) && s[i + 2] == ']' && isWhitespace(s[i + 3])
    }

    /** `PARENT_PROPERTY_REGEX = /^[\w-]+:\s*$/` - a property without a value. */
    fun isParentProperty(s: CharSequence): Boolean {
        val nameEnd = skipWordOrDash(s, 0)
        if (nameEnd == 0 || nameEnd >= s.length || s[nameEnd] != ':') return false
        return skipWhitespace(s, nameEnd + 1) == s.length
    }

    /** `ARRAY_ITEM_REGEX = /^\s*-\s+/` - YAML array items. */
    fun isArrayItem(s: CharSequence): Boolean {
        val i = skipWhitespace(s)
        if (i >= s.length || s[i] != '-') return false
        return i + 1 < s.length && isWhitespace(s[i + 1])
    }

    /**
     * The name captured by `QUOTED_PROPERTY_NAME_WITH_CAPTURE_GROUP = /^(?:"([^"]+)"|'([^']+)')\s*:/`, or null when it does not
     * match. Also answers `QUOTED_PROPERTY_NAME = /^"[^"]+"\s*:|^'[^']+'\s*:/`, which matches exactly the same strings.
     */
    fun quotedPropertyName(s: CharSequence): String? {
        if (s.isEmpty()) return null
        val quote = s[0]
        if (quote != '"' && quote != '\'') return null
        // `[^"]+` cannot contain the quote, so the name ends at the first quote after it
        var close = 1
        while (close < s.length && s[close] != quote) close++
        if (close == 1 || close >= s.length) return null
        val colon = skipWhitespace(s, close + 1)
        return if (colon < s.length && s[colon] == ':') s.substring(1, close) else null
    }

    /** The name captured by `STANDARD_PROPERTY_NAME = /^:?([\w-]+)\s*:/`, or null when it does not match. */
    fun standardPropertyName(s: CharSequence): String? {
        val nameStart = if (s.isNotEmpty() && s[0] == ':') 1 else 0
        val nameEnd = skipWordOrDash(s, nameStart)
        if (nameEnd == nameStart) return null
        val colon = skipWhitespace(s, nameEnd)
        return if (colon < s.length && s[colon] == ':') s.substring(nameStart, nameEnd) else null
    }

    /** `FLOW_ARRAY_START_REGEX = /^[\w-]+:\s*\[\s*\S+/` - the start of a flow array with content. */
    fun isFlowArrayStart(s: CharSequence): Boolean {
        val nameEnd = skipWordOrDash(s, 0)
        if (nameEnd == 0 || nameEnd >= s.length || s[nameEnd] != ':') return false
        val bracket = skipWhitespace(s, nameEnd + 1)
        if (bracket >= s.length || s[bracket] != '[') return false
        return skipWhitespace(s, bracket + 1) < s.length
    }

    /** `YAML_COMMENT_REGEX = /^\s*#/` */
    fun isYamlComment(s: CharSequence): Boolean {
        val i = skipWhitespace(s)
        return i < s.length && s[i] == '#'
    }

    // ---- formatter-utils.ts --------------------------------------------------------------------------------------

    /** `isPropertyLine`: whether the line defines a property (`name: value`, `"name": value`, `:name: value`). */
    fun isPropertyLine(line: String): Boolean {
        val trimmed = JsText.trim(line)
        if (!trimmed.contains(':')) return false
        if (quotedPropertyName(trimmed) != null) return true
        // upstream re-checks the captured name against /^:?[\w-]+$/, which a `[\w-]+` capture always satisfies
        return standardPropertyName(trimmed) != null
    }

    /** `isEmptyProperty`: a property with no value. */
    fun isEmptyProperty(line: String): Boolean = isParentProperty(JsText.trim(line))

    /** `getPropertyName`: the property name, handling quoted names and values containing colons. */
    fun getPropertyName(line: String): String {
        val trimmed = JsText.trim(line)
        quotedPropertyName(trimmed)?.let { return it }
        return standardPropertyName(trimmed) ?: ""
    }

    /** `isArrayProperty`: a property without a value whose next line is an array item. */
    fun isArrayProperty(line: String, lines: List<String>, i: Int): Boolean {
        if (!isEmptyProperty(JsText.trim(line))) return false
        return i + 1 < lines.size && isArrayItem(JsText.trim(lines[i + 1]))
    }
}
