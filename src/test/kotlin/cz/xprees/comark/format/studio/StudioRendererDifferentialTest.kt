package cz.xprees.comark.format.studio

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Feeds the comark trees of every Studio oracle case (`src/test/testData/studio/oracle.json`) to [StudioPipeline] and requires
 * byte equality with what nuxt-studio 1.8.0 itself wrote (comark 0.7.0 `renderMarkdown` plus `padBlankLinesInComponents`):
 *
 * - the text-path tree (`comarkTree`, `frontmatter`) must give `text`, Studio's output after a save from the code editor;
 * - the database-path tree (`dbTree`, `dbFrontmatter`) must give `db`, Studio's output for a document it opened from Nuxt
 *   Content's database, which is where both of its editors start. Its trees differ from the text path's: for example
 *   `highlights` of a code fence is a space-joined string there.
 *
 * Cases whose text path failed (invalid YAML in front matter or props) have no text-path tree; they are counted, not skipped
 * silently.
 */
class StudioRendererDifferentialTest {
    private val oracle by lazy { JsonParser.parseString(File("src/test/testData/studio/oracle.json").readText()).asJsonObject }
    private val cases by lazy { oracle.getAsJsonArray("cases").map { it.asJsonObject } }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    @Test
    fun rendersEveryTextPathTreeLikeStudio() {
        val withTree = cases.filter { it["comarkTree"]?.isJsonObject == true }
        assertEquals("cases without a text-path tree (the text path threw)", 34, cases.size - withTree.size)
        assertEquals("cases with a text-path tree", 438, withTree.size)
        check("text path", withTree, "comarkTree", "frontmatter", "text")
    }

    @Test
    fun rendersEveryDatabasePathTreeLikeStudio() {
        val withTree = cases.filter { it["dbTree"]?.isJsonObject == true }
        assertEquals("cases with a database-path tree", 472, withTree.size)
        check("database path", withTree, "dbTree", "dbFrontmatter", "db")
    }

    private fun check(label: String, withTree: List<JsonObject>, treeKey: String, frontmatterKey: String, outputKey: String) {
        val mismatches = ArrayList<String>()
        for (case in withTree) {
            val name = case["name"].asString
            val expected = case.text(outputKey) ?: error("$name: $outputKey must be text when $treeKey is recorded")
            val actual = render(case[treeKey], case[frontmatterKey])
            if (actual != expected) mismatches += describe(name, expected, actual)
        }
        println("Studio renderer, $label: ${withTree.size - mismatches.size} of ${withTree.size} oracle trees match")
        mismatches.forEach { println(it) }
        assertEquals("$label mismatches:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    private fun render(tree: JsonElement, frontmatter: JsonElement?): String = try {
        StudioPipeline.render(StudioJson.document(tree, frontmatter))
    } catch (e: Exception) {
        "<<exception ${e::class.java.simpleName}: ${e.message}>>"
    }

    private fun describe(name: String, expected: String, actual: String): String {
        var i = 0
        while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
        val from = maxOf(0, i - 60)
        fun window(s: String) = s.substring(minOf(from, s.length), minOf(s.length, i + 60)).replace("\n", "\\n")
        return "  $name @ $i\n    expected: ${window(expected)}\n    actual:   ${window(actual)}"
    }
}
