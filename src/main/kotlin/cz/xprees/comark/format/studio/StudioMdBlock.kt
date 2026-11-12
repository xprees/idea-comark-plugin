package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioMdUtil.isSpace

/** An attribute of a markdown-it token; values are strings except where comark's YAML props put other JavaScript values. */
internal class MdAttr(val name: String, var value: Any?)

/** markdown-it `Token` */
internal class MdToken(var type: String, var tag: String, var nesting: Int) {
    var attrs: MutableList<MdAttr>? = null
    var map: IntArray? = null
    var level = 0
    var children: MutableList<MdToken>? = null
    var content = ""
    var markup = ""
    var info = ""
    var block = false
    var hidden = false

    /** For a `link_open` or `image` resolved through a link reference definition: the normalized label (not markdown-it). */
    var reference: String? = null

    /** For an `mdc_inline_props` token: no `}` closed the props, so they ran to the end of the inline content (not comark). */
    var unclosedProps = false

    /** For an `mdc_inline_props` token: no element took the props (they follow plain text), so the tree has none (not comark). */
    var droppedProps = false

    /** For a token with Comark props: the props text lost characters when it was read ([StudioComarkSyntax.PropsSearch.malformed]). */
    var malformedProps = false

    fun attrIndex(name: String): Int = attrs?.indexOfFirst { it.name == name } ?: -1

    fun attrPush(attr: MdAttr) {
        val a = attrs
        if (a != null) a += attr else attrs = mutableListOf(attr)
    }

    fun attrSet(name: String, value: Any?) {
        val idx = attrIndex(name)
        if (idx < 0) attrPush(MdAttr(name, value)) else attrs!![idx] = MdAttr(name, value)
    }

    fun attrJoin(name: String, value: Any?) {
        val idx = attrIndex(name)
        if (idx < 0) attrPush(MdAttr(name, value)) else attrs!![idx].value = "${StudioJs.str(attrs!![idx].value)} ${StudioJs.str(value)}"
    }

    override fun toString() = "$type($tag,$nesting)"
}

internal class MdReference(val href: String, val title: String)

/** The parse `env`: reference definitions and comark's stack of open block components. */
internal class MdEnv {
    var references: HashMap<String, MdReference>? = null
    val comarkBlockTokens = ArrayList<MdToken>()

    /**
     * Not comark: when set, invalid YAML in a component's props block does not abort the parse. The block is read as if
     * its props were empty and the line of its opening `---` is recorded in [propsYamlErrors], so that [StudioSafety] can
     * keep that one block verbatim instead of giving up on the whole document.
     */
    var lenientPropsYaml = false
    val propsYamlErrors = ArrayList<Int>()

    /** Not markdown-it: the lines where block parsing stopped at the nesting limit, dropping the rest of the container. */
    val nestingLimitLines = ArrayList<Int>()

    /** Not comark: with [lenientPropsYaml], the lines of the opening `---` of props blocks whose YAML has a comment. */
    val propsYamlComments = ArrayList<Int>()
}

/** Thrown where comark (or markdown-exit) itself throws during the parse; Studio's text path then fails. */
internal class StudioParseException(message: String) : RuntimeException(message)

/** markdown-it `StateBlock` */
internal class MdStateBlock(val src: String, val env: MdEnv, val tokens: MutableList<MdToken>) {
    var bMarks: IntArray
    var eMarks: IntArray
    var tShift: IntArray
    var sCount: IntArray
    var bsCount: IntArray
    var blkIndent = 0
    var line = 0
    var lineMax = 0
    var tight = false
    var listIndent = -1
    var parentType = "root"
    var level = 0

    init {
        val b = ArrayList<Int>()
        val e = ArrayList<Int>()
        val t = ArrayList<Int>()
        val sc = ArrayList<Int>()
        val len = src.length
        var start = 0
        while (start < len) {
            val lineEnd = src.indexOf('\n', start)
            val end = if (lineEnd == -1) len else lineEnd
            var indent = 0
            var offset = 0
            var pos = start
            while (pos < end) {
                val ch = src[pos].code
                if (isSpace(ch)) {
                    indent++
                    offset += if (ch == 9) 4 - offset % 4 else 1
                    pos++
                    continue
                }
                break
            }
            b += start; e += end; t += indent; sc += offset
            start = end + 1
        }
        b += len; e += len; t += 0; sc += 0
        bMarks = b.toIntArray()
        eMarks = e.toIntArray()
        tShift = t.toIntArray()
        sCount = sc.toIntArray()
        bsCount = IntArray(b.size)
        lineMax = b.size - 1
    }

    fun ch(pos: Int): Int = if (pos >= 0 && pos < src.length) src[pos].code else -1

    /** JavaScript `src.slice(a, b)` */
    fun slice(a: Int, b: Int): String {
        val s = a.coerceIn(0, src.length)
        val e = b.coerceIn(0, src.length)
        return if (s >= e) "" else src.substring(s, e)
    }

    fun push(type: String, tag: String, nesting: Int): MdToken {
        val token = MdToken(type, tag, nesting)
        token.block = true
        if (nesting < 0) level--
        token.level = level
        if (nesting > 0) level++
        tokens += token
        return token
    }

    fun isEmpty(line: Int) = bMarks[line] + tShift[line] >= eMarks[line]

    fun skipEmptyLines(from: Int): Int {
        var f = from
        while (f < lineMax) {
            if (bMarks[f] + tShift[f] < eMarks[f]) break
            f++
        }
        return f
    }

    fun skipSpaces(pos: Int): Int {
        var p = pos
        while (p < src.length) {
            if (!isSpace(src[p].code)) break
            p++
        }
        return p
    }

    fun skipSpacesBack(pos: Int, min: Int): Int {
        if (pos <= min) return pos
        var p = pos
        while (p > min) if (!isSpace(src[--p].code)) return p + 1
        return p
    }

    fun skipChars(pos: Int, code: Int): Int {
        var p = pos
        while (p < src.length) {
            if (src[p].code != code) break
            p++
        }
        return p
    }

    fun skipCharsBack(pos: Int, code: Int, min: Int): Int {
        if (pos <= min) return pos
        var p = pos
        while (p > min) if (code != src[--p].code) return p + 1
        return p
    }

    fun getLines(begin: Int, end: Int, indent: Int, keepLastLF: Boolean): String {
        if (begin >= end) return ""
        val sb = StringBuilder()
        for (line in begin until end) {
            var lineIndent = 0
            val lineStart = bMarks[line]
            var first = lineStart
            val last = if (line + 1 < end || keepLastLF) eMarks[line] + 1 else eMarks[line]
            while (first < last && lineIndent < indent) {
                val ch = ch(first)
                if (isSpace(ch)) {
                    if (ch == 9) lineIndent += 4 - (lineIndent + bsCount[line]) % 4 else lineIndent++
                } else if (first - lineStart < tShift[line]) {
                    lineIndent++
                } else {
                    break
                }
                first++
            }
            if (lineIndent > indent) sb.append(" ".repeat(lineIndent - indent))
            sb.append(slice(first, last))
        }
        return sb.toString()
    }
}

/**
 * markdown-exit's block parser with the block rules of comark 0.7.0's default plugins (html, components) registered the
 * way `createMarkdownParser` registers them. Rule order:
 * table, code, comark_block_slots, comark_block_yaml, comark_block_shorthand, comark_block, fence, blockquote, hr, list,
 * reference, comark_html_block (markdown-exit's own html_block after it never gets a chance), heading, lheading, paragraph.
 */
internal object StudioMdBlock {
    private const val MAX_NESTING = 100

    private enum class R { TABLE, CODE, SLOTS, YAML, SHORTHAND, COMARK_BLOCK, FENCE, BLOCKQUOTE, HR, LIST, REFERENCE, HTML_BLOCK, HEADING, LHEADING, PARAGRAPH }

    private val ALL = R.values().toList()
    private val TERM_PARAGRAPH = listOf(R.TABLE, R.COMARK_BLOCK, R.FENCE, R.BLOCKQUOTE, R.HR, R.LIST, R.HTML_BLOCK, R.HEADING)
    private val TERM_BLOCKQUOTE = listOf(R.COMARK_BLOCK, R.FENCE, R.BLOCKQUOTE, R.HR, R.LIST, R.HTML_BLOCK, R.HEADING)
    private val TERM_LIST = listOf(R.COMARK_BLOCK, R.FENCE, R.BLOCKQUOTE, R.HR)
    private val TERM_REFERENCE = TERM_PARAGRAPH

    private fun rule(r: R, s: MdStateBlock, start: Int, end: Int, silent: Boolean): Boolean = when (r) {
        R.TABLE -> table(s, start, end, silent)
        R.CODE -> code(s, start, end)
        R.SLOTS -> comarkBlockSlots(s, start, end, silent)
        R.YAML -> comarkBlockYaml(s, start, end, silent)
        R.SHORTHAND -> comarkBlockShorthand(s, start, silent)
        R.COMARK_BLOCK -> comarkBlock(s, start, end, silent)
        R.FENCE -> fence(s, start, end, silent)
        R.BLOCKQUOTE -> blockquote(s, start, end, silent)
        R.HR -> hr(s, start, silent)
        R.LIST -> list(s, start, end, silent)
        R.REFERENCE -> reference(s, start, silent)
        R.HTML_BLOCK -> htmlBlock(s, start, end, silent)
        R.HEADING -> heading(s, start, silent)
        R.LHEADING -> lheading(s, start, end)
        R.PARAGRAPH -> paragraph(s, start, end)
    }

    private fun terminates(rules: List<R>, s: MdStateBlock, line: Int, end: Int): Boolean = rules.any { rule(it, s, line, end, true) }

    fun tokenize(state: MdStateBlock, startLine: Int, endLine: Int) {
        var line = startLine
        var hasEmptyLines = false
        while (line < endLine) {
            line = state.skipEmptyLines(line)
            state.line = line
            if (line >= endLine) break
            if (state.sCount[line] < state.blkIndent) break
            if (state.level >= MAX_NESTING) {
                state.env.nestingLimitLines += line
                state.line = endLine
                break
            }
            val prevLine = state.line
            var ok = false
            for (r in ALL) {
                ok = rule(r, state, line, endLine, false)
                if (ok) {
                    if (prevLine >= state.line) throw StudioParseException("block rule didn't increment state.line")
                    break
                }
            }
            if (!ok) throw StudioParseException("none of the block rules matched")
            state.tight = !hasEmptyLines
            if (state.isEmpty(state.line - 1)) hasEmptyLines = true
            line = state.line
            if (line < endLine && state.isEmpty(line)) {
                hasEmptyLines = true
                line++
                state.line = line
            }
        }
    }

    // ---- markdown-exit rules ----------------------------------------------------------------------------------------

    private fun blockquote(state: MdStateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        var max: Int
        val oldLineMax = state.lineMax
        if (state.sCount[startLine] - state.blkIndent >= 4) return false
        if (state.ch(pos) != 62) return false
        if (silent) return true
        val oldBMarks = ArrayList<Int>()
        val oldBSCount = ArrayList<Int>()
        val oldSCount = ArrayList<Int>()
        val oldTShift = ArrayList<Int>()
        val oldParentType = state.parentType
        state.parentType = "blockquote"
        var lastLineEmpty = false
        var nextLine = startLine
        while (nextLine < endLine) {
            val isOutdented = state.sCount[nextLine] < state.blkIndent
            pos = state.bMarks[nextLine] + state.tShift[nextLine]
            max = state.eMarks[nextLine]
            if (pos >= max) break
            if (state.ch(pos++) == 62 && !isOutdented) {
                var initial = state.sCount[nextLine] + 1
                val spaceAfterMarker: Boolean
                var adjustTab = false
                if (state.ch(pos) == 32) {
                    pos++
                    initial++
                    spaceAfterMarker = true
                } else if (state.ch(pos) == 9) {
                    spaceAfterMarker = true
                    if ((state.bsCount[nextLine] + initial) % 4 == 3) {
                        pos++
                        initial++
                    } else {
                        adjustTab = true
                    }
                } else {
                    spaceAfterMarker = false
                }
                var offset = initial
                oldBMarks += state.bMarks[nextLine]
                state.bMarks[nextLine] = pos
                while (pos < max) {
                    val ch = state.ch(pos)
                    if (isSpace(ch)) {
                        if (ch == 9) offset += 4 - (offset + state.bsCount[nextLine] + (if (adjustTab) 1 else 0)) % 4 else offset++
                    } else {
                        break
                    }
                    pos++
                }
                lastLineEmpty = pos >= max
                oldBSCount += state.bsCount[nextLine]
                state.bsCount[nextLine] = state.sCount[nextLine] + 1 + (if (spaceAfterMarker) 1 else 0)
                oldSCount += state.sCount[nextLine]
                state.sCount[nextLine] = offset - initial
                oldTShift += state.tShift[nextLine]
                state.tShift[nextLine] = pos - state.bMarks[nextLine]
                nextLine++
                continue
            }
            if (lastLineEmpty) break
            if (terminates(TERM_BLOCKQUOTE, state, nextLine, endLine)) {
                state.lineMax = nextLine
                if (state.blkIndent != 0) {
                    oldBMarks += state.bMarks[nextLine]
                    oldBSCount += state.bsCount[nextLine]
                    oldTShift += state.tShift[nextLine]
                    oldSCount += state.sCount[nextLine]
                    state.sCount[nextLine] -= state.blkIndent
                }
                break
            }
            oldBMarks += state.bMarks[nextLine]
            oldBSCount += state.bsCount[nextLine]
            oldTShift += state.tShift[nextLine]
            oldSCount += state.sCount[nextLine]
            state.sCount[nextLine] = -1
            nextLine++
        }
        val oldIndent = state.blkIndent
        state.blkIndent = 0
        val tokenO = state.push("blockquote_open", "blockquote", 1)
        tokenO.markup = ">"
        val lines = intArrayOf(startLine, 0)
        tokenO.map = lines
        tokenize(state, startLine, nextLine)
        val tokenC = state.push("blockquote_close", "blockquote", -1)
        tokenC.markup = ">"
        state.lineMax = oldLineMax
        state.parentType = oldParentType
        lines[1] = state.line
        for (i in oldTShift.indices) {
            state.bMarks[i + startLine] = oldBMarks[i]
            state.tShift[i + startLine] = oldTShift[i]
            state.sCount[i + startLine] = oldSCount[i]
            state.bsCount[i + startLine] = oldBSCount[i]
        }
        state.blkIndent = oldIndent
        return true
    }

    private fun code(state: MdStateBlock, startLine: Int, endLine: Int): Boolean {
        if (state.sCount[startLine] - state.blkIndent < 4) return false
        var nextLine = startLine + 1
        var last = nextLine
        while (nextLine < endLine) {
            if (state.isEmpty(nextLine)) {
                nextLine++
                continue
            }
            if (state.sCount[nextLine] - state.blkIndent >= 4) {
                nextLine++
                last = nextLine
                continue
            }
            break
        }
        state.line = last
        val token = state.push("code_block", "code", 0)
        token.content = state.getLines(startLine, last, 4 + state.blkIndent, false) + "\n"
        token.map = intArrayOf(startLine, state.line)
        return true
    }

    private fun fence(state: MdStateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        var max = state.eMarks[startLine]
        if (state.sCount[startLine] - state.blkIndent >= 4) return false
        if (pos + 3 > max) return false
        val marker = state.ch(pos)
        if (marker != 126 && marker != 96) return false
        var mem = pos
        pos = state.skipChars(pos, marker)
        var len = pos - mem
        if (len < 3) return false
        val markup = state.slice(mem, pos)
        val params = state.slice(pos, max)
        if (marker == 96 && params.contains('`')) return false
        if (silent) return true
        var nextLine = startLine
        var haveEndMarker = false
        while (true) {
            nextLine++
            if (nextLine >= endLine) break
            pos = state.bMarks[nextLine] + state.tShift[nextLine]
            mem = pos
            max = state.eMarks[nextLine]
            if (pos < max && state.sCount[nextLine] < state.blkIndent) break
            if (state.ch(pos) != marker) continue
            if (state.sCount[nextLine] - state.blkIndent >= 4) continue
            pos = state.skipChars(pos, marker)
            if (pos - mem < len) continue
            pos = state.skipSpaces(pos)
            if (pos < max) continue
            haveEndMarker = true
            break
        }
        len = state.sCount[startLine]
        state.line = nextLine + (if (haveEndMarker) 1 else 0)
        val token = state.push("fence", "code", 0)
        token.info = params
        token.content = state.getLines(startLine + 1, nextLine, len, true)
        token.markup = markup
        token.map = intArrayOf(startLine, state.line)
        return true
    }

    private fun heading(state: MdStateBlock, startLine: Int, silent: Boolean): Boolean {
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        var max = state.eMarks[startLine]
        if (state.sCount[startLine] - state.blkIndent >= 4) return false
        var ch = state.ch(pos)
        if (ch != 35 || pos >= max) return false
        var level = 1
        ch = state.ch(++pos)
        while (ch == 35 && pos < max && level <= 6) {
            level++
            ch = state.ch(++pos)
        }
        if (level > 6 || pos < max && !isSpace(ch)) return false
        if (silent) return true
        max = state.skipSpacesBack(max, pos)
        val tmp = state.skipCharsBack(max, 35, pos)
        if (tmp > pos && isSpace(state.ch(tmp - 1))) max = tmp
        state.line = startLine + 1
        val tokenO = state.push("heading_open", "h$level", 1)
        tokenO.markup = "########".substring(0, level)
        tokenO.map = intArrayOf(startLine, state.line)
        val tokenI = state.push("inline", "", 0)
        tokenI.content = StudioMdUtil.asciiTrim(state.slice(pos, max))
        tokenI.map = intArrayOf(startLine, state.line)
        tokenI.children = ArrayList()
        val tokenC = state.push("heading_close", "h$level", -1)
        tokenC.markup = "########".substring(0, level)
        return true
    }

    private fun hr(state: MdStateBlock, startLine: Int, silent: Boolean): Boolean {
        val max = state.eMarks[startLine]
        if (state.sCount[startLine] - state.blkIndent >= 4) return false
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        val marker = state.ch(pos++)
        if (marker != 42 && marker != 45 && marker != 95) return false
        var cnt = 1
        while (pos < max) {
            val ch = state.ch(pos++)
            if (ch != marker && !isSpace(ch)) return false
            if (ch == marker) cnt++
        }
        if (cnt < 3) return false
        if (silent) return true
        state.line = startLine + 1
        val token = state.push("hr", "hr", 0)
        token.map = intArrayOf(startLine, state.line)
        token.markup = marker.toChar().toString().repeat(cnt)
        return true
    }

    private val BLOCK_NAMES = listOf(
        "address", "article", "aside", "base", "basefont", "blockquote", "body", "caption", "center", "col", "colgroup", "dd",
        "details", "dialog", "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "frame", "frameset",
        "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hr", "html", "iframe", "legend", "li", "link", "main", "menu",
        "menuitem", "nav", "noframes", "ol", "optgroup", "option", "p", "param", "search", "section", "summary", "table", "tbody",
        "td", "tfoot", "th", "thead", "title", "tr", "track", "ul",
    )

    private const val JS_S = StudioJs.RE_S
    private const val ATTR_NAME = "[a-zA-Z_:][a-zA-Z0-9:._-]*"
    private const val ATTR_VALUE = "(?:[^\"'=<>`\\x00-\\x20]+|'[^']*'|\"[^\"]*\")"
    private const val ATTRIBUTE = "(?:$JS_S+$ATTR_NAME(?:$JS_S*=$JS_S*$ATTR_VALUE)?)"
    private const val OPEN_TAG = "<[A-Za-z][A-Za-z0-9\\-]*$ATTRIBUTE*$JS_S*/?>"
    private const val CLOSE_TAG = "</[A-Za-z][A-Za-z0-9\\-]*$JS_S*>"

    private class HtmlSequence(val open: Regex, val close: Regex, val canTerminate: Boolean)

    private val HTML_SEQUENCES = listOf(
        HtmlSequence(Regex("^<(script|pre|style|textarea)(?=($JS_S|>|\\z))", RegexOption.IGNORE_CASE), Regex("</(script|pre|style|textarea)>", RegexOption.IGNORE_CASE), true),
        HtmlSequence(Regex("^<!--"), Regex("-->"), true),
        HtmlSequence(Regex("^<\\?"), Regex("\\?>"), true),
        HtmlSequence(Regex("^<![A-Z]"), Regex(">"), true),
        HtmlSequence(Regex("^<!\\[CDATA\\["), Regex("\\]\\]>"), true),
        HtmlSequence(Regex("^</?(${BLOCK_NAMES.joinToString("|")})(?=($JS_S|/?>|\\z))", RegexOption.IGNORE_CASE), Regex("^\\z"), true),
        HtmlSequence(Regex("^(?:$OPEN_TAG|$CLOSE_TAG)$JS_S*\\z"), Regex("^\\z"), false),
    )

    /** comark `html_block` (`internal/parse/html/html_block_rule.js`) */
    private fun htmlBlock(state: MdStateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        var max = state.eMarks[startLine]
        if (state.sCount[startLine] - state.blkIndent >= 4) return false
        if (state.ch(pos) != 0x3c) return false
        var lineText = state.slice(pos, max)
        val seq = HTML_SEQUENCES.firstOrNull { it.open.containsMatchIn(lineText) } ?: return false
        if (silent) return seq.canTerminate
        var nextLine = startLine + 1
        if (!seq.close.containsMatchIn(lineText)) {
            while (nextLine < endLine) {
                if (state.sCount[nextLine] < state.blkIndent) break
                pos = state.bMarks[nextLine] + state.tShift[nextLine]
                max = state.eMarks[nextLine]
                lineText = state.slice(pos, max)
                if (seq.close.containsMatchIn(lineText)) {
                    if (lineText.isNotEmpty()) nextLine++
                    break
                }
                nextLine++
            }
        }
        state.line = nextLine
        val token = state.push("html_block", "", 1)
        token.map = intArrayOf(startLine, nextLine)
        token.content = state.getLines(startLine, nextLine, state.blkIndent, true)
        return true
    }

    private fun lheading(state: MdStateBlock, startLine: Int, endLine: Int): Boolean {
        if (state.sCount[startLine] - state.blkIndent >= 4) return false
        val oldParentType = state.parentType
        state.parentType = "paragraph"
        var level = 0
        var marker = -1
        var nextLine = startLine + 1
        while (nextLine < endLine && !state.isEmpty(nextLine)) {
            if (state.sCount[nextLine] - state.blkIndent > 3) {
                nextLine++
                continue
            }
            if (state.sCount[nextLine] >= state.blkIndent) {
                var pos = state.bMarks[nextLine] + state.tShift[nextLine]
                val max = state.eMarks[nextLine]
                if (pos < max) {
                    marker = state.ch(pos)
                    if (marker == 45 || marker == 61) {
                        pos = state.skipChars(pos, marker)
                        pos = state.skipSpaces(pos)
                        if (pos >= max) {
                            level = if (marker == 61) 1 else 2
                            break
                        }
                    }
                }
            }
            if (state.sCount[nextLine] < 0) {
                nextLine++
                continue
            }
            if (terminates(TERM_PARAGRAPH, state, nextLine, endLine)) break
            nextLine++
        }
        if (level == 0 || marker == -1) {
            state.parentType = oldParentType
            return false
        }
        val content = StudioMdUtil.asciiTrim(state.getLines(startLine, nextLine, state.blkIndent, false))
        state.line = nextLine + 1
        val tokenO = state.push("heading_open", "h$level", 1)
        tokenO.markup = marker.toChar().toString()
        tokenO.map = intArrayOf(startLine, state.line)
        val tokenI = state.push("inline", "", 0)
        tokenI.content = content
        tokenI.map = intArrayOf(startLine, state.line - 1)
        tokenI.children = ArrayList()
        val tokenC = state.push("heading_close", "h$level", -1)
        tokenC.markup = marker.toChar().toString()
        state.parentType = oldParentType
        return true
    }

    private fun skipBulletListMarker(state: MdStateBlock, startLine: Int): Int {
        val max = state.eMarks[startLine]
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        val marker = state.ch(pos++)
        if (marker != 42 && marker != 45 && marker != 43) return -1
        if (pos < max) {
            if (!isSpace(state.ch(pos))) return -1
        }
        return pos
    }

    private fun skipOrderedListMarker(state: MdStateBlock, startLine: Int): Int {
        val start = state.bMarks[startLine] + state.tShift[startLine]
        val max = state.eMarks[startLine]
        var pos = start
        if (pos + 1 >= max) return -1
        var ch = state.ch(pos++)
        if (ch < 48 || ch > 57) return -1
        while (true) {
            if (pos >= max) return -1
            ch = state.ch(pos++)
            if (ch in 48..57) {
                if (pos - start >= 10) return -1
                continue
            }
            if (ch == 41 || ch == 46) break
            return -1
        }
        if (pos < max) {
            ch = state.ch(pos)
            if (!isSpace(ch)) return -1
        }
        return pos
    }

    private fun markTightParagraphs(state: MdStateBlock, idx: Int) {
        val level = state.level + 2
        var i = idx + 2
        val l = state.tokens.size - 2
        while (i < l) {
            if (state.tokens[i].level == level && state.tokens[i].type == "paragraph_open") {
                state.tokens[i + 2].hidden = true
                state.tokens[i].hidden = true
                i += 2
            }
            i++
        }
    }

    private fun list(state: MdStateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        var max: Int
        var pos: Int
        var start = 0
        var token: MdToken
        var nextLine = startLine
        var tight = true
        if (state.sCount[nextLine] - state.blkIndent >= 4) return false
        if (state.listIndent >= 0 && state.sCount[nextLine] - state.listIndent >= 4 && state.sCount[nextLine] < state.blkIndent) return false
        var isTerminatingParagraph = false
        if (silent && state.parentType == "paragraph") {
            if (state.sCount[nextLine] >= state.blkIndent) isTerminatingParagraph = true
        }
        val isOrdered: Boolean
        var markerValue = 0L
        var posAfterMarker = skipOrderedListMarker(state, nextLine)
        if (posAfterMarker >= 0) {
            isOrdered = true
            start = state.bMarks[nextLine] + state.tShift[nextLine]
            markerValue = state.slice(start, posAfterMarker - 1).toLong()
            if (isTerminatingParagraph && markerValue != 1L) return false
        } else {
            posAfterMarker = skipBulletListMarker(state, nextLine)
            if (posAfterMarker >= 0) isOrdered = false else return false
        }
        if (isTerminatingParagraph) {
            if (state.skipSpaces(posAfterMarker) >= state.eMarks[nextLine]) return false
        }
        if (silent) return true
        val markerCharCode = state.ch(posAfterMarker - 1)
        val listTokIdx = state.tokens.size
        if (isOrdered) {
            token = state.push("ordered_list_open", "ol", 1)
            if (markerValue != 1L) token.attrs = mutableListOf(MdAttr("start", markerValue.toString()))
        } else {
            token = state.push("bullet_list_open", "ul", 1)
        }
        val listLines = intArrayOf(nextLine, 0)
        token.map = listLines
        token.markup = markerCharCode.toChar().toString()
        var prevEmptyEnd = false
        val oldParentType = state.parentType
        state.parentType = "list"
        while (nextLine < endLine) {
            pos = posAfterMarker
            max = state.eMarks[nextLine]
            val initial = state.sCount[nextLine] + posAfterMarker - (state.bMarks[nextLine] + state.tShift[nextLine])
            var offset = initial
            while (pos < max) {
                val ch = state.ch(pos)
                if (ch == 9) {
                    offset += 4 - (offset + state.bsCount[nextLine]) % 4
                } else if (ch == 32) {
                    offset++
                } else {
                    break
                }
                pos++
            }
            val contentStart = pos
            var indentAfterMarker = if (contentStart >= max) 1 else offset - initial
            if (indentAfterMarker > 4) indentAfterMarker = 1
            val indent = initial + indentAfterMarker
            token = state.push("list_item_open", "li", 1)
            token.markup = markerCharCode.toChar().toString()
            val itemLines = intArrayOf(nextLine, 0)
            token.map = itemLines
            if (isOrdered) token.info = state.slice(start, posAfterMarker - 1)
            val oldTight = state.tight
            val oldTShift = state.tShift[nextLine]
            val oldSCount = state.sCount[nextLine]
            val oldListIndent = state.listIndent
            state.listIndent = state.blkIndent
            state.blkIndent = indent
            state.tight = true
            state.tShift[nextLine] = contentStart - state.bMarks[nextLine]
            state.sCount[nextLine] = offset
            if (contentStart >= max && state.isEmpty(nextLine + 1)) {
                state.line = minOf(state.line + 2, endLine)
            } else {
                tokenize(state, nextLine, endLine)
            }
            if (!state.tight || prevEmptyEnd) tight = false
            prevEmptyEnd = state.line - nextLine > 1 && state.isEmpty(state.line - 1)
            state.blkIndent = state.listIndent
            state.listIndent = oldListIndent
            state.tShift[nextLine] = oldTShift
            state.sCount[nextLine] = oldSCount
            state.tight = oldTight
            token = state.push("list_item_close", "li", -1)
            token.markup = markerCharCode.toChar().toString()
            nextLine = state.line
            itemLines[1] = nextLine
            if (nextLine >= endLine) break
            if (state.sCount[nextLine] < state.blkIndent) break
            if (state.sCount[nextLine] - state.blkIndent >= 4) break
            if (terminates(TERM_LIST, state, nextLine, endLine)) break
            if (isOrdered) {
                posAfterMarker = skipOrderedListMarker(state, nextLine)
                if (posAfterMarker < 0) break
                start = state.bMarks[nextLine] + state.tShift[nextLine]
            } else {
                posAfterMarker = skipBulletListMarker(state, nextLine)
                if (posAfterMarker < 0) break
            }
            if (markerCharCode != state.ch(posAfterMarker - 1)) break
        }
        token = if (isOrdered) state.push("ordered_list_close", "ol", -1) else state.push("bullet_list_close", "ul", -1)
        token.markup = markerCharCode.toChar().toString()
        listLines[1] = nextLine
        state.line = nextLine
        state.parentType = oldParentType
        if (tight) markTightParagraphs(state, listTokIdx)
        return true
    }

    private fun paragraph(state: MdStateBlock, startLine: Int, endLine: Int): Boolean {
        val oldParentType = state.parentType
        var nextLine = startLine + 1
        state.parentType = "paragraph"
        while (nextLine < endLine && !state.isEmpty(nextLine)) {
            if (state.sCount[nextLine] - state.blkIndent > 3) {
                nextLine++
                continue
            }
            if (state.sCount[nextLine] < 0) {
                nextLine++
                continue
            }
            if (terminates(TERM_PARAGRAPH, state, nextLine, endLine)) break
            nextLine++
        }
        val content = StudioMdUtil.asciiTrim(state.getLines(startLine, nextLine, state.blkIndent, false))
        state.line = nextLine
        val tokenO = state.push("paragraph_open", "p", 1)
        tokenO.map = intArrayOf(startLine, state.line)
        val tokenI = state.push("inline", "", 0)
        tokenI.content = content
        tokenI.map = intArrayOf(startLine, state.line)
        tokenI.children = ArrayList()
        state.push("paragraph_close", "p", -1)
        state.parentType = oldParentType
        return true
    }

    private fun reference(state: MdStateBlock, startLine: Int, silent: Boolean): Boolean {
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        var max = state.eMarks[startLine]
        var nextLine = startLine + 1
        if (state.sCount[startLine] - state.blkIndent >= 4) return false
        if (state.ch(pos) != 91) return false
        fun getNextLine(nl: Int): String? {
            val endLine = state.lineMax
            if (nl >= endLine || state.isEmpty(nl)) return null
            var isContinuation = false
            if (state.sCount[nl] - state.blkIndent > 3) isContinuation = true
            if (state.sCount[nl] < 0) isContinuation = true
            if (!isContinuation) {
                val oldParentType = state.parentType
                state.parentType = "reference"
                val terminate = terminates(TERM_REFERENCE, state, nl, endLine)
                state.parentType = oldParentType
                if (terminate) return null
            }
            val p = state.bMarks[nl] + state.tShift[nl]
            val m = state.eMarks[nl]
            return state.slice(p, m + 1)
        }
        var str = state.slice(pos, max + 1)
        max = str.length
        var labelEnd = -1
        fun c(i: Int) = if (i >= 0 && i < str.length) str[i].code else -1
        pos = 1
        while (pos < max) {
            val ch = c(pos)
            if (ch == 91) {
                return false
            } else if (ch == 93) {
                labelEnd = pos
                break
            } else if (ch == 10) {
                val lineContent = getNextLine(nextLine)
                if (lineContent != null) {
                    str += lineContent
                    max = str.length
                    nextLine++
                }
            } else if (ch == 92) {
                pos++
                if (pos < max && c(pos) == 10) {
                    val lineContent = getNextLine(nextLine)
                    if (lineContent != null) {
                        str += lineContent
                        max = str.length
                        nextLine++
                    }
                }
            }
            pos++
        }
        if (labelEnd < 0 || c(labelEnd + 1) != 58) return false
        pos = labelEnd + 2
        while (pos < max) {
            val ch = c(pos)
            if (ch == 10) {
                val lineContent = getNextLine(nextLine)
                if (lineContent != null) {
                    str += lineContent
                    max = str.length
                    nextLine++
                }
            } else if (isSpace(ch)) {
                // skip
            } else {
                break
            }
            pos++
        }
        val destRes = StudioMdHelpers.parseLinkDestination(str, pos, max)
        if (!destRes.ok) return false
        val href = StudioMdUtil.normalizeLink(destRes.str)
        if (!StudioMdUtil.validateLink(href)) return false
        pos = destRes.pos
        val destEndPos = pos
        val destEndLineNo = nextLine
        val start = pos
        while (pos < max) {
            val ch = c(pos)
            if (ch == 10) {
                val lineContent = getNextLine(nextLine)
                if (lineContent != null) {
                    str += lineContent
                    max = str.length
                    nextLine++
                }
            } else if (isSpace(ch)) {
                // skip
            } else {
                break
            }
            pos++
        }
        var titleRes = StudioMdHelpers.parseLinkTitle(str, pos, max, null)
        while (titleRes.canContinue) {
            val lineContent = getNextLine(nextLine) ?: break
            str += lineContent
            pos = max
            max = str.length
            nextLine++
            titleRes = StudioMdHelpers.parseLinkTitle(str, pos, max, titleRes)
        }
        var title: String
        if (pos < max && start != pos && titleRes.ok) {
            title = titleRes.str
            pos = titleRes.pos
        } else {
            title = ""
            pos = destEndPos
            nextLine = destEndLineNo
        }
        while (pos < max) {
            if (!isSpace(c(pos))) break
            pos++
        }
        if (pos < max && c(pos) != 10) {
            if (title.isNotEmpty()) {
                title = ""
                pos = destEndPos
                nextLine = destEndLineNo
                while (pos < max) {
                    if (!isSpace(c(pos))) break
                    pos++
                }
            }
        }
        if (pos < max && c(pos) != 10) return false
        val label = StudioMdUtil.normalizeReference(str.substring(1, labelEnd))
        if (label.isEmpty()) return false
        if (silent) return true
        val refs = state.env.references ?: HashMap<String, MdReference>().also { state.env.references = it }
        if (!refs.containsKey(label)) refs[label] = MdReference(href, title)
        state.line = nextLine
        val token = state.push("reference", "", 0)
        token.map = intArrayOf(startLine, state.line)
        token.info = label
        return true
    }

    private fun tableLine(state: MdStateBlock, line: Int): String = state.slice(state.bMarks[line] + state.tShift[line], state.eMarks[line])

    private fun escapedSplit(str: String): MutableList<String> {
        val result = ArrayList<String>()
        val max = str.length
        var pos = 0
        var ch = if (str.isNotEmpty()) str[0].code else -1
        var isEscaped = false
        var lastPos = 0
        var current = StringBuilder()
        while (pos < max) {
            if (ch == 124) {
                if (!isEscaped) {
                    result += current.toString() + str.substring(lastPos, pos)
                    current = StringBuilder()
                    lastPos = pos + 1
                } else {
                    current.append(str, lastPos, pos - 1)
                    lastPos = pos
                }
            }
            isEscaped = ch == 92
            pos++
            ch = if (pos < max) str[pos].code else -1
        }
        result += current.toString() + str.substring(lastPos)
        return result
    }

    private val ALIGN_RE = Regex("^:?-+:?\\z")

    private fun table(state: MdStateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        if (startLine + 2 > endLine) return false
        var nextLine = startLine + 1
        if (state.sCount[nextLine] < state.blkIndent) return false
        if (state.sCount[nextLine] - state.blkIndent >= 4) return false
        var pos = state.bMarks[nextLine] + state.tShift[nextLine]
        if (pos >= state.eMarks[nextLine]) return false
        val firstCh = state.ch(pos++)
        if (firstCh != 124 && firstCh != 45 && firstCh != 58) return false
        if (pos >= state.eMarks[nextLine]) return false
        val secondCh = state.ch(pos++)
        if (secondCh != 124 && secondCh != 45 && secondCh != 58 && !isSpace(secondCh)) return false
        if (firstCh == 45 && isSpace(secondCh)) return false
        while (pos < state.eMarks[nextLine]) {
            val ch = state.ch(pos)
            if (ch != 124 && ch != 45 && ch != 58 && !isSpace(ch)) return false
            pos++
        }
        var lineText = tableLine(state, startLine + 1)
        var columns: MutableList<String> = lineText.split('|').toMutableList()
        val aligns = ArrayList<String>()
        for (i in columns.indices) {
            val t = StudioJs.trim(columns[i])
            if (t.isEmpty()) {
                if (i == 0 || i == columns.size - 1) continue else return false
            }
            if (!ALIGN_RE.matches(t)) return false
            if (t.last() == ':') {
                aligns += if (t[0] == ':') "center" else "right"
            } else if (t[0] == ':') {
                aligns += "left"
            } else {
                aligns += ""
            }
        }
        lineText = StudioJs.trim(tableLine(state, startLine))
        if (!lineText.contains('|')) return false
        if (state.sCount[startLine] - state.blkIndent >= 4) return false
        columns = escapedSplit(lineText)
        if (columns.isNotEmpty() && columns[0] == "") columns.removeAt(0)
        if (columns.isNotEmpty() && columns[columns.size - 1] == "") columns.removeAt(columns.size - 1)
        val columnCount = columns.size
        if (columnCount == 0 || columnCount != aligns.size) return false
        if (silent) return true
        val oldParentType = state.parentType
        state.parentType = "table"
        val tokenTo = state.push("table_open", "table", 1)
        val tableLines = intArrayOf(startLine, 0)
        tokenTo.map = tableLines
        state.push("thead_open", "thead", 1).map = intArrayOf(startLine, startLine + 1)
        state.push("tr_open", "tr", 1).map = intArrayOf(startLine, startLine + 1)
        for (i in columns.indices) {
            val th = state.push("th_open", "th", 1)
            if (aligns[i].isNotEmpty()) th.attrs = mutableListOf(MdAttr("style", "text-align:${aligns[i]}"))
            val il = state.push("inline", "", 0)
            il.content = StudioJs.trim(columns[i])
            il.children = ArrayList()
            state.push("th_close", "th", -1)
        }
        state.push("tr_close", "tr", -1)
        state.push("thead_close", "thead", -1)
        var tbodyLines: IntArray? = null
        var autocompletedCells = 0
        nextLine = startLine + 2
        while (nextLine < endLine) {
            if (state.sCount[nextLine] < state.blkIndent) break
            if (terminates(TERM_BLOCKQUOTE, state, nextLine, endLine)) break
            lineText = StudioJs.trim(tableLine(state, nextLine))
            if (lineText.isEmpty()) break
            if (state.sCount[nextLine] - state.blkIndent >= 4) break
            columns = escapedSplit(lineText)
            if (columns.isNotEmpty() && columns[0] == "") columns.removeAt(0)
            if (columns.isNotEmpty() && columns[columns.size - 1] == "") columns.removeAt(columns.size - 1)
            autocompletedCells += columnCount - columns.size
            if (autocompletedCells > 65536) break
            if (nextLine == startLine + 2) {
                val tbo = state.push("tbody_open", "tbody", 1)
                tbodyLines = intArrayOf(startLine + 2, 0)
                tbo.map = tbodyLines
            }
            state.push("tr_open", "tr", 1).map = intArrayOf(nextLine, nextLine + 1)
            for (i in 0 until columnCount) {
                val td = state.push("td_open", "td", 1)
                if (aligns[i].isNotEmpty()) td.attrs = mutableListOf(MdAttr("style", "text-align:${aligns[i]}"))
                val il = state.push("inline", "", 0)
                il.content = if (i < columns.size && columns[i].isNotEmpty()) StudioJs.trim(columns[i]) else ""
                il.children = ArrayList()
                state.push("td_close", "td", -1)
            }
            state.push("tr_close", "tr", -1)
            nextLine++
        }
        if (tbodyLines != null) {
            state.push("tbody_close", "tbody", -1)
            tbodyLines[1] = nextLine
        }
        state.push("table_close", "table", -1)
        tableLines[1] = nextLine
        state.parentType = oldParentType
        state.line = nextLine
        return true
    }

    // ---- comark components (plugins/components.js) ------------------------------------------------------------------

    private val RE_COMPONENT_NAME = Regex("^[a-zA-Z$][\\w$-]*")

    fun isValidComponentName(name: String) = RE_COMPONENT_NAME.containsMatchIn(name)

    private val BLOCK_YAML_LINES = mapOf(
        "---" to "---", "```yaml [props]" to "```", "~~~yaml [props]" to "~~~", "```yml [props]" to "```", "~~~yml [props]" to "~~~",
    )

    private fun applyProps(token: MdToken, params: StudioComarkSyntax.BlockParams) {
        if (params.malformed) token.malformedProps = true
        params.props?.forEach { (key, value) -> if (key == "class") token.attrJoin(key, value) else token.attrSet(key, value) }
    }

    private fun comarkBlockShorthand(state: MdStateBlock, startLine: Int, silent: Boolean): Boolean {
        val line = state.slice(state.bMarks[startLine] + state.tShift[startLine], state.eMarks[startLine])
        if (line.isEmpty() || line[0] != ':' || !isValidComponentName(line.substring(1))) return false
        val params = StudioComarkSyntax.parseBlockParams(line.substring(1))
        if (params.remaining != null) return false
        if (!silent) {
            if (params.content != null) {
                val open = state.push("mdc_block_shorthand", params.name, 1)
                applyProps(open, params)
                open.map = intArrayOf(startLine, startLine + 1)
                val inline = state.push("inline", "", 0)
                inline.content = params.content
                inline.children = ArrayList()
                state.push("mdc_block_shorthand", params.name, -1).map = intArrayOf(startLine, startLine + 1)
            } else {
                val token = state.push("mdc_block_shorthand", params.name, 0)
                token.map = intArrayOf(startLine, startLine + 1)
                applyProps(token, params)
            }
        }
        state.line = startLine + 1
        return true
    }

    private fun comarkBlock(state: MdStateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        var pos: Int
        var nextLine: Int
        var autoClosed = false
        var start = state.bMarks[startLine] + state.tShift[startLine]
        var max = state.eMarks[startLine]
        val indent = state.sCount[startLine]
        var inCodeFence = false
        var codeFenceCharCode = 0
        var codeFenceCount = 0
        var nestingDepth = 0
        if (state.ch(start) != 58) return false
        pos = start + 1
        while (pos <= max) {
            if (state.ch(pos) != 58) break
            pos++
        }
        val markerCount = pos - start
        if (markerCount < 2) return false
        val markup = state.slice(start, pos)
        val nameStart = state.skipSpaces(pos)
        if (nameStart < max && !isValidComponentName(state.slice(nameStart, max))) return false
        val params = StudioComarkSyntax.parseBlockParams(state.slice(pos, max))
        if (params.name.isEmpty()) return false
        if (silent) return true
        val childShifts = ArrayList<Int>()
        var codeFenceShift = 0
        var hasOutdentedChild = false
        nextLine = startLine
        while (true) {
            nextLine++
            if (nextLine >= endLine) break
            start = state.bMarks[nextLine] + state.tShift[nextLine]
            max = state.eMarks[nextLine]
            if (start < max && state.sCount[nextLine] < state.blkIndent) break
            val lineIndent = if (start >= max) indent else state.sCount[nextLine]
            if (lineIndent < indent) hasOutdentedChild = true
            childShifts += if (start >= max) 0 else if (inCodeFence) codeFenceShift else minOf(indent, lineIndent)
            val lineCharCode = state.ch(start)
            if (inCodeFence) {
                if (lineCharCode == codeFenceCharCode) {
                    var fencePos = start + 1
                    while (fencePos < max && state.ch(fencePos) == codeFenceCharCode) fencePos++
                    if (fencePos - start >= codeFenceCount) {
                        val afterFence = state.skipSpaces(fencePos)
                        if (afterFence >= max) inCodeFence = false
                    }
                }
                continue
            }
            if (lineCharCode == 0x60 || lineCharCode == 0x7e) {
                var fencePos = start + 1
                while (fencePos < max && state.ch(fencePos) == lineCharCode) fencePos++
                if (fencePos - start >= 3) {
                    inCodeFence = true
                    codeFenceCharCode = lineCharCode
                    codeFenceCount = fencePos - start
                    codeFenceShift = childShifts[childShifts.size - 1]
                    continue
                }
            }
            if (lineCharCode != 58) continue
            pos = start + 1
            while (pos <= max) {
                if (state.ch(pos) != 58) break
                pos++
            }
            if (pos - start != markerCount) continue
            pos = state.skipSpaces(pos)
            if (pos < max) {
                nestingDepth++
                continue
            }
            if (nestingDepth > 0) {
                nestingDepth--
                continue
            }
            autoClosed = true
            break
        }
        val oldParent = state.parentType
        val oldLineMax = state.lineMax
        state.parentType = "comark_block"
        state.lineMax = nextLine
        val tokenOpen = state.push("mdc_block_open", params.name, 1)
        tokenOpen.markup = markup
        tokenOpen.block = true
        tokenOpen.info = params.name
        tokenOpen.map = intArrayOf(startLine, nextLine)
        applyProps(tokenOpen, params)
        if (params.content != null) {
            state.push("paragraph_open", "p", 1).map = intArrayOf(startLine, startLine + 1)
            val inline = state.push("inline", "", 0)
            inline.content = params.content
            inline.children = ArrayList()
            state.push("paragraph_close", "p", -1)
        }
        state.env.comarkBlockTokens.add(0, tokenOpen)
        if (!hasOutdentedChild || !tokenizeDedented(state, startLine + 1, nextLine, childShifts)) {
            val blkIndent = state.blkIndent
            state.blkIndent = indent
            tokenize(state, startLine + 1, nextLine)
            state.blkIndent = blkIndent
        }
        state.env.comarkBlockTokens.removeAt(0)
        val tokenClose = state.push("mdc_block_close", params.name, -1)
        tokenClose.map = intArrayOf(startLine, nextLine)
        tokenClose.markup = state.slice(start, pos)
        tokenClose.block = true
        val openIdx = state.tokens.indexOf(tokenOpen)
        val closeIdx = state.tokens.indexOf(tokenClose)
        val inner = state.tokens.subList(openIdx + 1, closeIdx).filter { it.level == tokenOpen.level + 1 }
        for (t in inner) if (inner.size <= 2 && t.tag == "p") t.hidden = true
        state.parentType = oldParent
        state.lineMax = oldLineMax
        state.line = nextLine + (if (autoClosed) 1 else 0)
        return true
    }

    private fun dedentLine(state: MdStateBlock, line: Int, columns: Int): Boolean {
        val lineStart = state.bMarks[line]
        val max = state.eMarks[line]
        var pos = lineStart
        var consumed = 0
        while (pos < max && consumed < columns) {
            val code = state.ch(pos)
            if (code == 0x20) {
                consumed++
            } else if (code == 0x09) {
                val width = 4 - ((consumed + state.bsCount[line]) % 4)
                if (consumed + width > columns) return false
                consumed += width
            } else {
                break
            }
            pos++
        }
        if (consumed > 0) {
            state.bMarks[line] = pos
            state.bsCount[line] += consumed
            state.sCount[line] -= consumed
            state.tShift[line] -= pos - lineStart
        }
        return true
    }

    private fun tokenizeDedented(state: MdStateBlock, from: Int, to: Int, shifts: List<Int>): Boolean {
        val b = ArrayList<Int>()
        val bs = ArrayList<Int>()
        val sc = ArrayList<Int>()
        val ts = ArrayList<Int>()
        fun restore() {
            for (i in b.indices) {
                val line = from + i
                state.bMarks[line] = b[i]; state.bsCount[line] = bs[i]; state.sCount[line] = sc[i]; state.tShift[line] = ts[i]
            }
        }
        for (line in from until to) {
            b += state.bMarks[line]; bs += state.bsCount[line]; sc += state.sCount[line]; ts += state.tShift[line]
            if (!dedentLine(state, line, shifts[line - from])) {
                restore()
                return false
            }
        }
        val blkIndent = state.blkIndent
        state.blkIndent = 0
        tokenize(state, from, to)
        state.blkIndent = blkIndent
        restore()
        return true
    }

    private fun comarkBlockYaml(state: MdStateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        if (state.env.comarkBlockTokens.isEmpty()) return false
        val start = state.bMarks[startLine] + state.tShift[startLine]
        val end = state.eMarks[startLine]
        val line = state.slice(start, end)
        val closingFence = BLOCK_YAML_LINES[line] ?: return false
        if (line == "---") {
            val parentOpenLine = state.env.comarkBlockTokens[0].map?.get(0)
            if (parentOpenLine == null || startLine != parentOpenLine + 1) return false
        }
        var lineEnd = startLine + 1
        var found = false
        while (lineEnd < endLine) {
            val inner = state.slice(state.bMarks[lineEnd] + state.tShift[startLine], state.eMarks[lineEnd])
            if (inner == closingFence) {
                found = true
                break
            }
            lineEnd++
        }
        if (!found) return false
        if (!silent) {
            val yaml = state.getLines(startLine + 1, lineEnd, state.blkIndent, false)
            val data = try {
                StudioYamlLoader.parseYaml(yaml)
            } catch (e: StudioYamlException) {
                if (!state.env.lenientPropsYaml) throw StudioParseException("props YAML: ${e.message}")
                state.env.propsYamlErrors += startLine
                JsUndefined
            }
            if (state.env.lenientPropsYaml && StudioYamlLoader.hasComment(yaml)) state.env.propsYamlComments += startLine
            val token = state.env.comarkBlockTokens[0]
            for ((key, value) in jsEntries(data)) {
                if (key == "class") {
                    token.attrJoin(key, value)
                    continue
                }
                if (value is String) {
                    token.attrSet(key, value)
                } else {
                    token.attrSet(":$key", StudioJs.jsonStringify(value))
                }
            }
        }
        state.line = lineEnd + 1
        return true
    }

    /** `Object.entries(data || {})` */
    @Suppress("UNCHECKED_CAST")
    private fun jsEntries(data: Any?): List<Pair<String, Any?>> = when (data) {
        is Map<*, *> -> StudioJs.entries(data as Map<String, Any?>)
        is List<*> -> data.mapIndexed { i, v -> i.toString() to v }
        is String -> data.mapIndexed { i, c -> i.toString() to c.toString() }
        else -> emptyList()
    }

    private val SLOT_BREAK = Regex("^#\\w+")

    private fun comarkBlockSlots(state: MdStateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        if (state.env.comarkBlockTokens.isEmpty()) return false
        val start = state.bMarks[startLine] + state.tShift[startLine]
        if (!(state.ch(start) == 35 && state.ch(start + 1) != 32 && state.ch(start + 1) != 35)) return false
        val line = state.slice(start, state.eMarks[startLine])
        val params = StudioComarkSyntax.parseBlockParams(line.substring(1))
        var lineEnd = startLine + 1
        var inCodeFence = false
        var codeFenceChar = ' '
        var codeFenceCount = 0
        while (lineEnd < endLine) {
            val inner = state.slice(state.bMarks[lineEnd] + state.tShift[startLine], state.eMarks[lineEnd])
            if (inCodeFence) {
                if (inner.isNotEmpty() && inner[0] == codeFenceChar) {
                    var fencePos = 1
                    while (fencePos < inner.length && inner[fencePos] == codeFenceChar) fencePos++
                    if (fencePos >= codeFenceCount && StudioJs.trim(inner.substring(fencePos)) == "") inCodeFence = false
                }
                lineEnd++
                continue
            }
            if (inner.isNotEmpty() && (inner[0] == '`' || inner[0] == '~')) {
                val ch = inner[0]
                var fencePos = 1
                while (fencePos < inner.length && inner[fencePos] == ch) fencePos++
                if (fencePos >= 3) {
                    inCodeFence = true
                    codeFenceChar = ch
                    codeFenceCount = fencePos
                    lineEnd++
                    continue
                }
            }
            if (SLOT_BREAK.containsMatchIn(inner) || inner.startsWith("::")) break
            lineEnd++
        }
        if (silent) {
            state.line = lineEnd
            return true
        }
        val oldLineMax = state.lineMax
        val slot = state.push("mdc_block_slot", "template", 1)
        slot.attrSet("#${params.name}", "")
        applyProps(slot, params)
        state.line = startLine + 1
        state.lineMax = lineEnd
        tokenize(state, startLine + 1, lineEnd)
        state.push("mdc_block_slot", "template", -1)
        state.line = lineEnd
        state.lineMax = oldLineMax
        return true
    }
}
