package cz.xprees.comark.format.studio

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.File

/**
 * `src/test/testData/studio/db-vectors.json`, recorded by `src/test/node/studio-oracle/record-db-vectors.mjs` (Gradle:
 * `recordStudioDbVectors`): Nuxt Studio 1.8.0's database path D on documents that are already text-path output.
 *
 * For each distinct y = T(x) (x = an oracle input, a `text-vectors.json` input or a front matter probe, where T succeeds) it
 * holds T(y) and, per collection configuration ([CONFIGS]), D(y), T(D(y)) and D(D(y)) and the DB-PATH.md rules that explain
 * D(y) != y. See `src/test/node/studio-oracle/DB-PATH.md` for the rules and README.md for the format.
 */
internal object StudioDbVectors {
    /** The collection configurations, in recording order: page with Studio's defaults, page with a custom schema, data. */
    val CONFIGS = listOf("pageDefault", "pageSchema", "data")

    /** What D, T∘D and D∘D gave for one y under one configuration. A path that threw is [Failure]. */
    sealed interface Result {
        data class Text(val text: String) : Result
        data class Failure(val error: String) : Result
    }

    class ConfigResult(
        /** The content id the document was recorded under (its file name decides an injected title). */
        val id: String,
        /** D(y): Studio's database path output, or the reason Studio has no document. */
        val d: Result,
        /** T(D(y)), `null` when D threw. */
        val tOfD: Result?,
        /** D(D(y)), `null` when D threw. */
        val dOfD: Result?,
        /** D(y) == y */
        val dEqualsY: Boolean,
        /** The DB-PATH.md rule ids explaining D(y) != y; empty when D(y) == y. `["error"]` is not used: see [d]. */
        val rules: List<String>,
    ) {
        val dText: String? get() = (d as? Result.Text)?.text
    }

    class Vector(
        val y: String,
        /**
         * `oracle:<case name>`, `text-vectors:<index>`, `fm-probe:<front matter>.<body>`, `db-fuzz:<index>` or
         * `db-probe:<index>` (`db-reproducers.json`): the first input whose T is y.
         */
        val source: String,
        /** How many recorded inputs have this T(x). */
        val inputs: Int,
        /** T(y), the text path on its own output. */
        val tOfY: Result,
        val byConfig: Map<String, ConfigResult>,
    ) {
        val yStable: Boolean get() = tOfY == Result.Text(y)
    }

    /** The file: its header (versions, hashes, configurations, summary statistics) and the vectors. */
    class Corpus(val header: JsonObject, val vectors: List<Vector>) {
        val summary: JsonObject get() = header.getAsJsonObject("summary")
    }

    /** The configurations of `record-db-vectors.mjs`, as [StudioCollection]s. */
    fun collection(config: String, id: String): StudioCollection = when (config) {
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

    val corpus: Corpus by lazy { load(File("src/test/testData/studio/db-vectors.json")) }
    val vectors: List<Vector> get() = corpus.vectors

    private fun result(e: JsonElement?): Result? = when {
        e == null || e.isJsonNull -> null
        e.isJsonPrimitive -> Result.Text(e.asString)
        else -> Result.Failure(e.asJsonObject["error"].asString)
    }

    private fun load(f: File): Corpus {
        val reader = JsonReader(f.reader()).apply { setNestingLimit(10_000) }
        val root = reader.use { JsonParser.parseReader(it).asJsonObject }
        val vectors = root.getAsJsonArray("vectors").map { it.asJsonObject.toVector() }
        root.remove("vectors")
        return Corpus(root, vectors)
    }

    private fun JsonObject.toVector(): Vector {
        val y = get("y").asString
        val stable = get("yStable").asBoolean
        val tOfY = if (stable) Result.Text(y) else result(get("ty"))!!
        val configs = CONFIGS.associateWith { name ->
            val c = getAsJsonObject(name)
            val dRaw = result(c["d"])
            val failed = dRaw is Result.Failure
            val d = if (failed) dRaw!! else if (c["dEqualsY"].asBoolean) Result.Text(y) else dRaw!!
            val dText = (d as? Result.Text)?.text
            ConfigResult(
                id = c["id"].asString,
                d = d,
                tOfD = if (dText == null) null else if (c["tOfDEqualsD"].asBoolean) Result.Text(dText) else result(c["td"]),
                dOfD = if (dText == null) null else if (c["dOfDEqualsD"].asBoolean) Result.Text(dText) else result(c["dd"]),
                dEqualsY = !failed && c["dEqualsY"].asBoolean,
                rules = c.getAsJsonArray("rules")?.map { it.asString } ?: emptyList(),
            )
        }
        return Vector(y, get("source").asString, get("inputs")?.asInt ?: 1, tOfY, configs)
    }
}
