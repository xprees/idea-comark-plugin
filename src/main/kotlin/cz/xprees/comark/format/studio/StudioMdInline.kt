package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioMdUtil.isSpace

/** markdown-it delimiter (emphasis, strikethrough) */
internal class MdDelimiter(val marker: Int, var length: Int, val token: Int, var end: Int, var open: Boolean, var close: Boolean)

/** markdown-it `StateInline` */
internal class MdStateInline(val src: String, val env: MdEnv, val tokens: MutableList<MdToken>) {
    var tokensMeta: MutableList<MutableList<MdDelimiter>?> = MutableList(tokens.size) { null }
    var pos = 0
    var posMax = src.length
    var level = 0
    var pending = StringBuilder()
    var pendingLevel = 0
    val cache = HashMap<Int, Int>()
    var delimiters: MutableList<MdDelimiter> = ArrayList()
    private val prevDelimiters = ArrayList<MutableList<MdDelimiter>>()
    val backticks = HashMap<Int, Int>()
    var backticksScanned = false
    var linkLevel = 0

    fun ch(i: Int): Int = if (i >= 0 && i < src.length) src[i].code else -1

    fun slice(a: Int, b: Int): String {
        val s = a.coerceIn(0, src.length)
        val e = b.coerceIn(0, src.length)
        return if (s >= e) "" else src.substring(s, e)
    }

    fun pushPending(): MdToken {
        val token = MdToken("text", "", 0)
        token.content = pending.toString()
        token.level = pendingLevel
        tokens += token
        pending = StringBuilder()
        return token
    }

    fun push(type: String, tag: String, nesting: Int): MdToken {
        if (pending.isNotEmpty()) pushPending()
        val token = MdToken(type, tag, nesting)
        var meta: MutableList<MdDelimiter>? = null
        if (nesting < 0) {
            level--
            delimiters = if (prevDelimiters.isEmpty()) ArrayList() else prevDelimiters.removeAt(prevDelimiters.size - 1)
        }
        token.level = level
        if (nesting > 0) {
            level++
            prevDelimiters += delimiters
            delimiters = ArrayList()
            meta = delimiters
        }
        pendingLevel = level
        tokens += token
        tokensMeta += meta
        return token
    }

    class Scanned(val canOpen: Boolean, val canClose: Boolean, val length: Int)

    fun scanDelims(start: Int, canSplitWord: Boolean): Scanned {
        val max = posMax
        val marker = ch(start)
        var lastChar: Int
        if (start == 0) {
            lastChar = 32
        } else if (start == 1) {
            lastChar = ch(0)
            if ((lastChar and 0xF800) == 0xD800) lastChar = 0xFFFD
        } else {
            lastChar = ch(start - 1)
            if ((lastChar and 0xFC00) == 0xDC00) {
                val highSurr = ch(start - 2)
                lastChar = if ((highSurr and 0xFC00) == 0xD800) 0x10000 + ((highSurr - 0xD800) shl 10) + (lastChar - 0xDC00) else 0xFFFD
            } else if ((lastChar and 0xFC00) == 0xD800) {
                lastChar = 0xFFFD
            }
        }
        var pos = start
        while (pos < max && ch(pos) == marker) pos++
        val count = pos - start
        var nextChar = if (pos < max) ch(pos) else 32
        if ((nextChar and 0xFC00) == 0xD800) {
            val lowSurr = ch(pos + 1)
            nextChar = if ((lowSurr and 0xFC00) == 0xDC00) 0x10000 + ((nextChar - 0xD800) shl 10) + (lowSurr - 0xDC00) else 0xFFFD
        } else if ((nextChar and 0xFC00) == 0xDC00) {
            nextChar = 0xFFFD
        }
        val isLastPunctChar = StudioMdUtil.isMdAsciiPunct(lastChar) || StudioMdUtil.isPunctCharCode(lastChar)
        val isNextPunctChar = StudioMdUtil.isMdAsciiPunct(nextChar) || StudioMdUtil.isPunctCharCode(nextChar)
        val isLastWhiteSpace = StudioMdUtil.isWhiteSpace(lastChar)
        val isNextWhiteSpace = StudioMdUtil.isWhiteSpace(nextChar)
        val leftFlanking = !isNextWhiteSpace && (!isNextPunctChar || isLastWhiteSpace || isLastPunctChar)
        val rightFlanking = !isLastWhiteSpace && (!isLastPunctChar || isNextWhiteSpace || isNextPunctChar)
        return Scanned(
            canOpen = leftFlanking && (canSplitWord || !rightFlanking || isLastPunctChar),
            canClose = rightFlanking && (canSplitWord || !leftFlanking || isNextPunctChar),
            length = count,
        )
    }
}

/** markdown-it `helpers` */
internal object StudioMdHelpers {
    class Dest(val ok: Boolean, val pos: Int, val str: String)

    fun parseLinkDestination(str: String, start: Int, max: Int): Dest {
        fun c(i: Int) = if (i >= 0 && i < str.length) str[i].code else -1
        var pos = start
        val fail = Dest(false, 0, "")
        if (c(pos) == 60) {
            pos++
            while (pos < max) {
                val code = c(pos)
                if (code == 10) return fail
                if (code == 60) return fail
                if (code == 62) return Dest(true, pos + 1, StudioMdUtil.unescapeAll(str.substring(start + 1, pos)))
                if (code == 92 && pos + 1 < max) {
                    pos += 2
                    continue
                }
                pos++
            }
            return fail
        }
        var level = 0
        while (pos < max) {
            val code = c(pos)
            if (code == 32) break
            if (code < 32 || code == 127) break
            if (code == 92 && pos + 1 < max) {
                if (c(pos + 1) == 32) break
                pos += 2
                continue
            }
            if (code == 40) {
                level++
                if (level > 32) return fail
            }
            if (code == 41) {
                if (level == 0) break
                level--
            }
            pos++
        }
        if (start == pos) return fail
        if (level != 0) return fail
        return Dest(true, pos, StudioMdUtil.unescapeAll(str.substring(start, minOf(pos, str.length))))
    }

    class Title(val ok: Boolean, val canContinue: Boolean, val pos: Int, val str: String, val marker: Int)

    fun parseLinkTitle(str: String, startIn: Int, max: Int, prev: Title?): Title {
        fun c(i: Int) = if (i >= 0 && i < str.length) str[i].code else -1
        var start = startIn
        var pos = startIn
        var acc: String
        val marker: Int
        if (prev != null) {
            acc = prev.str
            marker = prev.marker
        } else {
            if (pos >= max) return Title(false, false, 0, "", 0)
            var m = c(pos)
            if (m != 34 && m != 39 && m != 40) return Title(false, false, 0, "", 0)
            start++
            pos++
            if (m == 40) m = 41
            marker = m
            acc = ""
        }
        while (pos < max) {
            val code = c(pos)
            if (code == marker) {
                return Title(true, false, pos + 1, acc + StudioMdUtil.unescapeAll(str.substring(start, pos)), marker)
            } else if (code == 40 && marker == 41) {
                return Title(false, false, 0, acc, marker)
            } else if (code == 92 && pos + 1 < max) {
                pos++
            }
            pos++
        }
        acc += StudioMdUtil.unescapeAll(str.substring(start, minOf(pos, str.length)))
        return Title(false, true, 0, acc, marker)
    }

    fun parseLinkLabel(state: MdStateInline, start: Int, disableNested: Boolean): Int {
        var found = false
        val max = state.posMax
        val oldPos = state.pos
        state.pos = start + 1
        var level = 1
        while (state.pos < max) {
            val marker = state.ch(state.pos)
            if (marker == 93) {
                level--
                if (level == 0) {
                    found = true
                    break
                }
            }
            val prevPos = state.pos
            StudioMdInline.skipToken(state)
            if (marker == 91) {
                if (prevPos == state.pos - 1) {
                    level++
                } else if (disableNested) {
                    state.pos = oldPos
                    return -1
                }
            }
        }
        val labelEnd = if (found) state.pos else -1
        state.pos = oldPos
        return labelEnd
    }
}

/**
 * markdown-exit's inline parser with comark's inline rules. Rule order (after `createMarkdownParser` and Studio's emoji
 * plugin): comark_html_inline, text, linkify (off), newline, escape, backticks, strikethrough, emoji, emphasis,
 * comark_inline_span, link, image, autolink, html_inline (shadowed by comark's), entity, comark_inline_props,
 * comark_inline_component. Post-processing: balance_pairs, strikethrough, emphasis, fragments_join.
 */
internal object StudioMdInline {
    private const val MAX_NESTING = 100

    private enum class R { HTML, TEXT, NEWLINE, ESCAPE, BACKTICKS, STRIKETHROUGH, EMOJI, EMPHASIS, SPAN, LINK, IMAGE, AUTOLINK, ENTITY, PROPS, COMPONENT }

    private val RULES = R.values()

    private fun rule(r: R, s: MdStateInline, silent: Boolean): Boolean = when (r) {
        R.HTML -> htmlInline(s, silent)
        R.TEXT -> text(s, silent)
        R.NEWLINE -> newline(s, silent)
        R.ESCAPE -> escape(s, silent)
        R.BACKTICKS -> backtick(s, silent)
        R.STRIKETHROUGH -> strikethroughTokenize(s, silent)
        R.EMOJI -> emoji(s, silent)
        R.EMPHASIS -> emphasisTokenize(s, silent)
        R.SPAN -> comarkInlineSpan(s, silent)
        R.LINK -> link(s, silent)
        R.IMAGE -> image(s, silent)
        R.AUTOLINK -> autolink(s, silent)
        R.ENTITY -> entity(s, silent)
        R.PROPS -> comarkInlineProps(s, silent)
        R.COMPONENT -> comarkInlineComponent(s, silent)
    }

    fun parse(str: String, env: MdEnv, outTokens: MutableList<MdToken>) {
        val state = MdStateInline(str, env, outTokens)
        tokenize(state)
        linkPairs(state)
        strikethroughPostProcess(state)
        emphasisPostProcess(state)
        fragmentsJoin(state)
    }

    fun skipToken(state: MdStateInline) {
        val pos = state.pos
        val cached = state.cache[pos]
        if (cached != null) {
            state.pos = cached
            return
        }
        var ok = false
        if (state.level < MAX_NESTING) {
            for (r in RULES) {
                state.level++
                ok = rule(r, state, true)
                state.level--
                if (ok) {
                    if (pos >= state.pos) throw StudioParseException("inline rule didn't increment state.pos")
                    break
                }
            }
        } else {
            state.pos = state.posMax
        }
        if (!ok) state.pos++
        state.cache[pos] = state.pos
    }

    fun tokenize(state: MdStateInline) {
        val end = state.posMax
        while (state.pos < end) {
            val prevPos = state.pos
            var ok = false
            if (state.level < MAX_NESTING) {
                for (r in RULES) {
                    ok = rule(r, state, false)
                    if (ok) {
                        if (prevPos >= state.pos) throw StudioParseException("inline rule didn't increment state.pos")
                        break
                    }
                }
            }
            if (ok) {
                if (state.pos >= end) break
                continue
            }
            state.pending.append(state.src[state.pos++])
        }
        if (state.pending.isNotEmpty()) state.pushPending()
    }

    // ---- rules --------------------------------------------------------------------------------------------------------

    private fun isTerminatorChar(ch: Int) = when (ch) {
        10, 33, 35, 36, 37, 38, 42, 43, 45, 58, 60, 61, 62, 64, 91, 92, 93, 94, 95, 96, 123, 125, 126 -> true
        else -> false
    }

    private fun text(state: MdStateInline, silent: Boolean): Boolean {
        var pos = state.pos
        while (pos < state.posMax && !isTerminatorChar(state.ch(pos))) pos++
        if (pos == state.pos) return false
        if (!silent) state.pending.append(state.src, state.pos, pos)
        state.pos = pos
        return true
    }

    private fun newline(state: MdStateInline, silent: Boolean): Boolean {
        var pos = state.pos
        if (state.ch(pos) != 10) return false
        val pmax = state.pending.length - 1
        val max = state.posMax
        if (!silent) {
            if (pmax >= 0 && state.pending[pmax] == ' ') {
                if (pmax >= 1 && state.pending[pmax - 1] == ' ') {
                    var ws = pmax - 1
                    while (ws >= 1 && state.pending[ws - 1] == ' ') ws--
                    state.pending.setLength(ws)
                    state.push("hardbreak", "br", 0)
                } else {
                    state.pending.setLength(pmax)
                    state.push("softbreak", "br", 0)
                }
            } else {
                state.push("softbreak", "br", 0)
            }
        }
        pos++
        while (pos < max && isSpace(state.ch(pos))) pos++
        state.pos = pos
        return true
    }

    private const val ESCAPABLE = "\\!\"#$%&'()*+,./:;<=>?@[]^_`{|}~-"

    private fun escape(state: MdStateInline, silent: Boolean): Boolean {
        var pos = state.pos
        val max = state.posMax
        if (state.ch(pos) != 92) return false
        pos++
        if (pos >= max) return false
        var ch1 = state.ch(pos)
        if (ch1 == 10) {
            if (!silent) state.push("hardbreak", "br", 0)
            pos++
            while (pos < max) {
                ch1 = state.ch(pos)
                if (!isSpace(ch1)) break
                pos++
            }
            state.pos = pos
            return true
        }
        var escapedStr = state.src[pos].toString()
        if (ch1 in 0xD800..0xDBFF && pos + 1 < max) {
            val ch2 = state.ch(pos + 1)
            if (ch2 in 0xDC00..0xDFFF) {
                escapedStr += state.src[pos + 1]
                pos++
            }
        }
        val origStr = "\\" + escapedStr
        if (!silent) {
            val token = state.push("text_special", "", 0)
            token.content = if (ch1 < 256 && ESCAPABLE.indexOf(ch1.toChar()) >= 0) escapedStr else origStr
            token.markup = origStr
            token.info = "escape"
        }
        state.pos = pos + 1
        return true
    }

    private val BACKTICK_TRIM = Regex("^ ([^\\n\\r\\u2028\\u2029]+) \\z")

    private fun backtick(state: MdStateInline, silent: Boolean): Boolean {
        var pos = state.pos
        val src = state.src
        if (state.ch(pos) != 96) return false
        val start = pos
        pos++
        val max = state.posMax
        while (pos < max && state.ch(pos) == 96) pos++
        val marker = state.slice(start, pos)
        val openerLength = marker.length
        if (state.backticksScanned && (state.backticks[openerLength] ?: 0) <= start) {
            if (!silent) state.pending.append(marker)
            state.pos += openerLength
            return true
        }
        var matchEnd = pos
        while (true) {
            val matchStart = src.indexOf('`', matchEnd)
            if (matchStart == -1) break
            matchEnd = matchStart + 1
            while (matchEnd < max && state.ch(matchEnd) == 96) matchEnd++
            val closerLength = matchEnd - matchStart
            if (closerLength == openerLength) {
                if (!silent) {
                    val token = state.push("code_inline", "code", 0)
                    token.markup = marker
                    val content = state.slice(pos, matchStart).replace('\n', ' ')
                    token.content = BACKTICK_TRIM.matchEntire(content)?.groupValues?.get(1) ?: content
                }
                state.pos = matchEnd
                return true
            }
            state.backticks[closerLength] = matchStart
        }
        state.backticksScanned = true
        if (!silent) state.pending.append(marker)
        state.pos += openerLength
        return true
    }

    private fun strikethroughTokenize(state: MdStateInline, silent: Boolean): Boolean {
        val start = state.pos
        val marker = state.ch(start)
        if (silent) return false
        if (marker != 126) return false
        val scanned = state.scanDelims(state.pos, true)
        var len = scanned.length
        if (len < 2) return false
        if (len % 2 != 0) {
            state.push("text", "", 0).content = "~"
            len--
        }
        var i = 0
        while (i < len) {
            state.push("text", "", 0).content = "~~"
            state.delimiters += MdDelimiter(marker, 0, state.tokens.size - 1, -1, scanned.canOpen, scanned.canClose)
            i += 2
        }
        state.pos += scanned.length
        return true
    }

    private fun strikethroughPost(state: MdStateInline, delimiters: List<MdDelimiter>) {
        val loneMarkers = ArrayList<Int>()
        for (startDelim in delimiters) {
            if (startDelim.marker != 126) continue
            if (startDelim.end == -1) continue
            val endDelim = delimiters[startDelim.end]
            var token = state.tokens[startDelim.token]
            token.type = "s_open"; token.tag = "s"; token.nesting = 1; token.markup = "~~"; token.content = ""
            token = state.tokens[endDelim.token]
            token.type = "s_close"; token.tag = "s"; token.nesting = -1; token.markup = "~~"; token.content = ""
            val before = state.tokens[endDelim.token - 1]
            if (before.type == "text" && before.content == "~") loneMarkers += endDelim.token - 1
        }
        while (loneMarkers.isNotEmpty()) {
            val i = loneMarkers.removeAt(loneMarkers.size - 1)
            var j = i + 1
            while (j < state.tokens.size && state.tokens[j].type == "s_close") j++
            j--
            if (i != j) {
                val t = state.tokens[j]
                state.tokens[j] = state.tokens[i]
                state.tokens[i] = t
            }
        }
    }

    private fun strikethroughPostProcess(state: MdStateInline) {
        strikethroughPost(state, state.delimiters)
        for (meta in state.tokensMeta) if (meta != null) strikethroughPost(state, meta)
    }

    private fun emphasisTokenize(state: MdStateInline, silent: Boolean): Boolean {
        val start = state.pos
        val marker = state.ch(start)
        if (silent) return false
        if (marker != 95 && marker != 42) return false
        val scanned = state.scanDelims(state.pos, marker == 42)
        for (i in 0 until scanned.length) {
            state.push("text", "", 0).content = marker.toChar().toString()
            state.delimiters += MdDelimiter(marker, scanned.length, state.tokens.size - 1, -1, scanned.canOpen, scanned.canClose)
        }
        state.pos += scanned.length
        return true
    }

    private fun emphasisPost(state: MdStateInline, delimiters: List<MdDelimiter>) {
        var i = delimiters.size - 1
        while (i >= 0) {
            val startDelim = delimiters[i]
            if ((startDelim.marker != 95 && startDelim.marker != 42) || startDelim.end == -1) {
                i--
                continue
            }
            val endDelim = delimiters[startDelim.end]
            val isStrong = i > 0 && delimiters[i - 1].end == startDelim.end + 1 && delimiters[i - 1].marker == startDelim.marker &&
                delimiters[i - 1].token == startDelim.token - 1 && delimiters[startDelim.end + 1].token == endDelim.token + 1
            val ch = startDelim.marker.toChar().toString()
            val o = state.tokens[startDelim.token]
            o.type = if (isStrong) "strong_open" else "em_open"
            o.tag = if (isStrong) "strong" else "em"
            o.nesting = 1
            o.markup = if (isStrong) ch + ch else ch
            o.content = ""
            val c = state.tokens[endDelim.token]
            c.type = if (isStrong) "strong_close" else "em_close"
            c.tag = if (isStrong) "strong" else "em"
            c.nesting = -1
            c.markup = if (isStrong) ch + ch else ch
            c.content = ""
            if (isStrong) {
                state.tokens[delimiters[i - 1].token].content = ""
                state.tokens[delimiters[startDelim.end + 1].token].content = ""
                i--
            }
            i--
        }
    }

    private fun emphasisPostProcess(state: MdStateInline) {
        emphasisPost(state, state.delimiters)
        for (meta in state.tokensMeta) if (meta != null) emphasisPost(state, meta)
    }

    private fun processDelimiters(delimiters: List<MdDelimiter>) {
        val openersBottom = HashMap<Int, IntArray>()
        val max = delimiters.size
        if (max == 0) return
        var headerIdx = 0
        var lastTokenIdx = -2
        val jumps = ArrayList<Int>()
        for (closerIdx in 0 until max) {
            val closer = delimiters[closerIdx]
            jumps += 0
            if (delimiters[headerIdx].marker != closer.marker || lastTokenIdx != closer.token - 1) headerIdx = closerIdx
            lastTokenIdx = closer.token
            if (!closer.close) continue
            val bottoms = openersBottom.getOrPut(closer.marker) { IntArray(6) { -1 } }
            val minOpenerIdx = bottoms[(if (closer.open) 3 else 0) + closer.length % 3]
            var openerIdx = headerIdx - jumps[headerIdx] - 1
            var newMinOpenerIdx = openerIdx
            while (openerIdx > minOpenerIdx) {
                val opener = delimiters[openerIdx]
                if (opener.marker != closer.marker) {
                    openerIdx -= jumps[openerIdx] + 1
                    continue
                }
                if (opener.open && opener.end < 0) {
                    var isOddMatch = false
                    if (opener.close || closer.open) {
                        if ((opener.length + closer.length) % 3 == 0) {
                            if (opener.length % 3 != 0 || closer.length % 3 != 0) isOddMatch = true
                        }
                    }
                    if (!isOddMatch) {
                        val lastJump = if (openerIdx > 0 && !delimiters[openerIdx - 1].open) jumps[openerIdx - 1] + 1 else 0
                        jumps[closerIdx] = closerIdx - openerIdx + lastJump
                        jumps[openerIdx] = lastJump
                        closer.open = false
                        opener.end = closerIdx
                        opener.close = false
                        newMinOpenerIdx = -1
                        lastTokenIdx = -2
                        break
                    }
                }
                openerIdx -= jumps[openerIdx] + 1
            }
            if (newMinOpenerIdx != -1) bottoms[(if (closer.open) 3 else 0) + closer.length % 3] = newMinOpenerIdx
        }
    }

    private fun linkPairs(state: MdStateInline) {
        processDelimiters(state.delimiters)
        for (meta in state.tokensMeta) if (meta != null) processDelimiters(meta)
    }

    private fun fragmentsJoin(state: MdStateInline) {
        var level = 0
        val tokens = state.tokens
        val max = tokens.size
        var last = 0
        var curr = 0
        while (curr < max) {
            if (tokens[curr].nesting < 0) level--
            tokens[curr].level = level
            if (tokens[curr].nesting > 0) level++
            if (tokens[curr].type == "text" && curr + 1 < max && tokens[curr + 1].type == "text") {
                tokens[curr + 1].content = tokens[curr].content + tokens[curr + 1].content
            } else {
                if (curr != last) tokens[last] = tokens[curr]
                last++
            }
            curr++
        }
        while (tokens.size > last) tokens.removeAt(tokens.size - 1)
    }

    private val DIGITAL_RE = Regex("^&#([xX][a-fA-F0-9]{1,6}|\\d{1,7});")
    private val NAMED_RE = Regex("^&([a-zA-Z][a-zA-Z0-9]{1,31});")

    private fun entity(state: MdStateInline, silent: Boolean): Boolean {
        val pos = state.pos
        val max = state.posMax
        if (state.ch(pos) != 38) return false
        if (pos + 1 >= max) return false
        val rest = state.src.substring(pos)
        if (state.ch(pos + 1) == 35) {
            val m = DIGITAL_RE.find(rest)
            if (m != null) {
                if (!silent) {
                    val g = m.groupValues[1]
                    val code = if (g[0].lowercaseChar() == 'x') g.substring(1).toLong(16) else g.toLong()
                    val token = state.push("text_special", "", 0)
                    token.content = if (StudioMdUtil.isValidEntityCode(code)) StudioMdUtil.fromCodePoint(code.toInt()) else "\uFFFD"
                    token.markup = m.value
                    token.info = "entity"
                }
                state.pos += m.value.length
                return true
            }
        } else {
            val m = NAMED_RE.find(rest)
            if (m != null) {
                val decoded = StudioEntities.decodeHtmlStrict(m.value)
                if (decoded != m.value) {
                    if (!silent) {
                        val token = state.push("text_special", "", 0)
                        token.content = decoded
                        token.markup = m.value
                        token.info = "entity"
                    }
                    state.pos += m.value.length
                    return true
                }
            }
        }
        return false
    }

    private val AUTOLINK_RE = Regex("^([a-zA-Z][a-zA-Z0-9+.-]{1,31}):([^<>\\x00-\\x20]*)\\z")
    private val EMAIL_RE =
        Regex("^([\\w.!#$%&'*+/=?^`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*)\\z")

    private fun autolink(state: MdStateInline, silent: Boolean): Boolean {
        var pos = state.pos
        if (state.ch(pos) != 60) return false
        val start = state.pos
        val max = state.posMax
        while (true) {
            if (++pos >= max) return false
            val ch = state.ch(pos)
            if (ch == 60) return false
            if (ch == 62) break
        }
        val url = state.slice(start + 1, pos)
        val isUrl = AUTOLINK_RE.matches(url)
        if (isUrl || EMAIL_RE.matches(url)) {
            val fullUrl = StudioMdUtil.normalizeLink(if (isUrl) url else "mailto:$url")
            if (!StudioMdUtil.validateLink(fullUrl)) return false
            if (!silent) {
                val o = state.push("link_open", "a", 1)
                o.attrs = mutableListOf(MdAttr("href", fullUrl))
                o.markup = "autolink"
                o.info = "auto"
                state.push("text", "", 0).content = StudioMdUtil.normalizeLinkText(url)
                val c = state.push("link_close", "a", -1)
                c.markup = "autolink"
                c.info = "auto"
            }
            state.pos += url.length + 2
            return true
        }
        return false
    }

    private fun skipWs(state: MdStateInline, from: Int, max: Int): Int {
        var pos = from
        while (pos < max) {
            val code = state.ch(pos)
            if (!isSpace(code) && code != 10) break
            pos++
        }
        return pos
    }

    private fun link(state: MdStateInline, silent: Boolean): Boolean {
        var href = ""
        var title = ""
        var start: Int
        var parseReference = true
        var referenceLabel: String? = null
        if (state.ch(state.pos) != 91) return false
        val oldPos = state.pos
        val max = state.posMax
        val labelStart = state.pos + 1
        val labelEnd = StudioMdHelpers.parseLinkLabel(state, state.pos, true)
        if (labelEnd < 0) return false
        var pos = labelEnd + 1
        if (pos < max && state.ch(pos) == 40) {
            parseReference = false
            pos++
            pos = skipWs(state, pos, max)
            if (pos >= max) return false
            start = pos
            val res = StudioMdHelpers.parseLinkDestination(state.src, pos, state.posMax)
            if (res.ok) {
                href = StudioMdUtil.normalizeLink(res.str)
                if (StudioMdUtil.validateLink(href)) pos = res.pos else href = ""
                start = pos
                pos = skipWs(state, pos, max)
                val t = StudioMdHelpers.parseLinkTitle(state.src, pos, state.posMax, null)
                if (pos < max && start != pos && t.ok) {
                    title = t.str
                    pos = t.pos
                    pos = skipWs(state, pos, max)
                }
            }
            if (pos >= max || state.ch(pos) != 41) parseReference = true
            pos++
        }
        if (parseReference) {
            val refs = state.env.references ?: return false
            var label: String? = null
            if (pos < max && state.ch(pos) == 91) {
                start = pos + 1
                pos = StudioMdHelpers.parseLinkLabel(state, pos, false)
                if (pos >= 0) label = state.slice(start, pos++) else pos = labelEnd + 1
            } else {
                pos = labelEnd + 1
            }
            if (label.isNullOrEmpty()) label = state.slice(labelStart, labelEnd)
            val ref = refs[StudioMdUtil.normalizeReference(label)]
            if (ref == null) {
                state.pos = oldPos
                return false
            }
            href = ref.href
            title = ref.title
            referenceLabel = StudioMdUtil.normalizeReference(label)
        }
        if (!silent) {
            state.pos = labelStart
            state.posMax = labelEnd
            val o = state.push("link_open", "a", 1)
            o.reference = referenceLabel
            val attrs = mutableListOf(MdAttr("href", href))
            o.attrs = attrs
            if (title.isNotEmpty()) attrs += MdAttr("title", title)
            state.linkLevel++
            tokenize(state)
            state.linkLevel--
            state.push("link_close", "a", -1)
        }
        state.pos = pos
        state.posMax = max
        return true
    }

    private fun image(state: MdStateInline, silent: Boolean): Boolean {
        var href = ""
        val title: String
        var referenceLabel: String? = null
        val oldPos = state.pos
        val max = state.posMax
        if (state.ch(state.pos) != 33) return false
        if (state.ch(state.pos + 1) != 91) return false
        val labelStart = state.pos + 2
        val labelEnd = StudioMdHelpers.parseLinkLabel(state, state.pos + 1, false)
        if (labelEnd < 0) return false
        var pos = labelEnd + 1
        if (pos < max && state.ch(pos) == 40) {
            pos++
            pos = skipWs(state, pos, max)
            if (pos >= max) return false
            var res = StudioMdHelpers.parseLinkDestination(state.src, pos, state.posMax)
            if (res.ok) {
                href = StudioMdUtil.normalizeLink(res.str)
                if (StudioMdUtil.validateLink(href)) pos = res.pos else href = ""
            }
            val start = pos
            pos = skipWs(state, pos, max)
            val t = StudioMdHelpers.parseLinkTitle(state.src, pos, state.posMax, null)
            if (pos < max && start != pos && t.ok) {
                title = t.str
                pos = t.pos
                pos = skipWs(state, pos, max)
            } else {
                title = ""
            }
            if (pos >= max || state.ch(pos) != 41) {
                state.pos = oldPos
                return false
            }
            pos++
        } else {
            val refs = state.env.references ?: return false
            var label: String? = null
            if (pos < max && state.ch(pos) == 91) {
                val start = pos + 1
                pos = StudioMdHelpers.parseLinkLabel(state, pos, false)
                if (pos >= 0) label = state.slice(start, pos++) else pos = labelEnd + 1
            } else {
                pos = labelEnd + 1
            }
            if (label.isNullOrEmpty()) label = state.slice(labelStart, labelEnd)
            val ref = refs[StudioMdUtil.normalizeReference(label)]
            if (ref == null) {
                state.pos = oldPos
                return false
            }
            href = ref.href
            title = ref.title
            referenceLabel = StudioMdUtil.normalizeReference(label)
        }
        if (!silent) {
            val content = state.slice(labelStart, labelEnd)
            val tokens = ArrayList<MdToken>()
            parse(content, state.env, tokens)
            val token = state.push("image", "img", 0)
            token.reference = referenceLabel
            val attrs = mutableListOf(MdAttr("src", href), MdAttr("alt", ""))
            token.attrs = attrs
            token.children = tokens
            token.content = content
            if (title.isNotEmpty()) attrs += MdAttr("title", title)
        }
        state.pos = pos
        state.posMax = max
        return true
    }

    /** `HTML_TAG_RE` matched at [from], or -1; comment/processing/declaration/CDATA by hand to keep Java's regex stack small. */
    fun matchHtmlTag(s: String, from: Int): Int {
        if (from >= s.length || s[from] != '<') return -1
        fun startsAt(p: String, at: Int) = s.startsWith(p, at)
        if (startsAt("<!--", from)) {
            if (startsAt("<!-->", from)) return from + 5
            if (startsAt("<!--->", from)) return from + 6
            // <!--(?:[^-]|-[^-]|--[^>])*-->
            var i = from + 4
            while (i < s.length) {
                if (s[i] != '-') {
                    i++
                    continue
                }
                if (i + 1 < s.length && s[i + 1] != '-') {
                    i += 2
                    continue
                }
                if (i + 2 < s.length && s[i + 1] == '-' && s[i + 2] == '>') return i + 3
                if (i + 2 < s.length && s[i + 1] == '-') {
                    i += 3
                    continue
                }
                return -1
            }
            return -1
        }
        if (startsAt("<?", from)) {
            val end = s.indexOf("?>", from + 2)
            return if (end < 0) -1 else end + 2
        }
        if (startsAt("<![CDATA[", from)) {
            val end = s.indexOf("]]>", from + 9)
            if (end >= 0) return end + 3
        }
        if (startsAt("<!", from) && from + 2 < s.length && s[from + 2].let { it in 'a'..'z' || it in 'A'..'Z' }) {
            val end = s.indexOf('>', from + 2)
            return if (end < 0) -1 else end + 1
        }
        val m = OPEN_CLOSE.matcher(s)
        m.region(from, s.length)
        return if (m.lookingAt()) m.end() else -1
    }

    private const val JS_S = "[\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"
    private const val ATTR_NAME = "[a-zA-Z_:][a-zA-Z0-9:._-]*"
    private const val ATTR_VALUE = "(?:[^\"'=<>`\\x00-\\x20]+|'[^']*'|\"[^\"]*\")"
    private const val ATTRIBUTE = "(?:$JS_S+$ATTR_NAME(?:$JS_S*=$JS_S*$ATTR_VALUE)?)"
    const val OPEN_TAG = "<[A-Za-z][A-Za-z0-9\\-]*$ATTRIBUTE*$JS_S*/?>"
    const val CLOSE_TAG = "</[A-Za-z][A-Za-z0-9\\-]*$JS_S*>"
    private val OPEN_CLOSE = java.util.regex.Pattern.compile("(?:$OPEN_TAG|$CLOSE_TAG)")

    private val LINK_OPEN = Regex("^<a[>\\s]", RegexOption.IGNORE_CASE)
    private val LINK_CLOSE = Regex("^</a\\s*>", RegexOption.IGNORE_CASE)

    /** comark `html_inline` (`internal/parse/html/html_inline_rule.js`) */
    private fun htmlInline(state: MdStateInline, silent: Boolean): Boolean {
        val max = state.posMax
        val pos = state.pos
        if (state.ch(pos) != 0x3c || pos + 2 >= max) return false
        val ch = state.ch(pos + 1)
        val lc = ch or 0x20
        if (ch != 0x21 && ch != 0x3f && ch != 0x2f && !(lc in 0x61..0x7a)) return false
        val end = matchHtmlTag(state.src, pos)
        if (end < 0) return false
        val content = state.src.substring(pos, end)
        if (!silent) {
            val token = state.push("html_inline", "", 0)
            token.content = content
            if (LINK_OPEN.containsMatchIn(content)) state.linkLevel++
            if (LINK_CLOSE.containsMatchIn(content)) state.linkLevel--
        }
        state.pos += content.length
        return true
    }

    // ---- comark ---------------------------------------------------------------------------------------------------------

    private val emojiMap: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (entry in StudioEmojiData.ENTRIES.split(',')) {
            val eq = entry.indexOf('=')
            m[entry.substring(0, eq)] = entry.substring(eq + 1)
        }
        m
    }

    /** comark `plugins/emoji.js` */
    private fun emoji(state: MdStateInline, silent: Boolean): Boolean {
        val max = state.posMax
        val start = state.pos
        if (state.ch(start) != 0x3a) return false
        var pos = start + 1
        while (pos < max) {
            val code = state.ch(pos)
            if (code == 0x3a) {
                val name = state.src.substring(start + 1, pos)
                val char = emojiMap[name]
                if (!char.isNullOrEmpty()) {
                    if (!silent) {
                        val token = state.push("emoji", "", 0)
                        token.markup = name
                        token.content = char
                    }
                    state.pos = pos + 1
                    return true
                }
                return false
            }
            if (code in 0x61..0x7a || code in 0x41..0x5a || code in 0x30..0x39 || code == 0x5f || code == 0x2d || code == 0x2b) {
                pos++
                continue
            }
            return false
        }
        return false
    }

    /** comark `comark_inline_span` */
    private fun comarkInlineSpan(state: MdStateInline, silent: Boolean): Boolean {
        val start = state.pos
        if (state.ch(start) != 0x5b) return false
        val close = StudioComarkSyntax.findClosingBracket(state.src, start)
        val index = if (close == -1) state.src.length else close
        val nextChar = state.ch(index + 1)
        if (nextChar == 0x28 || nextChar == 0x5b) return false
        if (state.linkLevel > 0 && nextChar != 0x7b) return false
        if (silent) return false
        state.push("mdc_inline_span", "span", 1)
        val oldPos = state.pos
        val oldPosMax = state.posMax
        state.pos = start + 1
        state.posMax = index
        tokenize(state)
        state.pos = oldPos
        state.posMax = oldPosMax
        state.push("mdc_inline_span", "span", -1)
        state.pos = index + 1
        return true
    }

    private val ALLOWED_PREV_CHARS = setOf(' ', '\t', '\n', '*', '_', '[')

    private fun isNameChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '$' || c == '-'

    /** comark `comark_inline_component` */
    private fun comarkInlineComponent(state: MdStateInline, silent: Boolean): Boolean {
        val start = state.pos
        if (state.ch(start) != 0x3a) return false
        if (start > 0 && state.src[start - 1] !in ALLOWED_PREV_CHARS) return false
        var index = start + 1
        var nameEnd = -1
        var contentStart = -1
        var contentEnd = -1
        while (index < state.src.length) {
            val c = state.src[index]
            if (c == '[') {
                nameEnd = index
                val r = StudioComarkSyntax.parseBracketContent(state.src, index)
                if (r != null) {
                    contentStart = index + 1
                    contentEnd = r.second - 1
                    index = r.second
                }
                break
            }
            if (!isNameChar(c)) break
            index++
        }
        if (nameEnd == -1) nameEnd = index
        if (nameEnd <= start + 1) return false
        val name = state.src.substring(start + 1, nameEnd)
        if (!StudioMdBlock.isValidComponentName(name)) return false
        state.pos = index
        if (silent) return true
        if (contentStart != -1) {
            state.push("mdc_inline_component", name, 1)
            val oldPos = state.pos
            val oldPosMax = state.posMax
            state.pos = contentStart
            state.posMax = contentEnd
            tokenize(state)
            state.pos = oldPos
            state.posMax = oldPosMax
            state.push("mdc_inline_component", name, -1)
        } else {
            state.push("mdc_inline_component", name, 0)
        }
        return true
    }

    /** comark `comark_inline_props` (`plugins/attributes.js`) */
    private fun comarkInlineProps(state: MdStateInline, silent: Boolean): Boolean {
        val start = state.pos
        if (state.ch(start) != 0x7b) return false
        if (state.ch(start + 1) == 0x7b || state.ch(start - 1) == 0x7b || state.ch(start - 1) == 0x24) return false
        val search = StudioComarkSyntax.searchProps(state.src, start) ?: return false
        val end = search.second
        if (end == start) return false
        state.pos = end
        if (silent) return true
        val token = state.push("mdc_inline_props", "span", 0)
        token.attrs = search.first.map { MdAttr(it.first, it.second) }.toMutableList()
        token.hidden = true
        return true
    }
}
