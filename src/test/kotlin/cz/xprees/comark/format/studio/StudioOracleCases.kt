package cz.xprees.comark.format.studio

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import java.io.File

/** The Studio oracle's cases (`src/test/testData/studio/oracle.json`) and what the text-path differential tests share. */
internal object StudioOracleCases {
    val cases: List<JsonObject> by lazy { load("oracle.json", "cases") }

    /** `text-vectors.json`: seeded random documents built from the corpus (`record-text-vectors.mjs`). */
    val textVectors: List<JsonObject> by lazy { load("text-vectors.json", "vectors") }

    private fun load(file: String, key: String): List<JsonObject> {
        // the deepest vectors (markdown-it's nesting limit) go past Gson's default nesting limit of 255
        val reader = JsonReader(File("src/test/testData/studio/$file").reader()).apply { setNestingLimit(10_000) }
        return reader.use { JsonParser.parseReader(it).asJsonObject.getAsJsonArray(key).map { e -> e.asJsonObject } }
    }

    fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /**
     * How the port's text path compares with Studio's on one input. Every case lands in exactly one category; the tests
     * assert the exact count of each, so a regression moves a case out of [MATCH] and fails the test.
     */
    enum class Outcome(val description: String) {
        MATCH("same result as Studio (byte-identical tree or text)"),
        BOTH_FAIL("Studio's text path throws, and the port reports that (format returns null)"),
        MISSED_FAILURE("Studio throws, the port produces a document"),
        UNEXPECTED_FAILURE("the port reports a Studio failure, Studio produces a document"),
        UNSUPPORTED("the port declines (a Studio code path it does not reproduce)"),
        CRASH("the port throws something else"),
        TREE_MISMATCH("the trees differ"),
        TEXT_MISMATCH("the trees agree but the rendered text differs"),
    }

    class Verdict(val outcome: Outcome, val detail: String = "")

    /** A canonical rendering of a comark value or node, with object keys in JavaScript's own-key order. */
    fun canon(v: Any?): String = when (v) {
        null -> "null"
        JsUndefined -> "undefined"
        is String -> StudioJs.jsonStringify(v)!!
        is Boolean -> v.toString()
        is Double -> "#" + StudioJs.numberToString(v)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val m = v as Map<String, Any?>
            StudioJs.keys(m).joinToString(",", "{", "}") { StudioJs.jsonStringify(it) + ":" + canon(m[it]) }
        }
        is List<*> -> v.joinToString(",", "[", "]") { canon(it) }
        is StudioText -> StudioJs.jsonStringify(v.text)!!
        is StudioElement -> (listOf(StudioJs.jsonStringify(v.tag)!!, canon(v.attrs)) + v.children.map { canon(it) }).joinToString(",", "[", "]")
        is StudioComment -> "[null," + canon(v.attrs) + (if (v.text != null) "," + StudioJs.jsonStringify(v.text) else "") + "]"
        is JsDate -> "Date(${v.iso})"
        else -> v.toString()
    }

    /** Studio records `tree.frontmatter || {}`. */
    private fun recordedFrontmatter(doc: StudioDocument): Any? = if (StudioJs.truthy(doc.frontmatterValue)) doc.frontmatterValue else emptyMap<String, Any?>()

    /** Compares [StudioTreeBuilder.build] of [input] with Studio's recorded tree (`null` when Studio threw). */
    fun compareTree(input: String, tree: JsonElement?, frontmatter: JsonElement?): Verdict {
        val actual = try {
            StudioTreeBuilder.build(input)
        } catch (e: StudioTextPathException) {
            return if (tree == null || !tree.isJsonObject) Verdict(Outcome.BOTH_FAIL) else Verdict(Outcome.UNEXPECTED_FAILURE, e.message ?: "")
        } catch (e: StudioUnsupportedException) {
            return Verdict(Outcome.UNSUPPORTED, e.message ?: "")
        } catch (e: Exception) {
            return Verdict(Outcome.CRASH, "$e at ${e.stackTrace.take(3).joinToString(" < ")}")
        }
        if (tree == null || !tree.isJsonObject) return Verdict(Outcome.MISSED_FAILURE)
        val expectedNodes = StudioJson.document(tree.asJsonObject, null).nodes
        val expectedFrontmatter = frontmatter?.let { StudioJson.value(it) } ?: emptyMap<String, Any?>()
        val e = canon(expectedNodes) + "\n" + canon(expectedFrontmatter)
        val a = canon(actual.nodes) + "\n" + canon(recordedFrontmatter(actual))
        return if (e == a) Verdict(Outcome.MATCH) else Verdict(Outcome.TREE_MISMATCH, firstDifference(e, a))
    }

    /** Compares [StudioFormatter.format] of [input] with Studio's recorded output (a string, or `{"error": ...}`). */
    fun compareText(input: String, text: JsonElement?): Verdict {
        val expected = text?.takeIf { it.isJsonPrimitive }?.asString
        val actual = try {
            StudioPipeline.render(StudioTreeBuilder.build(input))
        } catch (e: StudioTextPathException) {
            return if (expected == null) Verdict(Outcome.BOTH_FAIL) else Verdict(Outcome.UNEXPECTED_FAILURE, e.message ?: "")
        } catch (e: StudioUnsupportedException) {
            return Verdict(Outcome.UNSUPPORTED, e.message ?: "")
        } catch (e: Exception) {
            return Verdict(Outcome.CRASH, "$e at ${e.stackTrace.take(3).joinToString(" < ")}")
        }
        check(StudioFormatter.format(input) == actual)
        if (expected == null) return Verdict(Outcome.MISSED_FAILURE, text.toString())
        return if (expected == actual) Verdict(Outcome.MATCH) else Verdict(Outcome.TEXT_MISMATCH, firstDifference(expected, actual))
    }

    /** Prints the tally and every non-matching case, then asserts the exact count of every category. */
    fun assertTally(label: String, verdicts: List<Pair<String, Verdict>>, expected: Map<Outcome, Int>) {
        val tally = Outcome.values().associateWith { o -> verdicts.count { it.second.outcome == o } }
        println("$label: " + tally.filterValues { it > 0 }.entries.joinToString(", ") { "${it.key} ${it.value}" } + " (of ${verdicts.size})")
        for ((name, v) in verdicts) if (v.outcome != Outcome.MATCH && v.outcome != Outcome.BOTH_FAIL) println("  ${v.outcome} $name ${v.detail}")
        val want = Outcome.values().associateWith { expected[it] ?: 0 }
        assertEquals("$label outcome counts (${Outcome.values().joinToString { "${it.name}: ${it.description}" }})", want, tally)
    }

    fun firstDifference(expected: String, actual: String, context: Int = 70): String {
        var i = 0
        while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
        val from = maxOf(0, i - context)
        fun window(s: String) = s.substring(minOf(from, s.length), minOf(s.length, i + context)).replace("\n", "\\n")
        return "@ $i\n    expected: ${window(expected)}\n    actual:   ${window(actual)}"
    }
}
