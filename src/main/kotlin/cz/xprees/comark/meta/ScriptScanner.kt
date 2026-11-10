package cz.xprees.comark.meta

/**
 * Minimal lexical scanning of TypeScript / JavaScript text for [VueSfcParser]: skipping strings, comments and bracketed groups,
 * splitting object literals and type literals into members. It is deliberately tolerant (a broken file yields fewer results,
 * never an exception) because it runs on whatever is in the editor, and it needs no TypeScript or Vue plugin.
 */
internal object ScriptScanner {
    fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'

    fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    fun startsWith(text: CharSequence, at: Int, prefix: String): Boolean {
        if (at < 0 || at + prefix.length > text.length) return false
        for (k in prefix.indices) if (text[at + k] != prefix[k]) return false
        return true
    }

    fun indexOf(text: CharSequence, needle: String, from: Int, to: Int = text.length): Int {
        var i = from
        val last = to - needle.length
        while (i <= last) {
            if (startsWith(text, i, needle)) return i
            i++
        }
        return -1
    }

    /** True when [word] is at [at] as a whole identifier. */
    fun isWordAt(text: CharSequence, at: Int, word: String, end: Int = text.length): Boolean {
        if (at + word.length > end || !startsWith(text, at, word)) return false
        if (at > 0 && (isIdentPart(text[at - 1]) || text[at - 1] == '.')) return false
        return at + word.length >= end || !isIdentPart(text[at + word.length])
    }

    fun isCommentStart(text: CharSequence, i: Int, end: Int) = i + 1 < end && text[i] == '/' && (text[i + 1] == '/' || text[i + 1] == '*')

    /** Index just past the string literal whose quote is at [start] (`'`, `"` or a template literal with `${}` parts). */
    fun skipString(text: CharSequence, start: Int, end: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < end) {
            val c = text[i]
            when {
                c == '\\' -> i += 2
                c == quote -> return i + 1
                quote == '`' && c == '$' && i + 1 < end && text[i + 1] == '{' -> {
                    val close = findClosing(text, i + 1, end)
                    i = if (close < 0) end else close + 1
                }

                quote != '`' && c == '\n' -> return i
                else -> i++
            }
        }
        return end
    }

    /** Index just past the comment at [start] (`//` or block comment), or [start] when there is none. */
    fun skipComment(text: CharSequence, start: Int, end: Int): Int {
        if (!isCommentStart(text, start, end)) return start
        return if (text[start + 1] == '/') {
            var i = start + 2
            while (i < end && text[i] != '\n') i++
            i
        } else {
            val close = indexOf(text, "*/", start + 2, end)
            if (close < 0) end else close + 2
        }
    }

    fun skipTrivia(text: CharSequence, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            when {
                text[i].isWhitespace() -> i++
                isCommentStart(text, i, end) -> i = skipComment(text, i, end)
                else -> return i
            }
        }
        return i
    }

    /** Index after the last non-whitespace character before [end] (but not before [start]). */
    fun trimEnd(text: CharSequence, start: Int, end: Int): Int {
        var e = end
        while (e > start && text[e - 1].isWhitespace()) e--
        return e
    }

    /**
     * Index of the bracket closing the one at [open] (`{`, `(`, `[` or a type argument list `<`), skipping strings, comments and
     * nested groups; -1 when it is not closed before [end]. Inside `<...>` the `>` of an arrow (`=>`) does not count.
     */
    fun findClosing(text: CharSequence, open: Int, end: Int): Int {
        val openChar = text[open]
        val closeChar = when (openChar) {
            '{' -> '}'
            '(' -> ')'
            '[' -> ']'
            '<' -> '>'
            else -> return -1
        }
        var i = open + 1
        while (i < end) {
            val c = text[i]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    i = skipString(text, i, end)
                    continue
                }

                isCommentStart(text, i, end) -> {
                    i = skipComment(text, i, end)
                    continue
                }

                c == closeChar -> {
                    if (closeChar == '>' && text[i - 1] == '=') {
                        i++
                        continue
                    }
                    return i
                }

                c == '{' || c == '(' || c == '[' || (c == '<' && openChar == '<') -> {
                    val close = findClosing(text, i, end)
                    if (close < 0) return -1
                    i = close + 1
                    continue
                }

                c == '}' || c == ')' || c == ']' -> return -1
                else -> i++
            }
        }
        return -1
    }

    /** True when the `<` at [at] opens a type argument list (`PropType<...>`), not a comparison. */
    private fun isTypeArgumentStart(text: CharSequence, at: Int, end: Int): Boolean {
        if (at == 0 || !isIdentPart(text[at - 1]) || at + 1 >= end) return false
        val next = text[at + 1]
        return isIdentStart(next) || next == '{' || next == '(' || next == '[' || next == '\'' || next == '"' || next == '`'
    }

    /** End of an expression starting at [start]: the next top-level `,` (or [end]). */
    fun expressionEnd(text: CharSequence, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val c = text[i]
            when {
                c == ',' -> return i
                c == '"' || c == '\'' || c == '`' -> i = skipString(text, i, end)
                isCommentStart(text, i, end) -> i = skipComment(text, i, end)
                c == '{' || c == '(' || c == '[' || (c == '<' && isTypeArgumentStart(text, i, end)) -> {
                    val close = findClosing(text, i, end)
                    i = if (close < 0) end else close + 1
                }

                else -> i++
            }
        }
        return end
    }

    /** Top-level comma separated ranges of `[start, end)`, each trimmed; empty ranges are dropped. */
    fun splitTopLevel(text: CharSequence, start: Int, end: Int): List<IntRange> {
        val parts = ArrayList<IntRange>()
        var i = start
        while (i < end) {
            val s = skipTrivia(text, i, end)
            if (s >= end) break
            val e = expressionEnd(text, s, end)
            val trimmed = trimEnd(text, s, e)
            if (trimmed > s) parts += s until trimmed
            i = e + 1
        }
        return parts
    }

    /** Splits a type expression on top-level [separator] (`&` or `|`). */
    fun splitType(text: CharSequence, start: Int, end: Int, separator: Char): List<IntRange> {
        val parts = ArrayList<IntRange>()
        var partStart = start
        var i = start
        while (i < end) {
            val c = text[i]
            when {
                c == separator -> {
                    addTrimmed(text, partStart, i, parts)
                    i++
                    partStart = i
                }

                c == '"' || c == '\'' || c == '`' -> i = skipString(text, i, end)
                isCommentStart(text, i, end) -> i = skipComment(text, i, end)
                c == '{' || c == '(' || c == '[' || c == '<' -> {
                    val close = findClosing(text, i, end)
                    i = if (close < 0) end else close + 1
                }

                else -> i++
            }
        }
        addTrimmed(text, partStart, end, parts)
        return parts
    }

    private fun addTrimmed(text: CharSequence, start: Int, end: Int, into: MutableList<IntRange>) {
        val s = skipTrivia(text, start, end)
        val e = trimEnd(text, s, end)
        if (e > s) into += s until e
    }

    // ---- Doc comments ---------------------------------------------------------------------------------------------

    /** A doc comment split into its description and `@tags` (tag name -> text). */
    class DocComment(val description: String?, val tags: Map<String, String>)

    /** Parses a block comment (`/** ... */`) or consecutive `//` line comments into description and tags. */
    fun parseDoc(raw: String): DocComment {
        val body = when {
            raw.startsWith("/*") -> raw.removePrefix("/**").removePrefix("/*").removeSuffix("*/")
            else -> raw.lines().joinToString("\n") { it.trim().removePrefix("//") }
        }
        val description = StringBuilder()
        val tags = LinkedHashMap<String, String>()
        var currentTag: String? = null
        val tagText = StringBuilder()
        fun flushTag() {
            currentTag?.let { tags.putIfAbsent(it, tagText.toString().trim()) }
            currentTag = null
            tagText.setLength(0)
        }
        for (rawLine in body.lines()) {
            val line = rawLine.trim().removePrefix("*").trim()
            if (line.startsWith("@")) {
                flushTag()
                val name = line.drop(1).takeWhile { !it.isWhitespace() }
                currentTag = name
                tagText.append(line.drop(1 + name.length).trim())
            } else if (currentTag != null) {
                if (tagText.isNotEmpty()) tagText.append('\n')
                tagText.append(line)
            } else {
                if (description.isNotEmpty() || line.isNotEmpty()) description.append(line).append('\n')
            }
        }
        flushTag()
        return DocComment(description.toString().trim().ifEmpty { null }, tags)
    }

    /** Collapses whitespace runs, which makes multi-line type texts readable in a single line. */
    fun normalize(text: CharSequence): String = text.toString().trim().replace(Regex("\\s+"), " ")

    // ---- Members of object and type literals ----------------------------------------------------------------------

    /** `key: value` (or a shorthand `key`) of an object literal; value offsets are -1 for a shorthand. */
    class ObjectEntry(val key: String, val keyStart: Int, val valueStart: Int, val valueEnd: Int, val doc: String?) {
        fun value(text: CharSequence): String? = if (valueStart < 0) null else text.subSequence(valueStart, valueEnd).toString().trim()
    }

    /** Entries of the object literal whose braces are at [open] and [close]. */
    fun objectEntries(text: CharSequence, open: Int, close: Int): List<ObjectEntry> {
        val entries = ArrayList<ObjectEntry>()
        var i = open + 1
        var doc: String? = null
        while (i < close) {
            val c = text[i]
            when {
                c.isWhitespace() || c == ',' -> {
                    i++
                    continue
                }

                isCommentStart(text, i, close) -> {
                    val e = skipComment(text, i, close)
                    doc = mergeDoc(doc, text.subSequence(i, e).toString())
                    i = e
                    continue
                }
            }
            val keyStart: Int
            val key: String?
            when {
                startsWith(text, i, "...") -> {
                    i = expressionEnd(text, i, close)
                    doc = null
                    continue
                }

                c == '"' || c == '\'' -> {
                    val e = skipString(text, i, close)
                    key = text.subSequence(i + 1, maxOf(i + 1, e - 1)).toString()
                    keyStart = i + 1
                    i = e
                }

                isIdentStart(c) -> {
                    var e = i
                    while (e < close && isIdentPart(text[e])) e++
                    key = text.subSequence(i, e).toString()
                    keyStart = i
                    i = e
                }

                else -> {
                    // computed keys and anything unexpected
                    i = expressionEnd(text, i, close)
                    doc = null
                    continue
                }
            }
            val after = skipTrivia(text, i, close)
            when {
                after < close && text[after] == ':' -> {
                    val valueStart = skipTrivia(text, after + 1, close)
                    val end = expressionEnd(text, valueStart, close)
                    entries += ObjectEntry(key, keyStart, valueStart, trimEnd(text, valueStart, end), doc)
                    i = end
                }

                after < close && (text[after] == '(' || text[after] == '<') -> {
                    // method shorthand `key() { ... }`
                    val end = expressionEnd(text, after, close)
                    entries += ObjectEntry(key, keyStart, after, trimEnd(text, after, end), doc)
                    i = end
                }

                else -> {
                    entries += ObjectEntry(key, keyStart, -1, -1, doc)
                    i = expressionEnd(text, after, close)
                }
            }
            doc = null
        }
        return entries
    }

    /** A member of a type literal / interface body: `name?: Type` or a method signature `name?(args): Ret`. */
    class TypeMember(val name: String, val nameStart: Int, val optional: Boolean, val type: String?, val isMethod: Boolean, val doc: String?)

    /** Members of the type literal (or interface body) whose braces are at [open] and [close]. */
    fun typeMembers(text: CharSequence, open: Int, close: Int): List<TypeMember> {
        val members = ArrayList<TypeMember>()
        var i = open + 1
        var doc: String? = null
        while (i < close) {
            val c = text[i]
            when {
                c.isWhitespace() || c == ';' || c == ',' -> {
                    i++
                    continue
                }

                isCommentStart(text, i, close) -> {
                    val e = skipComment(text, i, close)
                    doc = mergeDoc(doc, text.subSequence(i, e).toString())
                    i = e
                    continue
                }
            }
            if (isWordAt(text, i, "readonly", close)) {
                val next = skipTrivia(text, i + "readonly".length, close)
                if (next < close && (isIdentStart(text[next]) || text[next] == '"' || text[next] == '\'')) i = next
            }
            val ch = text[i]
            val keyStart: Int
            val key: String
            when {
                ch == '"' || ch == '\'' -> {
                    val e = skipString(text, i, close)
                    key = text.subSequence(i + 1, maxOf(i + 1, e - 1)).toString()
                    keyStart = i + 1
                    i = e
                }

                isIdentStart(ch) -> {
                    var e = i
                    while (e < close && isIdentPart(text[e])) e++
                    key = text.subSequence(i, e).toString()
                    keyStart = i
                    i = e
                }

                else -> {
                    // index signatures `[key: string]: T`, call signatures, garbage
                    i = memberEnd(text, i, close)
                    doc = null
                    continue
                }
            }
            var j = skipTrivia(text, i, close)
            var optional = false
            if (j < close && text[j] == '?') {
                optional = true
                j = skipTrivia(text, j + 1, close)
            }
            when {
                j < close && text[j] == ':' -> {
                    val typeStart = skipTrivia(text, j + 1, close)
                    val end = memberEnd(text, typeStart, close)
                    val type = normalize(text.subSequence(typeStart, end)).removePrefix("| ").removeSuffix(";").removeSuffix(",").trim()
                    members += TypeMember(key, keyStart, optional, type.ifEmpty { null }, false, doc)
                    i = end
                }

                j < close && (text[j] == '(' || text[j] == '<') -> {
                    val end = memberEnd(text, j, close)
                    members += TypeMember(key, keyStart, optional, normalize(text.subSequence(j, end)), true, doc)
                    i = end
                }

                else -> {
                    members += TypeMember(key, keyStart, optional, null, false, doc)
                    i = memberEnd(text, j, close)
                }
            }
            doc = null
        }
        return members
    }

    /**
     * End of a type member starting at [start]: a top-level `;` or `,`, or a line break that does not continue the type (a type
     * spanning lines continues when a line ends with `|`, `&`, `=>`, `:` or the next one starts with `|`, `&`, `.`, `=>`).
     */
    fun memberEnd(text: CharSequence, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val c = text[i]
            when {
                c == ';' || c == ',' -> return i
                c == '\n' -> {
                    val sofar = text.subSequence(start, i).toString().trim()
                    if (sofar.isEmpty() || sofar.endsWith("|") || sofar.endsWith("&") || sofar.endsWith("=>") || sofar.endsWith(":")) {
                        i++
                        continue
                    }
                    var k = i
                    while (k < end && text[k].isWhitespace()) k++
                    if (k < end && (text[k] == '|' || text[k] == '&' || text[k] == '.' || startsWith(text, k, "=>"))) {
                        i = k
                        continue
                    }
                    return i
                }

                c == '"' || c == '\'' || c == '`' -> i = skipString(text, i, end)
                isCommentStart(text, i, end) -> {
                    // a doc comment starts the next member
                    if (startsWith(text, i, "/**") && text.subSequence(start, i).isNotBlank()) return i
                    i = skipComment(text, i, end)
                }

                c == '{' || c == '(' || c == '[' || c == '<' -> {
                    val close = findClosing(text, i, end)
                    i = if (close < 0) end else close + 1
                }

                else -> i++
            }
        }
        return end
    }

    /** Doc comments above a member: a block comment replaces what came before, consecutive line comments add up. */
    private fun mergeDoc(previous: String?, comment: String): String =
        if (comment.startsWith("//") && previous != null && previous.startsWith("//")) "$previous\n$comment" else comment
}
