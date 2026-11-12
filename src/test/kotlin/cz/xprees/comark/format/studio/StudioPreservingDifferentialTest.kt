package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioOracleCases.cases
import cz.xprees.comark.format.studio.StudioOracleCases.firstDifference
import cz.xprees.comark.format.studio.StudioOracleCases.text
import cz.xprees.comark.format.studio.StudioOracleCases.textVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StudioFormatter.formatPreserving] and [StudioSafety] on the oracle corpus and the random documents of
 * `text-vectors.json`: block-wise rendering is Studio's rendering, unclassified documents come out exactly as Studio writes
 * them, kept blocks are the source, and the results are fixed points. The counts are exact.
 */
class StudioPreservingDifferentialTest {
    private class Doc(val name: String, val input: String, val studio: String?, val studioStable: Boolean?)

    private val corpus by lazy {
        cases.map { Doc(it["name"].asString, it["input"].asString, it.text("text"), it["textStable"]?.asBoolean) }
    }
    private val vectors by lazy { textVectors.mapIndexed { i, v -> Doc("vector #$i", v["input"].asString, v.text("text"), null) } }

    private fun report(label: String, failures: List<String>) {
        println("$label: ${failures.size}")
        failures.take(20).forEach { println("  $it") }
    }

    /** Rendering every block on its own and joining them as formatPreserving does gives exactly Studio's text. */
    @Test
    fun blockwiseRenderingIsStudiosRendering() {
        for ((label, docs) in listOf("corpus" to corpus, "random documents" to vectors)) {
            val succeeded = docs.filter { it.studio != null }
            val failures = succeeded.mapNotNull { d ->
                val blockwise = StudioFormatter.formatBlockwise(d.input)
                if (blockwise == d.studio) null else "${d.name}\n" + firstDifference(d.studio!!, blockwise ?: "<null>")
            }
            report("block-wise rendering differs from Studio ($label, of ${succeeded.size})", failures)
            assertEquals(label, emptyList<String>(), failures)
        }
        assertEquals(446, corpus.count { it.studio != null })
        assertEquals(4400, vectors.count { it.studio != null })
    }

    /** With no classified block, formatPreserving is Studio's text path exactly. */
    @Test
    fun unclassifiedDocumentsComeOutAsStudioWritesThem() {
        val expected = mapOf("corpus" to Triple(480, 308, 172), "random documents" to Triple(5021, 2443, 2577))
        for ((label, docs) in listOf("corpus" to corpus, "random documents" to vectors)) {
            val results = docs.map { it to StudioFormatter.formatPreserving(it.input) }
            val unclassified = results.filter { (_, r) -> r != null && r.kept.isEmpty() }
            val failures = unclassified.mapNotNull { (d, r) ->
                if (r!!.text == d.studio) null else "${d.name}\n" + firstDifference(d.studio ?: "<null>", r.text)
            }
            report("unclassified but different from Studio ($label)", failures)
            assertEquals(label, emptyList<String>(), failures)
            val classified = results.count { (_, r) -> r != null && r.kept.isNotEmpty() }
            println("$label: ${docs.size} documents, ${unclassified.size} with no kept block, $classified with kept blocks, ${results.count { it.second == null }} not parsed")
            assertEquals(label, expected.getValue(label), Triple(docs.size, unclassified.size, classified))
        }
    }

    /**
     * Where blocks are kept, each kept block is its source lines (cut independently here), every other block is Studio's
     * output for it, and the result holds them in document order.
     */
    @Test
    fun keptBlocksAreTheSourceAndTheRestIsStudiosOutput() {
        var checked = 0
        val failures = ArrayList<String>()
        for (d in corpus + vectors) {
            val r = StudioFormatter.formatPreserving(d.input) ?: continue
            if (r.kept.isEmpty()) continue
            checked++
            val lines = d.input.split(Regex("\r\n|\r|\n"))
            var cursor = 0
            for (b in r.blocks) {
                val piece = if (b.kept) b.source else b.studioOutput ?: ""
                if (b.kept) {
                    var end = b.endLine
                    // markdown-it's blank line: spaces and tabs only (a line of U+00A0 is content)
                    if (b.kind == StudioBlockKind.BLOCK) while (end > b.startLine + 1 && lines[end - 1].all { it == ' ' || it == '\t' }) end--
                    // the front matter ends with the `---` that starts its last line
                    val expected = if (b.kind == StudioBlockKind.FRONTMATTER) {
                        (lines.subList(b.startLine, end - 1) + "---").joinToString("\n")
                    } else {
                        lines.subList(b.startLine, end).joinToString("\n")
                    }
                    // a body block on the closing front matter line starts after its `---`
                    val actual = if (b.kind == StudioBlockKind.BLOCK && r.blocks.firstOrNull()?.let { it.kind == StudioBlockKind.FRONTMATTER && it.endLine > b.startLine } == true) {
                        lines[b.startLine].substring(0, 3) + b.source
                    } else {
                        b.source
                    }
                    if (actual != expected) failures += "${d.name}: kept block at line ${b.startLine} is not the source\n" + firstDifference(expected, actual)
                }
                if (piece.isEmpty()) continue
                val at = r.text.indexOf(piece, cursor)
                if (at < 0) {
                    failures += "${d.name}: ${if (b.kept) "kept" else "rendered"} block at line ${b.startLine} not found in order: ${piece.take(80)}"
                    break
                }
                cursor = at + piece.length
            }
        }
        report("kept blocks not verbatim, or blocks not in order (of $checked documents with kept blocks)", failures)
        assertEquals(emptyList<String>(), failures)
        assertEquals(2749, checked)
    }

    /**
     * Studio's text path is not idempotent on some documents; this port agrees with the oracle on which (corpus), and every
     * such document has a classified block, so formatPreserving never writes a form Studio changes again. Tallied by the
     * first reason of the document's first kept block.
     */
    @Test
    fun studioIsNotIdempotentOnlyWhereBlocksAreClassified() {
        val disagreements = corpus.filter { it.studio != null }.mapNotNull { d ->
            val t = StudioFormatter.format(d.input)!!
            val stable = StudioFormatter.format(t) == t
            if (stable == d.studioStable) null else "${d.name}: port says stable=$stable, oracle textStable=${d.studioStable}"
        }
        report("T(T(x)) == T(x) disagrees with the oracle", disagreements)
        assertEquals(emptyList<String>(), disagreements)

        val tallies = LinkedHashMap<String, Map<String, Int>>()
        val unflagged = ArrayList<String>()
        for ((label, docs) in listOf("corpus" to corpus, "random documents" to vectors)) {
            val tally = sortedMapOf<String, Int>()
            var total = 0
            for (d in docs) {
                val t = StudioFormatter.format(d.input) ?: continue
                if (StudioFormatter.format(t) == t) continue
                total++
                val first = StudioFormatter.formatPreserving(d.input)?.kept?.firstOrNull()?.reasons?.first()
                if (first == null) unflagged += d.name
                tally.merge(first?.name ?: "UNFLAGGED", 1, Int::plus)
            }
            println("Studio text path not idempotent ($label): ${tally.values.sum()} $tally")
            tallies[label] = tally
            assertEquals(label, if (label == "corpus") 83 else 732, total)
        }
        assertEquals(emptyList<String>(), unflagged)
        assertEquals(
            mapOf(
                "corpus" to mapOf(
                    "ADJACENT_LISTS" to 3, "COMPONENT_IN_LIST_ITEM" to 6, "COMPONENT_NAMED_A" to 14, "DROPPED_BLOCK" to 1,
                    "FENCE_IN_LIST_ITEM" to 1, "INDENTED_COMPONENT" to 8, "INLINE_CODE_WITH_BACKTICK" to 1,
                    "INLINE_COMPONENT_OBJECT_PROPS" to 1, "LEADING_THEMATIC_BREAK" to 2, "MULTILINE_HEADING" to 8,
                    "MULTILINE_PROP" to 9, "NESTED_TASK_LIST" to 14, "NOT_IDEMPOTENT" to 4, "RAW_HTML" to 10,
                    "TABLE_ALIGNMENT" to 1,
                ),
                "random documents" to mapOf(
                    "ADJACENT_LISTS" to 13, "COMPONENT_IN_LIST_ITEM" to 15, "COMPONENT_NAMED_A" to 59,
                    "DETACHED_ATTRIBUTES" to 52, "DROPPED_BLOCK" to 11, "ESCAPED_BRACE" to 13, "FENCE_IN_LIST_ITEM" to 4,
                    "FOOTNOTE" to 2, "FRONTMATTER_COMMENT" to 6, "FRONTMATTER_NOT_MAPPING" to 2, "HTML_COMMENT" to 23,
                    "INDENTED_COMPONENT" to 9, "INLINE_CODE_WITH_BACKTICK" to 7, "INLINE_COMPONENT_OBJECT_PROPS" to 5,
                    "LEADING_THEMATIC_BREAK" to 25, "LINK_REFERENCE_DEFINITION" to 1, "MALFORMED_ATTRIBUTE" to 14,
                    "MULTILINE_HEADING" to 160, "MULTILINE_PROP" to 4, "NESTED_TASK_LIST" to 16, "NOT_IDEMPOTENT" to 157,
                    "PROPS_COMMENT" to 3, "RAW_HTML" to 123, "TABLE_ALIGNMENT" to 1, "UNCLOSED_ATTRIBUTES" to 7,
                ),
            ),
            tallies,
        )
    }

    /** How many blocks each reason keeps (a block with several reasons counts for each). */
    @Test
    fun keptBlocksByReason() {
        val tallies = LinkedHashMap<String, Map<String, Int>>()
        for ((label, docs) in listOf("corpus" to corpus, "random documents" to vectors)) {
            val tally = sortedMapOf<String, Int>()
            for (d in docs) {
                val r = StudioFormatter.formatPreserving(d.input) ?: continue
                for (b in r.kept) for (reason in b.reasons) tally.merge(reason.name, 1, Int::plus)
            }
            println("kept blocks by reason ($label): $tally")
            tallies[label] = tally
        }
        assertEquals(
            mapOf(
                "corpus" to mapOf(
                    "ADJACENT_LISTS" to 14, "COMPONENT_IN_LIST_ITEM" to 12, "COMPONENT_NAMED_A" to 20,
                    "DETACHED_ATTRIBUTES" to 2, "DROPPED_BLOCK" to 13, "ESCAPED_BRACE" to 2, "FENCE_HIGHLIGHTS" to 9,
                    "FENCE_IN_LIST_ITEM" to 1, "FOOTNOTE" to 4, "FRONTMATTER_COMMENT" to 3, "HEADING_ID" to 3,
                    "HTML_COMMENT" to 10, "INDENTED_COMPONENT" to 13, "INLINE_CODE_WITH_BACKTICK" to 1,
                    "INLINE_COMPONENT_OBJECT_PROPS" to 1, "INVALID_YAML" to 36, "LEADING_THEMATIC_BREAK" to 9,
                    "LINK_REFERENCE_DEFINITION" to 5, "MALFORMED_ATTRIBUTE" to 1, "MULTILINE_HEADING" to 16,
                    "MULTILINE_PROP" to 10, "NESTED_TASK_LIST" to 21, "NESTING_LIMIT" to 2, "NOT_IDEMPOTENT" to 14,
                    "PROPS_COMMENT" to 12, "RAW_HTML" to 86, "REFERENCE_LINK" to 2, "TABLE_ALIGNMENT" to 3,
                    "UNCLOSED_ATTRIBUTES" to 5,
                ),
                "random documents" to mapOf(
                    "ADJACENT_LISTS" to 45, "COMPONENT_IN_LIST_ITEM" to 48, "COMPONENT_NAMED_A" to 96,
                    "DETACHED_ATTRIBUTES" to 625, "DROPPED_BLOCK" to 95, "ESCAPED_BRACE" to 67, "FENCE_HIGHLIGHTS" to 26,
                    "FENCE_IN_LIST_ITEM" to 7, "FOOTNOTE" to 20, "FRONTMATTER_COMMENT" to 21, "FRONTMATTER_NOT_MAPPING" to 15,
                    "HEADING_ID" to 11, "HTML_COMMENT" to 168, "INDENTED_COMPONENT" to 64, "INLINE_CODE_WITH_BACKTICK" to 25,
                    "INLINE_COMPONENT_OBJECT_PROPS" to 7, "INVALID_YAML" to 621, "LEADING_THEMATIC_BREAK" to 52,
                    "LINK_REFERENCE_DEFINITION" to 36, "MALFORMED_ATTRIBUTE" to 458, "MULTILINE_HEADING" to 258,
                    "MULTILINE_PROP" to 4, "NESTED_TASK_LIST" to 25, "NESTING_LIMIT" to 4, "NOT_IDEMPOTENT" to 267,
                    "PROPS_COMMENT" to 11, "RAW_HTML" to 746, "REFERENCE_LINK" to 9, "TABLE_ALIGNMENT" to 9,
                    "UNCLOSED_ATTRIBUTES" to 230,
                ),
            ),
            tallies,
        )
    }

    /**
     * formatPreserving is a fixed point: formatting its output again changes nothing. The only exceptions start with a
     * thematic break after blank lines: Studio trims those lines, so the output starts with `---` and a later `---` line
     * (a component's props block) turns everything before it into front matter. Studio has the same defect.
     */
    @Test
    fun formatPreservingIsIdempotent() {
        val failures = ArrayList<String>()
        var count = 0
        for (d in corpus + vectors) {
            val once = StudioFormatter.formatPreserving(d.input) ?: continue
            count++
            val twice = StudioFormatter.formatPreserving(once.text)?.text
            if (twice == once.text) continue
            val leadingBreak = !d.input.startsWith("---") && d.input.trimStart().startsWith("---") && once.text.startsWith("---")
            failures += (if (leadingBreak) "LEADING_THEMATIC_BREAK " else "") + d.name + "\n" + firstDifference(once.text, twice ?: "<null>")
        }
        report("formatPreserving not idempotent (of $count)", failures)
        assertTrue(failures.joinToString("\n"), failures.all { it.startsWith("LEADING_THEMATIC_BREAK ") })
        assertEquals(4, failures.size)
        assertEquals(5500, count)
    }
}
