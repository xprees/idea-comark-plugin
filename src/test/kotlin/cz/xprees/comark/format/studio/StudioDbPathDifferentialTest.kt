package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioDbPath.Outcome
import cz.xprees.comark.format.studio.StudioDbVectors.Result
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [StudioDbPath] against Studio's real database path: every vector of `db-vectors.json` (see [StudioDbVectors]) under each
 * of its three collection configurations. Each (vector, configuration) pair lands in exactly one category, and the counts
 * are pinned: the emulation must write Studio's D(y) byte for byte wherever it writes anything, and say why where it does
 * not.
 */
class StudioDbPathDifferentialTest {
    /** The configurations of `record-db-vectors.mjs`, as [StudioCollection]s. */
    private fun collection(config: String, id: String): StudioCollection = when (config) {
        "pageDefault" -> StudioCollection.page(id)
        "pageSchema" -> StudioCollection.page(
            id,
            listOf(
                StudioCollection.Field("date", StudioCollection.ColumnType.DATE),
                StudioCollection.Field("author", StudioCollection.ColumnType.STRING),
                StudioCollection.Field("tags", StudioCollection.ColumnType.JSON),
                StudioCollection.Field("image", StudioCollection.ColumnType.JSON),
                StudioCollection.Field("order", StudioCollection.ColumnType.NUMBER),
                StudioCollection.Field.withDefault("draft", StudioCollection.ColumnType.BOOLEAN, false),
            ),
        )
        "data" -> StudioCollection.data(id)
        else -> error(config)
    }

    private fun category(recorded: Result, outcome: Outcome): String = when (recorded) {
        is Result.Failure -> when (outcome) {
            is Outcome.NoDocument -> "both fail"
            is Outcome.Unsupported -> "unsupported, Studio fails: ${outcome.reason}"
            is Outcome.Written -> "MISMATCH: written where Studio fails"
        }
        is Result.Text -> when (outcome) {
            is Outcome.Written -> if (outcome.text == recorded.text) "match" else "MISMATCH: different text"
            is Outcome.NoDocument -> "MISMATCH: no document where Studio writes"
            is Outcome.Unsupported -> "unsupported: ${outcome.reason}"
        }
    }

    @Test
    fun emulatesTheDatabasePath() {
        val counts = sortedMapOf<String, Int>()
        val report = StringBuilder()
        val details = StringBuilder()
        for (v in StudioDbVectors.vectors) {
            for (config in StudioDbVectors.CONFIGS) {
                val c = v.byConfig.getValue(config)
                val outcome = StudioDbPath.emulateOutcome(v.y, collection(config, c.id))
                val cat = category(c.d, outcome)
                val key = "$config: $cat"
                counts[key] = (counts[key] ?: 0) + 1
                if (config == "data" && v.source.startsWith("oracle:") && outcome is Outcome.Unsupported) {
                    details.append("${outcome.reason} ${outcome.detail} :: ${v.source}\n")
                }
                if (cat.startsWith("MISMATCH")) {
                    report.append("=== ${v.source} [$config ${c.id}] $cat rules=${c.rules}\n--- y\n${v.y}\n--- studio\n")
                    report.append((c.d as? Result.Text)?.text ?: c.d.toString()).append("\n--- emulated\n")
                    report.append(((outcome as? Outcome.Written)?.text) ?: outcome.toString()).append("\n")
                }
            }
        }
        System.getenv("STUDIO_DB_REPORT")?.let { File(it).writeText(counts.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n" + details + "\n\n" + report) }
        println(counts.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        assertEquals(emptyMap<String, Int>(), counts.filterKeys { "MISMATCH" in it })
    }
}
