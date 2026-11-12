package cz.xprees.comark.format.studio

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.intellij.util.diff.Diff
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * What the **real** Nuxt Studio 1.8.0 does to the IDE's output: `style-vectors.json` (`record-style-vectors.mjs`) holds, for
 * every oracle and random input x, y = [StudioStyle.format] (x) and real Studio's text path T(y) and database path D(y).
 *
 * The test first checks that the engine still writes every recorded y (when it does not, the vectors are stale: write the
 * inputs with `STUDIO_STYLE_INPUTS=<file>` and record again, see the oracle README). Then every document lands in one
 * category per save path: unchanged; changed only inside the blocks the result reports (kept verbatim, unstable, or not
 * verified against the database path, or affected by a damaged block before them); or changed elsewhere, labelled by how
 * ([StudioDbRewrite]: damaging or markup only). For the database path, the lines after a block kept for an unclosed HTML tag
 * and the end of a document with a kept footnote count as reported: the warnings on those blocks say Studio changes them.
 * The counts are pinned; they are the residual differences against real Studio, not against the emulation.
 */
class StudioStyleOracleTest {
    private class Doc(val name: String, val input: String, val config: String, val collection: StudioCollection)

    private val docs: List<Doc> by lazy {
        StudioOracleCases.cases.map {
            val data = it["collectionType"]?.asString == "data"
            Doc(it["name"].asString, it["input"].asString, if (data) "data" else "pageDefault", if (data) StudioCollection.data(DOC_ID) else StudioCollection.page(DOC_ID))
        } + StudioOracleCases.textVectors.mapIndexed { i, v -> Doc("text-vectors #$i", v["input"].asString, "pageDefault", StudioCollection.page(DOC_ID)) }
    }

    private val vectors: Map<String, JsonObject> by lazy {
        val reader = JsonReader(File(VECTORS).reader()).apply { setNestingLimit(10_000) }
        reader.use { JsonParser.parseReader(it).asJsonObject.getAsJsonArray("vectors").associate { e -> e.asJsonObject["name"].asString to e.asJsonObject } }
    }

    @Test
    fun realStudioLeavesTheOutputUnchangedOutsideReportedBlocks() {
        val inputsFile = System.getenv("STUDIO_STYLE_INPUTS")
        val written = ArrayList<Map<String, String>>()
        val counts = sortedMapOf<String, Int>()
        val details = LinkedHashMap<String, MutableList<String>>()
        fun add(category: String, detail: String) {
            counts.merge(category, 1, Int::plus)
            details.getOrPut(category) { ArrayList() }.let { if (it.size < 6) it += detail }
        }
        for (doc in docs) {
            val r = StudioStyle.format(doc.input, StudioStyleOptions(collection = doc.collection))
            if (r == null) {
                add("not parsed", doc.name)
                continue
            }
            written += mapOf("name" to doc.name, "config" to doc.config, "id" to DOC_ID, "y" to r.text)
            val v = vectors[doc.name]
            if (v == null || v["y"].asString != r.text) {
                add("STALE (re-record style-vectors.json)", doc.name)
                continue
            }
            val reported = HashSet<Int>()
            r.keptBlocks.forEach { reported += it.outputLines }
            r.unstableBlocks.forEach { reported += it.outputLines }
            r.unverifiedBlocks.forEach { reported += it.outputLines }
            r.affectedBlocks.forEach { reported += it.outputLines }
            // what the warnings on kept blocks say about the database path: an unclosed HTML tag takes in the rest of the
            // document, and footnote definitions move to a section appended at its end
            val lineCount = r.text.split("\n").size
            val reportedForD = HashSet(reported)
            r.keptBlocks.filter { StudioRewriteReason.UNCLOSED_HTML_TAG in it.reasons }.minOfOrNull { it.outputLines.first }?.let { reportedForD += it until lineCount }
            val hunks = ArrayList<String>()
            val t = path(r.text, v, "t", reported, "T", hunks)
            val d = path(r.text, v, "d", reportedForD, "D", hunks, appendsAtEnd = r.keptBlocks.any { StudioRewriteReason.FOOTNOTE in it.reasons })
            add("T: $t | D: $d", "${doc.name}\n" + hunks.joinToString("\n"))
        }
        if (inputsFile != null) {
            // non-ASCII characters escaped: some random documents hold lone surrogates, which UTF-8 cannot encode
            val json = GsonBuilder().disableHtmlEscaping().create().toJson(mapOf("documents" to written))
            val ascii = buildString { for (ch in json) if (ch.code < 0x80) append(ch) else append("\\u%04x".format(ch.code)) }
            File(inputsFile).apply { parentFile?.mkdirs() }.writeText(ascii)
        }
        println(counts.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        details.filterKeys { "STALE" in it }.values.flatten().forEach { println("stale: $it") }
        System.getenv("STUDIO_STYLE_REPORT")?.let { dir ->
            File(dir, "real-studio.txt").writeText(
                counts.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n" +
                    details.entries.joinToString("\n") { (k, v) -> "##### $k\n" + v.joinToString("\n\n") },
            )
        }
        assertEquals(emptyMap<String, Int>(), counts.filterKeys { "STALE" in it })
        assertEquals(REAL_STUDIO, counts)
    }

    /** How the recorded save path ([key]: `t` or `d`) changes [y]: one of the category labels. */
    private fun path(y: String, v: JsonObject, key: String, reported: Set<Int>, label: String, hunks: MutableList<String>, appendsAtEnd: Boolean = false): String {
        if (v["${key}EqualsY"].asBoolean) return "unchanged"
        val out = v[key]
        if (out == null || !out.isJsonPrimitive) {
            return if (reported.isNotEmpty()) "throws (a kept or reported block)" else "THROWS"
        }
        val other = out.asString
        val yLines = y.split("\n")
        val otherLines = other.split("\n")
        val changes = Diff.buildChanges(yLines.toTypedArray(), otherLines.toTypedArray())
        var c = changes
        var outside = false
        while (c != null) {
            val deleted = (c.line0 until c.line0 + c.deleted).toList()
            // the footnote section the database path appends after the last block
            if (appendsAtEnd && c.deleted == 0 && yLines.subList(c.line0, yLines.size).all { it.isBlank() }) {
                c = c.link
                continue
            }
            val content = deleted.filter { yLines.getOrNull(it)?.isNotBlank() == true }
            // content lines must lie in reported blocks; blank lines and insertions must at least border one
            if (content.any { it !in reported } || content.isEmpty() && !StudioStyle.bordersReported(yLines, c.line0, c.deleted, reported)) {
                if (!outside) {
                    hunks += "$label: " + yLines.subList(c.line0, c.line0 + c.deleted).joinToString("\\n") + "  ==>  " +
                        otherLines.subList(c.line1, c.line1 + c.inserted).joinToString("\\n")
                }
                outside = true
            }
            c = c.link
        }
        if (!outside) return "changes only reported blocks"
        val how = try {
            StudioStyle.classify(StudioTreeBuilder.build(y).nodes, StudioTreeBuilder.build(other).nodes)
        } catch (_: StudioTextPathException) {
            null
        } catch (_: StudioUnsupportedException) {
            null
        }
        val kind = when {
            how == null -> "unclassified"
            how.damages -> "damaging, ${how.name}"
            else -> "markup only, ${how.name}"
        }
        return "$label CHANGES OTHER LINES ($kind)"
    }

    private companion object {
        const val DOC_ID = "content/docs/page.md"
        const val VECTORS = "src/test/testData/studio/style-vectors.json"

        /** Every oracle and random document by what real Studio's two save paths do to its formatted text. */
        val REAL_STUDIO = mapOf(
            "T: changes only reported blocks | D: D CHANGES OTHER LINES (damaging, TEXT)" to 31,
            "T: changes only reported blocks | D: D CHANGES OTHER LINES (unclassified)" to 1,
            "T: changes only reported blocks | D: changes only reported blocks" to 1969,
            "T: changes only reported blocks | D: throws (a kept or reported block)" to 33,
            "T: changes only reported blocks | D: unchanged" to 11,
            "T: throws (a kept or reported block) | D: D CHANGES OTHER LINES (unclassified)" to 3,
            "T: throws (a kept or reported block) | D: changes only reported blocks" to 595,
            "T: throws (a kept or reported block) | D: throws (a kept or reported block)" to 54,
            "T: throws (a kept or reported block) | D: unchanged" to 5,
            "T: unchanged | D: D CHANGES OTHER LINES (damaging, STRUCTURE)" to 2,
            "T: unchanged | D: D CHANGES OTHER LINES (damaging, TEXT)" to 3,
            "T: unchanged | D: changes only reported blocks" to 668,
            "T: unchanged | D: throws (a kept or reported block)" to 6,
            "T: unchanged | D: unchanged" to 2119,
            "not parsed" to 1,
        )
    }
}
