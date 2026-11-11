package cz.xprees.comark.format.studio

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [StudioRenderer.renderMarkdown] against comark 0.7.0 for hand-made trees whose attribute values the parsed oracle corpus
 * does not produce (numbers, booleans, `null`, objects, bindings, `$` html markers, odd `ol` starts, ...), recorded by
 * `src/test/node/studio-oracle/record-vectors.mjs` into `src/test/testData/studio/render-vectors.json`.
 */
class StudioRenderVectorsTest {
    @Test
    fun rendersLikeComark() {
        val vectors = JsonParser.parseString(File("src/test/testData/studio/render-vectors.json").readText()).asJsonObject
            .getAsJsonArray("vectors").map { it.asJsonObject }
        val failures = ArrayList<String>()
        for (v in vectors) {
            val tree = v["tree"].asJsonObject
            val actual = StudioRenderer.renderMarkdown(StudioJson.document(tree, tree["frontmatter"]))
            val expected = v["markdown"].asString
            if (actual != expected) {
                failures += "${tree["nodes"]}\n  expected ${v["markdown"]}\n  actual   ${com.google.gson.JsonPrimitive(actual)}"
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
