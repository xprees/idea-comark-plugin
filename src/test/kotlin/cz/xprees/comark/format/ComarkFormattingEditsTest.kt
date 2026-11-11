package cz.xprees.comark.format

import com.google.gson.JsonParser
import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** How [ComarkFormattingService] turns the formatter's result into document edits: line ranges and minimal edits. */
class ComarkFormattingEditsTest {
    private fun edits(text: String, vararg ranges: TextRange, tabSize: Int = 2, whitespaceOnly: Boolean = false) =
        ComarkFormattingService.computeEdits(text, ranges.toList(), tabSize, whitespaceOnly)

    private fun apply(text: String, edits: List<ComarkFormattingService.Companion.Edit>): String {
        val sb = StringBuilder(text)
        for (edit in edits.asReversed()) sb.replace(edit.start, edit.end, edit.text)
        return sb.toString()
    }

    private fun reformat(text: String, vararg ranges: TextRange, tabSize: Int = 2, whitespaceOnly: Boolean = false) =
        apply(text, edits(text, *ranges, tabSize = tabSize, whitespaceOnly = whitespaceOnly))

    private fun whole(text: String) = TextRange(0, text.length)

    @Test
    fun wholeFileGivesTheFormatterResult() {
        val text = "::a\n:::b\n    text  \n:::\n::"
        assertEquals(ComarkFormatter.format(text), reformat(text, whole(text)))
    }

    @Test
    fun editsTouchOnlyIndentationAndTrailingWhitespace() {
        val text = "::a\n:::b\n      text  \n:::\n::\n"
        val result = edits(text, whole(text))
        // ":::b" gets two spaces, "      text  " loses four spaces of indentation and its trailing spaces, ":::" gets two
        assertEquals(4, result.size)
        val textStart = text.indexOf("      text")
        assertEquals(listOf(4, textStart + 2, textStart + 10, text.indexOf(":::\n::")), result.map { it.start })
        assertTrue(result.all { edit -> edit.text.isBlank() && text.substring(edit.start, edit.end).isBlank() })
    }

    @Test
    fun rangeFormattingChangesOnlyTheLinesItTouches() {
        val text = "::a\n:::b\nline one\nline two\nline three\n:::\n::"
        val lineTwo = text.indexOf("line two")
        val result = reformat(text, TextRange(lineTwo, lineTwo + 3))
        assertEquals("::a\n:::b\nline one\n  line two\nline three\n:::\n::", result)
        // a selection of whole lines ends at the start of the next line, which is not part of it
        val lineThree = text.indexOf("line three")
        assertEquals("::a\n:::b\nline one\n  line two\nline three\n:::\n::", reformat(text, TextRange(lineTwo, lineThree)))
        // an empty range formats its line
        assertEquals("::a\n:::b\nline one\nline two\n  line three\n:::\n::", reformat(text, TextRange(lineThree + 2, lineThree + 2)))
    }

    @Test
    fun finalLineBreakIsAddedOnlyWhenTheLastLineIsFormatted() {
        assertEquals("a\n", reformat("a", TextRange(0, 1)))
        val text = "::a\ntext\n::"
        assertEquals(text, reformat(text, TextRange(0, 2)))
        assertEquals("$text\n", reformat(text, TextRange(text.length - 1, text.length)))
        assertEquals("", reformat("", TextRange(0, 0)))
    }

    @Test
    fun whitespaceOnlyCallersKeepCharactersThatAreOnlyJavaScriptWhitespace() {
        val text = "::a\n:::b\n\u00A0text\n:::\n::\n"
        assertEquals("::a\n  :::b\n\u00A0text\n  :::\n::\n", reformat(text, whole(text), whitespaceOnly = true))
        assertEquals("::a\n  :::b\n  text\n  :::\n::\n", reformat(text, whole(text)))
    }

    @Test
    fun failureFurtherDownDoesNotBlockARangeAbove() {
        val body = (1..20).joinToString("\n") { "line $it" }
        val text = "::a\n:::b\n$body\n:::\n::\n  ```js\nbad\n  ```\n"
        val start = text.indexOf("line 1")
        assertEquals(text.replace("\nline 1\n", "\n  line 1\n"), reformat(text, TextRange(start, start + 1)))
    }

    /** Range formatting must give each line in the range exactly what whole-file formatting gives it, and touch nothing else. */
    @Test
    fun rangeResultsAgreeWithWholeFileFormattingOnTheRecordedCorpus() {
        val random = Random(42)
        val cases = JsonParser.parseString(File("src/test/testData/formatter/differential/cases.json").readText()).asJsonArray
            .map { it.asJsonObject }
            .filter { it["error"] == null && !it["formatOnType"].asBoolean }
        var compared = 0
        for (case in cases) {
            // documents never contain \r: the editor normalizes line separators
            val text = case["input"].asString.replace("\r", "")
            val tabSize = case["tabSize"].asInt
            val full = try {
                ComarkFormatter.format(text, tabSize).split('\n')
            } catch (_: ComarkFormatterException) {
                continue
            }
            val original = text.split('\n')
            repeat(3) {
                val a = random.nextInt(text.length + 1)
                val b = random.nextInt(text.length + 1)
                val range = TextRange(minOf(a, b), maxOf(a, b))
                val result = reformat(text, range, tabSize = tabSize).split('\n')
                val firstLine = text.substring(0, range.startOffset).count { it == '\n' }
                val lastLine = if (range.isEmpty) firstLine else text.substring(0, range.endOffset - 1).count { it == '\n' }.coerceAtLeast(firstLine)
                for (line in original.indices) {
                    val expected = if (line in firstLine..lastLine) full[line] else original[line]
                    assertEquals("${case["name"].asString} $range line $line", expected, result[line])
                }
                compared++
            }
        }
        assertTrue("compared $compared ranges", compared > 300)
    }
}
