package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioDbVectors.Result
import cz.xprees.comark.format.studio.StudioOracleCases.firstDifference
import cz.xprees.comark.format.studio.StudioOracleCases.text
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [StudioStyle] on the recorded corpora: the oracle corpus (`oracle.json`), the random documents of `text-vectors.json`
 * and the database-path vectors of `db-vectors.json`. Every document lands in exactly one category per test, and the counts
 * are pinned; the categories that must never appear are asserted empty.
 */
class StudioStyleDifferentialTest {
    private class Doc(val name: String, val input: String, val collection: StudioCollection)

    /** Oracle cases and random documents, in the collection the oracle recorded them under. */
    private val inputs: List<Doc> by lazy {
        StudioOracleCases.cases.map {
            val type = it["collectionType"]?.asString
            Doc(it["name"].asString, it["input"].asString, if (type == "data") StudioCollection.data(DOC_ID) else StudioCollection.page(DOC_ID))
        } + StudioOracleCases.textVectors.mapIndexed { i, v -> Doc("text-vectors #$i", v["input"].asString, StudioCollection.page(DOC_ID)) }
    }

    /** Every y and recorded D(y) of `db-vectors.json`, with the configuration's collection. */
    private val dbDocs: List<Doc> by lazy {
        StudioDbVectors.vectors.flatMap { v ->
            val c = v.byConfig.getValue("pageDefault")
            listOf(Doc("${v.source} y", v.y, StudioDbVectors.collection("pageDefault", c.id))) +
                StudioDbVectors.CONFIGS.mapNotNull { config ->
                    val r = v.byConfig.getValue(config)
                    r.dText?.takeIf { it != v.y }?.let { Doc("${v.source} D [$config]", it, StudioDbVectors.collection(config, r.id)) }
                }
        }
    }

    private fun options(doc: Doc) = StudioStyleOptions(collection = doc.collection)

    private fun report(file: String, counts: Map<String, Int>, details: Map<String, MutableList<String>>) {
        println(counts.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        System.getenv("STUDIO_STYLE_REPORT")?.let { dir ->
            File(dir, file).writeText(
                counts.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n" +
                    details.entries.joinToString("\n") { (k, v) -> "##### $k\n" + v.take(8).joinToString("\n") },
            )
        }
    }

    private class Tally {
        val counts = sortedMapOf<String, Int>()
        val details = LinkedHashMap<String, MutableList<String>>()
        fun add(category: String, detail: () -> String = { "" }) {
            counts.merge(category, 1, Int::plus)
            val list = details.getOrPut(category) { ArrayList() }
            if (list.size < 8) list += detail()
        }
    }

    /** format(format(x)) == format(x) on every input of all three corpora, and every run finds its fixed point. */
    @Test
    fun formatIsIdempotent() {
        val tally = Tally()
        for ((label, docs) in listOf("oracle+text-vectors" to inputs, "db-vectors" to dbDocs)) {
            for (doc in docs) {
                val r = StudioStyle.format(doc.input, options(doc))
                if (r == null) {
                    tally.add("$label: not parsed")
                    continue
                }
                if (!r.diagnostics.converged) tally.add("$label: NO FIXED POINT") { doc.name }
                val again = StudioStyle.format(r.text, options(doc))
                when {
                    again?.text != r.text -> tally.add("$label: NOT IDEMPOTENT") { "${doc.name}\n" + firstDifference(r.text, again?.text ?: "<null>") }
                    again.diagnostics.rounds != 1 -> tally.add("$label: SECOND RUN NEEDED MORE THAN ONE PASS") { doc.name }
                    r.diagnostics.rounds == 1 -> tally.add("$label: idempotent, fixed point in 1 pass")
                    else -> tally.add("$label: idempotent, fixed point in ${r.diagnostics.rounds} passes")
                }
            }
        }
        report("idempotence.txt", tally.counts, tally.details)
        assertEquals(emptyMap<String, Int>(), tally.counts.filterKeys { it.uppercase() == it || "NOT" in it || "NO FIXED" in it || "MORE THAN" in it })
        assertEquals(IDEMPOTENCE, tally.counts)
    }

    /**
     * What Studio does to the IDE's output y = format(x): Studio's text path leaves y unchanged, and its database path too
     * wherever [StudioDbPath] can tell, except in the blocks the result reports (kept verbatim, or written in the text-path
     * form where the two paths disagree). Documents with kept blocks are checked with those blocks removed.
     */
    @Test
    fun outputSurvivesStudio() {
        val tally = Tally()
        for (doc in inputs) {
            val r = StudioStyle.format(doc.input, options(doc))
            if (r == null) {
                tally.add("not parsed")
                continue
            }
            val kept = r.keptBlocks.isNotEmpty()
            val y = if (kept) withoutLines(r.text, r.keptBlocks.map { it.outputLines }) else r.text
            val prefix = if (kept) "kept blocks removed: " else ""
            if (kept && body(y).isEmpty()) {
                tally.add("kept blocks removed: no body left") { "${doc.name}: ${r.keptBlocks.map { it.reasons }}\n${r.text.take(300)}" }
                continue
            }
            val t = StudioFormatter.format(y)
            if (t != y) {
                // with blocks cut out, their neighbours can run together (lists merge, blank lines move)
                tally.add(if (kept) "kept blocks removed: T changes what is left" else "T CHANGES Y") { "${doc.name}\n" + firstDifference(y, t ?: "<null>") }
                continue
            }
            // with blocks removed, the front matter Studio derives from the body (title, description) no longer applies
            val keptFm = r.keptBlocks.any { it.outputLines.first == 0 && y.startsWith("---\n").not() }
            val collection = if (keptFm) StudioCollection.data(DOC_ID) else doc.collection
            when (val d = StudioDbPath.emulateOutcome(y, collection)) {
                is StudioDbPath.Outcome.Written -> when {
                    d.text == y || kept && body(d.text) == body(y) -> tally.add("${prefix}dual fixed point")
                    r.unstableBlocks.isNotEmpty() -> tally.add("${prefix}D rewrites only reported unstable blocks")
                    // the order depends on elements in the kept blocks too, which the emulation cannot see
                    kept && StudioStyle.classify(StudioTreeBuilder.build(y).nodes, StudioTreeBuilder.build(d.text).nodes) == StudioDbRewrite.ATTRIBUTE_ORDER ->
                        tally.add("${prefix}D reorders props (the order depends on the kept blocks)")
                    else -> tally.add("${prefix}D CHANGES Y") { "${doc.name}\n" + firstDifference(y, d.text) }
                }
                is StudioDbPath.Outcome.NoDocument -> tally.add("${prefix}T fixed point, Studio has no D document")
                is StudioDbPath.Outcome.Unsupported -> tally.add("${prefix}T fixed point, D not emulated: ${d.reason}")
            }
        }
        report("survives.txt", tally.counts, tally.details)
        assertEquals(emptyMap<String, Int>(), tally.counts.filterKeys { "T CHANGES" in it || "D CHANGES" in it })
        assertEquals(SURVIVES, tally.counts)
    }

    /**
     * Files Studio wrote: where Studio itself leaves them unchanged on both paths (dual fixed points), format leaves them
     * unchanged too. The other recorded outputs are counted by whether format changes them.
     */
    @Test
    fun studioWrittenFilesAreUnchanged() {
        val tally = Tally()
        fun check(label: String, name: String, z: String, collection: StudioCollection, dualFixed: Boolean) {
            val r = StudioStyle.format(z, StudioStyleOptions(collection = collection))
            val unchanged = r?.text == z
            val why = when {
                r == null -> "not parsed"
                unchanged -> "unchanged"
                r.keptBlocks.isNotEmpty() -> "changed, with kept blocks"
                else -> "changed"
            }
            if (dualFixed) {
                tally.add("$label dual fixed point: ${if (unchanged) why else "CHANGED: $why"}") { "$name\n" + firstDifference(z, r?.text ?: "<null>") }
            } else {
                tally.add("$label not a dual fixed point: $why")
            }
        }
        for (c in StudioOracleCases.cases) {
            val db = c.text("db") ?: continue
            val type = c["collectionType"]?.asString
            val collection = if (type == "data") StudioCollection.data(DOC_ID) else StudioCollection.page(DOC_ID)
            val dual = c.text("textOfDb") == db && c["dbStable"]?.asBoolean == true
            check("oracle D(x)", c["name"].asString, db, collection, dual)
        }
        for (v in StudioDbVectors.vectors) {
            for (config in StudioDbVectors.CONFIGS) {
                val r = v.byConfig.getValue(config)
                val collection = StudioDbVectors.collection(config, r.id)
                // y = T(x) as Studio's code editor wrote it
                check("[$config] T(x)", v.source, v.y, collection, v.yStable && r.dEqualsY)
                // D(y) as Studio's database path wrote it
                val d = r.dText ?: continue
                if (d == v.y) continue
                val dual = r.tOfD == Result.Text(d) && r.dOfD == Result.Text(d)
                check("[$config] D(y)", v.source, d, collection, dual)
            }
        }
        report("studio-written.txt", tally.counts, tally.details)
        assertEquals(emptyMap<String, Int>(), tally.counts.filterKeys { "CHANGED" in it })
        assertEquals(STUDIO_WRITTEN, tally.counts)
    }

    /** The body of [text]: what follows its front matter. */
    private fun body(text: String): String =
        if (text.startsWith("---\n")) text.indexOf("\n---\n", 3).let { if (it < 0) text else text.substring(it + 5).trimStart('\n') } else text

    /**
     * Custom options are applied on top of the Studio style, and only where the new form parses to the same tree, so they
     * are idempotent too on the oracle corpus and the random documents (not guaranteed by construction; counted).
     */
    @Test
    fun customOptionsAreIdempotent() {
        val custom = StudioStyleOptions(
            indentSize = 3, bulletMarker = '*', orderedDelimiter = ')', emphasisChar = '_', strongChar = '_', fenceChar = '~',
            propsBlockThreshold = 1, sortProps = false, frontMatter = StudioFrontMatterMode.REDUMP_ONLY,
        )
        val tally = Tally()
        for (doc in inputs) {
            val options = custom.copy(collection = doc.collection)
            val r = StudioStyle.format(doc.input, options)
            if (r == null) {
                tally.add("not parsed")
                continue
            }
            val again = StudioStyle.format(r.text, options)
            when {
                again?.text != r.text -> tally.add("NOT IDEMPOTENT") { "${doc.name}\n" + firstDifference(r.text, again?.text ?: "<null>") }
                r.diagnostics.restyledBlocks > 0 -> tally.add("idempotent, restyled")
                else -> tally.add("idempotent, nothing to restyle")
            }
        }
        report("custom.txt", tally.counts, tally.details)
        assertEquals(CUSTOM, tally.counts)
    }

    /** [text] without the lines [ranges] (and a blank line after each, so the blocks around them stay apart). */
    private fun withoutLines(text: String, ranges: List<IntRange>): String {
        val lines = text.split("\n")
        val drop = BooleanArray(lines.size)
        for (r in ranges) {
            for (l in r) if (l in lines.indices) drop[l] = true
            val next = r.last + 1
            if (next in lines.indices && lines[next].isEmpty()) drop[next] = true
        }
        val out = lines.filterIndexed { i, _ -> !drop[i] }.joinToString("\n").trim('\n').replace(Regex("\n{3,}"), "\n\n")
        return if (out.isEmpty()) "" else out + "\n"
    }

    private companion object {
        /** The content id the oracle and db-vectors record documents under (decides an injected title). */
        const val DOC_ID = "content/docs/page.md"

        /** Every input by whether formatting is idempotent and how many passes the fixed point took. */
        val IDEMPOTENCE = mapOf(
            "db-vectors: idempotent, fixed point in 1 pass" to 16391,
            "db-vectors: idempotent, fixed point in 2 passes" to 3,
            "db-vectors: not parsed" to 9,
            "oracle+text-vectors: idempotent, fixed point in 1 pass" to 5493,
            "oracle+text-vectors: idempotent, fixed point in 2 passes" to 7,
            "oracle+text-vectors: not parsed" to 1,
        )
        /**
         * The output y of every oracle and random input: dual fixed points, documents the database-path emulation cannot
         * follow ([StudioDbPath.Reason]), and documents whose only database-path rewrites are the reported unstable blocks.
         * "kept blocks removed": checked without the kept blocks, where removing them can make the neighbours run together.
         */
        val SURVIVES = mapOf(
            "D rewrites only reported unstable blocks" to 54,
            "T fixed point, D not emulated: ATTRIBUTE_NAME" to 29,
            "T fixed point, D not emulated: ATTRIBUTE_VALUE" to 2,
            "T fixed point, D not emulated: BLOCK_ATTRIBUTES" to 88,
            "T fixed point, D not emulated: BLOCK_SYNTAX" to 113,
            "T fixed point, D not emulated: BRACKET_SPAN" to 39,
            "T fixed point, D not emulated: CODE_BLOCK" to 122,
            "T fixed point, D not emulated: COMPONENT_HR" to 95,
            "T fixed point, D not emulated: COMPONENT_NAME" to 30,
            "T fixed point, D not emulated: INLINE_SYNTAX" to 422,
            "T fixed point, D not emulated: LIST" to 11,
            "T fixed point, D not emulated: RAW_HTML" to 4,
            "T fixed point, D not emulated: SLOT" to 9,
            "T fixed point, D not emulated: URL" to 7,
            "dual fixed point" to 1721,
            "kept blocks removed: D reorders props (the order depends on the kept blocks)" to 22,
            "kept blocks removed: D rewrites only reported unstable blocks" to 21,
            "kept blocks removed: T changes what is left" to 9,
            "kept blocks removed: T fixed point, D not emulated: ATTRIBUTE_NAME" to 18,
            "kept blocks removed: T fixed point, D not emulated: ATTRIBUTE_VALUE" to 2,
            "kept blocks removed: T fixed point, D not emulated: BLOCK_ATTRIBUTES" to 63,
            "kept blocks removed: T fixed point, D not emulated: BLOCK_SYNTAX" to 31,
            "kept blocks removed: T fixed point, D not emulated: BRACKET_SPAN" to 16,
            "kept blocks removed: T fixed point, D not emulated: CODE_BLOCK" to 76,
            "kept blocks removed: T fixed point, D not emulated: COMPONENT_HR" to 21,
            "kept blocks removed: T fixed point, D not emulated: COMPONENT_NAME" to 13,
            "kept blocks removed: T fixed point, D not emulated: INLINE_SYNTAX" to 219,
            "kept blocks removed: T fixed point, D not emulated: LIST" to 7,
            "kept blocks removed: T fixed point, D not emulated: RAW_HTML" to 1,
            "kept blocks removed: T fixed point, D not emulated: SLOT" to 10,
            "kept blocks removed: dual fixed point" to 1074,
            "kept blocks removed: no body left" to 1151,
            "not parsed" to 1,
        )
        /** Custom options on the oracle corpus and the random documents. */
        val CUSTOM = mapOf(
            "idempotent, nothing to restyle" to 3396,
            "idempotent, restyled" to 2104,
            "not parsed" to 1,
        )

        /**
         * Recorded Studio outputs by whether they are dual fixed points and whether format changes them. "CHANGED" must not
         * appear: format leaves every dual fixed point Studio wrote unchanged. (`[pageDefault] T(x)` and `[pageSchema] T(x)`
         * are mostly not dual fixed points: the database path injects a title, and `draft: false` for pageSchema.)
         */
        val STUDIO_WRITTEN = mapOf(
            "[data] D(y) dual fixed point: unchanged" to 1656,
            "[data] D(y) not a dual fixed point: changed" to 339,
            "[data] D(y) not a dual fixed point: changed, with kept blocks" to 79,
            "[data] D(y) not a dual fixed point: not parsed" to 3,
            "[data] D(y) not a dual fixed point: unchanged" to 236,
            "[data] T(x) dual fixed point: unchanged" to 2393,
            "[data] T(x) not a dual fixed point: changed" to 655,
            "[data] T(x) not a dual fixed point: changed, with kept blocks" to 180,
            "[data] T(x) not a dual fixed point: unchanged" to 1611,
            "[pageDefault] D(y) dual fixed point: unchanged" to 3711,
            "[pageDefault] D(y) not a dual fixed point: changed" to 354,
            "[pageDefault] D(y) not a dual fixed point: changed, with kept blocks" to 79,
            "[pageDefault] D(y) not a dual fixed point: not parsed" to 3,
            "[pageDefault] D(y) not a dual fixed point: unchanged" to 310,
            "[pageDefault] T(x) dual fixed point: unchanged" to 333,
            "[pageDefault] T(x) not a dual fixed point: changed" to 3428,
            "[pageDefault] T(x) not a dual fixed point: changed, with kept blocks" to 843,
            "[pageDefault] T(x) not a dual fixed point: unchanged" to 235,
            "[pageSchema] D(y) dual fixed point: unchanged" to 4029,
            "[pageSchema] D(y) not a dual fixed point: changed" to 355,
            "[pageSchema] D(y) not a dual fixed point: changed, with kept blocks" to 79,
            "[pageSchema] D(y) not a dual fixed point: not parsed" to 3,
            "[pageSchema] D(y) not a dual fixed point: unchanged" to 328,
            "[pageSchema] T(x) not a dual fixed point: changed" to 3860,
            "[pageSchema] T(x) not a dual fixed point: changed, with kept blocks" to 948,
            "[pageSchema] T(x) not a dual fixed point: unchanged" to 31,
            "oracle D(x) dual fixed point: unchanged" to 327,
            "oracle D(x) not a dual fixed point: changed" to 67,
            "oracle D(x) not a dual fixed point: changed, with kept blocks" to 14,
            "oracle D(x) not a dual fixed point: unchanged" to 72,
        )
    }
}
