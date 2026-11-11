package cz.xprees.comark.parser

/**
 * Finds Comark inline syntax in inline Markdown text (the content of a paragraph, heading, table cell or a component's
 * `[content]`): inline components (`:name`, `:name[content]{attrs}`), spans (`[text]`, `[text]{attrs}`), attribute groups after
 * inline syntax or at the end of a line, and `{{ binding }}` expressions.
 *
 * The rules mirror the reference implementation (comark `components.ts`, `attributes.ts`, `binding.ts`), which runs as
 * markdown-it inline rules. Code spans, inline links / images (their destination), reference links, autolinks, bare URLs and
 * inline HTML are skipped, so nothing inside them is ever reported. Emphasis delimiters are tracked just well enough to know
 * whether a `{` directly follows a closing `**` / `_` / `~~`, which is when comark attaches the attributes to the emphasis.
 *
 * This is plain text processing (no PSI), so highlighting, completion and navigation can all share it. Callers that have a
 * Markdown PSI tree pass the ranges of constructs the Markdown parser already recognised (code spans, inline HTML, ...) as
 * [scan]'s `opaque` ranges; they are skipped like the scanner's own code spans.
 *
 * Deliberate differences from comark, which matter only for unfinished text: an unclosed `[` or `{` is never a span or an
 * attribute group (comark's streaming mode auto-closes them), and an attribute group never spans lines.
 */
object ComarkInlineScanner {
    /** Characters allowed right before the `:` of an inline component (comark's `ALLOWED_PREV_CHARS`). */
    private const val ALLOWED_BEFORE_COMPONENT = " \t\n*_[]})>"

    enum class Kind {
        /** The `:` of an inline component. */
        COMPONENT_MARKER,
        COMPONENT_NAME,
        CONTENT_OPEN,
        COMPONENT_CONTENT,
        CONTENT_CLOSE,
        SPAN_OPEN,
        SPAN_CONTENT,
        SPAN_CLOSE,
        ATTR_LBRACE,
        ATTR_RBRACE,
        ATTR_CLASS,
        ATTR_ID,
        ATTR_NAME,
        ATTR_EQ,
        ATTR_VALUE,
        BINDING_OPEN,
        BINDING_EXPRESSION,
        BINDING_CLOSE,
    }

    /** A typed range `[start, end)` of the scanned text. */
    data class Token(val kind: Kind, val start: Int, val end: Int)

    /** What an attribute group applies to. */
    enum class AttributeTarget {
        /** The inline component it follows. */
        COMPONENT,

        /** The span it follows. */
        SPAN,

        /** The inline element it directly follows: emphasis, code span, link, image, inline HTML, ... */
        PRECEDING_INLINE,

        /** Trailing group after text at the end of a line: the paragraph, heading or list item (or the text before it). */
        BLOCK,
    }

    sealed class Element {
        abstract val start: Int
        abstract val end: Int
    }

    /** `{...}` attribute group; [scan] holds its parts (see [ComarkSyntax.scanAttributes]). */
    class Attributes(val scan: ComarkSyntax.AttrScan, val target: AttributeTarget) : Element() {
        override val start: Int get() = scan.tokens.first().start
        override val end: Int get() = scan.end
    }

    /**
     * `:name`, `:name[content]`, `:name{attrs}` or `:name[content]{attrs}`. [contentStart] / [contentEnd] delimit the text
     * between the brackets (both -1 without content); [end] includes the attributes.
     */
    class InlineComponent(
        override val start: Int,
        val nameStart: Int,
        val nameEnd: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val attributes: Attributes?,
    ) : Element() {
        val name: IntRange get() = nameStart until nameEnd
        val hasContent: Boolean get() = contentStart >= 0
        override val end: Int get() = attributes?.end ?: if (hasContent) contentEnd + 1 else nameEnd
    }

    /** `[content]` or `[content]{attrs}`; [contentEnd] is the index of the closing `]`. */
    class Span(override val start: Int, val contentEnd: Int, val attributes: Attributes?) : Element() {
        val contentStart: Int get() = start + 1
        override val end: Int get() = attributes?.end ?: (contentEnd + 1)
    }

    /** `{{ expression }}` or `{{ expression || default }}`; [expressionStart] / [expressionEnd] exclude the braces and padding. */
    class Binding(override val start: Int, override val end: Int, val expressionStart: Int, val expressionEnd: Int) : Element()

    /** Scan result: the elements in document order, nested ones (inside a span or component content) included. */
    class Result(val elements: List<Element>) {
        /** Every typed range, in document order. */
        val tokens: List<Token> by lazy(LazyThreadSafetyMode.PUBLICATION) { elements.flatMap(::tokensOf).sortedBy { it.start } }

        val components: List<InlineComponent> get() = elements.filterIsInstance<InlineComponent>()

        val spans: List<Span> get() = elements.filterIsInstance<Span>()

        /** The innermost element whose range contains [offset] (an element's end offset counts as inside). */
        fun elementAt(offset: Int): Element? = elements.filter { offset >= it.start && offset <= it.end }.minByOrNull { it.end - it.start }

        /** The token containing [offset], preferring the one starting there. */
        fun tokenAt(offset: Int): Token? = tokens.firstOrNull { offset >= it.start && offset < it.end }
    }

    private val EMPTY = Result(emptyList())

    /**
     * Scans `text[from, to)` as one inline content. [opaque] ranges (absolute offsets, any order) are skipped as single
     * non-text inline elements, the way code spans are.
     */
    fun scan(text: CharSequence, from: Int = 0, to: Int = text.length, opaque: List<IntRange> = emptyList()): Result {
        if (from >= to) return EMPTY
        val elements = ArrayList<Element>()
        Scanner(text, to, opaque.filter { !it.isEmpty() }.sortedBy { it.first }, elements).scanLevel(from, to, inLink = false, afterSyntax = false)
        if (elements.isEmpty()) return EMPTY
        elements.sortBy { it.start }
        return Result(elements)
    }

    private fun tokensOf(element: Element): List<Token> = when (element) {
        is InlineComponent -> buildList {
            add(Token(Kind.COMPONENT_MARKER, element.start, element.start + 1))
            add(Token(Kind.COMPONENT_NAME, element.nameStart, element.nameEnd))
            if (element.hasContent) {
                add(Token(Kind.CONTENT_OPEN, element.contentStart - 1, element.contentStart))
                if (element.contentEnd > element.contentStart) add(Token(Kind.COMPONENT_CONTENT, element.contentStart, element.contentEnd))
                add(Token(Kind.CONTENT_CLOSE, element.contentEnd, element.contentEnd + 1))
            }
            element.attributes?.let { addAll(tokensOf(it)) }
        }

        is Span -> buildList {
            add(Token(Kind.SPAN_OPEN, element.start, element.start + 1))
            if (element.contentEnd > element.contentStart) add(Token(Kind.SPAN_CONTENT, element.contentStart, element.contentEnd))
            add(Token(Kind.SPAN_CLOSE, element.contentEnd, element.contentEnd + 1))
            element.attributes?.let { addAll(tokensOf(it)) }
        }

        is Attributes -> element.scan.tokens.mapNotNull { token ->
            val kind = when (token.part) {
                ComarkSyntax.AttrPart.LBRACE -> Kind.ATTR_LBRACE
                ComarkSyntax.AttrPart.RBRACE -> Kind.ATTR_RBRACE
                ComarkSyntax.AttrPart.CLASS -> Kind.ATTR_CLASS
                ComarkSyntax.AttrPart.ID -> Kind.ATTR_ID
                ComarkSyntax.AttrPart.NAME -> Kind.ATTR_NAME
                ComarkSyntax.AttrPart.EQ -> Kind.ATTR_EQ
                ComarkSyntax.AttrPart.VALUE -> Kind.ATTR_VALUE
                ComarkSyntax.AttrPart.TEXT -> null
            }
            kind?.let { Token(it, token.start, token.end) }
        }

        is Binding -> listOf(
            Token(Kind.BINDING_OPEN, element.start, element.start + 2),
            Token(Kind.BINDING_EXPRESSION, element.expressionStart, element.expressionEnd),
            Token(Kind.BINDING_CLOSE, element.end - 2, element.end),
        ).filter { it.end > it.start }
    }

    // ---- Scanning -----------------------------------------------------------------------------------------------------

    private class Delimiter(val char: Char)

    private enum class DelimiterRole { CLOSER, UNPAIRED_CLOSER, OTHER }

    private class Scanner(
        private val text: CharSequence,
        private val limit: Int,
        private val opaque: List<IntRange>,
        private val out: MutableList<Element>,
    ) {
        /** End offset of the last brace group rejected as text (comark's `rejectedUntil`); shared like comark's inline state. */
        private var rejectedUntil = -1

        /**
         * Scans one inline level `[from, to)`. [afterSyntax] tells whether the level starts right after a non-text token (the
         * opening of a span, component content or link label), which lets a `{` at its very start be attributes.
         */
        fun scanLevel(from: Int, to: Int, inLink: Boolean, afterSyntax: Boolean) {
            // `{` exactly at this offset follows a non-text inline token (comark: the last token is not text)
            var syntaxEnd = if (afterSyntax) from else -1
            // `{` exactly at this offset follows a closing emphasis delimiter
            var closerEnd = -1
            // `{` exactly at this offset follows a delimiter run that could close but has no opener: comark restores it as text
            var unpairedCloserEnd = -1
            val openers = ArrayList<Delimiter>()
            var i = from
            while (i < to) {
                val opaqueEnd = opaqueEndAt(i, to)
                if (opaqueEnd > i) {
                    i = opaqueEnd
                    syntaxEnd = i
                    continue
                }
                when (val c = text[i]) {
                    '\\' -> i = minOf(i + 2, to)

                    '`' -> {
                        val run = runLength(i, to, '`')
                        val close = findBacktickClose(i + run, to, run)
                        if (close >= 0) {
                            i = close + run
                            syntaxEnd = i
                        } else {
                            i += run
                        }
                    }

                    '*', '_', '~' -> {
                        val run = runLength(i, to, c)
                        if (c == '~' && run != 2) {
                            i += run
                            continue
                        }
                        when (handleDelimiter(i, run, from, to, c, openers)) {
                            DelimiterRole.CLOSER -> closerEnd = i + run
                            DelimiterRole.UNPAIRED_CLOSER -> unpairedCloserEnd = i + run
                            DelimiterRole.OTHER -> {}
                        }
                        i += run
                    }

                    '<' -> {
                        val end = autolinkOrHtmlEnd(i, to)
                        if (end > i) {
                            i = end
                            syntaxEnd = i
                        } else {
                            i++
                        }
                    }

                    'h', 'w' -> {
                        val end = if (i == from || isBareUrlBoundary(text[i - 1])) bareUrlEnd(i, to) else -1
                        if (end > i) {
                            i = end
                            syntaxEnd = i
                        } else {
                            i++
                        }
                    }

                    ':' -> {
                        val end = inlineComponent(i, from, to, inLink)
                        if (end > i) {
                            i = end
                            syntaxEnd = i
                        } else {
                            i++
                        }
                    }

                    '[' -> {
                        val end = bracket(i, to, inLink)
                        if (end > i) {
                            i = end
                            syntaxEnd = i
                        } else {
                            i++
                        }
                    }

                    '{' -> {
                        val end = if (i == unpairedCloserEnd) i else brace(i, from, to, afterSyntax = i == syntaxEnd, afterCloser = i == closerEnd)
                        if (end > i) {
                            i = end
                            syntaxEnd = i
                        } else {
                            i++
                        }
                    }

                    else -> i++
                }
            }
        }

        // ---- Comark constructs --------------------------------------------------------------------------------------

        /** `:name[content]{attrs}` at [i]; returns its end, or [i] when there is none. */
        private fun inlineComponent(i: Int, from: Int, to: Int, inLink: Boolean): Int {
            if (i > from && text[i - 1] !in ALLOWED_BEFORE_COMPONENT) return i
            val nameStart = i + 1
            val nameLength = ComarkSyntax.inlineNameLength(text, nameStart, to)
            if (nameLength == 0) return i
            val nameEnd = nameStart + nameLength
            var end = nameEnd
            var contentStart = -1
            var contentEnd = -1
            if (end < to && text[end] == '[') {
                val close = ComarkSyntax.findClosingBracket(text, end, to)
                if (close >= 0) {
                    contentStart = end + 1
                    contentEnd = close
                    scanLevel(contentStart, contentEnd, inLink, afterSyntax = true)
                    end = close + 1
                }
            }
            val attributes = attributesRightAfter(end, to, AttributeTarget.COMPONENT)
            out += InlineComponent(i, nameStart, nameEnd, contentStart, contentEnd, attributes)
            return attributes?.end ?: end
        }

        /** A `[` at [i]: span, inline link / image, reference link or footnote reference; returns its end or [i]. */
        private fun bracket(i: Int, to: Int, inLink: Boolean): Int {
            val close = ComarkSyntax.findClosingBracket(text, i, to)
            if (close < 0) return i
            // `[^note]` is a footnote reference in Nuxt Content (remark), never a span
            if (text[i + 1] == '^' && close > i + 2) return close + 1
            val next = if (close + 1 < to) text[close + 1] else '\n'
            when (next) {
                '(' -> {
                    val end = inlineLinkEnd(close + 1, to)
                    if (end < 0) return i
                    scanLevel(i + 1, close, inLink = true, afterSyntax = true)
                    return end
                }

                '[' -> {
                    val labelClose = ComarkSyntax.findClosingBracket(text, close + 1, to)
                    if (labelClose < 0) return i
                    scanLevel(i + 1, close, inLink = true, afterSyntax = true)
                    return labelClose + 1
                }
            }
            // inside a link label a bare `[text]` stays literal; only an explicit `[text]{attrs}` is a span
            if (inLink && next != '{') return i
            scanLevel(i + 1, close, inLink, afterSyntax = true)
            val attributes = attributesRightAfter(close + 1, to, AttributeTarget.SPAN)
            out += Span(i, close, attributes)
            return attributes?.end ?: (close + 1)
        }

        /** A `{` at [i]: a binding or an attribute group; returns its end or [i]. */
        private fun brace(i: Int, from: Int, to: Int, afterSyntax: Boolean, afterCloser: Boolean): Int {
            if (i + 1 < to && text[i + 1] == '{') return binding(i, to)
            // Vue mustache `{{ }}` and template `${ }` are not attributes
            if (i > from && (text[i - 1] == '{' || text[i - 1] == '$')) return i
            val target = when {
                afterSyntax -> AttributeTarget.PRECEDING_INLINE
                afterCloser -> AttributeTarget.PRECEDING_INLINE
                else -> AttributeTarget.BLOCK
            }
            val attributes = attributes(i, to, afterText = !afterSyntax, afterCloser = afterCloser, target = target) ?: return i
            out += attributes
            return attributes.end
        }

        /** Attributes directly after an inline component or span (always accepted when well-formed). */
        private fun attributesRightAfter(at: Int, to: Int, target: AttributeTarget): Attributes? {
            if (at >= to || text[at] != '{') return null
            if (at + 1 < to && text[at + 1] == '{') return null
            if (text[at - 1] == '$') return null
            return attributes(at, to, afterText = false, afterCloser = false, target = target)
        }

        /**
         * Comark `comark_inline_props`: after a non-text token or a closing delimiter a group always applies; after text it applies
         * only when nothing but blanks follows it on its line.
         */
        private fun attributes(at: Int, to: Int, afterText: Boolean, afterCloser: Boolean, target: AttributeTarget): Attributes? {
            if (afterText && !afterCloser && at < rejectedUntil) return null
            val scan = ComarkSyntax.scanAttributes(text, at, to) ?: return null
            if (!scan.closed) return null
            if (afterText && !afterCloser) {
                // comark checks the rest of the whole inline source, so a group closing a span's or component's content (followed
                // by its `]`) is text
                var j = scan.end
                while (j < limit && (text[j] == ' ' || text[j] == '\t' || text[j] == '\r')) j++
                if (j < limit && text[j] != '\n') {
                    rejectedUntil = scan.end
                    return null
                }
            }
            return Attributes(scan, target)
        }

        /** `{{ expression }}` (comark `binding.ts`); returns its end or [i]. */
        private fun binding(i: Int, to: Int): Int {
            var close = i + 2
            while (close < to - 1 && !(text[close] == '}' && text[close + 1] == '}')) close++
            if (close >= to - 1) return i
            var expressionStart = i + 2
            var expressionEnd = close
            while (expressionStart < expressionEnd && text[expressionStart].isWhitespace()) expressionStart++
            while (expressionEnd > expressionStart && text[expressionEnd - 1].isWhitespace()) expressionEnd--
            if (expressionStart == expressionEnd) return i
            // the value before a `||` default must not be empty either
            val separator = indexOf("||", expressionStart, expressionEnd)
            if (separator >= 0 && text.subSequence(expressionStart, separator).isBlank()) return i
            out += Binding(i, close + 2, expressionStart, expressionEnd)
            return close + 2
        }

        // ---- Markdown constructs that hide Comark syntax ---------------------------------------------------------------

        /**
         * Tracks emphasis delimiter runs (CommonMark flanking rules, simplified pairing) and tells whether the run at [i] closes an
         * earlier opener of the same character.
         */
        private fun handleDelimiter(i: Int, run: Int, from: Int, to: Int, c: Char, openers: MutableList<Delimiter>): DelimiterRole {
            val prev = if (i > from) text[i - 1] else '\n'
            val next = if (i + run < to) text[i + run] else '\n'
            val leftFlanking = !isWhitespace(next) && (!isPunctuation(next) || isWhitespace(prev) || isPunctuation(prev))
            val rightFlanking = !isWhitespace(prev) && (!isPunctuation(prev) || isWhitespace(next) || isPunctuation(next))
            val canOpen: Boolean
            val canClose: Boolean
            if (c == '_') {
                canOpen = leftFlanking && (!rightFlanking || isPunctuation(prev))
                canClose = rightFlanking && (!leftFlanking || isPunctuation(next))
            } else {
                canOpen = leftFlanking
                canClose = rightFlanking
            }
            if (canClose) {
                val opener = openers.indexOfLast { it.char == c }
                if (opener >= 0) {
                    while (openers.size > opener) openers.removeAt(openers.size - 1)
                    return DelimiterRole.CLOSER
                }
            }
            if (canOpen) {
                openers += Delimiter(c)
                return DelimiterRole.OTHER
            }
            return if (canClose) DelimiterRole.UNPAIRED_CLOSER else DelimiterRole.OTHER
        }

        /** Index of a closing backtick run of exactly [length] backticks at or after [from], or -1. */
        private fun findBacktickClose(from: Int, to: Int, length: Int): Int {
            var j = from
            while (j < to) {
                if (text[j] == '`') {
                    val run = runLength(j, to, '`')
                    if (run == length) return j
                    j += run
                } else {
                    j++
                }
            }
            return -1
        }

        /** End of `(destination "title")` starting at the `(` at [open] (balanced parentheses, quotes, `<...>`), or -1. */
        private fun inlineLinkEnd(open: Int, to: Int): Int {
            var depth = 0
            var j = open
            var quote = 0.toChar()
            var angle = false
            while (j < to) {
                val c = text[j]
                when {
                    c == '\\' -> j++
                    quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                    angle -> if (c == '>') angle = false else if (c == '\n') return -1
                    c == '<' && j > open && text.subSequence(open + 1, j).isBlank() -> angle = true
                    (c == '"' || c == '\'') && j > open && text[j - 1].isWhitespace() -> quote = c
                    c == '(' -> depth++
                    c == ')' -> {
                        depth--
                        if (depth == 0) return j + 1
                    }

                    c == '\n' && j + 1 < to && text[j + 1] == '\n' -> return -1
                }
                j++
            }
            return -1
        }

        /** End of an autolink (`<https://...>`, `<me@example.com>`), an HTML tag or an HTML comment at [i], or -1. */
        private fun autolinkOrHtmlEnd(i: Int, to: Int): Int {
            if (i + 1 >= to) return -1
            val first = text[i + 1]
            if (first.isLetter()) {
                // URI autolink: scheme followed by ':' and no spaces or angle brackets
                var j = i + 1
                while (j < to && (text[j].isLetterOrDigit() || text[j] == '+' || text[j] == '.' || text[j] == '-')) j++
                if (j < to && text[j] == ':' && j - i - 1 in 2..32) {
                    var k = j + 1
                    while (k < to && text[k] != '>' && text[k] != '<' && !text[k].isWhitespace()) k++
                    if (k < to && text[k] == '>') return k + 1
                }
                // email autolink
                var k = i + 1
                while (k < to && text[k] != '>' && text[k] != '<' && !text[k].isWhitespace()) k++
                if (k < to && text[k] == '>' && indexOf("@", i + 1, k) > i + 1) return k + 1
            }
            if (first == '!' && i + 3 < to && text[i + 2] == '-' && text[i + 3] == '-') {
                val close = indexOf("-->", i + 4, to)
                return if (close >= 0) close + 3 else -1
            }
            val nameStart = if (first == '/') i + 2 else i + 1
            if (nameStart >= to || !text[nameStart].isLetter()) return -1
            var j = nameStart
            var quote = 0.toChar()
            while (j < to) {
                val c = text[j]
                if (quote != 0.toChar()) {
                    if (c == quote) quote = 0.toChar()
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '>') {
                    return j + 1
                } else if (c == '<') {
                    return -1
                }
                j++
            }
            return -1
        }

        /** End of a bare `http(s)://` or `www.` URL at [i] (linkify, on by default in comark), or -1. */
        private fun bareUrlEnd(i: Int, to: Int): Int {
            val isUrl = startsWith("https://", i, to) || startsWith("http://", i, to) || startsWith("www.", i, to)
            if (!isUrl) return -1
            var j = i
            while (j < to && !text[j].isWhitespace() && text[j] != '<') j++
            // trailing punctuation is not part of the link
            while (j > i && text[j - 1] in "?!.,:;*_~'\")") j--
            return if (j > i + 4) j else -1
        }

        private fun isBareUrlBoundary(c: Char) = c.isWhitespace() || c in "*_~("

        // ---- Helpers --------------------------------------------------------------------------------------------------

        /** End of the opaque range starting at [i], or -1. */
        private fun opaqueEndAt(i: Int, to: Int): Int {
            if (opaque.isEmpty()) return -1
            var lo = 0
            var hi = opaque.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val start = opaque[mid].first
                when {
                    start < i -> lo = mid + 1
                    start > i -> hi = mid - 1
                    else -> return minOf(opaque[mid].last + 1, to)
                }
            }
            return -1
        }

        private fun runLength(i: Int, to: Int, c: Char): Int {
            var j = i
            while (j < to && text[j] == c) j++
            return j - i
        }

        private fun startsWith(prefix: String, at: Int, to: Int): Boolean {
            if (at + prefix.length > to) return false
            for (k in prefix.indices) if (text[at + k] != prefix[k]) return false
            return true
        }

        private fun indexOf(needle: String, from: Int, to: Int): Int {
            var j = from
            while (j + needle.length <= to) {
                if (startsWith(needle, j, to)) return j
                j++
            }
            return -1
        }

        private fun isWhitespace(c: Char) = c.isWhitespace() || c == ' '

        private fun isPunctuation(c: Char): Boolean {
            if (c.code < 128) return c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
            return when (Character.getType(c).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
                -> true

                else -> false
            }
        }
    }
}
