package cz.xprees.comark.format

import cz.xprees.comark.testutil.withDialect
import cz.xprees.comark.settings.ComarkSettings
import com.google.gson.JsonParser
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.format.FormatterExpressions.isComponentEnd
import cz.xprees.comark.format.FormatterExpressions.isComponentStart
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownListItem
import java.io.File

/**
 * What Reformat Code does to a Comark document - [ComarkFormattingService.computeEdits] with the [DocumentStructure] from the
 * PSI, i.e. [ComarkDocumentFormatter] - compared with monarch-mdc on the recorded differential corpus and monarch-mdc's own test
 * suite (see `ComarkFormatterDifferentialTest`, which checks the plain port on all of them).
 *
 * The documented deviations of [ComarkDocumentFormatter] make the output differ on purpose. Inputs that hit one are identified
 * by [deviations], from the input and its PSI alone, and skipped; every other input must come out byte-identical, and the number
 * of skipped inputs per deviation is asserted, so the deviations cannot silently grow.
 */
class ComarkFormattingPipelineTest : BasePlatformTestCase() {
    private val testData = File("src/test/testData/formatter")

    private enum class Deviation { LIST_ITEM_COMPONENT, INDENTED_CODE_BLOCK, NON_SPACE_FENCE_INDENT, HARD_LINE_BREAK }

    private class Case(val name: String, val tabSize: Int, val input: String, val expected: String?)

    private fun parse(text: String): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("case.mdc", ComarkLanguage, text).also { assertInstanceOf(it, ComarkFile::class.java) }

    /** The deviations [file] (with text [text]) hits. */
    private fun deviations(file: PsiFile, text: String): Set<Deviation> {
        val result = HashSet<Deviation>()
        val lines = text.split('\n')
        if (PsiTreeUtil.findChildrenOfType(file, ComarkBlockComponent::class.java).any { it.parent is MarkdownListItem && it.closeMarker != null }) {
            result += Deviation.LIST_ITEM_COMPONENT
        }
        PsiTreeUtil.processElements(file) { element ->
            when (element.elementType) {
                MarkdownElementTypes.CODE_BLOCK -> result += Deviation.INDENTED_CODE_BLOCK
                MarkdownElementTypes.CODE_FENCE -> {
                    val first = text.substring(0, element.textRange.startOffset).count { it == '\n' }
                    val end = element.node.findChildByType(MarkdownTokenTypes.CODE_FENCE_END)
                    val last = if (end != null) text.substring(0, end.startOffset).count { it == '\n' } - 1 else first + element.text.count { it == '\n' }
                    val fenceIndent = lines[first].takeWhile(JsText::isWhitespace).length
                    for (l in first + 1..minOf(last, lines.lastIndex)) {
                        val indent = lines[l].takeWhile(JsText::isWhitespace)
                        if (JsText.trim(lines[l]).isNotEmpty() && indent.drop(fenceIndent).any { it != ' ' }) result += Deviation.NON_SPACE_FENCE_INDENT
                    }
                }
            }
            true
        }
        for (i in 0 until lines.lastIndex) {
            val content = JsText.trim(lines[i])
            val trailing = lines[i].substring(JsText.leadingWhitespace(lines[i]) + content.length)
            val next = lines[i + 1]
            if (content.isNotEmpty() && trailing.length >= 2 && trailing.all { it == ' ' } && JsText.trim(next).isNotEmpty() &&
                !isComponentStart(next) && !isComponentEnd(next)
            ) {
                result += Deviation.HARD_LINE_BREAK
            }
        }
        return result
    }

    private fun reformat(file: PsiFile, text: String, tabSize: Int): String {
        val edits = ComarkFormattingService.computeEdits(text, listOf(TextRange(0, text.length)), tabSize, structure = ComarkDocumentStructure.of(file))
        val sb = StringBuilder(text)
        for (edit in edits.asReversed()) sb.replace(edit.start, edit.end, edit.text)
        return sb.toString()
    }

    /**
     * What [compare] skipped: the number of cases per deviation (a case can count for several), the number of distinct cases,
     * and how many of those actually come out different from monarch-mdc's result.
     */
    private data class Skipped(val perDeviation: Map<Deviation, Int>, val cases: Int, val different: Int)

    /** Compares all [cases], failing on any difference outside the deviations. */
    private fun compare(cases: List<Case>): Skipped {
        val perDeviation = HashMap<Deviation, Int>()
        var skipped = 0
        var different = 0
        val failures = ArrayList<String>()
        for (case in cases) {
            val file = parse(case.input)
            val hit = deviations(file, case.input)
            val actual = try {
                reformat(file, case.input, case.tabSize)
            } catch (e: ComarkFormatterException) {
                if (hit.isEmpty() && case.expected != null) failures += "${case.name}: threw $e"
                null
            }
            if (hit.isNotEmpty()) {
                for (d in hit) perDeviation.merge(d, 1, Int::plus)
                skipped++
                if (actual != case.expected) different++
                continue
            }
            if (case.expected == null) {
                if (actual != null) failures += "${case.name}: monarch-mdc throws, got output"
            } else if (actual != null && actual != case.expected) {
                failures += "${case.name}: expected ${case.expected.replace("\n", "\\n").take(300)}\n    but was ${actual.replace("\n", "\\n").take(300)}"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} cases differ from monarch-mdc:\n" + failures.take(10).joinToString("\n"))
        val result = Skipped(perDeviation, skipped, different)
        println("Compared ${cases.size} cases, skipped for deviations: $result")
        return result
    }

    fun testRecordedDifferentialCases() {
        val cases = JsonParser.parseString(File(testData, "differential/cases.json").readText()).asJsonArray
            .map { it.asJsonObject }
            // Reformat Code never formats on type, and editor documents never contain \r
            .filter { !it["formatOnType"].asBoolean && '\r' !in it["input"].asString }
            .map { Case(it["name"].asString, it["tabSize"].asInt, it["input"].asString, it["expected"]?.asString) }
        assertEquals(170, cases.size)
        assertEquals(EXPECTED_RECORDED_SKIPS, compare(cases))
        // the comark dialect reads two more cases' indented nested components as code blocks
        assertEquals(EXPECTED_RECORDED_SKIPS_COMARK, withDialect(ComarkSettings.Dialect.COMARK) { compare(cases) })
    }

    fun testUpstreamTestSuite() {
        val cases = File(testData, "upstream/input").listFiles()!!.filter { it.isFile }.sortedBy { it.name }.map { input ->
            Case(input.name, 2, input.readText(), File(testData, "upstream/output/${input.name}").readText())
        }
        assertEquals(35, cases.size)
        assertEquals(EXPECTED_UPSTREAM_SKIPS, compare(cases))
    }

    /** `COMARK_FORMATTER_DIFF_DIR`, as for `ComarkFormatterDifferentialTest.externalCorpus`: reports the skipped counts. */
    fun testExternalCorpus() {
        val dir = System.getenv("COMARK_FORMATTER_DIFF_DIR")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val cases = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.sortedBy { it.name }.flatMap { file ->
            JsonParser.parseString(file.readText()).asJsonArray.map { it.asJsonObject }
                .filter { !it["formatOnType"].asBoolean && '\r' !in it["input"].asString }
                .map { Case(it["name"].asString, it["tabSize"].asInt, it["input"].asString, it["expected"]?.asString) }
        }
        compare(cases)
    }

    private companion object {
        /**
         * Nuxt Content dialect (the default): 28 of the 170 cases hit a deviation; 23 of them come out different (the others happen
         * to be unaffected). Every other case is byte-identical to monarch-mdc.
         */
        val EXPECTED_RECORDED_SKIPS = Skipped(
            mapOf(
                Deviation.LIST_ITEM_COMPONENT to 1,
                Deviation.INDENTED_CODE_BLOCK to 22,
                Deviation.NON_SPACE_FENCE_INDENT to 6,
                Deviation.HARD_LINE_BREAK to 2,
            ),
            cases = 28,
            different = 23,
        )

        /** Comark dialect: 30 of the 170 cases hit a deviation; 25 of them come out different. */
        val EXPECTED_RECORDED_SKIPS_COMARK = Skipped(
            mapOf(
                Deviation.LIST_ITEM_COMPONENT to 1,
                Deviation.INDENTED_CODE_BLOCK to 24,
                Deviation.NON_SPACE_FENCE_INDENT to 6,
                Deviation.HARD_LINE_BREAK to 2,
            ),
            cases = 30,
            different = 25,
        )

        /** One upstream test has a component inside a list item, which monarch-mdc happens to leave where it is. */
        val EXPECTED_UPSTREAM_SKIPS = Skipped(mapOf(Deviation.LIST_ITEM_COMPONENT to 1), cases = 1, different = 0)
    }
}
