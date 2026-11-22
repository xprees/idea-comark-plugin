package cz.xprees.comark.format.studio

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import cz.xprees.comark.format.StudioEngine
import cz.xprees.comark.format.studio.StudioOracleCases.Outcome
import cz.xprees.comark.format.studio.StudioOracleCases.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress of the nuxt-studio 1.8.2 / comark 0.9.2 port against its oracle (`src/test/testData/studio/1.8.2/oracle.json`) and
 * the seeded random documents recorded from the same release (`1.8.2/text-vectors.json`).
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

    @Test
    fun textPathRandomDocuments() {
        val vectors = StudioOracleCases.textVectors182
        ratchet("1.8.2 tree builder, random documents", VECTOR_TREE_FLOOR, vectors) {
            verdict(StudioOracleCases.compareTree(it["input"].asString, it["tree"], it["frontmatter"], StudioEngine.V1_8_2))
        }
        ratchet("1.8.2 text path, random documents", VECTOR_TEXT_FLOOR, vectors) {
            verdict(StudioOracleCases.compareText(it["input"].asString, it["text"], StudioEngine.V1_8_2))
        }
    }

    /**
     * The block-wise machinery ([StudioTreeBuilder.buildSegmented], [StudioSafety], [StudioFormatter.formatPreserving]) with
     * release 1.8.2, as `StudioPreservingDifferentialTest` checks 1.8.0: rendering each top-level block and joining them gives
     * exactly what 1.8.2 writes, and a document with no kept block comes out exactly as 1.8.2 writes it.
     */
    @Test
    fun blockwiseRendering() {
        val docs = cases.map { it["name"].asString to it } + StudioOracleCases.textVectors182.mapIndexed { i, v -> "vector #$i" to v }
        ratchet("1.8.2 block-wise rendering", BLOCKWISE_FLOOR, docs.filter { it.second.text("text") != null }.map { it.second }) {
            diff(it.text("text"), StudioFormatter.formatBlockwise(it["input"].asString, StudioEngine.V1_8_2))
        }
        var unclassified = 0
        ratchet("1.8.2 formatPreserving without kept blocks", PRESERVING_FLOOR, docs.map { it.second }) {
            val r = StudioFormatter.formatPreserving(it["input"].asString, StudioEngine.V1_8_2)
            if (r == null || r.kept.isNotEmpty()) return@ratchet null
            unclassified++
            diff(it.text("text"), r.text)
        }
        println("1.8.2: $unclassified documents with no kept block")
        assertTrue("1.8.2: $unclassified documents with no kept block, below $UNCLASSIFIED_FLOOR", unclassified >= UNCLASSIFIED_FLOOR)
    }

    /** The 1.8.2 Studio style is idempotent on the oracle inputs and the random documents, and every run finds a fixed point. */
    @Test
    fun styleIsIdempotent() {
        val docs = cases.map { it["input"].asString } + StudioOracleCases.textVectors182.map { it["input"].asString }
        val options = StudioStyleOptions(collection = StudioCollection.page(DOC_ID), release = StudioEngine.V1_8_2)
        ratchet("1.8.2 style idempotence", IDEMPOTENT_FLOOR, docs.map { JsonObject().apply { addProperty("input", it) } }) {
            val r = StudioStyle.format(it["input"].asString, options) ?: return@ratchet null
            if (!r.diagnostics.converged) return@ratchet "no fixed point"
            diff(r.text, StudioStyle.format(r.text, options)?.text)
        }
    }

    /**
     * [StudioDbPath] with release 1.8.2 against the database path recorded from it (`1.8.2/db-vectors.json`), as
     * [StudioDbPathDifferentialTest] checks 1.8.0: no `MISMATCH` category, and the matches per configuration ratcheted.
     */
    @Test
    fun databasePath() {
        val counts = StudioDbPathDifferentialTest().categories(StudioDbVectors.vectors182, StudioEngine.V1_8_2, System.getenv("STUDIO_DB_REPORT_182"))
        assertEquals(emptyMap<String, Int>(), counts.filterKeys { "MISMATCH" in it })
        for ((config, floor) in DB_MATCH_FLOORS) {
            val count = counts["$config: match"] ?: 0
            assertTrue("1.8.2 database path [$config]: $count matches, below the floor of $floor", count >= floor)
        }
    }

    /**
     * Files nuxt-studio 1.8.2 wrote and leaves unchanged on both save paths (dual fixed points: the oracle's `db` where
     * `textOfDb` and `db2` equal it, and the T(x) and D(y) of `1.8.2/db-vectors.json` that both paths keep) come out of the
     * 1.8.2 Studio style unchanged, as `StudioStyleDifferentialTest.studioWrittenFilesAreUnchanged` checks for 1.8.0.
     */
    @Test
    fun studioWrittenFilesAreUnchanged() {
        val counts = sortedMapOf<String, Int>()
        val failing = ArrayList<String>()
        fun check(label: String, name: String, z: String, collection: StudioCollection) {
            val r = StudioStyle.format(z, StudioStyleOptions(collection = collection, release = StudioEngine.V1_8_2))
            val outcome = if (r?.text == z) "unchanged" else "CHANGED"
            counts.merge("$label: $outcome", 1, Int::plus)
            if (r?.text != z) failing += "$label $name\n" + StudioOracleCases.firstDifference(z, r?.text ?: "<null>")
        }
        for (c in cases) {
            val db = c.text("db") ?: continue
            if (c.text("textOfDb") != db || c["dbStable"]?.asBoolean != true) continue
            val collection = if (c["collectionType"]?.asString == "data") StudioCollection.data(DOC_ID) else StudioCollection.page(DOC_ID)
            check("oracle D(x)", c["name"].asString, db, collection)
        }
        for (v in StudioDbVectors.vectors182) {
            for (config in StudioDbVectors.CONFIGS) {
                val r = v.byConfig.getValue(config)
                val collection = StudioDbVectors.collection(config, r.id)
                if (v.yStable && r.dEqualsY) check("[$config] T(x)", v.source, v.y, collection)
                val d = r.dText ?: continue
                if (d != v.y && r.tOfD == StudioDbVectors.Result.Text(d) && r.dOfD == StudioDbVectors.Result.Text(d)) check("[$config] D(y)", v.source, d, collection)
            }
        }
        println(counts.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        failing.take(20).forEach { println("  $it") }
        assertEquals(emptyList<String>(), counts.keys.filter { "CHANGED" in it })
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
        val failing = cases.mapIndexedNotNull { i, case -> mismatch(case)?.let { "${case["name"]?.asString ?: "vector #$i"}: $it" } }
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
        const val VECTOR_TREE_FLOOR = 5021
        const val VECTOR_TEXT_FLOOR = 5021
        val DB_MATCH_FLOORS = mapOf("pageDefault" to 2131, "pageSchema" to 2115, "data" to 2131)
        const val DOC_ID = "content/docs/page.md"
        const val BLOCKWISE_FLOOR = 4849
        const val PRESERVING_FLOOR = 5501
        const val UNCLASSIFIED_FLOOR = 2998
        const val IDEMPOTENT_FLOOR = 5501
    }
}
