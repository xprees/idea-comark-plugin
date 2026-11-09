package cz.xprees.comark.highlighting

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.highlighting.ComarkHighlighterColors as C
import org.intellij.plugins.markdown.highlighting.MarkdownHighlighterColors

class ComarkInlineAnnotatorTest : BasePlatformTestCase() {
    private val comarkKeys: Set<TextAttributesKey> = C::class.java.declaredFields
        .filter { it.type == TextAttributesKey::class.java }
        .map { it.get(null) as TextAttributesKey }
        .toSet()

    private fun infos(fileName: String, text: String): List<HighlightInfo> {
        myFixture.configureByText(fileName, text)
        return myFixture.doHighlighting()
    }

    /** Comark inline highlights as `text -> key`, in document order. */
    private fun highlights(fileName: String, text: String): List<Pair<String, TextAttributesKey>> =
        infos(fileName, text)
            .filter { it.forcedTextAttributesKey in comarkKeys }
            .sortedBy { it.startOffset }
            .map { text.substring(it.startOffset, it.endOffset) to it.forcedTextAttributesKey }

    private fun assertHighlighted(highlights: List<Pair<String, TextAttributesKey>>, vararg expected: Pair<String, TextAttributesKey>) {
        for (pair in expected) assertTrue("missing $pair in $highlights", pair in highlights)
    }

    fun testParagraph() {
        val highlights = highlights("a.mdc", "Hello [World]{.a} and :badge[New]{color=\"blue\"} {{ user }}\n")
        assertHighlighted(
            highlights,
            "World" to C.SPAN,
            "[" to C.CONTENT_BRACKETS,
            ".a" to C.ATTRIBUTE_CLASS,
            ":" to C.COMPONENT_MARKER,
            "badge" to C.INLINE_COMPONENT,
            "color" to C.ATTRIBUTE_NAME,
            "=" to C.ATTRIBUTE_EQ,
            "\"blue\"" to C.ATTRIBUTE_VALUE,
            "{" to C.ATTRIBUTE_BRACES,
            "{{" to C.BINDING_BRACES,
            "user" to C.BINDING,
        )
    }

    fun testHeadingTableListAndQuote() {
        val text = """
            # Title {#intro}

            - item :icon
            - [ ] task {.done}

            > quote [span]{.q}

            | a | b {.c} |
            |---|---|
            | :x | [y] |
        """.trimIndent()
        val highlights = highlights("b.mdc", text)
        assertHighlighted(
            highlights,
            "#intro" to C.ATTRIBUTE_ID,
            "icon" to C.INLINE_COMPONENT,
            ".done" to C.ATTRIBUTE_CLASS,
            "span" to C.SPAN,
            ".q" to C.ATTRIBUTE_CLASS,
            ".c" to C.ATTRIBUTE_CLASS,
            "x" to C.INLINE_COMPONENT,
            "y" to C.SPAN,
        )
        // the task list checkbox is not a span
        assertFalse(" " to C.SPAN in highlights)
    }

    fun testComponentHeaderContent() {
        val highlights = highlights("c.mdc", "::alert[Press :kbd{value=\"K\"}]{.x}\nBody\n::\n")
        assertHighlighted(highlights, "kbd" to C.INLINE_COMPONENT, "value" to C.ATTRIBUTE_NAME)
    }

    fun testNothingInsideCode() {
        val text = "Use `:icon` and `[a]{.b}`\n\n```js\nconst a = ':icon [a]{.b}'\n```\n\n```\n:icon\n```\n\n    :indented [code]{.x}\n"
        assertEquals(emptyList<Any>(), highlights("d.mdc", text))
    }

    fun testComarkCodeFenceIsHighlightedAsComark() {
        // a Markdown code fence in a Comark document is injected as Markdown, which the substitutor turns into Comark as well
        val text = "Example:\n\n```md\nPress :kbd{value=\"K\"}\n```\n"
        assertHighlighted(highlights("g.mdc", text), "kbd" to C.INLINE_COMPONENT, "value" to C.ATTRIBUTE_NAME)
    }

    fun testNothingInPlainMarkdown() {
        assertEquals(emptyList<Any>(), highlights("plain.md", "Hello [World]{.a} and :badge[New]\n"))
    }

    fun testMarkdownLinkColoringIsDroppedForSpans() {
        val text = "Hello [World]{.a}, :badge[New][more] and [link](https://example.com) or [ref][r]\n\n[r]: https://example.com\n"
        val infos = infos("e.mdc", text)
        fun keysAt(fragment: String) = infos.filter { text.substring(it.startOffset, it.endOffset) == fragment }.mapNotNull { it.forcedTextAttributesKey }
        val linkKeys = setOf(MarkdownHighlighterColors.LINK_LABEL, MarkdownHighlighterColors.REFERENCE_LINK, MarkdownHighlighterColors.LINK_TEXT)
        assertTrue(keysAt("[World]").none { it in linkKeys })
        assertTrue(keysAt("[New]").none { it in linkKeys })
        assertTrue(keysAt("[more]").none { it in linkKeys })
        assertTrue(keysAt("[New][more]").none { it in linkKeys })
        // real links keep their colors
        assertTrue(keysAt("[link]").contains(MarkdownHighlighterColors.LINK_TEXT))
        assertTrue(keysAt("[r]").contains(MarkdownHighlighterColors.LINK_LABEL))
    }

    fun testPlainMarkdownKeepsLinkLabelColoring() {
        val text = "Hello [World]\n"
        val infos = infos("plain2.md", text)
        assertTrue(infos.any { text.substring(it.startOffset, it.endOffset) == "[World]" && it.forcedTextAttributesKey == MarkdownHighlighterColors.LINK_LABEL })
    }

    fun testBlockSyntaxIsLeftToTheLexer() {
        // block component headers are colored by the syntax highlighter, the annotator adds nothing there
        val highlights = highlights("f.mdc", "::card{.x}\n#slot\ntext\n::\n")
        assertEquals(emptyList<Any>(), highlights)
    }
}
