package cz.xprees.comark.compat

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.preview.InlineComarkRewriter
import cz.xprees.comark.preview.OffsetMapping
import junit.framework.TestCase

/** Offset bookkeeping of the preview's snippet rewriting. */
class OffsetMappingTest : TestCase() {
    fun testCopiedAndGeneratedSegments() {
        val mapping = OffsetMapping()
        mapping.copy(10, 3) // "abc" from 10..13
        mapping.generated(13, 4) // "<x/>" replacing something at 13
        mapping.copy(20, 2) // "de" from 20..22
        assertEquals(9, mapping.length)
        assertEquals(10, mapping.source(0))
        assertEquals(12, mapping.source(2))
        assertEquals(13, mapping.source(3))
        assertEquals(13, mapping.source(6))
        assertEquals(20, mapping.source(7))
        assertEquals(22, mapping.source(9))
        assertEquals(13, mapping.sourceEnd(3))
        assertEquals(22, mapping.sourceEnd(9))
    }

    fun testEmpty() {
        assertEquals(0, OffsetMapping().source(5))
    }
}

/** The preview-only rewriting of inline Comark syntax. */
class InlineRewriterTest : BasePlatformTestCase() {
    private fun rewrite(text: String) = InlineComarkRewriter(text).rewrite().text

    fun testInlineComponentWithContentKeepsMarkdownContent() {
        assertEquals(
            """Click <span class="comark-inline-component" data-component="button" title=":button{type=&quot;primary&quot;}">**Submit**</span> now""",
            rewrite("Click :button[**Submit**]{type=\"primary\"} now"),
        )
    }

    fun testStandaloneInlineComponent() {
        assertEquals(
            """A <span class="comark-inline-component" data-component="icon-star" title=":icon-star">icon-star</span>.""",
            rewrite("A :icon-star."),
        )
    }

    fun testComponentNeedsAllowedPrecedingCharacter() {
        assertEquals("time 12:30 and https://example.com and a:b", rewrite("time 12:30 and https://example.com and a:b"))
    }

    fun testSpanAndDroppedAttributes() {
        assertEquals("""a <span class="comark-span" title="{.red}">word</span> and **bold** end""", rewrite("a [word]{.red} and **bold**{.x} end"))
        assertEquals("# Title \n", rewrite("# Title {#custom}\n"))
        assertEquals("text {not attrs} more", rewrite("text {not attrs} more"))
    }

    fun testMustacheAndCodeAreUntouched() {
        assertEquals("Hello {{ name }} and `:code{x}`", rewrite("Hello {{ name }} and `:code{x}`"))
        assertEquals("```\n:inline[x]\n```\n", rewrite("```\n:inline[x]\n```\n"))
        assertEquals("    :indented\n", rewrite("    :indented\n"))
    }

    fun testLinksStayLinks() {
        assertEquals("[text](https://x.y/:path) <https://a.b/:c>", rewrite("[text](https://x.y/:path) <https://a.b/:c>"))
    }

    fun testNestedInlineComponents() {
        val result = InlineComarkRewriter("x :outer[a :inner b] y").rewrite()
        assertEquals(
            """x <span class="comark-inline-component" data-component="outer" title=":outer">a <span class="comark-inline-component" data-component="inner" title=":inner">inner</span> b</span> y""",
            result.text,
        )
        // "b" maps back to its original position
        assertEquals("x :outer[a :inner b] y".indexOf(" b]") + 1, result.mapping.source(result.text.indexOf(" b<") + 1))
    }
}
