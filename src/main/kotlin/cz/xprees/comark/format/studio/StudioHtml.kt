package cz.xprees.comark.format.studio

/**
 * comark 0.7.0's HTML handling (`internal/parse/html/index.js`): [htmlToNodes] for HTML blocks and [parseInlineHtmlTag] for
 * inline tags. Both run htmlparser2 12 in HTML mode; [HtmlParser] ports the parts of its `Tokenizer` and `Parser` they reach
 * (no XML mode, no streaming), including the callback granularity: text is reported in pieces around character
 * references, and comark trims and keeps each piece separately.
 */
internal object StudioHtml {
    val VOID_ELEMENTS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    private fun attrs(attribs: Map<String, String>, inline: Boolean): LinkedHashMap<String, Any?> {
        val a = LinkedHashMap<String, Any?>()
        a["\$"] = linkedMapOf<String, Any?>("html" to 1.0, "block" to if (inline) 0.0 else 1.0)
        for ((k, v) in attribs) {
            if (v == "") a[":$k"] = "true" else a[k] = v
        }
        return a
    }

    class InlineTag(val tag: String, val attrs: Map<String, Any?>, val isVoid: Boolean, val isClose: Boolean)

    private val CLOSE_TAG = Regex("^</([a-zA-Z][a-zA-Z0-9]*)${StudioJs.RE_S}*>")

    fun parseInlineHtmlTag(html: String): InlineTag? {
        val trimmed = StudioJs.trim(html)
        if (!trimmed.startsWith("<")) return null
        CLOSE_TAG.find(trimmed)?.let { return InlineTag(it.groupValues[1].lowercase(), emptyMap(), isVoid = false, isClose = true) }
        var info: InlineTag? = null
        HtmlParser(object : HtmlParser.Callbacks {
            override fun onOpenTag(name: String, attribs: Map<String, String>) {
                info = InlineTag(name, attrs(attribs, true), name in VOID_ELEMENTS, false)
            }
        }, decodeEntities = false, wantsCloseTag = false).parse(trimmed)
        return info
    }

    private class Frame(val tag: String, val attrs: Map<String, Any?>, val children: MutableList<StudioNode> = ArrayList())

    fun htmlToNodes(html: String): List<StudioNode> {
        val root = ArrayList<StudioNode>()
        val stack = ArrayList<Frame>()
        fun add(node: StudioNode) {
            if (stack.isNotEmpty()) stack.last().children += node else root += node
        }
        HtmlParser(object : HtmlParser.Callbacks {
            override fun onOpenTag(name: String, attribs: Map<String, String>) {
                val a = attrs(attribs, false)
                if (name in VOID_ELEMENTS) {
                    add(StudioElement(name, a))
                    return
                }
                stack += Frame(name, a)
            }

            override fun onText(text: String) {
                val t = StudioJs.trim(text)
                if (t.isEmpty()) return
                add(StudioText(t))
            }

            override fun onCloseTag(name: String) {
                if (name in VOID_ELEMENTS) return
                var idx = stack.size - 1
                while (idx >= 0 && stack[idx].tag != name) idx--
                if (idx >= 0) {
                    while (stack.size > idx) {
                        val f = stack.removeAt(stack.size - 1)
                        add(StudioElement(f.tag, f.attrs, f.children.toList()))
                    }
                }
            }

            override fun onComment(data: String) = add(StudioComment(data))
        }, decodeEntities = true, wantsCloseTag = true).parse(StudioJs.trim(html))
        return root
    }
}

/** htmlparser2 12 `Parser` + `Tokenizer` in HTML mode, fed one complete chunk. */
internal class HtmlParser(private val cbs: Callbacks, private val decodeEntities: Boolean, private val wantsCloseTag: Boolean) {
    interface Callbacks {
        fun onOpenTag(name: String, attribs: Map<String, String>) {}
        fun onText(text: String) {}
        fun onCloseTag(name: String) {}
        fun onComment(data: String) {}
    }

    private companion object {
        val formTags = setOf("input", "option", "optgroup", "select", "button", "datalist", "textarea")
        val pTag = setOf("p")
        val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6", "p")
        val tableSectionTags = setOf("thead", "tbody")
        val ddtTags = setOf("dd", "dt")
        val rtpTags = setOf("rt", "rp")
        val openImpliesClose: Map<String, Set<String>> = buildMap {
            put("tr", setOf("tr", "th", "td")); put("th", setOf("th")); put("td", setOf("thead", "th", "td"))
            put("body", setOf("head", "link", "script")); put("a", setOf("a")); put("li", setOf("li")); put("p", pTag)
            for (h in listOf("h1", "h2", "h3", "h4", "h5", "h6")) put(h, headingTags)
            for (f in listOf("select", "input", "output", "button", "datalist", "textarea")) put(f, formTags)
            put("option", setOf("option")); put("optgroup", setOf("optgroup", "option")); put("dd", ddtTags); put("dt", ddtTags)
            for (b in listOf(
                "address", "article", "aside", "blockquote", "details", "div", "dl", "fieldset", "figcaption", "figure", "footer",
                "form", "header", "hr", "main", "nav", "ol", "pre", "section", "table", "ul",
            )) put(b, pTag)
            put("rt", rtpTags); put("rp", rtpTags); put("tbody", tableSectionTags); put("tfoot", tableSectionTags)
        }
        val voidElements = setOf(
            "area", "base", "basefont", "br", "col", "command", "embed", "frame", "hr", "img", "input", "isindex", "keygen", "link",
            "meta", "param", "source", "track", "wbr",
        )
        val foreignContextElements = setOf("math", "svg")
        val htmlIntegrationElements = setOf("mi", "mo", "mn", "ms", "mtext", "annotation-xml", "foreignObject", "desc", "title")
        val svgTagNameAdjustments = mapOf(
            "altglyph" to "altGlyph", "altglyphdef" to "altGlyphDef", "altglyphitem" to "altGlyphItem", "animatecolor" to "animateColor",
            "animatemotion" to "animateMotion", "animatetransform" to "animateTransform", "clippath" to "clipPath", "feblend" to "feBlend",
            "fecolormatrix" to "feColorMatrix", "fecomponenttransfer" to "feComponentTransfer", "fecomposite" to "feComposite",
            "feconvolvematrix" to "feConvolveMatrix", "fediffuselighting" to "feDiffuseLighting", "fedisplacementmap" to "feDisplacementMap",
            "fedistantlight" to "feDistantLight", "fedropshadow" to "feDropShadow", "feflood" to "feFlood", "fefunca" to "feFuncA",
            "fefuncb" to "feFuncB", "fefuncg" to "feFuncG", "fefuncr" to "feFuncR", "fegaussianblur" to "feGaussianBlur", "feimage" to "feImage",
            "femerge" to "feMerge", "femergenode" to "feMergeNode", "femorphology" to "feMorphology", "feoffset" to "feOffset",
            "fepointlight" to "fePointLight", "fespecularlighting" to "feSpecularLighting", "fespotlight" to "feSpotLight", "fetile" to "feTile",
            "feturbulence" to "feTurbulence", "foreignobject" to "foreignObject", "glyphref" to "glyphRef", "lineargradient" to "linearGradient",
            "radialgradient" to "radialGradient", "textpath" to "textPath",
        )

        const val NONE = 0
        const val SVG = 1
        const val MATHML = 2

        // Tokenizer states
        const val TEXT = 1
        const val BEFORE_TAG_NAME = 2
        const val IN_TAG_NAME = 3
        const val IN_SELF_CLOSING_TAG = 4
        const val BEFORE_CLOSING_TAG_NAME = 5
        const val IN_CLOSING_TAG_NAME = 6
        const val AFTER_CLOSING_TAG_NAME = 7
        const val BEFORE_ATTRIBUTE_NAME = 8
        const val IN_ATTRIBUTE_NAME = 9
        const val AFTER_ATTRIBUTE_NAME = 10
        const val BEFORE_ATTRIBUTE_VALUE = 11
        const val IN_ATTRIBUTE_VALUE_DQ = 12
        const val IN_ATTRIBUTE_VALUE_SQ = 13
        const val IN_ATTRIBUTE_VALUE_NQ = 14
        const val BEFORE_DECLARATION = 15
        const val IN_DECLARATION = 16
        const val BEFORE_COMMENT = 18
        const val CDATA_SEQUENCE = 19
        const val DECLARATION_SEQUENCE = 20
        const val IN_SPECIAL_COMMENT = 21
        const val IN_COMMENT_LIKE = 22
        const val SPECIAL_START_SEQUENCE = 23
        const val IN_SPECIAL_TAG = 24
        const val IN_PLAIN_TEXT = 25
        const val IN_ENTITY = 26

        fun seq(s: String) = IntArray(s.length) { s[it].code }
        val EMPTY = IntArray(0)
        val CDATA = seq("CDATA[")
        val CDATA_END = seq("]]>")
        val COMMENT_END = seq("--!>")
        val DOCTYPE = seq("doctype")
        val IFRAME_END = seq("</iframe")
        val NOEMBED_END = seq("</noembed")
        val NOFRAMES_END = seq("</noframes")
        val PLAINTEXT = seq("</plaintext")
        val SCRIPT_END = seq("</script")
        val STYLE_END = seq("</style")
        val TITLE_END = seq("</title")
        val TEXTAREA_END = seq("</textarea")
        val XMP_END = seq("</xmp")
        val specialStartSequences = mapOf(
            IFRAME_END[2] to IFRAME_END, NOEMBED_END[2] to NOEMBED_END, PLAINTEXT[2] to PLAINTEXT, SCRIPT_END[2] to SCRIPT_END,
            TITLE_END[2] to TITLE_END, XMP_END[2] to XMP_END,
        )

        fun isWhitespace(c: Int) = c == 32 || c == 10 || c == 9 || c == 12 || c == 13
        fun isEndOfTagSection(c: Int) = c == 47 || c == 62 || isWhitespace(c)
        fun isAsciiAlpha(c: Int) = c in 97..122 || c in 65..90
    }

    // ---- Parser ---------------------------------------------------------------------------------------------------------

    private var buffer = ""
    private var tagname = ""
    private var attribname = ""
    private val attribvalue = StringBuilder()
    private var attribs: LinkedHashMap<String, String>? = null
    private val stack = ArrayList<String>() // stack[0] is the innermost, like htmlparser2's unshift/shift
    private val foreignContext = ArrayList<Int>().apply { add(NONE) }

    fun parse(data: String) {
        buffer = data
        run()
        finish()
    }

    /** `getSlice`: JavaScript `slice`, where a negative index counts from the end (a trailing `sectionStart` of -1 does that). */
    private fun slice(start: Int, end: Int): String {
        if (start == end) return ""
        val len = buffer.length
        val s = if (start < 0) maxOf(len + start, 0) else minOf(start, len)
        val e = if (end < 0) maxOf(len + end, 0) else minOf(end, len)
        return if (s >= e) "" else buffer.substring(s, e)
    }

    private fun isInForeignContext() = foreignContext[0] != NONE

    private fun isVoidElement(name: String) = name in voidElements

    private fun readTagName(start: Int, end: Int): String {
        val name = slice(start, end).lowercase()
        if (foreignContext[0] == SVG) return svgTagNameAdjustments[name] ?: name
        if (!isInForeignContext()) return if (name == "image") "img" else name
        return name
    }

    private fun pOnText(start: Int, end: Int) = cbs.onText(slice(start, end))

    private fun pOnTextEntity(text: String) = cbs.onText(text)

    private fun pOnOpenTagName(start: Int, end: Int) = emitOpenTag(readTagName(start, end))

    private fun emitOpenTag(name: String) {
        tagname = name
        if (name == "form" && stack.contains("form")) {
            tagname = ""
            return
        }
        val impliesClose = openImpliesClose[name]
        if (impliesClose != null) {
            while (stack.isNotEmpty() && stack[0] in impliesClose) popElement()
        }
        if (!isVoidElement(name)) {
            stack.add(0, name)
            when {
                name == "svg" -> foreignContext.add(0, SVG)
                name == "math" -> foreignContext.add(0, MATHML)
                name in htmlIntegrationElements -> foreignContext.add(0, NONE)
            }
        }
        attribs = LinkedHashMap()
    }

    private fun endOpenTag() {
        attribs?.let { cbs.onOpenTag(tagname, it) }
        attribs = null
        if (wantsCloseTag && isVoidElement(tagname)) cbs.onCloseTag(tagname)
        tagname = ""
    }

    private fun pOnOpenTagEnd() = endOpenTag()

    private fun pOnCloseTag(start: Int, end: Int) {
        val name = readTagName(start, end)
        if (!isVoidElement(name)) {
            val pos = stack.indexOf(name)
            if (pos != -1) {
                repeat(pos) { popElement() }
                popElement()
            } else if (name == "p") {
                emitOpenTag("p")
                closeCurrentTag(true)
            }
        } else if (name == "br") {
            cbs.onOpenTag("br", emptyMap())
            if (wantsCloseTag) cbs.onCloseTag("br")
        }
    }

    private fun pOnSelfClosingTag() {
        if (isInForeignContext()) closeCurrentTag(false) else pOnOpenTagEnd()
    }

    private fun popElement() {
        val element = stack.removeAt(0)
        if (element in foreignContextElements || element in htmlIntegrationElements) foreignContext.removeAt(0)
        if (wantsCloseTag) cbs.onCloseTag(element)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun closeCurrentTag(isOpenImplied: Boolean) {
        val name = tagname
        endOpenTag()
        if (stack.isNotEmpty() && stack[0] == name) popElement()
    }

    private fun pOnAttribName(start: Int, end: Int) {
        attribname = slice(start, end).lowercase()
    }

    private fun pOnAttribData(start: Int, end: Int) {
        attribvalue.append(slice(start, end))
    }

    private fun pOnAttribEntity(text: String) {
        attribvalue.append(text)
    }

    private fun pOnAttribEnd() {
        val a = attribs
        if (a != null && !a.containsKey(attribname)) a[attribname] = attribvalue.toString()
        attribvalue.setLength(0)
    }

    private fun pOnComment(start: Int, end: Int, offset: Int) = cbs.onComment(slice(start, end - offset))

    private fun pOnCdata(start: Int, end: Int, offset: Int) {
        val value = slice(start, end - offset)
        if (isInForeignContext()) cbs.onText(value) else cbs.onComment("[CDATA[$value]]")
    }

    private fun pOnEnd() {
        if (wantsCloseTag) for (name in stack) cbs.onCloseTag(name)
    }

    // ---- Tokenizer ------------------------------------------------------------------------------------------------------

    private var state = TEXT
    private var sectionStart = 0
    private var index = 0
    private var entityStart = 0
    private var baseState = TEXT
    private var isSpecial = false
    private var currentSequence = EMPTY
    private var sequenceIndex = 0

    private fun cc(i: Int): Int = if (i >= 0 && i < buffer.length) buffer[i].code else -1

    private fun run() {
        while (index < buffer.length) {
            val c = buffer[index].code
            when (state) {
                TEXT -> stateText(c)
                IN_PLAIN_TEXT -> index = buffer.length - 1
                SPECIAL_START_SEQUENCE -> stateSpecialStartSequence(c)
                IN_SPECIAL_TAG -> stateInSpecialTag(c)
                CDATA_SEQUENCE -> stateCdataSequence(c)
                DECLARATION_SEQUENCE -> stateDeclarationSequence(c)
                IN_ATTRIBUTE_VALUE_DQ -> handleInAttributeValue(c, 34)
                IN_ATTRIBUTE_NAME -> stateInAttributeName(c)
                IN_COMMENT_LIKE -> stateInCommentLike(c)
                IN_SPECIAL_COMMENT -> stateInSpecialComment(c)
                BEFORE_ATTRIBUTE_NAME -> stateBeforeAttributeName(c)
                IN_TAG_NAME -> stateInTagName(c)
                IN_CLOSING_TAG_NAME -> stateInClosingTagName(c)
                BEFORE_TAG_NAME -> stateBeforeTagName(c)
                AFTER_ATTRIBUTE_NAME -> stateAfterAttributeName(c)
                IN_ATTRIBUTE_VALUE_SQ -> handleInAttributeValue(c, 39)
                BEFORE_ATTRIBUTE_VALUE -> stateBeforeAttributeValue(c)
                BEFORE_CLOSING_TAG_NAME -> stateBeforeClosingTagName(c)
                AFTER_CLOSING_TAG_NAME -> stateAfterClosingTagName(c)
                IN_ATTRIBUTE_VALUE_NQ -> stateInAttributeValueNoQuotes(c)
                IN_SELF_CLOSING_TAG -> stateInSelfClosingTag(c)
                IN_DECLARATION -> stateInDeclaration(c)
                BEFORE_DECLARATION -> stateBeforeDeclaration(c)
                BEFORE_COMMENT -> stateBeforeComment(c)
                IN_ENTITY -> stateInEntity()
            }
            index++
        }
        cleanup()
    }

    private fun stateText(c: Int) {
        if (c == 60 || (!decodeEntities && fastForwardTo(60))) {
            if (index > sectionStart) pOnText(sectionStart, index)
            state = BEFORE_TAG_NAME
            sectionStart = index
        } else if (decodeEntities && c == 38) {
            startEntity()
        }
    }

    private fun enterTagBody() {
        if (currentSequence === PLAINTEXT) {
            currentSequence = EMPTY
            state = IN_PLAIN_TEXT
        } else if (isSpecial) {
            state = IN_SPECIAL_TAG
            sequenceIndex = 0
        } else {
            state = TEXT
        }
    }

    private fun stateSpecialStartSequence(c: Int) {
        val lower = c or 0x20
        if (sequenceIndex < currentSequence.size) {
            if (lower == currentSequence[sequenceIndex]) {
                sequenceIndex++
                return
            }
            if (sequenceIndex == 3) {
                if (currentSequence === SCRIPT_END && lower == STYLE_END[3]) {
                    currentSequence = STYLE_END; sequenceIndex = 4; return
                }
                if (currentSequence === TITLE_END && lower == TEXTAREA_END[3]) {
                    currentSequence = TEXTAREA_END; sequenceIndex = 4; return
                }
            } else if (sequenceIndex == 4 && currentSequence === NOEMBED_END && lower == NOFRAMES_END[4]) {
                currentSequence = NOFRAMES_END; sequenceIndex = 5; return
            }
        } else if (isEndOfTagSection(c)) {
            sequenceIndex = 0
            state = IN_TAG_NAME
            stateInTagName(c)
            return
        }
        isSpecial = false
        currentSequence = EMPTY
        sequenceIndex = 0
        state = IN_TAG_NAME
        stateInTagName(c)
    }

    private fun stateCdataSequence(c: Int) {
        if (sequenceIndex < CDATA.size && c == CDATA[sequenceIndex]) {
            if (++sequenceIndex == CDATA.size) {
                state = IN_COMMENT_LIKE
                currentSequence = CDATA_END
                sequenceIndex = 0
                sectionStart = index + 1
            }
        } else {
            sequenceIndex = 0
            state = IN_SPECIAL_COMMENT
            stateInSpecialComment(c)
        }
    }

    private fun fastForwardTo(c: Int): Boolean {
        while (++index < buffer.length) {
            if (buffer[index].code == c) return true
        }
        index = buffer.length - 1
        return false
    }

    private fun emitComment(offset: Int) {
        pOnComment(sectionStart, index, offset)
        sequenceIndex = 0
        sectionStart = index + 1
        state = TEXT
    }

    private fun stateInCommentLike(c: Int) {
        if (currentSequence === COMMENT_END && sequenceIndex <= 1 && index == sectionStart + sequenceIndex && c == 62) {
            emitComment(sequenceIndex)
        } else if (currentSequence === COMMENT_END && sequenceIndex == 2 && c == 62) {
            emitComment(2)
        } else if (currentSequence === COMMENT_END && sequenceIndex == currentSequence.size - 1 && c != 62) {
            sequenceIndex = if (c == 45) 1 else 0
        } else if (c == currentSequence[sequenceIndex]) {
            if (++sequenceIndex == currentSequence.size) {
                if (currentSequence === CDATA_END) pOnCdata(sectionStart, index, 2) else pOnComment(sectionStart, index, 3)
                sequenceIndex = 0
                sectionStart = index + 1
                state = TEXT
            }
        } else if (sequenceIndex == 0) {
            if (fastForwardTo(currentSequence[0])) sequenceIndex = 1
        } else if (c != currentSequence[sequenceIndex - 1]) {
            sequenceIndex = 0
        }
    }

    private fun stateInSpecialTag(c: Int) {
        if (sequenceIndex == currentSequence.size) {
            if (isEndOfTagSection(c)) {
                val endOfText = index - currentSequence.size
                if (sectionStart < endOfText) {
                    val actualIndex = index
                    index = endOfText
                    pOnText(sectionStart, endOfText)
                    index = actualIndex
                }
                isSpecial = false
                sectionStart = endOfText + 2
                stateInClosingTagName(c)
                return
            }
            sequenceIndex = 0
        }
        if ((c or 0x20) == currentSequence[sequenceIndex]) {
            sequenceIndex += 1
        } else if (sequenceIndex == 0) {
            if (currentSequence === TITLE_END || currentSequence === TEXTAREA_END) {
                if (decodeEntities && c == 38) startEntity()
            } else if (fastForwardTo(60)) {
                sequenceIndex = 1
            }
        } else {
            sequenceIndex = if (c == 60) 1 else 0
        }
    }

    private fun stateBeforeTagName(c: Int) {
        if (c == 33) {
            state = BEFORE_DECLARATION
            sectionStart = index + 1
        } else if (c == 63) {
            state = IN_SPECIAL_COMMENT
            sectionStart = index
        } else if (isAsciiAlpha(c)) {
            sectionStart = index
            val special = if (isInForeignContext()) null else specialStartSequences[c or 0x20]
            if (special == null) {
                state = IN_TAG_NAME
            } else {
                isSpecial = true
                currentSequence = special
                sequenceIndex = 3
                state = SPECIAL_START_SEQUENCE
            }
        } else if (c == 47) {
            state = BEFORE_CLOSING_TAG_NAME
        } else {
            state = TEXT
            stateText(c)
        }
    }

    private fun stateInTagName(c: Int) {
        if (isEndOfTagSection(c)) {
            pOnOpenTagName(sectionStart, index)
            sectionStart = -1
            state = BEFORE_ATTRIBUTE_NAME
            stateBeforeAttributeName(c)
        }
    }

    private fun stateBeforeClosingTagName(c: Int) {
        if (isWhitespace(c)) {
            state = IN_SPECIAL_COMMENT
            sectionStart = index
        } else if (c == 62) {
            state = TEXT
            sectionStart = index + 1
        } else {
            state = if (isAsciiAlpha(c)) IN_CLOSING_TAG_NAME else IN_SPECIAL_COMMENT
            sectionStart = index
        }
    }

    private fun stateInClosingTagName(c: Int) {
        if (isEndOfTagSection(c)) {
            pOnCloseTag(sectionStart, index)
            sectionStart = -1
            state = AFTER_CLOSING_TAG_NAME
            stateAfterClosingTagName(c)
        }
    }

    private fun stateAfterClosingTagName(c: Int) {
        if (c == 62 || fastForwardTo(62)) {
            state = TEXT
            sectionStart = index + 1
        }
    }

    private fun stateBeforeAttributeName(c: Int) {
        if (c == 62) {
            pOnOpenTagEnd()
            enterTagBody()
            sectionStart = index + 1
        } else if (c == 47) {
            state = IN_SELF_CLOSING_TAG
        } else if (!isWhitespace(c)) {
            state = IN_ATTRIBUTE_NAME
            sectionStart = index
        }
    }

    private fun stateInSelfClosingTag(c: Int) {
        if (c == 62) {
            pOnSelfClosingTag()
            sectionStart = index + 1
            enterTagBody()
        } else if (!isWhitespace(c)) {
            state = BEFORE_ATTRIBUTE_NAME
            stateBeforeAttributeName(c)
        }
    }

    private fun stateInAttributeName(c: Int) {
        if (c == 61 || isEndOfTagSection(c)) {
            pOnAttribName(sectionStart, index)
            sectionStart = index
            state = AFTER_ATTRIBUTE_NAME
            stateAfterAttributeName(c)
        }
    }

    private fun stateAfterAttributeName(c: Int) {
        if (c == 61) {
            state = BEFORE_ATTRIBUTE_VALUE
        } else if (c == 47 || c == 62) {
            pOnAttribEnd()
            sectionStart = -1
            state = BEFORE_ATTRIBUTE_NAME
            stateBeforeAttributeName(c)
        } else if (!isWhitespace(c)) {
            pOnAttribEnd()
            state = IN_ATTRIBUTE_NAME
            sectionStart = index
        }
    }

    private fun stateBeforeAttributeValue(c: Int) {
        if (c == 34) {
            state = IN_ATTRIBUTE_VALUE_DQ
            sectionStart = index + 1
        } else if (c == 39) {
            state = IN_ATTRIBUTE_VALUE_SQ
            sectionStart = index + 1
        } else if (!isWhitespace(c)) {
            sectionStart = index
            state = IN_ATTRIBUTE_VALUE_NQ
            stateInAttributeValueNoQuotes(c)
        }
    }

    private fun handleInAttributeValue(c: Int, quote: Int) {
        if (c == quote || (!decodeEntities && fastForwardTo(quote))) {
            pOnAttribData(sectionStart, index)
            sectionStart = -1
            pOnAttribEnd()
            state = BEFORE_ATTRIBUTE_NAME
        } else if (decodeEntities && c == 38) {
            startEntity()
        }
    }

    private fun stateInAttributeValueNoQuotes(c: Int) {
        if (isWhitespace(c) || c == 62) {
            pOnAttribData(sectionStart, index)
            sectionStart = -1
            pOnAttribEnd()
            state = BEFORE_ATTRIBUTE_NAME
            stateBeforeAttributeName(c)
        } else if (decodeEntities && c == 38) {
            startEntity()
        }
    }

    private fun stateBeforeDeclaration(c: Int) {
        if (c == 91) {
            state = CDATA_SEQUENCE
            sequenceIndex = 0
        } else if ((c or 0x20) == DOCTYPE[0]) {
            state = DECLARATION_SEQUENCE
            currentSequence = DOCTYPE
            sequenceIndex = 1
        } else if (c == 62) {
            pOnComment(sectionStart, index, 0)
            state = TEXT
            sectionStart = index + 1
        } else if (c == 45) {
            state = BEFORE_COMMENT
        } else {
            state = IN_SPECIAL_COMMENT
        }
    }

    private fun stateDeclarationSequence(c: Int) {
        if (sequenceIndex == currentSequence.size) {
            state = IN_DECLARATION
            stateInDeclaration(c)
        } else if ((c or 0x20) == currentSequence[sequenceIndex]) {
            sequenceIndex += 1
        } else if (c == 62) {
            pOnComment(sectionStart, index, 0)
            state = TEXT
            sectionStart = index + 1
        } else {
            state = IN_SPECIAL_COMMENT
        }
    }

    private fun stateInDeclaration(c: Int) {
        if (c == 62 || fastForwardTo(62)) {
            state = TEXT
            sectionStart = index + 1
        }
    }

    private fun stateBeforeComment(c: Int) {
        if (c == 45) {
            state = IN_COMMENT_LIKE
            currentSequence = COMMENT_END
            sequenceIndex = 0
            sectionStart = index + 1
        } else if (c == 62) {
            pOnComment(sectionStart, index, 0)
            state = TEXT
            sectionStart = index + 1
        } else {
            state = IN_SPECIAL_COMMENT
        }
    }

    private fun stateInSpecialComment(c: Int) {
        if (c == 62 || fastForwardTo(62)) {
            pOnComment(sectionStart, index, 0)
            state = TEXT
            sectionStart = index + 1
        }
    }

    private fun startEntity() {
        baseState = state
        state = IN_ENTITY
        entityStart = index
    }

    private fun stateInEntity() {
        val mode = if (baseState == TEXT || baseState == IN_SPECIAL_TAG) StudioEntities.Mode.LEGACY else StudioEntities.Mode.ATTRIBUTE
        val decoded = StudioEntities.decodeAt(buffer, entityStart, mode)
        state = baseState
        if (decoded == null) {
            index -= 1
            return
        }
        if (baseState != TEXT && baseState != IN_SPECIAL_TAG) {
            if (sectionStart < entityStart) pOnAttribData(sectionStart, entityStart)
            sectionStart = entityStart + decoded.consumed
            index = sectionStart - 1
            pOnAttribEntity(decoded.text)
        } else {
            if (sectionStart < entityStart) pOnText(sectionStart, entityStart)
            sectionStart = entityStart + decoded.consumed
            index = sectionStart - 1
            // entities emits a multi-code-point reference as one callback per code point
            decoded.text.codePoints().forEach { pOnTextEntity(String(Character.toChars(it))) }
        }
    }

    private fun cleanup() {
        if (sectionStart != index) {
            if (state == TEXT || state == IN_PLAIN_TEXT || (state == IN_SPECIAL_TAG && sequenceIndex == 0)) {
                pOnText(sectionStart, index)
                sectionStart = index
            } else if (state == IN_ATTRIBUTE_VALUE_DQ || state == IN_ATTRIBUTE_VALUE_SQ || state == IN_ATTRIBUTE_VALUE_NQ) {
                pOnAttribData(sectionStart, index)
                sectionStart = index
            }
        }
    }

    private fun finish() {
        if (state == IN_ENTITY) state = baseState
        handleTrailingData()
        pOnEnd()
    }

    private fun handleTrailingData() {
        val endIndex = buffer.length
        if (state == IN_COMMENT_LIKE) {
            if (currentSequence === CDATA_END) {
                pOnComment(sectionStart - CDATA.size - 1, endIndex, 0)
            } else {
                pOnComment(sectionStart, endIndex, minOf(sequenceIndex, COMMENT_END.size - 1))
            }
            return
        }
        when (state) {
            BEFORE_DECLARATION, IN_SPECIAL_COMMENT, BEFORE_COMMENT, CDATA_SEQUENCE -> {
                pOnComment(sectionStart, endIndex, 0); return
            }
            DECLARATION_SEQUENCE -> {
                if (sequenceIndex != DOCTYPE.size) pOnComment(sectionStart, endIndex, 0)
                return
            }
            IN_DECLARATION -> return
        }
        if (sectionStart >= endIndex) return
        when (state) {
            IN_TAG_NAME, BEFORE_ATTRIBUTE_NAME, BEFORE_ATTRIBUTE_VALUE, AFTER_ATTRIBUTE_NAME, IN_ATTRIBUTE_NAME,
            IN_ATTRIBUTE_VALUE_SQ, IN_ATTRIBUTE_VALUE_DQ, IN_ATTRIBUTE_VALUE_NQ, IN_CLOSING_TAG_NAME,
            -> {}
            else -> pOnText(sectionStart, endIndex)
        }
    }
}
