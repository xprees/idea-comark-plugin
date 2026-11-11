package cz.xprees.comark.format.studio

/** A YAML document that js-yaml rejects. [message] follows js-yaml's `reason (line:column)`. */
class StudioYamlException(message: String) : RuntimeException(message)

/**
 * A port of js-yaml 5.5.0 `loadAll(source, { schema: JSON_SCHEMA })`, the loader comark 0.7.0 uses for front matter and
 * component props (`internal/yaml.js`). js-yaml 5 parses into an event stream (`parseEvents`) and builds values from it
 * (`constructFromEvents`); both halves are ported function by function from `dist/js-yaml.mjs`.
 *
 * With the JSON schema only plain scalars are typed, and only these forms: `null`, `true`, `false`, integers
 * `-?(0|[1-9][0-9]*)` and floats `-?(0|[1-9][0-9]*)(\.[0-9]*)?([eE][-+]?[0-9]+)?`. Everything else, `~`, `yes`, `0x1F`,
 * `2024-01-15` and the empty value included, is a string.
 *
 * Values are [String], [Boolean], [Double], `null`, [ArrayList] and [LinkedHashMap] (keys as JavaScript's `String(key)`).
 * An alias yields the very instance its anchor holds, as in JavaScript. Errors throw [StudioYamlException].
 */
object StudioYamlLoader {
    fun loadAll(source: String): List<Any?> = Constructor(source, Parser(source).parse()).run()

    /** comark `parseYaml`: one document, or `undefined` ([JsUndefined]) for an empty stream. */
    fun parseYaml(source: String): Any? {
        val docs = loadAll(source)
        if (docs.size > 1) throw StudioYamlException("expected a single document in the stream, but found more")
        return if (docs.isEmpty()) JsUndefined else docs[0]
    }

    // ---- events -----------------------------------------------------------------------------------------------------

    private const val NO_RANGE = -1

    private enum class Kind { DOCUMENT, SEQUENCE, MAPPING, SCALAR, ALIAS, POP }

    private const val PLAIN = 1
    private const val SINGLE_QUOTED = 2
    private const val DOUBLE_QUOTED = 3
    private const val LITERAL_BLOCK = 4
    private const val FOLDED_BLOCK = 5

    private const val CLIP = 1
    private const val STRIP = 2
    private const val KEEP = 3

    private class Event(
        val kind: Kind,
        var start: Int = 0,
        val valueStart: Int = NO_RANGE,
        val valueEnd: Int = NO_RANGE,
        val anchorStart: Int = NO_RANGE,
        val anchorEnd: Int = NO_RANGE,
        val tagStart: Int = NO_RANGE,
        val tagEnd: Int = NO_RANGE,
        val style: Int = PLAIN,
        val chomping: Int = CLIP,
        val indent: Int = -1,
        val fast: Boolean = false,
        val directives: List<Pair<String, String>> = emptyList(),
    )

    private class Props {
        var anchorStart = NO_RANGE
        var anchorEnd = NO_RANGE
        var tagStart = NO_RANGE
        var tagEnd = NO_RANGE
    }

    private fun throwAt(source: String, position: Int, message: String): Nothing {
        var line = 0
        var lineStart = 0
        var index = 0
        while (index < position && index < source.length) {
            val ch = source[index].code
            if (ch == 10) {
                line++; lineStart = index + 1
            } else if (ch == 13) {
                line++
                if (index + 1 < source.length && source[index + 1].code == 10) index++
                lineStart = index + 1
            }
            index++
        }
        throw StudioYamlException("$message (${line + 1}:${position - lineStart + 1})")
    }

    // ---- parser (src/parser/parser.ts) ------------------------------------------------------------------------------

    private const val CONTEXT_FLOW_IN = 1
    private const val CONTEXT_FLOW_OUT = 2
    private const val CONTEXT_BLOCK_IN = 3
    private const val CONTEXT_BLOCK_OUT = 4

    private val PATTERN_NON_PRINTABLE =
        Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x84\\x86-\\x9F\\uFFFE\\uFFFF]|[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])|(?:[^\\uD800-\\uDBFF]|^)[\\uDC00-\\uDFFF]")
    private val PATTERN_TAG_HANDLE = Regex("^(?:!|!!|![0-9A-Za-z-]+!)\\z")
    private const val NS_URI_CHAR = "(?:%[0-9A-Fa-f]{2}|[0-9A-Za-z\\-#;/?:@&=+$,_.!~*'()\\[\\]])"
    private const val NS_TAG_CHAR = "(?:%[0-9A-Fa-f]{2}|[0-9A-Za-z\\-#;/?:@&=+$.~*'()_])"
    private val PATTERN_TAG_URI = Regex("^(?:$NS_URI_CHAR)*\\z")
    private val PATTERN_TAG_SUFFIX = Regex("^(?:$NS_TAG_CHAR)+\\z")
    private val PATTERN_TAG_PREFIX = Regex("^(?:!(?:$NS_URI_CHAR)*|$NS_TAG_CHAR(?:$NS_URI_CHAR)*)\\z")
    private const val MAX_DEPTH = 100

    private class Snapshot(val position: Int, val line: Int, val lineStart: Int, val lineIndent: Int, val firstTabInLine: Int, val eventsLength: Int)

    private class Parser(source: String) {
        val input = source + "\u0000"
        val length = source.length
        var position = 0
        var line = 0
        var lineStart = 0
        var lineIndent = 0
        var firstTabInLine = -1
        var depth = 0
        var directives = ArrayList<Pair<String, String>>()
        var tagHandlers = HashMap<String, String>()
        val events = ArrayList<Event>()

        fun c(i: Int): Int = if (i >= 0 && i < input.length) input[i].code else -1

        fun parse(): List<Event> {
            val nul = input.indexOf('\u0000')
            if (nul < length) throwAt(input.substring(0, length), nul, "null byte is not allowed in input")
            while (position < length) {
                skipByteOrderMark()
                skipSeparationSpace(true)
                if (position >= length) break
                val documentStart = position
                readDocument()
                if (position == documentStart) throwError("can not read a document")
            }
            return events
        }

        fun throwError(message: String): Nothing = throwAt(input.substring(0, length), position, message)

        fun isEol(ch: Int) = ch == 10 || ch == 13
        fun isWhiteSpace(ch: Int) = ch == 9 || ch == 32
        fun isWsOrEol(ch: Int) = isWhiteSpace(ch) || isEol(ch)
        fun isWsOrEolOrEnd(ch: Int) = ch == 0 || isWsOrEol(ch)
        fun isFlowIndicator(ch: Int) = ch == 44 || ch == 91 || ch == 93 || ch == 123 || ch == 125
        fun fromDecimalCode(ch: Int) = if (ch in 48..57) ch - 48 else -1
        fun fromHexCode(ch: Int): Int {
            if (ch in 48..57) return ch - 48
            val lc = ch or 32
            return if (lc in 97..102) lc - 97 + 10 else -1
        }

        fun escapedHexLen(ch: Int) = when (ch) {
            120 -> 2
            117 -> 4
            85 -> 8
            else -> 0
        }

        fun isSimpleEscape(ch: Int) = ch == 48 || ch == 97 || ch == 98 || ch == 116 || ch == 9 || ch == 110 || ch == 118 || ch == 102 ||
            ch == 114 || ch == 101 || ch == 32 || ch == 34 || ch == 47 || ch == 92 || ch == 78 || ch == 95 || ch == 76 || ch == 80

        fun snapshot() = Snapshot(position, line, lineStart, lineIndent, firstTabInLine, events.size)

        fun restore(s: Snapshot) {
            position = s.position; line = s.line; lineStart = s.lineStart; lineIndent = s.lineIndent; firstTabInLine = s.firstTabInLine
            while (events.size > s.eventsLength) events.removeAt(events.size - 1)
        }

        fun addScalar(
            valueStart: Int, valueEnd: Int, p: Props?, style: Int, chomping: Int = CLIP, indent: Int = -1, fast: Boolean = false,
        ) {
            events += Event(
                Kind.SCALAR, valueStart = valueStart, valueEnd = valueEnd,
                anchorStart = p?.anchorStart ?: NO_RANGE, anchorEnd = p?.anchorEnd ?: NO_RANGE,
                tagStart = p?.tagStart ?: NO_RANGE, tagEnd = p?.tagEnd ?: NO_RANGE,
                style = style, chomping = chomping, indent = indent, fast = fast,
            )
        }

        fun addEmptyScalar() = addScalar(NO_RANGE, NO_RANGE, null, PLAIN)

        fun collectionEvent(kind: Kind, start: Int, p: Props) = Event(
            kind, start = start, anchorStart = p.anchorStart, anchorEnd = p.anchorEnd, tagStart = p.tagStart, tagEnd = p.tagEnd,
        )

        fun addPop() {
            events += Event(Kind.POP)
        }

        fun consumeLineBreak() {
            if (c(position) == 10) {
                position++
            } else {
                position++
                if (c(position) == 10) position++
            }
            line++
            lineStart = position
            lineIndent = 0
            firstTabInLine = -1
        }

        fun skipSeparationSpace(allowComments: Boolean): Int {
            var lineBreaks = 0
            var ch = c(position)
            var hasSeparation = position == lineStart || isWsOrEol(c(position - 1))
            while (ch != 0) {
                while (isWhiteSpace(ch)) {
                    hasSeparation = true
                    if (ch == 9 && firstTabInLine == -1) firstTabInLine = position
                    ch = c(++position)
                }
                if (allowComments && hasSeparation && ch == 35) {
                    do {
                        ch = c(++position)
                    } while (!isEol(ch) && ch != 0)
                }
                if (!isEol(ch)) break
                consumeLineBreak()
                lineBreaks++
                hasSeparation = true
                ch = c(position)
                while (ch == 32) {
                    lineIndent++
                    ch = c(++position)
                }
            }
            return lineBreaks
        }

        fun testDocumentSeparator(at: Int = position): Boolean {
            val ch = c(at)
            if ((ch == 45 || ch == 46) && ch == c(at + 1) && ch == c(at + 2)) {
                val following = c(at + 3)
                return following == 0 || isWsOrEol(following)
            }
            return false
        }

        fun skipByteOrderMark() {
            if (position == lineStart && c(position) == 0xFEFF) {
                position++
                lineStart = position
            }
        }

        fun testDocumentBoundary(): Boolean {
            if (position != lineStart) return false
            if (testDocumentSeparator()) return true
            if (c(position) != 0xFEFF) return false
            val s = snapshot()
            skipByteOrderMark()
            skipSeparationSpace(true)
            val ch = c(position)
            val result = position == lineStart && (ch == 37 || ch == 45 && testDocumentSeparator())
            restore(s)
            return result
        }

        fun skipUntilLineEnd() {
            var ch = c(position)
            while (ch != 0 && !isEol(ch)) ch = c(++position)
        }

        fun checkPrintable(start: Int, end: Int) {
            val m = PATTERN_NON_PRINTABLE.find(input.substring(start, end))
            if (m != null) {
                position = start + m.range.first + m.value.length - 1
                throwError("the stream contains non-printable characters")
            }
        }

        fun readTagProperty(props: Props, inFlow: Boolean): Boolean {
            if (c(position) != 33) return false
            if (props.tagStart != NO_RANGE) throwError("duplication of a tag property")
            val start = position
            var isVerbatim = false
            var isNamed = false
            var tagHandle = "!"
            var ch = c(++position)
            if (ch == 60) {
                isVerbatim = true
                ch = c(++position)
            } else if (ch == 33) {
                isNamed = true
                tagHandle = "!!"
                ch = c(++position)
            }
            var suffixStart = position
            val tagName: String
            if (isVerbatim) {
                while (ch != 0 && ch != 62) ch = c(++position)
                if (ch != 62) throwError("unexpected end of the stream within a verbatim tag")
                tagName = input.substring(suffixStart, position)
                position++
            } else {
                while (ch != 0 && !isWsOrEol(ch) && !(inFlow && isFlowIndicator(ch))) {
                    if (ch == 33) {
                        if (!isNamed) {
                            tagHandle = input.substring(suffixStart - 1, position + 1)
                            if (!PATTERN_TAG_HANDLE.containsMatchIn(tagHandle)) throwError("named tag handle cannot contain such characters")
                            isNamed = true
                            suffixStart = position + 1
                        } else {
                            throwError("tag suffix cannot contain exclamation marks")
                        }
                    }
                    ch = c(++position)
                }
                tagName = input.substring(suffixStart, position)
                if (tagName.any { it == ',' || it == '[' || it == ']' || it == '{' || it == '}' }) {
                    throwError("tag suffix cannot contain flow indicator characters")
                }
            }
            if (tagName.isNotEmpty() && !(if (isVerbatim) PATTERN_TAG_URI else PATTERN_TAG_SUFFIX).containsMatchIn(tagName)) {
                throwError("tag name cannot contain such characters: $tagName")
            }
            if (!isVerbatim && tagHandle != "!" && tagHandle != "!!" && !tagHandlers.containsKey(tagHandle)) {
                throwError("undeclared tag handle \"$tagHandle\"")
            }
            props.tagStart = start
            props.tagEnd = position
            return true
        }

        fun readAnchorProperty(props: Props): Boolean {
            if (c(position) != 38) return false
            if (props.anchorStart != NO_RANGE) throwError("duplication of an anchor property")
            position++
            val start = position
            while (c(position) != 0 && !isWsOrEol(c(position)) && !isFlowIndicator(c(position))) position++
            if (position == start) throwError("name of an anchor node must contain at least one character")
            props.anchorStart = start
            props.anchorEnd = position
            return true
        }

        fun readAlias(props: Props): Boolean {
            if (c(position) != 42) return false
            if (props.anchorStart != NO_RANGE || props.tagStart != NO_RANGE) throwError("alias node should not have any properties")
            position++
            val start = position
            while (c(position) != 0 && !isWsOrEol(c(position)) && !isFlowIndicator(c(position))) position++
            if (position == start) throwError("name of an alias node must contain at least one character")
            events += Event(Kind.ALIAS, anchorStart = start, anchorEnd = position)
            return true
        }

        fun readFlowScalarBreak(nodeIndent: Int) {
            skipSeparationSpace(false)
            if (lineIndent < nodeIndent) throwError("deficient indentation")
        }

        fun readSingleQuotedScalar(nodeIndent: Int, props: Props): Boolean {
            if (c(position) != 39) return false
            position++
            val start = position
            var simple = true
            while (c(position) != 0) {
                val ch = c(position)
                if (ch == 39) {
                    if (c(position + 1) == 39) {
                        simple = false
                        position += 2
                        continue
                    }
                    val end = position
                    position++
                    addScalar(start, end, props, SINGLE_QUOTED, CLIP, -1, simple)
                    return true
                }
                if (isEol(ch)) {
                    simple = false
                    readFlowScalarBreak(nodeIndent)
                } else if (position == lineStart && testDocumentSeparator()) {
                    throwError("unexpected end of the document within a single quoted scalar")
                } else if (ch != 9 && ch < 32) {
                    throwError("expected valid JSON character")
                } else {
                    position++
                }
            }
            throwError("unexpected end of the stream within a single quoted scalar")
        }

        fun readDoubleQuotedScalar(nodeIndent: Int, props: Props): Boolean {
            if (c(position) != 34) return false
            position++
            val start = position
            var simple = true
            while (c(position) != 0) {
                val ch = c(position)
                if (ch == 34) {
                    val end = position
                    position++
                    addScalar(start, end, props, DOUBLE_QUOTED, CLIP, -1, simple)
                    return true
                }
                if (ch == 92) {
                    simple = false
                    val escaped = c(++position)
                    if (isEol(escaped)) {
                        readFlowScalarBreak(nodeIndent)
                    } else if (isSimpleEscape(escaped)) {
                        position++
                    } else {
                        var hexLength = escapedHexLen(escaped)
                        if (hexLength == 0) throwError("unknown escape sequence")
                        while (hexLength-- > 0) {
                            position++
                            if (fromHexCode(c(position)) < 0) throwError("expected hexadecimal character")
                        }
                        position++
                    }
                } else if (isEol(ch)) {
                    simple = false
                    readFlowScalarBreak(nodeIndent)
                } else if (position == lineStart && testDocumentSeparator()) {
                    throwError("unexpected end of the document within a double quoted scalar")
                } else if (ch != 9 && ch < 32) {
                    throwError("expected valid JSON character")
                } else {
                    position++
                }
            }
            throwError("unexpected end of the stream within a double quoted scalar")
        }

        fun readBlockScalar(parentIndent: Int, props: Props): Boolean {
            val ch0 = c(position)
            var chomping = CLIP
            var indent = -1
            var detectedIndent = false
            if (ch0 != 124 && ch0 != 62) return false
            val style = if (ch0 == 124) LITERAL_BLOCK else FOLDED_BLOCK
            position++
            while (c(position) != 0) {
                val current = c(position)
                val digit = fromDecimalCode(current)
                if (current == 43 || current == 45) {
                    if (chomping != CLIP) throwError("repeat of a chomping mode identifier")
                    chomping = if (current == 43) KEEP else STRIP
                    position++
                } else if (digit >= 0) {
                    if (digit == 0) throwError("bad explicit indentation width of a block scalar; it cannot be less than one")
                    if (detectedIndent) throwError("repeat of an indentation width identifier")
                    indent = parentIndent + digit - 1
                    detectedIndent = true
                    position++
                } else {
                    break
                }
            }
            var hadWhitespace = false
            while (isWhiteSpace(c(position))) {
                hadWhitespace = true
                position++
            }
            if (hadWhitespace && c(position) == 35) skipUntilLineEnd()
            if (isEol(c(position))) consumeLineBreak() else if (c(position) != 0) throwError("a line break is expected")
            var contentIndent = if (detectedIndent) indent else -1
            var maxLeadingIndent = 0
            val valueStart = position
            var valueEnd = position
            while (c(position) != 0) {
                val linePosition = position
                var column = 0
                while (c(linePosition + column) == 32) column++
                val first = c(linePosition + column)
                if (first == 0) {
                    if (contentIndent >= 0) {
                        if (column > contentIndent) valueEnd = linePosition + column
                    } else if (column > 0) {
                        valueEnd = linePosition + column
                    }
                    break
                }
                if (testDocumentBoundary()) break
                if (!detectedIndent && contentIndent == -1 && isEol(first)) maxLeadingIndent = maxOf(maxLeadingIndent, column)
                if (!detectedIndent && contentIndent == -1 && !isEol(first)) {
                    if (first == 9 && column < parentIndent) {
                        position = linePosition + column
                        throwError("tab characters must not be used in indentation")
                    }
                    if (column in parentIndent until maxLeadingIndent) {
                        position = linePosition + column
                        throwError("bad indentation of a mapping entry")
                    }
                }
                if (contentIndent == -1 && first != 0 && !isEol(first) && column < parentIndent) {
                    lineIndent = column
                    position = linePosition + column
                    break
                }
                if (!detectedIndent && first != 0 && !isEol(first) && contentIndent == -1) contentIndent = column
                val requiredIndent = if (contentIndent == -1) parentIndent + 1 else contentIndent
                if (first != 0 && !isEol(first) && column < requiredIndent) {
                    lineIndent = column
                    position = linePosition + column
                    break
                }
                skipUntilLineEnd()
                valueEnd = position
                if (isEol(c(position))) {
                    consumeLineBreak()
                    valueEnd = position
                }
            }
            checkPrintable(valueStart, valueEnd)
            addScalar(valueStart, valueEnd, props, style, chomping, contentIndent)
            return true
        }

        fun canStartPlainScalar(nodeContext: Int): Boolean {
            val ch = c(position)
            val inFlow = nodeContext == CONTEXT_FLOW_IN
            if (ch == 0 || isWsOrEol(ch) || ch == 35 || ch == 38 || ch == 42 || ch == 33 || ch == 124 || ch == 62 || ch == 39 ||
                ch == 34 || ch == 37 || ch == 64 || ch == 96 || isFlowIndicator(ch)
            ) {
                return false
            }
            if (ch == 63 || ch == 45) {
                val following = c(position + 1)
                if (isWsOrEolOrEnd(following) || inFlow && isFlowIndicator(following)) return false
            }
            return true
        }

        fun readPlainScalar(nodeIndent: Int, nodeContext: Int, props: Props): Boolean {
            if (!canStartPlainScalar(nodeContext)) return false
            val start = position
            var end = position
            var ch = c(position)
            val inFlow = nodeContext == CONTEXT_FLOW_IN
            var multiline = false
            while (ch != 0) {
                if (testDocumentBoundary()) break
                if (ch == 58) {
                    val following = c(position + 1)
                    if (isWsOrEolOrEnd(following) || inFlow && isFlowIndicator(following)) break
                } else if (ch == 35) {
                    if (isWsOrEol(c(position - 1))) break
                } else if (inFlow && isFlowIndicator(ch)) {
                    break
                } else if (isEol(ch)) {
                    val savedPosition = position
                    val savedLine = line
                    val savedLineStart = lineStart
                    val savedLineIndent = lineIndent
                    skipSeparationSpace(false)
                    if (lineIndent >= nodeIndent) {
                        multiline = true
                        ch = c(position)
                        continue
                    }
                    position = savedPosition
                    line = savedLine
                    lineStart = savedLineStart
                    lineIndent = savedLineIndent
                    break
                }
                if (!isWhiteSpace(ch)) end = position + 1
                ch = c(++position)
            }
            if (end == start) return false
            checkPrintable(start, end)
            addScalar(start, end, props, PLAIN, CLIP, -1, !multiline)
            return true
        }

        fun skipFlowSeparationSpace(nodeIndent: Int) {
            val startLine = line
            skipSeparationSpace(true)
            if (line > startLine && lineIndent < nodeIndent || firstTabInLine != -1 && lineIndent < nodeIndent) {
                throwError("deficient indentation")
            }
        }

        fun insertFlowPairMapping(s: Snapshot) {
            events.add(s.eventsLength, Event(Kind.MAPPING, start = s.position))
        }

        fun readFlowCollection(nodeIndent: Int, props: Props): Boolean {
            val ch0 = c(position)
            val isMapping = ch0 == 123
            val start = position
            var readNext = true
            if (ch0 != 91 && ch0 != 123) return false
            val terminator = if (isMapping) 125 else 93
            events += collectionEvent(if (isMapping) Kind.MAPPING else Kind.SEQUENCE, start, props)
            position++
            while (c(position) != 0) {
                skipFlowSeparationSpace(nodeIndent)
                var ch = c(position)
                if (ch == terminator) {
                    position++
                    addPop()
                    return true
                } else if (!readNext) {
                    throwError("missed comma between flow collection entries")
                } else if (ch == 44) {
                    throwError("expected the node content, but found ','")
                }
                var isPair = false
                var isExplicitPair = false
                if (ch == 63 && isWsOrEol(c(position + 1))) {
                    isPair = true
                    isExplicitPair = true
                    position += 1
                    skipFlowSeparationSpace(nodeIndent)
                }
                val entryLine = line
                val entryStart = snapshot()
                val keyWasRead = parseNode(nodeIndent, CONTEXT_FLOW_IN, allowToSeek = false, allowCompact = true)
                skipFlowSeparationSpace(nodeIndent)
                ch = c(position)
                if ((isMapping || isExplicitPair || line == entryLine) && ch == 58) {
                    isPair = true
                    position++
                    skipFlowSeparationSpace(nodeIndent)
                    if (!isMapping) {
                        insertFlowPairMapping(entryStart)
                        if (!keyWasRead) addEmptyScalar()
                    } else if (!keyWasRead) {
                        addEmptyScalar()
                    }
                    if (!parseNode(nodeIndent, CONTEXT_FLOW_IN, allowToSeek = false, allowCompact = true)) addEmptyScalar()
                    skipFlowSeparationSpace(nodeIndent)
                    if (!isMapping) addPop()
                } else if (isMapping && isPair) {
                    if (!keyWasRead) addEmptyScalar()
                    addEmptyScalar()
                } else if (isMapping) {
                    addEmptyScalar()
                } else if (isPair) {
                    insertFlowPairMapping(entryStart)
                    if (!keyWasRead) addEmptyScalar()
                    addEmptyScalar()
                    addPop()
                }
                ch = c(position)
                if (ch == 44) {
                    readNext = true
                    position++
                } else {
                    readNext = false
                }
            }
            throwError("unexpected end of the stream within a flow collection")
        }

        fun readBlockSequence(nodeIndent: Int, props: Props): Boolean {
            if (firstTabInLine != -1 || c(position) != 45 || !isWsOrEolOrEnd(c(position + 1))) return false
            events += collectionEvent(Kind.SEQUENCE, position, props)
            while (c(position) == 45 && isWsOrEolOrEnd(c(position + 1))) {
                if (firstTabInLine != -1) {
                    position = firstTabInLine
                    throwError("tab characters must not be used in indentation")
                }
                val entryLine = line
                position++
                val hadBreak = skipSeparationSpace(true) > 0
                if (firstTabInLine != -1 && c(position) == 45 && isWsOrEolOrEnd(c(position + 1))) {
                    throwError("bad indentation of a sequence entry")
                }
                if (hadBreak && lineIndent <= nodeIndent) {
                    addEmptyScalar()
                } else {
                    parseNode(nodeIndent, CONTEXT_BLOCK_IN, allowToSeek = false, allowCompact = true)
                }
                skipSeparationSpace(true)
                if (lineIndent < nodeIndent || position >= length) break
                if (lineIndent > nodeIndent) throwError("bad indentation of a sequence entry")
                if (line == entryLine && c(position) == 45 && isWsOrEolOrEnd(c(position + 1))) {
                    throwError("bad indentation of a sequence entry")
                }
            }
            addPop()
            return true
        }

        fun readBlockMapping(nodeIndent: Int, flowIndent: Int, props: Props): Boolean {
            var atExplicitKey = false
            var detected = false
            var mappingOpened = false
            var pendingExplicitKey = false
            if (firstTabInLine != -1) return false
            var ch = c(position)
            while (ch != 0) {
                if (!atExplicitKey && firstTabInLine != -1) {
                    position = firstTabInLine
                    throwError("tab characters must not be used in indentation")
                }
                val following = c(position + 1)
                val entryLine = line
                if ((ch == 63 || ch == 58) && isWsOrEolOrEnd(following)) {
                    if (!mappingOpened) {
                        events += collectionEvent(Kind.MAPPING, position, props)
                        mappingOpened = true
                    }
                    if (ch == 63) {
                        if (atExplicitKey) addEmptyScalar()
                        detected = true
                        atExplicitKey = true
                    } else if (atExplicitKey) {
                        atExplicitKey = false
                    } else {
                        addEmptyScalar()
                        detected = true
                        atExplicitKey = false
                    }
                    position += 1
                    pendingExplicitKey = true
                } else {
                    if (atExplicitKey) {
                        addEmptyScalar()
                        atExplicitKey = false
                    }
                    val beforeKey = snapshot()
                    if (!parseNode(flowIndent, CONTEXT_FLOW_OUT, allowToSeek = false, allowCompact = true)) break
                    if (line == entryLine) {
                        ch = c(position)
                        while (isWhiteSpace(ch)) ch = c(++position)
                        if (ch == 58) {
                            ch = c(++position)
                            if (!isWsOrEolOrEnd(ch)) {
                                throwError("a whitespace character is expected after the key-value separator within a block mapping")
                            }
                            if (!mappingOpened) {
                                events.add(beforeKey.eventsLength, collectionEvent(Kind.MAPPING, beforeKey.position, props))
                                mappingOpened = true
                            }
                            detected = true
                            atExplicitKey = false
                            pendingExplicitKey = false
                        } else if (detected) {
                            throwError("expected ':' after a mapping key")
                        } else {
                            if (props.anchorStart != NO_RANGE || props.tagStart != NO_RANGE) {
                                restore(beforeKey)
                                return false
                            }
                            return true
                        }
                    } else if (detected) {
                        throwError("can not read a block mapping entry; a multiline key may not be an implicit key")
                    } else {
                        if (props.anchorStart != NO_RANGE || props.tagStart != NO_RANGE) {
                            restore(beforeKey)
                            return false
                        }
                        return true
                    }
                }
                if (parseNode(nodeIndent, CONTEXT_BLOCK_OUT, allowToSeek = true, allowCompact = pendingExplicitKey)) pendingExplicitKey = false
                if (!atExplicitKey) {
                    if (pendingExplicitKey) {
                        addEmptyScalar()
                        pendingExplicitKey = false
                    }
                }
                skipSeparationSpace(true)
                ch = c(position)
                if ((line == entryLine || lineIndent > nodeIndent) && ch != 0) {
                    throwError("bad indentation of a mapping entry")
                } else if (lineIndent < nodeIndent) {
                    break
                }
            }
            if (!detected) return false
            if (atExplicitKey) addEmptyScalar()
            if (mappingOpened) addPop()
            return true
        }

        fun parseNode(
            parentIndent: Int, nodeContext: Int, allowToSeek: Boolean, allowCompact: Boolean, allowPropertyMapping: Boolean = true,
        ): Boolean {
            if (depth >= MAX_DEPTH) throwError("nesting exceeded maxDepth ($MAX_DEPTH)")
            depth++
            var indentStatus = 1
            var atNewLine = false
            var hasContent = false
            var propertyStart: Snapshot? = null
            val props = Props()
            var allowBlockScalars = nodeContext == CONTEXT_BLOCK_OUT || nodeContext == CONTEXT_BLOCK_IN
            var allowBlockCollections = allowBlockScalars
            val allowBlockStyles = allowBlockScalars
            if (allowToSeek && skipSeparationSpace(true) != 0) {
                atNewLine = true
                indentStatus = if (lineIndent > parentIndent) 1 else if (lineIndent == parentIndent) 0 else -1
            }
            if (indentStatus == 1) {
                while (true) {
                    val ch = c(position)
                    val propertyState = snapshot()
                    if (atNewLine && indentStatus != 1 && (ch == 33 || ch == 38)) break
                    if (atNewLine && allowBlockStyles && (props.tagStart != NO_RANGE || props.anchorStart != NO_RANGE) && (ch == 33 || ch == 38)) {
                        val fallback = snapshot()
                        val flowIndent = parentIndent + 1
                        if (readBlockMapping(position - lineStart, flowIndent, props) &&
                            events.getOrNull(fallback.eventsLength)?.kind == Kind.MAPPING
                        ) {
                            depth--
                            return true
                        }
                        restore(fallback)
                    }
                    if (atNewLine && (ch == 33 && props.tagStart != NO_RANGE || ch == 38 && props.anchorStart != NO_RANGE)) break
                    if (!readTagProperty(props, nodeContext == CONTEXT_FLOW_IN) && !readAnchorProperty(props)) break
                    if (propertyStart == null) propertyStart = propertyState
                    if (skipSeparationSpace(true) != 0) {
                        atNewLine = true
                        allowBlockCollections = allowBlockStyles
                        indentStatus = if (lineIndent > parentIndent) 1 else if (lineIndent == parentIndent) 0 else -1
                    } else {
                        allowBlockCollections = false
                    }
                }
            }
            if (allowBlockCollections) allowBlockCollections = atNewLine || allowCompact
            if (indentStatus == 1 || nodeContext == CONTEXT_BLOCK_OUT) {
                val flowIndent = if (nodeContext == CONTEXT_FLOW_IN || nodeContext == CONTEXT_FLOW_OUT) parentIndent else parentIndent + 1
                val blockIndent = position - lineStart
                if (indentStatus == 1) {
                    if (allowBlockCollections && (readBlockSequence(blockIndent, props) || readBlockMapping(blockIndent, flowIndent, props)) ||
                        readFlowCollection(flowIndent, props)
                    ) {
                        hasContent = true
                    } else {
                        val ch = c(position)
                        if (propertyStart != null && allowPropertyMapping && allowBlockStyles && !allowBlockCollections && ch != 124 && ch != 62) {
                            val fallback = snapshot()
                            val propertyIndent = propertyStart.position - propertyStart.lineStart
                            restore(propertyStart)
                            if (readBlockMapping(propertyIndent, flowIndent, Props()) &&
                                events.getOrNull(fallback.eventsLength)?.kind == Kind.MAPPING
                            ) {
                                hasContent = true
                            } else {
                                restore(fallback)
                            }
                        }
                        if (!hasContent && (
                                allowBlockScalars && readBlockScalar(flowIndent, props) ||
                                    readSingleQuotedScalar(flowIndent, props) ||
                                    readDoubleQuotedScalar(flowIndent, props) ||
                                    readAlias(props) ||
                                    readPlainScalar(flowIndent, nodeContext, props)
                                )
                        ) {
                            hasContent = true
                        }
                    }
                } else if (indentStatus == 0) {
                    hasContent = allowBlockCollections && readBlockSequence(blockIndent, props)
                }
            }
            allowBlockScalars = allowBlockScalars && !hasContent
            if (!hasContent && (props.anchorStart != NO_RANGE || props.tagStart != NO_RANGE || allowBlockScalars)) {
                addScalar(NO_RANGE, NO_RANGE, props, PLAIN)
                hasContent = true
            }
            depth--
            return hasContent || props.anchorStart != NO_RANGE || props.tagStart != NO_RANGE
        }

        fun readDirective(): Boolean {
            if (lineIndent > 0 || c(position) != 37) return false
            position++
            val nameStart = position
            while (c(position) != 0 && !isWsOrEol(c(position))) position++
            val name = input.substring(nameStart, position)
            val args = ArrayList<String>()
            if (name.isEmpty()) throwError("directive name must not be less than one character in length")
            while (c(position) != 0 && !isEol(c(position))) {
                while (isWhiteSpace(c(position))) position++
                if (c(position) == 35 || isEol(c(position)) || c(position) == 0) break
                val start = position
                while (c(position) != 0 && !isWsOrEol(c(position))) position++
                args += input.substring(start, position)
            }
            if (isEol(c(position))) consumeLineBreak()
            if (name == "YAML") {
                if (directives.any { it.first == "yaml" }) throwError("duplication of %YAML directive")
                if (args.size != 1) throwError("YAML directive accepts exactly one argument")
                val m = Regex("^([0-9]+)\\.([0-9]+)\\z").find(args[0]) ?: throwError("ill-formed argument of the YAML directive")
                if (m.groupValues[1].toBigInteger() != java.math.BigInteger.ONE) throwError("unacceptable YAML version of the document")
                directives += "yaml" to args[0]
            } else if (name == "TAG") {
                if (args.size != 2) throwError("TAG directive accepts exactly two arguments")
                val (handle, prefix) = args
                if (!PATTERN_TAG_HANDLE.containsMatchIn(handle)) throwError("ill-formed tag handle (first argument) of the TAG directive")
                if (tagHandlers.containsKey(handle)) throwError("there is a previously declared suffix for \"$handle\" tag handle")
                if (!PATTERN_TAG_PREFIX.containsMatchIn(prefix)) throwError("ill-formed tag prefix (second argument) of the TAG directive")
                tagHandlers[handle] = prefix
                directives += handle to prefix
            }
            return true
        }

        fun readDocument() {
            directives = ArrayList()
            tagHandlers = HashMap()
            var hasDirectives = false
            skipSeparationSpace(true)
            while (readDirective()) {
                hasDirectives = true
                skipSeparationSpace(true)
            }
            var explicitStart = false
            var allowCompact = true
            if (lineIndent == 0 && c(position) == 45 && c(position + 1) == 45 && c(position + 2) == 45 && isWsOrEolOrEnd(c(position + 3))) {
                explicitStart = true
                val markerLine = line
                position += 3
                skipSeparationSpace(true)
                allowCompact = line > markerLine
            } else if (hasDirectives) {
                throwError("directives end mark is expected")
            }
            if (!explicitStart && position == lineStart && c(position) == 46 && testDocumentSeparator()) {
                position += 3
                skipSeparationSpace(true)
                return
            }
            events += Event(Kind.DOCUMENT, directives = directives.filter { it.first != "yaml" })
            if (!parseNode(lineIndent - 1, CONTEXT_BLOCK_OUT, allowToSeek = false, allowCompact = allowCompact, allowPropertyMapping = allowCompact)) {
                addEmptyScalar()
            }
            skipSeparationSpace(true)
            var explicitEnd = false
            if (position == lineStart && testDocumentSeparator()) {
                explicitEnd = c(position) == 46
                if (explicitEnd) {
                    val markerLine = line
                    position += 3
                    skipSeparationSpace(true)
                    if (line == markerLine && position < length) throwError("end of the stream or a document separator is expected")
                }
            }
            addPop()
            if (!explicitEnd && position < length && !testDocumentBoundary()) throwError("end of the stream or a document separator is expected")
        }
    }

    // ---- scalar values (src/parser/parser_scalar.ts) ----------------------------------------------------------------

    private fun simpleEscape(ch: Int): String? = when (ch) {
        48 -> "\u0000"
        97 -> "\u0007"
        98 -> "\b"
        116, 9 -> "\t"
        110 -> "\n"
        118 -> "\u000B"
        102 -> "\u000C"
        114 -> "\r"
        101 -> "\u001B"
        32 -> " "
        34 -> "\""
        47 -> "/"
        92 -> "\\"
        78 -> "\u0085"
        95 -> " "
        76 -> " "
        80 -> " "
        else -> null
    }

    private class Fold(val position: Int, val breaks: Int)

    private fun at(s: String, i: Int): Int = if (i >= 0 && i < s.length) s[i].code else -1

    private fun skipFoldedBreaks(input: String, from: Int, end: Int): Fold {
        var position = from
        var breaks = 0
        while (position < end) {
            val ch = at(input, position)
            if (ch == 10) {
                breaks++; position++
            } else if (ch == 13) {
                breaks++; position++
                if (at(input, position) == 10) position++
            } else if (ch == 32 || ch == 9) {
                position++
            } else {
                break
            }
        }
        return Fold(position, breaks)
    }

    private fun foldedBreaks(count: Int) = if (count == 1) " " else "\n".repeat(count - 1)

    private fun plainValue(input: String, start: Int, end: Int): String {
        val result = StringBuilder()
        var position = start
        var captureStart = start
        var captureEnd = start
        while (position < end) {
            val ch = at(input, position)
            if (ch == 10 || ch == 13) {
                result.append(input, captureStart, captureEnd)
                val fold = skipFoldedBreaks(input, position, end)
                result.append(foldedBreaks(fold.breaks))
                position = fold.position; captureStart = position; captureEnd = position
            } else {
                position++
                if (ch != 32 && ch != 9) captureEnd = position
            }
        }
        return result.append(input, captureStart, captureEnd).toString()
    }

    private fun singleQuotedValue(input: String, start: Int, end: Int): String {
        val result = StringBuilder()
        var position = start
        var captureStart = start
        var captureEnd = start
        while (position < end) {
            val ch = at(input, position)
            if (ch == 39) {
                result.append(input, captureStart, position).append('\'')
                position += 2
                captureStart = position; captureEnd = position
            } else if (ch == 10 || ch == 13) {
                result.append(input, captureStart, captureEnd)
                val fold = skipFoldedBreaks(input, position, end)
                result.append(foldedBreaks(fold.breaks))
                position = fold.position; captureStart = position; captureEnd = position
            } else {
                position++
                if (ch != 32 && ch != 9) captureEnd = position
            }
        }
        return result.append(input, captureStart, end).toString()
    }

    private fun hexValue(ch: Int): Int = if (ch in 48..57) ch - 48 else (ch or 32) - 97 + 10

    private fun doubleQuotedValue(input: String, start: Int, end: Int): String {
        val result = StringBuilder()
        var position = start
        var captureStart = start
        var captureEnd = start
        while (position < end) {
            val ch = at(input, position)
            if (ch == 92) {
                result.append(input, captureStart, position)
                position++
                val escaped = at(input, position)
                val simple = if (escaped in 0..255) simpleEscape(escaped) else null
                if (escaped == 10 || escaped == 13) {
                    position = skipFoldedBreaks(input, position, end).position
                } else if (simple != null) {
                    result.append(simple)
                    position++
                } else {
                    var hexLength = when (escaped) {
                        120 -> 2
                        117 -> 4
                        else -> 8
                    }
                    var hexResult = 0
                    while (hexLength > 0) {
                        position++
                        hexResult = (hexResult shl 4) + hexValue(at(input, position))
                        hexLength--
                    }
                    if (hexResult <= 0xFFFF) {
                        result.append(hexResult.toChar())
                    } else {
                        result.append(((hexResult - 0x10000 shr 10) + 0xD800).toChar())
                        result.append(((hexResult - 0x10000 and 1023) + 0xDC00).toChar())
                    }
                    position++
                }
                captureStart = position; captureEnd = position
            } else if (ch == 10 || ch == 13) {
                result.append(input, captureStart, captureEnd)
                val fold = skipFoldedBreaks(input, position, end)
                result.append(foldedBreaks(fold.breaks))
                position = fold.position; captureStart = position; captureEnd = position
            } else {
                position++
                if (ch != 32 && ch != 9) captureEnd = position
            }
        }
        return result.append(input, captureStart, end).toString()
    }

    private fun blockValue(input: String, start: Int, end: Int, indent: Int, chomping: Int, folded: Boolean): String {
        val textIndent = if (indent < 0) 0 else indent
        val region = input.substring(start, end).replace(Regex("\r\n?"), "\n")
        val lines = if (region.isEmpty()) emptyList() else (if (region.endsWith("\n")) region.dropLast(1) else region).split("\n")
        val result = StringBuilder()
        var didReadContent = false
        var emptyLines = 0
        var atMoreIndented = false
        for (line in lines) {
            var column = 0
            while (column < textIndent && at(line, column) == 32) column++
            if (indent < 0 || column >= line.length) {
                emptyLines++
                continue
            }
            val content = line.substring(minOf(textIndent, line.length))
            val first = at(content, 0)
            if (folded) {
                if (first == 32 || first == 9) {
                    atMoreIndented = true
                    result.append("\n".repeat(if (didReadContent) 1 + emptyLines else emptyLines))
                } else if (atMoreIndented) {
                    atMoreIndented = false
                    result.append("\n".repeat(emptyLines + 1))
                } else if (emptyLines == 0) {
                    if (didReadContent) result.append(' ')
                } else {
                    result.append("\n".repeat(emptyLines))
                }
            } else {
                result.append("\n".repeat(if (didReadContent) 1 + emptyLines else emptyLines))
            }
            result.append(content)
            didReadContent = true
            emptyLines = 0
        }
        if (chomping == KEEP) {
            result.append("\n".repeat(if (didReadContent) 1 + emptyLines else emptyLines))
        } else if (chomping != STRIP) {
            if (didReadContent) result.append('\n')
        }
        return result.toString()
    }

    private fun scalarValue(input: String, e: Event): String {
        if (e.valueStart == NO_RANGE) return ""
        if (e.fast) return input.substring(e.valueStart, e.valueEnd)
        return when (e.style) {
            SINGLE_QUOTED -> singleQuotedValue(input, e.valueStart, e.valueEnd)
            DOUBLE_QUOTED -> doubleQuotedValue(input, e.valueStart, e.valueEnd)
            LITERAL_BLOCK -> blockValue(input, e.valueStart, e.valueEnd, e.indent, e.chomping, false)
            FOLDED_BLOCK -> blockValue(input, e.valueStart, e.valueEnd, e.indent, e.chomping, true)
            else -> plainValue(input, e.valueStart, e.valueEnd)
        }
    }

    // ---- JSON schema tags -------------------------------------------------------------------------------------------

    private const val TAG_STR = "tag:yaml.org,2002:str"
    private const val TAG_NULL = "tag:yaml.org,2002:null"
    private const val TAG_BOOL = "tag:yaml.org,2002:bool"
    private const val TAG_INT = "tag:yaml.org,2002:int"
    private const val TAG_FLOAT = "tag:yaml.org,2002:float"
    private const val TAG_SEQ = "tag:yaml.org,2002:seq"
    private const val TAG_MAP = "tag:yaml.org,2002:map"

    private object NotResolved

    private val INT_IMPLICIT = Regex("^-?(?:0|[1-9][0-9]*)\\z")
    private val INT_EXPLICIT = Regex("^(?:[-+]?0b[0-1]+|[-+]?0o[0-7]+|[-+]?0x[0-9a-fA-F]+|[-+]?[0-9]+)\\z")
    private val FLOAT_IMPLICIT = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]*)?(?:[eE][-+]?[0-9]+)?\\z")
    private val FLOAT_EXPLICIT =
        Regex("^(?:[-+]?[0-9]+(?:\\.[0-9]*)?(?:[eE][-+]?[0-9]+)?|[-+]?\\.[0-9]+(?:[eE][-+]?[0-9]+)?|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))\\z")

    private fun parseIntLike(source: String): Double {
        var value = source
        var sign = 1.0
        if (value.startsWith("-") || value.startsWith("+")) {
            if (value[0] == '-') sign = -1.0
            value = value.substring(1)
        }
        val (digits, radix) = when {
            value.startsWith("0b") -> value.substring(2) to 2
            value.startsWith("0o") -> value.substring(2) to 8
            value.startsWith("0x") -> value.substring(2) to 16
            else -> value to 10
        }
        val magnitude = if (radix == 10) digits.toDouble() else java.math.BigInteger(digits, radix).toDouble()
        return sign * magnitude
    }

    private fun resolveInt(source: String, explicit: Boolean): Any {
        if (!(if (explicit) INT_EXPLICIT else INT_IMPLICIT).containsMatchIn(source)) return NotResolved
        val r = parseIntLike(source)
        return if (r.isFinite()) r else NotResolved
    }

    private fun resolveFloat(source: String, explicit: Boolean): Any {
        if (explicit) {
            if (!FLOAT_EXPLICIT.containsMatchIn(source)) return NotResolved
            var value = source.lowercase()
            val sign = if (value.startsWith("-")) -1.0 else 1.0
            if (value.startsWith("+") || value.startsWith("-")) value = value.substring(1)
            if (value == ".inf") return if (sign == 1.0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
            if (value == ".nan") return Double.NaN
            val r = sign * jsParseFloat(value)
            return if (r.isFinite()) r else NotResolved
        }
        if (!FLOAT_IMPLICIT.containsMatchIn(source)) return NotResolved
        val r = source.toDouble()
        return if (r.isFinite()) r else NotResolved
    }

    private fun jsParseFloat(s: String): Double {
        val m = Regex("^[0-9]*\\.?[0-9]*(?:e[-+]?[0-9]+)?").find(s)?.value ?: return Double.NaN
        return m.toDoubleOrNull() ?: Double.NaN
    }

    private fun resolveScalar(tag: String, source: String, explicit: Boolean): Any? = when (tag) {
        TAG_STR -> source
        TAG_NULL -> if (source == "null" || explicit && source == "") null else NotResolved
        TAG_BOOL -> when (source) {
            "true" -> true
            "false" -> false
            else -> NotResolved
        }
        TAG_INT -> resolveInt(source, explicit)
        TAG_FLOAT -> resolveFloat(source, explicit)
        else -> NotResolved
    }

    private fun resolveImplicit(source: String): Any? {
        val candidates = when (source.firstOrNull()) {
            'n' -> listOf(TAG_NULL)
            't', 'f' -> listOf(TAG_BOOL)
            '-', in '0'..'9' -> listOf(TAG_INT, TAG_FLOAT)
            else -> emptyList()
        }
        for (tag in candidates) {
            val v = resolveScalar(tag, source, false)
            if (v !== NotResolved) return v
        }
        return source
    }

    // ---- constructor (src/parser/constructor.ts) --------------------------------------------------------------------

    private class Anchor(var value: Any?, var isMapping: Boolean)

    private class Frame(val kind: Kind, val value: Any?, val anchor: Anchor?) {
        var docValue: Any? = null
        var key: Any? = null
        var keyPosition = 0
        var hasKey = false
    }

    private class Constructor(val source: String, val events: List<Event>) {
        var position = 0
        val documents = ArrayList<Any?>()
        val frames = ArrayList<Frame>()
        var anchors = HashMap<String, Anchor>()
        var tagHandlers = HashMap<String, String>()

        fun throwError(message: String): Nothing = throwAt(source, position, message)

        fun eventPosition(e: Event): Int = when {
            e.tagStart != NO_RANGE -> e.tagStart
            e.anchorStart != NO_RANGE -> e.anchorStart
            e.valueStart != NO_RANGE -> e.valueStart
            e.kind == Kind.SEQUENCE || e.kind == Kind.MAPPING -> e.start
            else -> 0
        }

        fun tagNameFull(rawTag: String): String {
            if (rawTag.startsWith("!<") && rawTag.endsWith(">")) return percentDecode(rawTag.substring(2, rawTag.length - 1))
            val handleEnd = rawTag.indexOf('!', 1)
            val handle = if (handleEnd == -1) "!" else rawTag.substring(0, handleEnd + 1)
            val prefix = tagHandlers[handle] ?: when (handle) {
                "!" -> "!"
                "!!" -> "tag:yaml.org,2002:"
                else -> handle
            }
            return percentDecode(prefix) + percentDecode(rawTag.substring(handle.length))
        }

        fun percentDecode(s: String): String {
            if (!s.contains('%')) return s
            val bytes = java.io.ByteArrayOutputStream()
            val out = StringBuilder()
            var i = 0
            fun flush() {
                if (bytes.size() > 0) {
                    out.append(bytes.toByteArray().toString(Charsets.UTF_8)); bytes.reset()
                }
            }
            while (i < s.length) {
                if (s[i] == '%' && i + 2 < s.length + 1) {
                    val hex = s.substring(i + 1, minOf(s.length, i + 3)).toIntOrNull(16)
                    if (hex != null) {
                        bytes.write(hex); i += 3; continue
                    }
                }
                flush()
                out.append(s[i]); i++
            }
            flush()
            return out.toString()
        }

        /** Returns the value and whether it is a mapping (for merge bookkeeping, unused by JSON_SCHEMA). */
        fun constructScalar(e: Event): Any? {
            val value = scalarValue(source, e)
            val rawTag = if (e.tagStart == NO_RANGE) "" else source.substring(e.tagStart, e.tagEnd)
            if (rawTag != "") {
                if (rawTag == "!") return value
                val tagName = tagNameFull(rawTag)
                when (tagName) {
                    TAG_STR, TAG_NULL, TAG_BOOL, TAG_INT, TAG_FLOAT -> {
                        val r = resolveScalar(tagName, value, true)
                        if (r === NotResolved) throwError("cannot resolve a node with !<$tagName> explicit tag")
                        return r
                    }
                    TAG_MAP, TAG_SEQ -> {
                        if (value != "") throwError("cannot resolve a node with !<$tagName> explicit tag")
                        return if (tagName == TAG_MAP) LinkedHashMap<String, Any?>() else ArrayList<Any?>()
                    }
                }
                throwError("unknown scalar tag !<$tagName>")
            }
            if (e.style == PLAIN) return resolveImplicit(value)
            return value
        }

        fun collectionTag(e: Event, default: String): String {
            val rawTag = if (e.tagStart == NO_RANGE) "" else source.substring(e.tagStart, e.tagEnd)
            return if (rawTag == "" || rawTag == "!") default else tagNameFull(rawTag)
        }

        fun mapKey(key: Any?): String {
            if (key is Map<*, *> || key is List<*>) throwError("object-based map does not support complex keys")
            return StudioJs.str(key)
        }

        @Suppress("UNCHECKED_CAST")
        fun addValue(value: Any?) {
            val frame = frames.last()
            when (frame.kind) {
                Kind.DOCUMENT -> frame.docValue = value
                Kind.SEQUENCE -> (frame.value as ArrayList<Any?>).add(value)
                else -> if (frame.hasKey) {
                    val key = frame.key
                    frame.key = null
                    frame.hasKey = false
                    position = frame.keyPosition
                    val map = frame.value as LinkedHashMap<String, Any?>
                    val k = mapKey(key)
                    if (map.containsKey(k)) throwError("duplicated mapping key")
                    map[k] = value
                } else {
                    frame.key = value
                    frame.keyPosition = position
                    frame.hasKey = true
                }
            }
        }

        fun storeAnchor(e: Event, value: Any?): Anchor? {
            if (e.anchorStart == NO_RANGE) return null
            val a = Anchor(value, value is Map<*, *>)
            anchors[source.substring(e.anchorStart, e.anchorEnd)] = a
            return a
        }

        fun run(): List<Any?> {
            for (e in events) {
                position = eventPosition(e)
                when (e.kind) {
                    Kind.DOCUMENT -> {
                        anchors = HashMap()
                        tagHandlers = HashMap()
                        for ((handle, prefix) in e.directives) tagHandlers[handle] = prefix
                        frames += Frame(Kind.DOCUMENT, null, null)
                    }
                    Kind.SCALAR -> {
                        val v = constructScalar(e)
                        storeAnchor(e, v)
                        addValue(v)
                    }
                    Kind.SEQUENCE -> {
                        val tag = collectionTag(e, TAG_SEQ)
                        if (tag != TAG_SEQ) throwError("unknown sequence tag !<$tag>")
                        val v = ArrayList<Any?>()
                        frames += Frame(Kind.SEQUENCE, v, storeAnchor(e, v))
                    }
                    Kind.MAPPING -> {
                        val tag = collectionTag(e, TAG_MAP)
                        if (tag != TAG_MAP) throwError("unknown mapping tag !<$tag>")
                        val v = LinkedHashMap<String, Any?>()
                        frames += Frame(Kind.MAPPING, v, storeAnchor(e, v))
                    }
                    Kind.ALIAS -> {
                        val name = source.substring(e.anchorStart, e.anchorEnd)
                        val anchor = anchors[name] ?: throwError("unidentified alias \"$name\"")
                        addValue(anchor.value)
                    }
                    Kind.POP -> {
                        val frame = frames.removeAt(frames.size - 1)
                        if (frame.kind == Kind.MAPPING && frame.hasKey) {
                            position = frame.keyPosition
                            throwError("incomplete mapping pair in event stream")
                        }
                        if (frame.kind == Kind.DOCUMENT) documents += frame.docValue else addValue(frame.value)
                    }
                }
            }
            return documents
        }
    }
}
