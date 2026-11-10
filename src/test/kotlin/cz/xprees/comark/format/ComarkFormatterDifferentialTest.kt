package cz.xprees.comark.format

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Checks that [ComarkFormatter] produces byte-identical output to the JavaScript formatter it is ported from
 * (`@nuxtlabs/monarch-mdc` 0.10.0).
 *
 * - `formatter/upstream`: monarch-mdc's own test suite (`tests/content/{input,output}`, MIT, see the LICENSE there), tabSize 2.
 * - `formatter/differential/cases.json`: input/expected pairs recorded by running the JavaScript formatter under Node. They are
 *   a subset of a much larger generated corpus (upstream inputs, every Markdown file of the Comark repository and the VS Code
 *   extension, mutations of them and thousands of random MDC documents, with tab sizes 0-8 and both format-on-type modes),
 *   chosen to cover every block of the JavaScript formatter plus a stratified random sample. A case with `error` is one where
 *   the JavaScript formatter throws; the port must throw [ComarkFormatterException] there.
 *
 * Setting the environment variable `COMARK_FORMATTER_DIFF_DIR` to a directory of such JSON files (e.g. the full corpus) checks
 * all of them too (run with `--rerun`, Gradle does not see the variable as an input).
 */
class ComarkFormatterDifferentialTest {
    private val testData = File("src/test/testData/formatter")

    private class Case(val name: String, val tabSize: Int, val formatOnType: Boolean, val input: String, val expected: String?, val error: String?)

    private fun readCases(file: File): List<Case> =
        JsonParser.parseString(file.readText()).asJsonArray.map { element ->
            val o = element.asJsonObject
            Case(
                name = o["name"].asString,
                tabSize = o["tabSize"].asInt,
                formatOnType = o["formatOnType"].asBoolean,
                input = o["input"].asString,
                expected = o["expected"]?.asString,
                error = o["error"]?.asString,
            )
        }

    /** Runs every case and returns the names (with a hint) of those that do not match. */
    private fun mismatches(cases: List<Case>): List<String> = cases.mapNotNull { case ->
        val actual = try {
            ComarkFormatter.format(case.input, case.tabSize, case.formatOnType)
        } catch (e: ComarkFormatterException) {
            return@mapNotNull if (case.error != null) null else "${case.name}: threw $e"
        }
        when {
            case.error != null -> "${case.name}: expected ${case.error}, got output"
            actual != case.expected -> "${case.name}: expected ${quote(case.expected!!)}\n    but was ${quote(actual)}"
            else -> null
        }
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s.take(400)) {
            when {
                c == '\n' -> append("\\n")
                c == '"' -> append("\\\"")
                c.code < 0x20 || c.code > 0x7e -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        if (s.length > 400) append("...")
        append('"')
    }

    private fun assertNoMismatches(cases: List<Case>) {
        val failed = mismatches(cases)
        if (failed.isNotEmpty()) fail("${failed.size} of ${cases.size} cases differ from monarch-mdc:\n" + failed.take(10).joinToString("\n"))
    }

    @Test
    fun upstreamTestSuite() {
        val inputs = File(testData, "upstream/input").listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        assertEquals(35, inputs.size)
        for (input in inputs) {
            val expected = File(testData, "upstream/output/${input.name}").readText()
            assertEquals(input.name, expected, ComarkFormatter.format(input.readText(), tabSize = 2))
        }
    }

    @Test
    fun recordedDifferentialCases() {
        val cases = readCases(File(testData, "differential/cases.json"))
        assertTrue("expected a few hundred recorded cases, got ${cases.size}", cases.size >= 200)
        assertTrue("the recorded cases include JavaScript failures", cases.any { it.error != null })
        assertTrue("the recorded cases include format-on-type", cases.any { it.formatOnType })
        assertNoMismatches(cases)
    }

    @Test
    fun externalCorpus() {
        val dir = System.getenv("COMARK_FORMATTER_DIFF_DIR")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.sortedBy { it.name }
        val cases = files.flatMap(::readCases)
        val failed = mismatches(cases)
        println("Differential corpus $dir: compared ${cases.size} cases (${cases.count { it.error != null }} JS errors), ${failed.size} mismatches")
        if (failed.isNotEmpty()) fail("${failed.size} of ${cases.size} cases differ from monarch-mdc:\n" + failed.take(20).joinToString("\n"))
    }
}
