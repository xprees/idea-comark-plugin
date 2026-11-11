package cz.xprees.comark.format.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [StudioYamlLoader], js-yaml 5.5.0 `loadAll` with `JSON_SCHEMA` as comark loads front matter and props. The differential
 * tests cover it through thousands of documents; these pin the schema's typing rules and errors directly.
 */
class StudioYamlLoaderTest {
    private fun load(s: String): Any? = StudioYamlLoader.parseYaml(s)

    @Test
    fun typesOnlyJsonSchemaScalars() {
        val m = load(
            "a: 1\nb: -0\nc: 1.5e3\nd: 0x1F\ne: ~\nf: null\ng: true\nh: yes\ni: 2024-01-15\nj:\nk: '1'\nl: 1.\nm: +1\nn: .5\no: True\n",
        ) as Map<*, *>
        assertEquals(1.0, m["a"])
        assertTrue((m["b"] as Double).let { it == 0.0 && 1 / it < 0 })
        assertEquals(1500.0, m["c"])
        assertEquals("0x1F", m["d"])
        assertEquals("~", m["e"])
        assertEquals(null, m["f"])
        assertEquals(true, m["g"])
        assertEquals("yes", m["h"])
        assertEquals("2024-01-15", m["i"])
        assertEquals("", m["j"])
        assertEquals("1", m["k"])
        assertEquals(1.0, m["l"])
        assertEquals("+1", m["m"])
        assertEquals(".5", m["n"])
        assertEquals("True", m["o"])
    }

    @Test
    fun aliasesShareTheAnchoredInstance() {
        val m = load("a: &x {k: [1, 2]}\nb: *x\n") as Map<*, *>
        assertSame(m["a"], m["b"])
        assertEquals(mapOf("k" to listOf(1.0, 2.0)), m["a"])
    }

    @Test
    fun explicitTagsAndKeys() {
        val m = load("s: !!str 12\nn: !!int \"7\"\n1: one\nnull: z\n") as Map<*, *>
        assertEquals("12", m["s"])
        assertEquals(7.0, m["n"])
        assertEquals("one", m["1"])
        assertEquals("z", m["null"])
    }

    @Test
    fun emptyStreamsAndOtherRoots() {
        assertSame(JsUndefined, load(""))
        assertSame(JsUndefined, load("# only a comment\n"))
        assertEquals("", load("\n\t\t\t---\n"))
        assertEquals("plain", load("plain"))
        assertEquals(listOf("a", "b"), load("- a\n- b\n"))
    }

    @Test
    fun rejectsWhatJsYamlRejects() {
        for ((yaml, message) in listOf(
            "a: 1\na: 2\n" to "duplicated mapping key (2:1)",
            "a:\n\t- x\n" to "tab characters must not be used in indentation",
            "a: b: c\n" to "bad indentation of a mapping entry",
            "a: [\n" to "deficient indentation (2:1)",
            "a: [1, 2\n" to "deficient indentation (2:1)",
            "a: [1, 2" to "unexpected end of the stream within a flow collection",
            "a: 1\n---\nb: 2\n" to "expected a single document in the stream, but found more",
            "x: *nope\n" to "unidentified alias \"nope\"",
            "k: !!foo bar\n" to "unknown scalar tag !<tag:yaml.org,2002:foo>",
        )) {
            try {
                load(yaml)
                fail("expected an error for $yaml")
            } catch (e: StudioYamlException) {
                assertTrue("${e.message} should start with $message", e.message!!.startsWith(message))
            }
        }
    }
}
