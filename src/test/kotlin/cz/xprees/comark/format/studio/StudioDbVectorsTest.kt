package cz.xprees.comark.format.studio

import com.google.gson.JsonObject
import cz.xprees.comark.format.studio.StudioDbVectors.CONFIGS
import cz.xprees.comark.format.studio.StudioDbVectors.Result
import cz.xprees.comark.format.studio.StudioDbVectors.corpus
import cz.xprees.comark.format.studio.StudioDbVectors.vectors
import cz.xprees.comark.format.studio.StudioOracleCases.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity checks on `db-vectors.json` (see [StudioDbVectors]): the file is well-formed, it was recorded from the same pinned
 * Studio, comark and js-yaml files as `oracle.json`, every y really is the recorded text-path output of its source, its
 * summary statistics agree with the vectors, and the Kotlin text path reproduces every T the file records (T(y) and
 * T(D(y))). Prints the statistics DB-PATH.md quotes.
 */
class StudioDbVectorsTest {
    private val rule = Regex("^(FM|B|Y)-[A-Z0-9-]+$|^RENDER$")

    @Test
    fun isWellFormed() {
        assertTrue("vectors", vectors.size > 4000)
        assertEquals(vectors.size, vectors.map { it.y }.toSet().size)
        assertEquals(corpus.summary["vectors"].asInt, vectors.size)
        assertEquals(CONFIGS, corpus.header.getAsJsonObject("configs").keySet().toList())
        val files = corpus.header.getAsJsonArray("files").map { it.asString }
        for (v in vectors) {
            assertTrue(v.source, v.source.matches(Regex("(?s)^(oracle:.+|text-vectors:\\d+|fm-probe:\\d+\\.\\d+|db-fuzz:\\d+|db-probe:\\d+)$")))
            assertTrue(v.source, v.inputs >= 1)
            for (name in CONFIGS) {
                val c = v.byConfig.getValue(name)
                assertTrue(c.id, c.id in files)
                when (val d = c.d) {
                    is Result.Failure -> {
                        assertTrue(d.error, d.error.isNotEmpty())
                        assertFalse(c.dEqualsY)
                        assertEquals(null, c.tOfD)
                        assertEquals(null, c.dOfD)
                        assertTrue(c.rules.isEmpty())
                    }
                    is Result.Text -> {
                        assertEquals(c.dEqualsY, d.text == v.y)
                        assertTrue("${v.source} $name rules", c.dEqualsY == c.rules.isEmpty())
                        for (r in c.rules) assertTrue("${v.source} $name rule $r", rule.matches(r))
                        assertTrue(c.tOfD != null && c.dOfD != null)
                        assertTrue("D output ends with a newline", d.text.endsWith("\n"))
                    }
                }
            }
        }
    }

    @Test
    fun wasRecordedFromTheOracleFiles() {
        val oracle = com.google.gson.JsonParser.parseReader(java.io.File("src/test/testData/studio/oracle.json").reader()).asJsonObject
        for (key in listOf("versions", "studioFiles", "rendererFiles")) assertEquals(key, oracle[key], corpus.header[key])
    }

    /** Every y is T(x) of its source: the oracle case's `text` or the text vector's `text`. */
    @Test
    fun yIsTheRecordedTextPathOutput() {
        val cases = StudioOracleCases.cases.associateBy { it["name"].asString }
        val textVectors = StudioOracleCases.textVectors
        var checked = 0
        for (v in vectors) {
            val expected = when {
                v.source.startsWith("oracle:") -> cases.getValue(v.source.removePrefix("oracle:")).text("text")
                v.source.startsWith("text-vectors:") -> textVectors[v.source.removePrefix("text-vectors:").toInt()].text("text")
                else -> continue
            }
            assertEquals(v.source, expected, v.y)
            checked++
        }
        assertEquals(corpus.summary["vectors"].asInt - probeVectors(), checked)
    }

    /** Vectors whose source is not an oracle case or text vector (front matter probes, database-path reproducers). */
    private fun probeVectors() = vectors.count { it.source.startsWith("fm-probe:") || it.source.startsWith("db-") }

    @Test
    fun summaryAgreesWithTheVectors() {
        val byConfig = corpus.summary.getAsJsonObject("byConfig")
        assertEquals(corpus.summary["yStable"].asInt, vectors.count { it.yStable })
        for (name in CONFIGS) {
            val s = byConfig.getAsJsonObject(name)
            assertCounts(name, s, vectors)
            assertCounts("$name (T-stable y)", s.getAsJsonObject("onTStable"), vectors.filter { it.yStable })
        }
    }

    private fun assertCounts(label: String, s: JsonObject, vs: List<StudioDbVectors.Vector>) {
        val rs = vs.map { it.byConfig.getValue(label.substringBefore(' ')) }
        val ok = rs.filter { it.d is Result.Text }
        assertEquals("$label vectors", s["vectors"].asInt, rs.size)
        assertEquals("$label dErrors", s["dErrors"].asInt, rs.count { it.d is Result.Failure })
        assertEquals("$label dEqualsY", s["dEqualsY"].asInt, ok.count { it.dEqualsY })
        assertEquals("$label tOfDEqualsD", s["tOfDEqualsD"].asInt, ok.count { it.tOfD == it.d })
        assertEquals("$label dOfDEqualsD", s["dOfDEqualsD"].asInt, ok.count { it.dOfD == it.d })
        assertEquals("$label dualFixedPoint", s["dualFixedPoint"].asInt, ok.count { it.tOfD == it.d && it.dOfD == it.d })
        val categories = s.getAsJsonObject("categories")
        for ((rule, count) in categories.entrySet()) {
            val actual = if (rule == "error") rs.count { it.d is Result.Failure } else rs.count { rule in it.rules }
            assertEquals("$label category $rule", count.asInt, actual)
        }
    }

    /**
     * The Kotlin text path ([StudioFormatter.format]) on the documents of this file: on every y (giving T(y)) and every
     * D(y) (giving T(D(y))). These are new inputs for the port (D's output is not in the text-path corpora), so mismatches
     * are counted rather than forbidden; the counts are exact and may only improve.
     */
    @Test
    fun kotlinTextPathReproducesTheRecordedTextPath() {
        var yMatch = 0
        var yMismatch = 0
        var dMatch = 0
        var dMismatch = 0
        val examples = mutableListOf<String>()
        fun expected(r: Result?) = (r as? Result.Text)?.text
        for (v in vectors) {
            if (StudioFormatter.format(v.y) == expected(v.tOfY)) yMatch++ else { yMismatch++; if (examples.size < 10) examples += "T(y) ${v.source}" }
            for (name in CONFIGS) {
                val c = v.byConfig.getValue(name)
                val d = c.dText ?: continue
                if (d == v.y) continue
                if (StudioFormatter.format(d) == expected(c.tOfD)) dMatch++ else { dMismatch++; if (examples.size < 10) examples += "T(D(y)) ${v.source} $name" }
            }
        }
        println("Kotlin T on db-vectors: T(y) $yMatch match, $yMismatch differ; T(D(y)) $dMatch match, $dMismatch differ")
        examples.forEach { println("  $it") }
        assertEquals("T(y) mismatches", 0, yMismatch)
        assertEquals("T(D(y)) mismatches", EXPECTED_T_OF_D_MISMATCHES, dMismatch)
    }

    @Test
    fun printsStatistics() {
        val byConfig = corpus.summary.getAsJsonObject("byConfig")
        println("db-vectors: ${vectors.size} distinct y, ${vectors.count { it.yStable }} with T(y) == y")
        for (name in CONFIGS) {
            for ((label, s) in listOf("all y" to byConfig.getAsJsonObject(name), "T-stable y" to byConfig.getAsJsonObject(name).getAsJsonObject("onTStable"))) {
                val n = s["vectors"].asInt
                val ok = n - s["dErrors"].asInt
                println(
                    "$name, $label: $n vectors, D throws ${s["dErrors"].asInt}, D(y)==y ${s["dEqualsY"].asInt}, " +
                        "T(D(y))==D(y) ${s["tOfDEqualsD"].asInt}/$ok, D(D(y))==D(y) ${s["dOfDEqualsD"].asInt}/$ok, " +
                        "both ${s["dualFixedPoint"].asInt}/$ok",
                )
                println("  rules: " + s.getAsJsonObject("categories").entrySet().joinToString(", ") { "${it.key} ${it.value.asInt}" })
            }
        }
    }

    private companion object {
        const val EXPECTED_T_OF_D_MISMATCHES = 0
    }
}
