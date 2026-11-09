package cz.xprees.comark.format

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tricky cases of the [ComarkFormatter] port, the JavaScript semantics it relies on and its [LineContext] probe. */
class ComarkFormatterTest {
    private fun format(text: String, tabSize: Int = 2, formatOnType: Boolean = false) = ComarkFormatter.format(text, tabSize, formatOnType)

    @Test
    fun nestedComponentsAreIndentedByDepthAndContentFollowsItsOpener() {
        val input = "::outer\ntext\n:::inner\n  inner text\n::::deep\ndeep text\n::::\n:::\n::\n"
        val expected = "::outer\ntext\n  :::inner\n  inner text\n    ::::deep\n    deep text\n    ::::\n  :::\n::\n"
        assertEquals(expected, format(input))
        val expected4 = "::outer\ntext\n    :::inner\n    inner text\n        ::::deep\n        deep text\n        ::::\n    :::\n::\n"
        assertEquals(expected4, format(input, tabSize = 4))
    }

    @Test
    fun propsYamlIsIndentedByPropertyDepth() {
        val input = "::a\n:::b\n---\ntitle: x\nlinks:\n      - label: one\n        to: /one\nnested:\n    deep: 1\n---\nbody\n:::\n::"
        val expected = "::a\n  :::b\n  ---\n  title: x\n  links:\n    - label: one\n      to: /one\n  nested:\n    deep: 1\n  ---\n  body\n  :::\n::\n"
        assertEquals(expected, format(input))
    }

    @Test
    fun emptyInputIsReturnedAsIsAndOtherwiseTheResultEndsWithOneLineBreak() {
        assertEquals("", format(""))
        assertEquals("a\n", format("a"))
        assertEquals("a\n", format("a\n"))
        assertEquals("a\n\n", format("a\n\n"))
    }

    @Test
    fun trailingWhitespaceAndCarriageReturnsAreRemoved() {
        assertEquals("::a\nx\n::\n", format("::a  \r\n  x \t\r\n::\r\n"))
    }

    @Test
    fun whitespaceFollowsJavaScriptNotKotlin() {
        // JavaScript's trim() removes the BOM and no-break spaces...
        assertEquals("::a\nx\n::\n", format("\uFEFF::a\n\u00A0x\u3000\n::"))
        // ...but not the information separators that Kotlin's trim() would remove, nor NEL
        assertEquals("\u001C::a\nx\u0085\n", format("\u001C::a\nx\u0085"))
        assertEquals("x", JsText.trim("\u2028  x\n\u2029"))
        assertTrue(JsText.isWhitespace('\uFEFF'))
        assertFalse(JsText.isWhitespace('\u001C'))
        assertFalse(JsText.isWhitespace('\u0085'))
        assertFalse(JsText.isWhitespace('\u180E'))
    }

    @Test
    fun taskListMarkerMatchesOneUtf16CodeUnit() {
        assertTrue(FormatterExpressions.isTaskListItem("- [x] done"))
        // an emoji is two code units, so JavaScript's `\[.\]` does not match it (the item is still an unordered list item)
        assertFalse(FormatterExpressions.isTaskListItem("- [\uD83D\uDE00] x"))
        assertTrue(FormatterExpressions.isUnorderedListItem("- [\uD83D\uDE00] x"))
    }

    @Test
    fun propertyNamesAreRecognisedLikeUpstream() {
        assertEquals("prop-name", FormatterExpressions.getPropertyName("\"prop-name\": 1"))
        assertEquals("x", FormatterExpressions.getPropertyName("'x' : 1"))
        assertEquals("dynamic", FormatterExpressions.getPropertyName(":dynamic: true"))
        assertEquals("", FormatterExpressions.getPropertyName("no colon"))
        assertTrue(FormatterExpressions.isPropertyLine("url: https://x"))
        assertFalse(FormatterExpressions.isPropertyLine("a b: c"))
        assertFalse(FormatterExpressions.isPropertyLine("- item"))
        assertTrue(FormatterExpressions.isFlowArrayStart("items: [ a"))
        assertFalse(FormatterExpressions.isFlowArrayStart("items: [  "))
    }

    @Test
    fun thematicBreakIsTakenForAYamlBlockAsUpstream() {
        // A faithful quirk: monarch-mdc toggles its YAML mode on every `---` line
        assertEquals("text\n\n---\n\n  More text\n", format("text\n\n---\n\nMore text\n"))
    }

    @Test
    fun negativeIndentationFailsLikeTheJavaScriptRangeError() {
        val code = assertThrows(ComarkFormatterException::class.java) { format("intro\n  ```js\ncode\n  ```\n") }
        assertEquals(2, code.line)
        assertEquals(-2, code.indent)
        val yaml = assertThrows(ComarkFormatterException::class.java) { format("---\nitems:\n    - a\n  - b\n- c\n---\n") }
        assertEquals(4, yaml.line)
    }

    @Test
    fun formatOnTypeKeepsTheIndentationOfBlankLines() {
        assertEquals("::a\n  :::b\n  \n  :::\n::\n", format("::a\n:::b\n\n:::\n::\n", formatOnType = true))
        assertEquals("::a\n  :::b\n\n  :::\n::\n", format("::a\n:::b\n  \n:::\n::\n"))
    }

    @Test
    fun checkCanceledIsCalledPerLine() {
        var calls = 0
        ComarkFormatter.format("a\nb\nc\n", checkCanceled = { calls++ })
        assertTrue(calls >= 4)
    }

    // ---- LineContext -------------------------------------------------------------------------------------------------

    private fun context(text: String, line: Int, tabSize: Int = 2) = ComarkFormatter.lineContext(text.split('\n'), line, tabSize)

    @Test
    fun lineContextReportsComponentDepthAndIndentation() {
        val text = "::a\none\n:::b\n\nmore\n:::\n::"
        with(context(text, 1)) {
            assertEquals(1, componentDepth)
            assertEquals(0, indent)
        }
        with(context(text, 3)) {
            assertEquals(2, componentDepth)
            assertEquals(2, indent)
            assertFalse(insideYamlBlock || insideCodeBlock || insideList)
        }
        // a closer gets its opener's indentation
        assertEquals(2, context(text, 5).indent)
        assertEquals(4, context(text, 3, tabSize = 4).indent)
    }

    @Test
    fun lineContextReportsYamlCodeAndLists() {
        val text = "::a\n:::b\n---\ntitle: x\ntext: |\n  line\n\n---\n```\ncode\n\n```\n- item\n\n:::\n::"
        with(context(text, 4)) {
            assertTrue(insideYamlBlock)
            assertFalse(insideMultilineString)
            assertEquals(2, yamlIndent)
        }
        assertTrue(context(text, 6).insideMultilineString)
        assertTrue(context(text, 10).insideCodeBlock)
        assertTrue(context(text, 13).insideList)
        assertFalse(context(text, 12).insideList)
    }

    @Test
    fun lineContextNeverThrows() {
        assertEquals(0, context("intro\n  ```js\ncode\n\n  ```", 3).indent)
        assertNull(context("a\nb", 1).yamlIndent)
    }

    /**
     * A formatted line depends on at most one line below it per pass, so formatting only a prefix of the document gives the
     * same result for the lines far enough from its end; [ComarkFormattingService] relies on this for range formatting.
     */
    @Test
    fun formattingAPrefixGivesTheSameLinesAsFormattingEverything() {
        val cases = JsonParser.parseString(File("src/test/testData/formatter/differential/cases.json").readText()).asJsonArray
            .map { it.asJsonObject }
            .filter { it["error"] == null && !it["formatOnType"].asBoolean }
        var compared = 0
        for (case in cases) {
            val input = case["input"].asString
            val tabSize = case["tabSize"].asInt
            val full = ComarkFormatter.format(input, tabSize).split('\n')
            val lines = input.split('\n')
            for (k in lines.indices step 3) {
                val cut = k + ComarkFormatter.MAX_PASSES + 2
                if (cut >= lines.size - 1) break
                val prefix = try {
                    ComarkFormatter.format(lines.subList(0, cut + 1).joinToString("\n"), tabSize).split('\n')
                } catch (_: ComarkFormatterException) {
                    continue
                }
                assertEquals("${case["name"].asString}, lines 0..$k", full.subList(0, k + 1), prefix.subList(0, k + 1))
                compared++
            }
        }
        assertTrue("compared $compared prefixes", compared > 100)
    }
}
