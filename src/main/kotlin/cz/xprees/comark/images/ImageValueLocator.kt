package cz.xprees.comark.images

import com.intellij.openapi.util.TextRange
import cz.xprees.comark.parser.ComarkSyntax

/**
 * An image-like value found in text.
 *
 * @property key the key or prop name (for YAML, the key path joined with `.`).
 * @property value the value with quotes removed and escapes resolved.
 * @property range the absolute range of the value as written, inside its quotes.
 */
data class ImageValue(val key: String, val value: String, val range: TextRange)

/**
 * A YAML value being typed (for completion): the key path it belongs to and the text typed so far, which starts at [prefixStart].
 * A sequence item (`- /x`) belongs to the key of its sequence.
 */
data class YamlValueSite(val keys: List<String>, val prefixStart: Int, val prefix: String)

/**
 * Text-based search for image values in component attributes and YAML (props blocks, front matter). Used where no PSI is at hand
 * or where the YAML plugin (optional) may be missing: the preview, the missing-image inspection and the no-YAML reference fallbacks.
 * Values are not checked to be paths; callers run them through [ImagePaths.normalize].
 */
object ImageValueLocator {
    private val QUOTES = "\"'`"

    /**
     * The values of image-like attributes ([ImageKeys.isImageProp]) of the attribute group [scan] found in [text]. Bound attributes
     * (`:src`) and `{…}` / `[…]` values are skipped. [baseOffset] is added to every range.
     */
    fun attributeValues(text: CharSequence, scan: ComarkSyntax.AttrScan, baseOffset: Int = 0): List<ImageValue> {
        val result = ArrayList<ImageValue>()
        val tokens = scan.tokens
        for ((index, token) in tokens.withIndex()) {
            if (token.part != ComarkSyntax.AttrPart.NAME) continue
            val eq = tokens.getOrNull(index + 1)?.takeIf { it.part == ComarkSyntax.AttrPart.EQ } ?: continue
            val value = tokens.getOrNull(index + 2)?.takeIf { it.part == ComarkSyntax.AttrPart.VALUE && it.start == eq.end } ?: continue
            val name = text.subSequence(token.start, token.end).toString()
            if (!ImageKeys.isImageProp(name)) continue
            var start = value.start
            var end = value.end
            val first = text[start]
            if (first == '{' || first == '[') continue
            if (first in QUOTES) {
                start++
                if (end > start && text[end - 1] == first) end--
            }
            if (end <= start) continue
            result += ImageValue(name, unescape(text.subSequence(start, end)), TextRange(start + baseOffset, end + baseOffset))
        }
        return result
    }

    /**
     * Single-line scalar values in the YAML [text] whose key path (keys from the top, sequence items inheriting their parent's key)
     * satisfies [keyFilter]. Understands indentation, `- ` items, quoted keys and values, plain keys containing `:` (`og:image`),
     * flow mappings on one line (`image: { src: /x.png }`) and trailing ` # comments`; block scalars (`|`, `>`) are skipped.
     * [baseOffset] is added to every range.
     */
    fun yamlValues(text: CharSequence, baseOffset: Int = 0, keyFilter: (List<String>) -> Boolean): List<ImageValue> {
        val result = ArrayList<ImageValue>()
        // (indent, key) of the mapping keys enclosing the current line
        val stack = ArrayList<Pair<Int, String>>()
        var blockIndent = -1
        var lineStart = 0
        while (lineStart <= text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            var contentEnd = lineEnd
            if (contentEnd > lineStart && text[contentEnd - 1] == '\r') contentEnd--
            blockIndent = parseLine(text, lineStart, contentEnd, stack, blockIndent, keyFilter, baseOffset, result)
            lineStart = lineEnd + 1
        }
        return result
    }

    private val VALUE_AFTER_KEY = Regex("""^( *)((?:-[ ]+)*)("[^"]*"|'[^']*'|[^\s"'#\[{][^#]*?)[ ]*:[ ]+['"]?([^'"#\s]*)$""")
    private val VALUE_ITEM = Regex("""^( *)((?:-[ ]+)+)['"]?([^'"#\s]*)$""")

    /**
     * The value typed at [offset] of the YAML [text] (`key: /pa|`, `- /pa|`, quoted or not) with its key path, worked out from the
     * indentation of the lines above; null when the caret is not in a single-line scalar value. Text-based because the value being
     * typed is often not valid YAML yet, and because the YAML plugin is optional.
     */
    fun yamlValueSite(text: CharSequence, offset: Int): YamlValueSite? {
        if (offset < 0 || offset > text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val before = text.subSequence(lineStart, offset).toString()
        val keyed = VALUE_AFTER_KEY.find(before)
        val match = keyed ?: VALUE_ITEM.find(before) ?: return null
        val indent = match.groupValues[1].length
        val dashes = match.groupValues[2].isNotEmpty()
        val own = keyed?.groupValues?.get(3)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
        val prefix = match.groupValues.last()
        val keys = parentKeys(text, lineStart, indent, dashes) + listOfNotNull(own)
        return YamlValueSite(keys, offset - prefix.length, prefix)
    }

    /**
     * The keys of the mappings enclosing a line indented by [indent] that starts at [lineStart]: each `key:` line without a value above
     * it and indented less (or equally, for a sequence written at its key's indentation: `gallery:` then `- /a.png`).
     */
    private fun parentKeys(text: CharSequence, lineStart: Int, indent: Int, dashes: Boolean): List<String> {
        val keys = ArrayList<String>()
        var limit = indent
        var sameIndent = dashes
        var end = lineStart - 1
        while (end >= 0 && (limit > 0 || sameIndent)) {
            var start = end
            while (start > 0 && text[start - 1] != '\n') start--
            var i = start
            while (i < end && text[i] == ' ') i++
            val lineIndent = i - start
            val blank = i >= end || text[i] == '#'
            if (!blank && lineIndent == 0 && (text.startsWith("---", i) || text.startsWith("...", i))) break
            if (!blank && (lineIndent < limit || lineIndent == limit && sameIndent)) {
                var k = i
                var lineDashes = false
                while (k < end && text[k] == '-' && (k + 1 == end || text[k + 1] == ' ')) {
                    lineDashes = true
                    k++
                    while (k < end && text[k] == ' ') k++
                }
                val key = if (k < end) keyAt(text, k, end) else null
                var v = key?.second ?: end
                while (v < end && text[v] == ' ') v++
                val keyOnly = key != null && (v >= end || text[v] == '#')
                if (keyOnly && !(lineIndent == limit && lineDashes)) {
                    keys += key!!.first
                } else if (lineIndent == limit) {
                    if (!lineDashes) break
                    // a sibling item at the same indentation: keep looking for the sequence's key
                    end = start - 1
                    continue
                }
                limit = lineIndent
                sameIndent = lineDashes
            }
            end = start - 1
        }
        return keys.asReversed()
    }

    /** Handles one line; returns the indent of the key whose block scalar (`|`, `>`) the next lines belong to, or -1. */
    private fun parseLine(
        text: CharSequence,
        start: Int,
        end: Int,
        stack: ArrayList<Pair<Int, String>>,
        blockIndent: Int,
        keyFilter: (List<String>) -> Boolean,
        baseOffset: Int,
        result: MutableList<ImageValue>,
    ): Int {
        var i = start
        while (i < end && text[i] == ' ') i++
        if (i >= end || text[i] == '#') return blockIndent
        val indent = i - start
        if (blockIndent >= 0 && indent > blockIndent) return blockIndent
        if (indent == 0 && (text.startsWith("---", i) || text.startsWith("...", i)) && isBlank(text, i + 3, end)) {
            stack.clear()
            return -1
        }
        while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
        // `- ` items: each dash opens a nested level; the item inherits the enclosing key
        var itemIndent = indent
        while (i < end && text[i] == '-' && (i + 1 == end || text[i + 1] == ' ')) {
            i++
            while (i < end && text[i] == ' ') i++
            itemIndent = i - start
        }
        if (i >= end) return -1
        val keys = stack.map { it.second }
        val key = keyAt(text, i, end)
        if (key == null) {
            // a sequence item value; plain continuation lines are ignored
            if (itemIndent != indent) scalar(text, i, end)?.let { range -> emit(text, keys, range, keyFilter, baseOffset, result) }
            return -1
        }
        val (name, valueStart) = key
        val path = keys + name
        var v = valueStart
        while (v < end && text[v] == ' ') v++
        when {
            v >= end || text[v] == '#' -> stack += itemIndent to name
            text[v] == '|' || text[v] == '>' -> return itemIndent
            text[v] == '{' -> flow(text, v + 1, end, path, keyFilter, baseOffset, result)
            text[v] == '[' -> {}
            else -> scalar(text, v, end)?.let { range -> emit(text, path, range, keyFilter, baseOffset, result) }
        }
        return -1
    }

    /** The key starting at [start] and the index just past its `:`, or null when the line holds no `key:`. */
    private fun keyAt(text: CharSequence, start: Int, end: Int): Pair<String, Int>? {
        val c = text[start]
        if (c == '"' || c == '\'') {
            var i = start + 1
            while (i < end && text[i] != c) {
                if (c == '"' && text[i] == '\\') i++
                i++
            }
            if (i >= end) return null
            var colon = i + 1
            while (colon < end && text[colon] == ' ') colon++
            if (colon >= end || text[colon] != ':') return null
            return text.subSequence(start + 1, i).toString() to colon + 1
        }
        if (c == '{' || c == '[' || c == '#') return null
        var i = start
        while (i < end) {
            if (text[i] == ':' && (i + 1 == end || text[i + 1] == ' ' || text[i + 1] == '\t')) {
                val name = text.subSequence(start, i).toString().trimEnd()
                return if (name.isEmpty()) null else name to i + 1
            }
            if (text[i] == ' ' && i + 1 < end && text[i + 1] == '#') return null
            i++
        }
        return null
    }

    /** The range of the single-line scalar starting at [start] (inside quotes, without a trailing comment), or null. */
    private fun scalar(text: CharSequence, start: Int, end: Int): TextRange? {
        val c = text[start]
        if (c == '"' || c == '\'') {
            var i = start + 1
            while (i < end && text[i] != c) {
                if (c == '"' && text[i] == '\\') i++
                i++
            }
            return TextRange(start + 1, minOf(i, end))
        }
        if (c == '&' || c == '*' || c == '!' || c == '|' || c == '>' || c == '{' || c == '[') return null
        var e = start
        while (e < end && !(text[e] == '#' && e > start && text[e - 1] == ' ')) e++
        while (e > start && text[e - 1].isWhitespace()) e--
        return if (e > start) TextRange(start, e) else null
    }

    /** `{ key: value, … }` on one line: emits the entries under [path]. */
    private fun flow(
        text: CharSequence,
        start: Int,
        end: Int,
        path: List<String>,
        keyFilter: (List<String>) -> Boolean,
        baseOffset: Int,
        result: MutableList<ImageValue>,
    ) {
        var i = start
        while (i < end) {
            while (i < end && (text[i] == ' ' || text[i] == ',')) i++
            if (i >= end || text[i] == '}') return
            var colon = i
            while (colon < end && text[colon] != ':' && text[colon] != ',' && text[colon] != '}') colon++
            if (colon >= end || text[colon] != ':') return
            val name = text.subSequence(i, colon).toString().trim().removeSurrounding("\"").removeSurrounding("'")
            var v = colon + 1
            while (v < end && text[v] == ' ') v++
            if (v >= end) return
            val q = text[v]
            var valueEnd: Int
            val range: TextRange
            if (q == '"' || q == '\'') {
                valueEnd = v + 1
                while (valueEnd < end && text[valueEnd] != q) valueEnd++
                range = TextRange(v + 1, minOf(valueEnd, end))
                valueEnd++
            } else {
                valueEnd = v
                while (valueEnd < end && text[valueEnd] != ',' && text[valueEnd] != '}') valueEnd++
                var e = valueEnd
                while (e > v && text[e - 1] == ' ') e--
                range = TextRange(v, e)
            }
            if (!range.isEmpty) emit(text, path + name, range, keyFilter, baseOffset, result)
            i = valueEnd
        }
    }

    private fun emit(
        text: CharSequence,
        path: List<String>,
        range: TextRange,
        keyFilter: (List<String>) -> Boolean,
        baseOffset: Int,
        result: MutableList<ImageValue>,
    ) {
        if (path.isEmpty() || range.isEmpty || !keyFilter(path)) return
        result += ImageValue(path.joinToString("."), range.subSequence(text).toString(), range.shiftRight(baseOffset))
    }

    private fun isBlank(text: CharSequence, start: Int, end: Int) = (start until end).all { text[it].isWhitespace() }

    private fun unescape(value: CharSequence): String {
        if ('\\' !in value) return value.toString()
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) i++
            out.append(value[i])
            i++
        }
        return out.toString()
    }
}
