package cz.xprees.comark.format.studio

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [StudioYaml] against js-yaml 5.5.0 and comark 0.7.0, using the vectors `src/test/node/studio-oracle/record-vectors.mjs`
 * recorded into `src/test/testData/studio/yaml-vectors.json`: for each value, js-yaml's `dump(value, { indent: 2,
 * lineWidth: -1 })` and comark's `stringifyYaml(value)`.
 */
class StudioYamlTest {
    private val vectors by lazy {
        JsonParser.parseString(File("src/test/testData/studio/yaml-vectors.json").readText()).asJsonObject.getAsJsonArray("vectors")
            .map { it.asJsonObject }
    }

    @Test
    fun dumpsLikeJsYaml() {
        val failures = ArrayList<String>()
        for (v in vectors) {
            val value = StudioJson.value(v["value"])
            val dump = runCatching { StudioYaml.dump(value) }.getOrElse { "<<${it::class.java.simpleName}: ${it.message}>>" }
            val comark = runCatching { StudioYaml.stringify(value) }.getOrElse { "<<${it::class.java.simpleName}: ${it.message}>>" }
            if (dump != v["dump"].asString) failures += "dump ${v["value"]}\n  expected ${v["dump"]}\n  actual   ${quote(dump)}"
            if (comark != v["comark"].asString) failures += "comark ${v["value"]}\n  expected ${v["comark"]}\n  actual   ${quote(comark)}"
        }
        println("StudioYaml: ${vectors.size - failures.size} of ${vectors.size} vectors match")
        assertEquals(failures.take(40).joinToString("\n"), 0, failures.size)
        assertEquals("vector count", 1989, vectors.size)
    }

    @Test
    fun writesSharedObjectsAsAnchorsAndAliases() {
        val base = linkedMapOf<String, Any?>("a" to 1.0)
        val tags = listOf("x", "y")
        val data = linkedMapOf<String, Any?>(
            "base" to base,
            "derived" to linkedMapOf<String, Any?>("<<" to base, "b" to 2.0),
            "list" to listOf(tags, tags, emptyList<Any?>()),
        )
        assertEquals(
            "base: &ref_0\n  a: 1\nderived:\n  '<<': *ref_0\n  b: 2\nlist:\n  - &ref_1\n    - x\n    - 'y'\n  - *ref_1\n  - []\n",
            StudioYaml.dump(data),
        )
        // equal but distinct objects are not aliased
        assertEquals("a:\n  k: 1\nb:\n  k: 1\n", StudioYaml.dump(mapOf("a" to mapOf("k" to 1.0), "b" to mapOf("k" to 1.0))))
        // nor are Kotlin's emptyList()/emptyMap() singletons
        assertEquals("a: []\nb: []\nc: {}\nd: {}\n", StudioYaml.dump(mapOf("a" to emptyList<Any?>(), "b" to emptyList<Any?>(), "c" to emptyMap<String, Any?>(), "d" to emptyMap<String, Any?>())))
    }

    @Test
    fun ordersIntegerKeysFirstLikeJavaScript() {
        val map = linkedMapOf<String, Any?>("b" to 1.0, "10" to 2.0, "2" to 3.0, "01" to 4.0, "4294967295" to 5.0, "4294967294" to 6.0)
        assertEquals("'2': 3\n'10': 2\n'4294967294': 6\nb: 1\n'01': 4\n'4294967295': 5\n", StudioYaml.dump(map))
    }

    @Test
    fun formatsNumbersLikeJavaScript() {
        val cases = mapOf(
            0.0 to "0", -0.0 to "0", 1.0 to "1", 1.5 to "1.5", 1e21 to "1e+21", 1e20 to "100000000000000000000", 1e-7 to "1e-7",
            1e-6 to "0.000001", 0.1 + 0.2 to "0.30000000000000004", 5e-324 to "5e-324", 123.456e10 to "1234560000000",
            1.7976931348623157e308 to "1.7976931348623157e+308", -2.5e-8 to "-2.5e-8", Double.NaN to "NaN",
        )
        for ((d, s) in cases) assertEquals(s, StudioJs.numberToString(d))
    }

    private fun quote(s: String) = com.google.gson.JsonPrimitive(s).toString()
}
