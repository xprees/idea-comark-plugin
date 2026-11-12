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
    private fun collection(config: String, id: String): StudioCollection = StudioDbVectors.collection(config, id)

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
        // coverage on the documents the IDE produces (T-stable y), data configuration: (written or no document, declined)
        val coverage = sortedMapOf<String, IntArray>()
        for (v in StudioDbVectors.vectors) {
            for (config in StudioDbVectors.CONFIGS) {
                val c = v.byConfig.getValue(config)
                val outcome = StudioDbPath.emulateOutcome(v.y, collection(config, c.id))
                val cat = category(c.d, outcome)
                val key = "$config: $cat"
                counts[key] = (counts[key] ?: 0) + 1
                if (config == "data" && v.yStable) {
                    val kind = v.source.substringBefore(':')
                    val cell = coverage.getOrPut(kind) { IntArray(2) }
                    if (outcome is Outcome.Unsupported) cell[1]++ else cell[0]++
                }
                if (cat.startsWith("MISMATCH")) {
                    report.append("=== ${v.source} [$config ${c.id}] $cat rules=${c.rules}\n--- y\n${v.y}\n--- studio\n")
                    report.append((c.d as? Result.Text)?.text ?: c.d.toString()).append("\n--- emulated\n")
                    report.append(((outcome as? Outcome.Written)?.text) ?: outcome.toString()).append("\n")
                }
            }
        }
        System.getenv("STUDIO_DB_REPORT")?.let { File(it).writeText(counts.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n" + report) }
        println(counts.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        println("T-stable y (data): " + coverage.entries.joinToString { "${it.key} ${it.value[0]} emulated, ${it.value[1]} declined" })
        assertEquals(emptyMap<String, Int>(), counts.filterKeys { "MISMATCH" in it })
        assertEquals(EXPECTED.toSortedMap(), counts)
    }

    private companion object {
        /**
         * Every (vector, configuration) pair by category, pinned. `match`: the emulation writes Studio's D(y). `both fail`:
         * Studio has no document and the emulation says so. `unsupported[, Studio fails]: REASON`: the emulation declines
         * (most of these y are not T-normal or contain constructs the IDE keeps verbatim). A `MISMATCH` category must never
         * appear; re-recording db-vectors.json changes these counts.
         */
        val EXPECTED = mapOf(
            "data: match" to 2108,
            "data: unsupported, Studio fails: BRACKET_SPAN" to 2,
            "data: unsupported, Studio fails: INLINE_SYNTAX" to 20,
            "data: unsupported, Studio fails: NOT_T_NORMAL" to 7,
            "data: unsupported, Studio fails: RAW_HTML" to 1,
            "data: unsupported: ATTRIBUTE_NAME" to 45,
            "data: unsupported: ATTRIBUTE_VALUE" to 5,
            "data: unsupported: BLOCK_ATTRIBUTES" to 125,
            "data: unsupported: BLOCK_SYNTAX" to 167,
            "data: unsupported: BRACKET_SPAN" to 99,
            "data: unsupported: CODE_BLOCK" to 130,
            "data: unsupported: COMPONENT_HR" to 117,
            "data: unsupported: COMPONENT_NAME" to 43,
            "data: unsupported: HTML_COMMENT" to 23,
            "data: unsupported: INLINE_SYNTAX" to 915,
            "data: unsupported: LIST" to 35,
            "data: unsupported: NON_MAPPING_FRONT_MATTER" to 11,
            "data: unsupported: NOT_T_NORMAL" to 686,
            "data: unsupported: RAW_HTML" to 178,
            "data: unsupported: SLOT" to 20,
            "data: unsupported: TEXT_PATH_FAILS" to 94,
            "data: unsupported: URL" to 8,
            "pageDefault: match" to 2108,
            "pageDefault: unsupported, Studio fails: BRACKET_SPAN" to 2,
            "pageDefault: unsupported, Studio fails: INLINE_SYNTAX" to 20,
            "pageDefault: unsupported, Studio fails: NOT_T_NORMAL" to 7,
            "pageDefault: unsupported, Studio fails: RAW_HTML" to 1,
            "pageDefault: unsupported: ATTRIBUTE_NAME" to 45,
            "pageDefault: unsupported: ATTRIBUTE_VALUE" to 5,
            "pageDefault: unsupported: BLOCK_ATTRIBUTES" to 125,
            "pageDefault: unsupported: BLOCK_SYNTAX" to 167,
            "pageDefault: unsupported: BRACKET_SPAN" to 99,
            "pageDefault: unsupported: CODE_BLOCK" to 130,
            "pageDefault: unsupported: COMPONENT_HR" to 117,
            "pageDefault: unsupported: COMPONENT_NAME" to 43,
            "pageDefault: unsupported: HTML_COMMENT" to 23,
            "pageDefault: unsupported: INLINE_SYNTAX" to 915,
            "pageDefault: unsupported: LIST" to 35,
            "pageDefault: unsupported: NON_MAPPING_FRONT_MATTER" to 11,
            "pageDefault: unsupported: NOT_T_NORMAL" to 686,
            "pageDefault: unsupported: RAW_HTML" to 178,
            "pageDefault: unsupported: SLOT" to 20,
            "pageDefault: unsupported: TEXT_PATH_FAILS" to 94,
            "pageDefault: unsupported: URL" to 8,
            "pageSchema: both fail" to 8,
            "pageSchema: match" to 2092,
            "pageSchema: unsupported, Studio fails: BLOCK_SYNTAX" to 1,
            "pageSchema: unsupported, Studio fails: BRACKET_SPAN" to 2,
            "pageSchema: unsupported, Studio fails: FRONT_MATTER_VALUE" to 4,
            "pageSchema: unsupported, Studio fails: INLINE_SYNTAX" to 22,
            "pageSchema: unsupported, Studio fails: NOT_T_NORMAL" to 7,
            "pageSchema: unsupported, Studio fails: RAW_HTML" to 1,
            "pageSchema: unsupported: ATTRIBUTE_NAME" to 45,
            "pageSchema: unsupported: ATTRIBUTE_VALUE" to 5,
            "pageSchema: unsupported: BLOCK_ATTRIBUTES" to 125,
            "pageSchema: unsupported: BLOCK_SYNTAX" to 166,
            "pageSchema: unsupported: BRACKET_SPAN" to 99,
            "pageSchema: unsupported: CODE_BLOCK" to 130,
            "pageSchema: unsupported: COMPONENT_HR" to 117,
            "pageSchema: unsupported: COMPONENT_NAME" to 43,
            "pageSchema: unsupported: FRONT_MATTER_VALUE" to 4,
            "pageSchema: unsupported: HTML_COMMENT" to 23,
            "pageSchema: unsupported: INLINE_SYNTAX" to 913,
            "pageSchema: unsupported: LIST" to 35,
            "pageSchema: unsupported: NON_MAPPING_FRONT_MATTER" to 11,
            "pageSchema: unsupported: NOT_T_NORMAL" to 686,
            "pageSchema: unsupported: RAW_HTML" to 178,
            "pageSchema: unsupported: SLOT" to 20,
            "pageSchema: unsupported: TEXT_PATH_FAILS" to 94,
            "pageSchema: unsupported: URL" to 8,
        )
    }
}
