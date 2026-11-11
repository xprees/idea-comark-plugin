package cz.xprees.comark.format.studio

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [StudioRenderer.renderMarkdown] against comark 0.7.0, using vectors recorded by
 * `src/test/node/studio-oracle/record-vectors.mjs`:
 *
 * - `render-vectors.json`: hand-made trees with attribute values the parsed oracle corpus does not produce (numbers,
 *   booleans, `null`, objects, bindings, `$` html markers, odd `ol` starts, string `highlights` as on Studio's database path,
 *   shared YAML objects, ...) and renderer branches the corpus does not reach;
 * - `render-random-vectors.json`: trees from a seeded random generator.
 *
 * The vector counts are fixed so that a re-recording cannot silently drop vectors.
 */
class StudioRenderVectorsTest {
    @Test
    fun rendersHandMadeTreesLikeComark() = check("render-vectors.json", 61)

    @Test
    fun rendersRandomTreesLikeComark() = check("render-random-vectors.json", 1500)

    private fun check(file: String, expectedCount: Int) {
        val vectors = JsonParser.parseString(File("src/test/testData/studio/$file").readText()).asJsonObject
            .getAsJsonArray("vectors").map { it.asJsonObject }
        assertEquals("$file: vector count", expectedCount, vectors.size)
        val failures = ArrayList<String>()
        for (v in vectors) {
            val tree = v["tree"].asJsonObject
            val actual = try {
                StudioRenderer.renderMarkdown(StudioJson.document(tree, tree["frontmatter"]))
            } catch (e: Exception) {
                "<<exception ${e::class.java.simpleName}: ${e.message}>>"
            }
            val expected = v["markdown"].asString
            if (actual != expected) {
                failures += "${tree["nodes"]}\n  expected ${v["markdown"]}\n  actual   ${JsonPrimitive(actual)}"
            }
        }
        println("Studio renderer, $file: ${vectors.size - failures.size} of ${vectors.size} vectors match")
        assertEquals(failures.take(30).joinToString("\n"), 0, failures.size)
    }
}
