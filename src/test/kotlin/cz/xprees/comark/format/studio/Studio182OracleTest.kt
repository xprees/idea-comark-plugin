package cz.xprees.comark.format.studio

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import cz.xprees.comark.format.StudioEngine
import cz.xprees.comark.format.studio.StudioOracleCases.Outcome
import cz.xprees.comark.format.studio.StudioOracleCases.text
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress of the nuxt-studio 1.8.2 / comark 0.9.2 port against its oracle (`src/test/testData/studio/1.8.2/oracle.json`).
 * Each check is a ratchet: it fails when fewer cases match than the floor, so a change can only keep or raise the count. Once
 * every case matches, `StudioEngine.V1_8_2` becomes available and these become exact like the 1.8.0 tests.
 */
class Studio182OracleTest {
    private val cases get() = StudioOracleCases.cases182

    @Test
    fun rendererOnStudioTrees() {
        ratchet("1.8.2 renderer, text-path trees", RENDER_TEXT_FLOOR, cases.filter { it["comarkTree"]?.isJsonObject == true }) {
            diff(it.text("text"), render(it["comarkTree"], it["frontmatter"]))
        }
        ratchet("1.8.2 renderer, database-path trees", RENDER_DB_FLOOR, cases.filter { it["dbTree"]?.isJsonObject == true }) {
            diff(it.text("db"), render(it["dbTree"], it["dbFrontmatter"]))
        }
    }

    @Test
    fun textPath() {
        ratchet("1.8.2 tree builder", TREE_FLOOR, cases) {
            verdict(StudioOracleCases.compareTree(it["input"].asString, it["comarkTree"], it["frontmatter"], StudioEngine.V1_8_2))
        }
        ratchet("1.8.2 text path", TEXT_FLOOR, cases) {
            verdict(StudioOracleCases.compareText(it["input"].asString, it["text"], StudioEngine.V1_8_2))
        }
    }

    private fun verdict(v: StudioOracleCases.Verdict): String? =
        if (v.outcome == Outcome.MATCH || v.outcome == Outcome.BOTH_FAIL) null else "${v.outcome} ${v.detail}"

    private fun diff(expected: String?, actual: String?): String? {
        if (expected == actual) return null
        val e = expected ?: "<none>"
        val a = actual ?: "<exception>"
        val i = e.indices.firstOrNull { it >= a.length || e[it] != a[it] } ?: e.length
        fun code(s: String) = s.getOrNull(i)?.let { "U+%04X".format(it.code) } ?: "end"
        return "${code(e)} expected, ${code(a)} actual; " + StudioOracleCases.firstDifference(e, a)
    }

    private fun render(tree: JsonElement?, frontmatter: JsonElement?): String? = try {
        StudioPipeline.render(StudioJson.document(tree!!, frontmatter).copy(release = StudioEngine.V1_8_2))
    } catch (e: Exception) {
        null
    }

    /** [mismatch] gives null for a matching case, else what differs. */
    private fun ratchet(label: String, floor: Int, cases: List<JsonObject>, mismatch: (JsonObject) -> String?) {
        val failing = cases.mapNotNull { case -> mismatch(case)?.let { "${case["name"].asString}: $it" } }
        val count = cases.size - failing.size
        println("$label: $count of ${cases.size} match (floor $floor)")
        failing.forEach { println("  $it") }
        assertTrue("$label: $count of ${cases.size} match, below the floor of $floor", count >= floor)
    }

    private companion object {
        const val RENDER_TEXT_FLOOR = 446
        const val RENDER_DB_FLOOR = 480
        const val TREE_FLOOR = 480
        const val TEXT_FLOOR = 480
    }
}
