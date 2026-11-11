package cz.xprees.comark.format.studio

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sanity checks on the Nuxt Studio oracle (`src/test/testData/studio/oracle.json`), recorded by
 * `src/test/node/studio-oracle/record.mjs` (Gradle: `recordStudioOracle`) from the real nuxt-studio 1.8.0 save pipeline.
 *
 * Every case holds the input, Studio's database-path output `db` = D(x), its text-path output `text` = T(x), `textOfDb` =
 * T(D(x)), whether each path is stable on its own output, and the comark tree and front matter the renderer receives on the
 * text path. A path that throws is recorded as `{"error": "..."}`. This test only checks that the file is well-formed and was
 * recorded from the expected Studio build, and prints the summary counts; the formatter tests compare against its contents.
 */
class StudioOracleCorpusTest {
    private val oracleFile = File("src/test/testData/studio/oracle.json")

    private val requiredStudioFiles = listOf("generate.js", "legacy.js", "schema.js", "utils.js", "tree.js")
        .map { "nuxt-studio/dist/module/runtime/utils/document/$it" }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** A recorded pipeline output: the markdown text, or `{"error": message}` when Studio threw. */
    private fun isOutput(e: JsonElement?): Boolean = e != null && (
        (e.isJsonPrimitive && e.asJsonPrimitive.isString) ||
            (e.isJsonObject && e.asJsonObject.keySet() == setOf("error") && e.asJsonObject.str("error") != null)
        )

    private fun JsonElement?.isText() = this != null && isJsonPrimitive && asJsonPrimitive.isString

    @Test
    fun oracleIsWellFormed() {
        assertTrue("missing $oracleFile, run ./gradlew recordStudioOracle", oracleFile.isFile)
        val root = JsonParser.parseString(oracleFile.readText()).asJsonObject

        val versions = root.getAsJsonObject("versions")
        assertEquals("1.8.0", versions.str("nuxt-studio"))
        assertEquals("0.7.0", versions.str("comark"))
        assertEquals("5.5.0", versions.str("js-yaml"))

        val hashes = root.getAsJsonObject("studioFiles")
        for (file in requiredStudioFiles) {
            val hash = hashes.str(file)
            assertTrue("no sha256 for $file", hash != null && Regex("[0-9a-f]{64}").matches(hash))
        }

        val cases = root.getAsJsonArray("cases").map { it.asJsonObject }
        val names = cases.map { it.str("name")!! }
        assertEquals("case names must be unique", names.size, names.toSet().size)
        assertTrue("hand-written corpus must have at least 60 documents", cases.count { it.str("source") == "handwritten" } >= 60)

        for (case in cases) {
            val name = case.str("name")
            assertTrue("$name: input", case.str("input") != null)
            assertTrue("$name: collectionType", case.str("collectionType") in setOf("page", "data"))
            assertTrue("$name: db", isOutput(case["db"]))
            assertTrue("$name: text", isOutput(case["text"]))
            assertTrue("$name: dbStable", case["dbStable"].asJsonPrimitive.isBoolean)
            assertTrue("$name: textStable", case["textStable"].asJsonPrimitive.isBoolean)
            if (case["db"].isText()) {
                assertTrue("$name: textOfDb", isOutput(case["textOfDb"]))
                assertTrue("$name: db ends with LF", case.str("db")!!.endsWith("\n"))
            }
            if (case["text"].isText()) {
                assertTrue("$name: comarkTree.nodes", case["comarkTree"].asJsonObject["nodes"].isJsonArray)
                assertTrue("$name: frontmatter", case["frontmatter"].isJsonObject)
                assertTrue("$name: renderedFromTree", isOutput(case["renderedFromTree"]))
            }
        }

        val summary = root.getAsJsonObject("summary")
        fun count(predicate: (JsonObject) -> Boolean) = cases.count(predicate)
        val recount = mapOf(
            "cases" to cases.size,
            "dbStable" to count { it["dbStable"].asBoolean },
            "textStable" to count { it["textStable"].asBoolean },
            "dbErrors" to count { !it["db"].isText() },
            "textErrors" to count { !it["text"].isText() },
            "dbUnchanged" to count { it.str("db") == it.str("input") },
            "textUnchanged" to count { it.str("text") == it.str("input") },
            "textOfDbEqualsDb" to count { it["db"].isText() && it.str("textOfDb") == it.str("db") },
            "renderedFromTreeEqualsText" to count { it["text"].isText() && it.str("renderedFromTree") == it.str("text") },
        )
        for ((key, value) in recount) assertEquals("summary.$key", value, summary[key].asInt)

        println(
            "Studio oracle: ${recount["cases"]} cases (" +
                summary.getAsJsonObject("bySource").entrySet().joinToString { "${it.key} ${it.value.asInt}" } + "), " +
                "dbStable ${recount["dbStable"]}, textStable ${recount["textStable"]}, " +
                "T(D(x))==D(x) ${recount["textOfDbEqualsDb"]}, unchanged db/text ${recount["dbUnchanged"]}/${recount["textUnchanged"]}, " +
                "errors db/text ${recount["dbErrors"]}/${recount["textErrors"]}, " +
                "tree renders to text ${recount["renderedFromTreeEqualsText"]}",
        )
    }
}
