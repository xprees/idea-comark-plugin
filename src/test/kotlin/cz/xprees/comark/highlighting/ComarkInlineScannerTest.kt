package cz.xprees.comark.highlighting

import cz.xprees.comark.parser.ComarkInlineScanner
import cz.xprees.comark.parser.ComarkInlineScanner.AttributeTarget
import cz.xprees.comark.parser.ComarkInlineScanner.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ComarkInlineScannerTest {
    private fun scan(text: String, opaque: List<IntRange> = emptyList()) = ComarkInlineScanner.scan(text, opaque = opaque)

    /** Tokens as `KIND:text`. */
    private fun tokens(text: String, opaque: List<IntRange> = emptyList()): List<String> =
        scan(text, opaque).tokens.map { "${it.kind}:${text.substring(it.start, it.end)}" }

    private fun assertNothing(text: String) = assertEquals("expected no Comark syntax in '$text'", emptyList<String>(), tokens(text))

    private fun attributes(text: String) = scan(text).elements.filterIsInstance<ComarkInlineScanner.Attributes>()

    private fun singleTarget(text: String): AttributeTarget {
        val groups = attributes(text)
        assertEquals("one attribute group in '$text'", 1, groups.size)
        return groups.single().target
    }

    // ---- Inline components ------------------------------------------------------------------------------------------

    @Test
    fun bareInlineComponent() {
        assertEquals(listOf("COMPONENT_MARKER::", "COMPONENT_NAME:icon"), tokens(":icon"))
        assertEquals(listOf("COMPONENT_MARKER::", "COMPONENT_NAME:icon-check"), tokens("Done :icon-check"))
    }

    @Test
    fun inlineComponentWithContentAndAttributes() {
        val text = ":badge[New]{color=\"blue\"}"
        assertEquals(
            listOf(
                "COMPONENT_MARKER::", "COMPONENT_NAME:badge", "CONTENT_OPEN:[", "COMPONENT_CONTENT:New", "CONTENT_CLOSE:]",
                "ATTR_LBRACE:{", "ATTR_NAME:color", "ATTR_EQ:=", "ATTR_VALUE:\"blue\"", "ATTR_RBRACE:}",
            ),
            tokens(text),
        )
        val component = scan(text).components.single()
        assertEquals("badge", text.substring(component.nameStart, component.nameEnd))
        assertEquals("New", text.substring(component.contentStart, component.contentEnd))
        assertEquals(AttributeTarget.COMPONENT, component.attributes!!.target)
        assertEquals(text.length, component.end)
    }

    @Test
    fun inlineComponentWithAttributesOnly() {
        assertEquals(
            listOf("COMPONENT_MARKER::", "COMPONENT_NAME:tooltip", "ATTR_LBRACE:{", "ATTR_NAME:text", "ATTR_EQ:=", "ATTR_VALUE:\"Hover\"", "ATTR_RBRACE:}"),
            tokens("See :tooltip{text=\"Hover\"} here"),
        )
    }

    @Test
    fun componentContentIsScannedForNestedSyntax() {
        val text = ":card[Use :kbd{value=\"K\"} and [span]{.x}]"
        val result = scan(text)
        assertEquals(listOf("card", "kbd"), result.components.map { text.substring(it.nameStart, it.nameEnd) })
        assertEquals(1, result.spans.size)
    }

    @Test
    fun previousCharacterRule() {
        for (prefix in listOf("", " ", "\t", "\n", "*", "_", "[", "]", "}", ")", ">")) {
            assertEquals("prefix '$prefix'", 1, scan("$prefix:icon").components.size)
        }
        for (text in listOf("a:icon", "http://example", "10:30", "Note: text", "(:icon)", "'s:x", "a-:b")) {
            assertNothing(text)
        }
    }

    @Test
    fun invalidComponentNames() {
        assertNothing(":8100")
        assertNothing(":_name")
        assertNothing(":-name")
        assertNothing(": spaced")
        assertNothing("::")
        assertEquals(listOf("COMPONENT_MARKER::", "COMPONENT_NAME:\$var"), tokens(":\$var"))
        // inline names stop at '.', unlike block names
        assertEquals(listOf("COMPONENT_MARKER::", "COMPONENT_NAME:a"), tokens(":a.b"))
    }

    @Test
    fun unclosedContentLeavesBareComponent() {
        assertEquals(listOf("COMPONENT_MARKER::", "COMPONENT_NAME:badge"), tokens(":badge[New"))
    }

    @Test
    fun dollarBeforeBraceIsATemplateNotAttributes() {
        assertEquals(listOf("COMPONENT_MARKER::", "COMPONENT_NAME:foo\$"), tokens(":foo\${x}"))
    }

    @Test
    fun attributesAfterAttributes() {
        val groups = attributes(":icon{.a}{.b}")
        assertEquals(1, groups.size)
        assertEquals(AttributeTarget.PRECEDING_INLINE, groups.single().target)
        assertEquals(AttributeTarget.COMPONENT, scan(":icon{.a}{.b}").components.single().attributes!!.target)
    }

    // ---- Spans ------------------------------------------------------------------------------------------------------

    @Test
    fun bareSpan() {
        assertEquals(listOf("SPAN_OPEN:[", "SPAN_CONTENT:World", "SPAN_CLOSE:]"), tokens("Hello [World]!"))
        assertEquals(listOf("SPAN_OPEN:[", "SPAN_CLOSE:]"), tokens("[]"))
    }

    @Test
    fun spanWithAttributes() {
        val text = "This is [highlighted text]{.highlight #ref-1} here"
        assertEquals(
            listOf("SPAN_OPEN:[", "SPAN_CONTENT:highlighted text", "SPAN_CLOSE:]", "ATTR_LBRACE:{", "ATTR_CLASS:.highlight", "ATTR_ID:#ref-1", "ATTR_RBRACE:}"),
            tokens(text),
        )
        assertEquals(AttributeTarget.SPAN, scan(text).spans.single().attributes!!.target)
    }

    @Test
    fun spanWithNestedMarkdownAndBrackets() {
        val result = scan("[**Bold** and [inner] text]{.x}")
        assertEquals(2, result.spans.size)
        assertEquals(0, result.spans.first().start)
    }

    @Test
    fun linksAreNotSpans() {
        assertNothing("[Link](https://example.com)")
        assertNothing("[Link](https://example.com \"Title\")")
        assertNothing("[Link](<dest with spaces>)")
        assertNothing("[a](b(c)d)")
        assertNothing("[text][ref]")
        assertNothing("![alt](image.png)")
        assertNothing("[^1]")
    }

    @Test
    fun unfinishedSyntaxIsNotASpan() {
        assertNothing("[unclosed")
        // a link that never closes is plain text in markdown-it, and the span rule already declined it
        assertNothing("[a](b")
    }

    @Test
    fun imageWithoutDestinationIsASpan() {
        assertEquals(1, scan("![alt]").spans.size)
    }

    @Test
    fun bracketsInsideLinkLabels() {
        assertTrue(scan("[a [b] c](url)").spans.isEmpty())
        assertEquals(1, scan("[a [b]{.x} c](url)").spans.size)
        assertEquals(1, scan("[a :icon c](url)").components.size)
    }

    @Test
    fun escapes() {
        assertNothing("\\[not a span]")
        assertNothing("\\:icon")
        assertNothing("text \\{.x}")
    }

    // ---- Attributes -------------------------------------------------------------------------------------------------

    @Test
    fun attributesAfterInlineSyntax() {
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("**bold**{.x} more"))
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("_italic_{#id} more"))
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("*em*{.x} more"))
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("~~gone~~{.x} more"))
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("`code`{.lang-js} more"))
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("[Link](url){target=\"_blank\"} more"))
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("[Link](url \"t\"){.x} more"))
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("![Logo](logo.svg){.logo width=\"80\"} more"))
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("<span>x</span>{.x} more"))
        assertEquals(AttributeTarget.PRECEDING_INLINE, singleTarget("{{ a }}{.x} more"))
    }

    @Test
    fun trailingAttributesApplyToTheBlock() {
        assertEquals(AttributeTarget.BLOCK, singleTarget("A paragraph {attr=\"value\"}"))
        assertEquals(AttributeTarget.BLOCK, singleTarget(" Getting started {#start}"))
        assertEquals(AttributeTarget.BLOCK, singleTarget("text {.x}   "))
        assertEquals(AttributeTarget.BLOCK, singleTarget("text {.x}\r\nnext line"))
        assertEquals(AttributeTarget.BLOCK, singleTarget("line one {.x}\nline two"))
        assertEquals(AttributeTarget.BLOCK, singleTarget("**bold** {.x}"))
        assertEquals(AttributeTarget.BLOCK, singleTarget("A paragraph [span] {attr=\"value\"}"))
        assertEquals(AttributeTarget.BLOCK, singleTarget("{.x}"))
        assertEquals(AttributeTarget.BLOCK, singleTarget(" b {.c} "))
    }

    @Test
    fun attributesAfterTextFollowedByMoreTextAreText() {
        assertNothing("text {.x} more")
        assertNothing("**bold** {.x} more")
        assertNothing("Use {curly} braces in prose.")
    }

    @Test
    fun rejectedGroupHidesGroupsInsideIt() {
        assertNothing("text {a {.b} c} more")
        // a later group after the rejected one still applies at the end of the line
        assertEquals(AttributeTarget.BLOCK, singleTarget("text {.x} more {.y}"))
    }

    @Test
    fun unpairedCloserIsNotEmphasis() {
        assertNothing("snake_case_{x}")
        assertNothing("a**{.x}")
    }

    @Test
    fun unclosedAttributesAreNotHighlighted() {
        assertNothing("text {.x")
        assertNothing("**bold**{.x")
    }

    @Test
    fun attributeParts() {
        assertEquals(
            listOf(
                "ATTR_LBRACE:{", "ATTR_CLASS:.a", "ATTR_ID:#b", "ATTR_NAME:c", "ATTR_EQ:=", "ATTR_VALUE:\"d e\"", "ATTR_NAME:bool",
                "ATTR_NAME::bound", "ATTR_EQ:=", "ATTR_VALUE:'{\"k\": 1}'", "ATTR_CLASS:.bg-[#000]", "ATTR_RBRACE:}",
            ),
            tokens("text {.a #b c=\"d e\" bool :bound='{\"k\": 1}' .bg-[#000]}"),
        )
    }

    @Test
    fun mustacheAndTemplatesAreNotAttributes() {
        assertNothing("\${x}")
        assertNothing("text {{x}")
        assertNothing("text {{ }}")
    }

    // ---- Bindings ---------------------------------------------------------------------------------------------------

    @Test
    fun bindings() {
        assertEquals(listOf("BINDING_OPEN:{{", "BINDING_EXPRESSION:name", "BINDING_CLOSE:}}"), tokens("Hi {{ name }}!"))
        assertEquals(listOf("BINDING_OPEN:{{", "BINDING_EXPRESSION:user.name || 'guest'", "BINDING_CLOSE:}}"), tokens("{{user.name || 'guest'}}"))
        assertNothing("{{ || 'x' }}")
        assertNothing("{{ unclosed")
        assertNothing("`{{ name }}`")
    }

    // ---- Things that hide Comark syntax -----------------------------------------------------------------------------

    @Test
    fun codeSpans() {
        assertNothing("`:icon` and `[span]{.x}`")
        assertNothing("``a ` :icon``")
        // an unclosed backtick run is text
        assertEquals(1, scan("` :icon").components.size)
    }

    @Test
    fun autolinksHtmlAndUrls() {
        assertNothing("<https://example.com/:a[b]>")
        assertNothing("<me@example.com>")
        assertNothing("<a href=\"x\" title=\"[b]{.c}\">")
        assertNothing("<!-- :icon [b] -->")
        assertNothing("https://example.com/[a]")
        assertNothing("see www.example.com/:x")
        assertEquals(1, scan("see https://example.com/a. [b]").spans.size)
        assertEquals(1, scan("a < b [c]").spans.size)
    }

    @Test
    fun opaqueRangesAreSkipped() {
        val text = "foo [a] bar {.x}"
        assertTrue(scan(text, opaque = listOf(4..6)).spans.isEmpty())
        // a group right after an opaque range follows a non-text element
        val code = "code{.x} more"
        assertEquals(AttributeTarget.PRECEDING_INLINE, scan(code, opaque = listOf(0..3)).elements.single().let { (it as ComarkInlineScanner.Attributes).target })
    }

    @Test
    fun multiLineContent() {
        val text = "line one :icon\n> line two [span]{.x}\nlast {#id}"
        val result = scan(text)
        assertEquals(1, result.components.size)
        assertEquals(1, result.spans.size)
        assertEquals(AttributeTarget.BLOCK, result.elements.filterIsInstance<ComarkInlineScanner.Attributes>().single().target)
    }

    @Test
    fun subRangeScan() {
        val text = "xx:icon yy"
        // the start of the scanned range counts as the start of the content
        val result = ComarkInlineScanner.scan(text, from = 2, to = 7)
        assertEquals(2, result.components.single().start)
    }

    @Test
    fun lookups() {
        val text = "a :badge[New]{.x} b"
        val result = scan(text)
        val component = result.components.single()
        assertSame(component, result.elementAt(text.indexOf("New")))
        assertEquals(Kind.COMPONENT_NAME, result.tokenAt(text.indexOf("badge"))!!.kind)
        assertEquals(Kind.ATTR_CLASS, result.tokenAt(text.indexOf(".x"))!!.kind)
        assertNull(result.elementAt(0))
        assertTrue(ComarkInlineScanner.scan("").elements.isEmpty())
    }

    @Test
    fun scanningIsTotal() {
        // no exception and sane ranges for arbitrary punctuation soup
        val soup = "[{:}]`*_~<>(){{}}\\[]: :a[ {. #} ![x](y{z}) **{.a}** _b_{.c} ~~d~~ \$ {{e}}"
        for (end in 0..soup.length) {
            for (token in ComarkInlineScanner.scan(soup, 0, end).tokens) {
                assertTrue(token.start in 0 until end && token.end in token.start + 1..end)
            }
        }
    }

    /** comark checks the rest of the whole inline source: a group that closes span or component content is followed by `]`. */
    @Test
    fun attributesAfterTextAtEndOfSpanOrContentAreText() {
        val kinds = scan("See [docs {.a}] and :badge[New {#x}] here.").tokens.map { it.kind }
        assertTrue(kinds.toString(), Kind.ATTR_CLASS !in kinds && Kind.ATTR_ID !in kinds)
    }

    @Test
    fun attributesAfterTextAtEndOfLineStillApply() {
        assertTrue(tokens("Some text {.a}").contains("ATTR_CLASS:.a"))
    }
}
