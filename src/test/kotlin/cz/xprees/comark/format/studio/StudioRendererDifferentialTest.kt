package cz.xprees.comark.format.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Feeds the comark tree and front matter of every Studio oracle case (`src/test/testData/studio/oracle.json`) to
 * [StudioPipeline] and requires byte equality with `renderedFromTree`, which is what comark 0.7.0 `renderMarkdown` plus
 * nuxt-studio 1.8.0 `padBlankLinesInComponents` produced for the same tree under Node.
 *
 * Cases whose text path failed (invalid YAML in front matter or props) have no tree; they are counted, not skipped silently.
 */
class StudioRendererDifferentialTest {
    private val oracle by lazy { JsonParser.parseString(File("src/test/testData/studio/oracle.json").readText()).asJsonObject }

    private fun JsonObject.isText(key: String) = get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true

    @Test
    fun rendersEveryOracleTreeLikeComark() {
        val cases = oracle.getAsJsonArray("cases").map { it.asJsonObject }
        val withTree = cases.filter { it["comarkTree"]?.isJsonObject == true }
        val withoutTree = cases.size - withTree.size
        val mismatches = ArrayList<String>()
        var matched = 0
        for (case in withTree) {
            val name = case["name"].asString
            assertTrue("$name: renderedFromTree must be text", case.isText("renderedFromTree"))
            val expected = case["renderedFromTree"].asString
            val actual = try {
                StudioPipeline.render(StudioJson.document(case["comarkTree"], case["frontmatter"]))
            } catch (e: Exception) {
                "<<exception ${e::class.java.simpleName}: ${e.message}>>"
            }
            if (actual == expected) matched++ else mismatches += describe(name, expected, actual)
        }
        println("Studio renderer: $matched of ${withTree.size} oracle trees match ($withoutTree cases have no tree: text path threw)")
        mismatches.forEach { println(it) }
        assertEquals("cases without a tree (the text path threw)", 34, withoutTree)
        assertEquals("mismatching cases:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    private fun describe(name: String, expected: String, actual: String): String {
        var i = 0
        while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
        val from = maxOf(0, i - 60)
        fun window(s: String) = s.substring(minOf(from, s.length), minOf(s.length, i + 60)).replace("\n", "\\n")
        return "  $name @ $i\n    expected: ${window(expected)}\n    actual:   ${window(actual)}"
    }
}
