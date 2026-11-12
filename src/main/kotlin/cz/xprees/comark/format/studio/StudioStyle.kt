package cz.xprees.comark.format.studio

import cz.xprees.comark.format.JsText
import cz.xprees.comark.format.studio.StudioSafety.AnalyzedBlock

/** What [StudioStyle] does with the front matter. */
enum class StudioFrontMatterMode {
    /**
     * Nuxt Studio's database-path data rules (Studio's value): for page collections a missing `title` (the first `#`
     * heading, else the file name) and `description` (the paragraph after it) are added, keys are ordered by the collection
     * schema, `navigation: true`, `null`s and empty lists are dropped, and the YAML is dumped as js-yaml does.
     */
    STUDIO_RULES,

    /** The data is kept as it is and only re-dumped as Studio's code editor does (js-yaml), with nothing added or dropped. */
    REDUMP_ONLY,

    /** The front matter is copied unchanged. */
    KEEP,
}

/**
 * The options of [StudioStyle.format]. The defaults are Nuxt Studio's own values; with every option at its default the
 * output is the Nuxt Studio style exactly (see [isStudio]).
 *
 * The renderer options ([indentSize], [bulletMarker], [orderedDelimiter], [emphasisChar], [strongChar], [fenceChar],
 * [propsBlockThreshold]), [sortProps] and [frontMatter] serve the Custom preset. They are applied on top of the Studio
 * style (the blocks Studio's renderer wrote are written again with them, and only where the result parses to the same
 * tree), so **with any non-default option the output is no longer guaranteed to be left unchanged by Studio**: Studio
 * rewrites it to its own style on its next save.
 *
 * @property collection the Nuxt Content collection of the document (its type, schema and file name), which decides the
 *   front matter Studio writes; `null` uses Studio's defaults for a page collection with an unknown schema
 *   ([StudioCollection.defaults]), whose injected title comes from a placeholder file name.
 * @property indentSize spaces per nesting level of a component inside a component (Studio: 2). From 4 on, a nested
 *   component's lines would read as an indented code block, so blocks with nested components keep Studio's form.
 * @property bulletMarker the bullet list marker, `-`, `*` or `+` (Studio: `-`)
 * @property orderedDelimiter the ordered list delimiter, `.` or `)` (Studio: `.`)
 * @property emphasisChar `*` or `_` for emphasis (Studio: `*`)
 * @property strongChar `*` or `_` for strong emphasis, written twice (Studio: `*`, i.e. `**`)
 * @property fenceChar the preferred code fence character, `` ` `` or `~` (Studio: `` ` ``); the other one is used where the
 *   code contains a fence of the preferred one
 * @property propsBlockThreshold the most props a block component keeps on its opening line, `::name{a="1" b="2"}`; with
 *   more, they go to a YAML props block (Studio: 3). Object and array props always use the YAML block.
 * @property sortProps whether props are written in the order Studio's database path gives them (rehype-sort-attributes:
 *   by how often each name occurs on the same tag in the document). `false` keeps the order of the source.
 * @property frontMatter the front matter handling (Studio: [StudioFrontMatterMode.STUDIO_RULES])
 */
data class StudioStyleOptions(
    val collection: StudioCollection? = null,
    val indentSize: Int = STUDIO_INDENT_SIZE,
    val bulletMarker: Char = '-',
    val orderedDelimiter: Char = '.',
    val emphasisChar: Char = '*',
    val strongChar: Char = '*',
    val fenceChar: Char = '`',
    val propsBlockThreshold: Int = STUDIO_PROPS_BLOCK_THRESHOLD,
    val sortProps: Boolean = true,
    val frontMatter: StudioFrontMatterMode = StudioFrontMatterMode.STUDIO_RULES,
) {
    init {
        require(indentSize in 0..8) { "indentSize must be 0..8: $indentSize" }
        require(bulletMarker in "-*+") { "bulletMarker must be -, * or +: $bulletMarker" }
        require(orderedDelimiter in ".)") { "orderedDelimiter must be . or ): $orderedDelimiter" }
        require(emphasisChar in "*_") { "emphasisChar must be * or _: $emphasisChar" }
        require(strongChar in "*_") { "strongChar must be * or _: $strongChar" }
        require(fenceChar in "`~") { "fenceChar must be ` or ~: $fenceChar" }
        require(propsBlockThreshold >= 0) { "propsBlockThreshold must not be negative: $propsBlockThreshold" }
    }

    /** Every option except [collection] has Studio's value: the output is the Nuxt Studio style. */
    val isStudio: Boolean
        get() = rendersLikeStudio && sortProps && frontMatter == StudioFrontMatterMode.STUDIO_RULES

    /** The renderer options have Studio's values. */
    val rendersLikeStudio: Boolean
        get() = rendererStyle == StudioRenderer.Style.STUDIO

    internal val rendererStyle: StudioRenderer.Style
        get() = StudioRenderer.Style(indentSize, bulletMarker, orderedDelimiter, emphasisChar, strongChar, fenceChar, propsBlockThreshold)

    /** [collection], or Studio's defaults. */
    val effectiveCollection: StudioCollection
        get() = collection ?: StudioCollection.defaults()

    companion object {
        /** Studio's indentation of a nested component. */
        const val STUDIO_INDENT_SIZE = 2

        /** Studio's (comark's `maxInlineAttributes`) most props on a block component's opening line. */
        const val STUDIO_PROPS_BLOCK_THRESHOLD = 3

        /** Nuxt Studio's style for documents of [collection]. */
        fun studio(collection: StudioCollection? = null) = StudioStyleOptions(collection = collection)
    }
}

/**
 * How Studio's database path changes a block (the document opened from Nuxt Content's database and saved), as
 * [StudioStyle] classifies it. [damages] tells whether the change is a different document (text, structure or attributes),
 * which [StudioStyle] keeps verbatim and reports, or only a different way of writing the same document.
 */
enum class StudioDbRewrite(val description: String, val damages: Boolean) {
    ATTRIBUTE_ORDER("orders the props by how often each name occurs in the document", false),
    MARKUP("writes the same content with different Markdown", false),
    TEXT("changes the text", true),
    STRUCTURE("changes the block structure", true),
    ATTRIBUTES("drops or changes props or attributes", true),
}

/**
 * A block [StudioStyle] copied from the input unchanged because Nuxt Studio damages it (see [reasons]).
 *
 * @property outputLines the lines of the block in [StudioStyleResult.text] (0-based, end exclusive)
 * @property inputLines the lines of the block in the input (0-based, end exclusive; a line break is `\r\n`, `\r` or `\n`)
 * @property dbRewrite for [StudioRewriteReason.DB_PATH_REWRITE], how the database path changes the block
 */
data class StudioKeptBlock(
    val outputLines: IntRange,
    val inputLines: IntRange,
    val reasons: Set<StudioRewriteReason>,
    val dbRewrite: StudioDbRewrite? = null,
) {
    /** One message per reason, for a weak warning on the block ("Nuxt Studio will rewrite this: ..."). */
    val messages: List<String>
        get() = reasons.map { StudioStyle.message(it, dbRewrite) }
}

/**
 * A block [StudioStyle] wrote in the form Studio's code editor (text path) keeps, which Studio's database path still
 * rewrites ([dbRewrite], a different way of writing the same content): the two save paths disagree on it, and decision 1
 * of the style picks the text-path form.
 */
data class StudioUnstableBlock(val outputLines: IntRange, val dbRewrite: StudioDbRewrite)

/**
 * A block [StudioStyle] wrote in the text path's form without being able to check it against Studio's database path: the
 * emulation ([StudioDbPath]) does not follow one of its constructs, or (for the front matter) the injected title or description
 * may come from a block the emulation could not read. Studio's database path may still rewrite it.
 *
 * @property outputLines the lines of the block in [StudioStyleResult.text] (0-based, end exclusive)
 * @property inputLines the lines of the input it came from (empty for front matter the style adds)
 * @property frontMatter the block is the front matter
 */
data class StudioUnverifiedBlock(val outputLines: IntRange, val inputLines: IntRange, val frontMatter: Boolean = false)

/**
 * A block [StudioStyle] wrote in the form Studio's code editor (text path) keeps on its own, which that editor still rewrites
 * in this document because of a block before it that Studio damages (one kept verbatim or not checked): Studio's output for
 * that block runs into this one (a missing blank line, a construct it leaves open). Found by running the text path
 * ([StudioFormatter.format]) over the whole result.
 *
 * @property outputLines the lines of the block in [StudioStyleResult.text] (0-based, end exclusive)
 * @property inputLines the input lines it came from
 * @property causeOutputLines the reported block before it whose rewrite spills into it, when there is one
 */
data class StudioAffectedBlock(val outputLines: IntRange, val inputLines: IntRange, val causeOutputLines: IntRange? = null)

/**
 * A top-level block of [StudioStyleResult.text] and the input lines it came from (0-based, end exclusive; [inputLines] is
 * empty for front matter the style adds). In document order; blocks that came from overlapping input lines are merged.
 */
data class StudioBlockMapping(val outputLines: IntRange, val inputLines: IntRange)

/** What happened to the front matter. */
enum class StudioFrontMatterStatus {
    /** No front matter in the input or the output. */
    NONE,

    /** Studio's data rules, computed exactly as Studio's database path does. */
    STUDIO_RULES,

    /**
     * Studio's data rules, with the injected title and description read from the code editor's parse of the body (the
     * database path's parse could not be emulated for this document; they differ only on unusual headings or paragraphs).
     */
    STUDIO_RULES_APPROXIMATE,

    /** Re-dumped as Studio's code editor does ([StudioFrontMatterMode.REDUMP_ONLY], or Studio's rules do not apply). */
    REDUMPED,

    /** Copied unchanged ([StudioFrontMatterMode.KEEP]). */
    COPIED,

    /** Kept verbatim because Studio damages it (comments, invalid YAML, ...): see [StudioStyleResult.keptBlocks]. */
    KEPT,

    /**
     * Nuxt Content cannot store this front matter (an invalid date or number for a typed schema field), so Studio's database
     * path has no document; re-dumped only.
     */
    NO_STUDIO_DOCUMENT,
}

/**
 * Counts and states for one [StudioStyle.format] run.
 *
 * @property blocks top-level blocks of the output, the front matter included
 * @property keptBlocks blocks copied verbatim ([StudioStyleResult.keptBlocks])
 * @property dbPathKeptBlocks of [keptBlocks], those kept because of the database path ([StudioRewriteReason.DB_PATH_REWRITE])
 * @property dbAdoptedBlocks blocks written in the database path's form (both save paths keep it, the text path's form did not)
 * @property unstableBlocks blocks where the two save paths disagree ([StudioStyleResult.unstableBlocks])
 * @property dbUnverifiedBlocks blocks whose database-path form could not be computed (constructs [StudioDbPath] does not
 *   follow); they are written in the text path's form
 * @property rounds formatting passes run until the output was a fixed point
 * @property converged `false` when no fixed point was found within [StudioStyle.MAX_ROUNDS] passes; the input is then
 *   returned unchanged, as one kept block
 * @property bomRemoved the input started with a byte order mark, which the output does not have
 * @property lineEndingsNormalized the input had `\r\n` or `\r` line breaks; the output has `\n`
 * @property restyledBlocks blocks written with the Custom renderer options
 * @property affectedBlocks blocks Studio's text path rewrites because of a damaged block before them ([StudioStyleResult.affectedBlocks])
 */
data class StudioStyleDiagnostics(
    val blocks: Int,
    val keptBlocks: Int,
    val dbPathKeptBlocks: Int,
    val dbAdoptedBlocks: Int,
    val unstableBlocks: Int,
    val dbUnverifiedBlocks: Int,
    val frontMatter: StudioFrontMatterStatus,
    val rounds: Int,
    val converged: Boolean,
    val bomRemoved: Boolean,
    val lineEndingsNormalized: Boolean,
    val restyledBlocks: Int,
    val affectedBlocks: Int = 0,
)

/**
 * [StudioStyle.format]'s result: the formatted [text] (`\n` line breaks, no byte order mark, ending with one `\n`), the
 * blocks copied verbatim with the reasons, the blocks the two Studio save paths disagree on, and [diagnostics].
 */
data class StudioStyleResult(
    val text: String,
    val keptBlocks: List<StudioKeptBlock>,
    val unstableBlocks: List<StudioUnstableBlock>,
    val diagnostics: StudioStyleDiagnostics,
    val unverifiedBlocks: List<StudioUnverifiedBlock> = emptyList(),
    val blockMap: List<StudioBlockMapping> = emptyList(),
    val affectedBlocks: List<StudioAffectedBlock> = emptyList(),
)

/**
 * The Nuxt Studio code style: the form of a Markdown document (Comark/MDC) that Nuxt Studio 1.8.0 leaves unchanged when
 * it saves it, on both of its save paths:
 *
 * - the **text path** T ([StudioFormatter.format]): a document edited in Studio's code editor;
 * - the **database path** D ([StudioDbPath]): a document Studio opened from Nuxt Content's database (the visual editor,
 *   or any save of a document loaded from the site).
 *
 * The target is, per block, a "dual fixed point" y with T(y) = y and D(y) = y. The algorithm:
 *
 * 1. [StudioFormatter.formatPreserving]: Studio's text-path rendering, with every block Studio damages kept verbatim
 *    ([StudioSafety]).
 * 2. The rendered blocks, with the front matter, are run through the database path ([StudioDbPath], on the document
 *    without the kept blocks; blocks it cannot follow are left out). Where D writes a block differently:
 *    - a different document (text, structure or attributes change, [StudioDbRewrite.damages]): the block is kept verbatim
 *      with [StudioRewriteReason.DB_PATH_REWRITE];
 *    - the same content written differently, and that form is kept by both paths: the block is written in D's form;
 *    - otherwise (the paths cannot be reconciled): the text path's form is written, reported in
 *      [StudioStyleResult.unstableBlocks].
 * 3. The front matter follows [StudioStyleOptions.frontMatter].
 * 4. Steps 1 to 3 are repeated on the output until it no longer changes (at most [MAX_ROUNDS] passes), so the result is a
 *    fixed point of the whole procedure: `format(format(x).text).text == format(x).text`. Where no fixed point is found
 *    (not seen on the test corpora), the input is returned unchanged as one kept block.
 * 5. With Custom options, the blocks Studio's renderer wrote are written again with them ([StudioStyleOptions]).
 *
 * Line breaks: `\r\n` and `\r` become `\n`, as Studio writes. A byte order mark at the start is removed: Studio's editor
 * reads the file as a string without one and writes none, so the Studio style has no BOM ([StudioStyleDiagnostics.bomRemoved]
 * tells the caller one was dropped; the IDE should then save the file without one). Any other U+FEFF before the first
 * content goes too (Studio trims it as whitespace). The result is deterministic.
 */
object StudioStyle {
    /** The most formatting passes [format] runs looking for a fixed point. */
    const val MAX_ROUNDS = 4

    private val DATA_COLLECTION = StudioCollection.data()

    /**
     * [text] in the Nuxt Studio style with [options], or `null` when the document cannot be parsed at all (markdown-it's
     * parse fails; invalid YAML alone does not make it fail, the block is kept instead).
     */
    fun format(text: String, options: StudioStyleOptions = StudioStyleOptions()): StudioStyleResult? = format(text, options, emptySet())

    /**
     * [format], with every block that holds one of the input lines [keepLines] (0-based) copied verbatim
     * ([StudioRewriteReason.FORMATTER_OFF]): the lines between formatter off/on markers.
     */
    fun format(text: String, options: StudioStyleOptions, keepLines: Set<Int>): StudioStyleResult? = try {
        formatChecked(text, options, keepLines)
    } catch (_: StackOverflowError) {
        null
    }

    /** The weak warning for a block kept for [reason]. */
    fun message(reason: StudioRewriteReason, dbRewrite: StudioDbRewrite? = null): String =
        if (reason == StudioRewriteReason.DB_PATH_REWRITE && dbRewrite != null) {
            "Nuxt Studio will rewrite this: its database path (a document opened from Nuxt Content) ${dbRewrite.description}"
        } else {
            "Nuxt Studio will rewrite this: ${reason.description}"
        }

    private fun formatChecked(text: String, options: StudioStyleOptions, keepLines: Set<Int>): StudioStyleResult? {
        val bom = text.startsWith('\uFEFF')
        // a U+FEFF before the first content (a byte order mark, or one more after blank lines) is whitespace to Studio's
        // trim; were it kept, the next run would take it for a byte order mark
        val lead = text.indexOfFirst { it != '\uFEFF' && !JsText.isWhitespace(it) }.let { if (it < 0) text.length else it }
        var input = text.substring(0, lead).replace("\uFEFF", "") + text.substring(lead)
        val crlf = '\r' in input
        if (crlf) input = input.replace(Regex("\r\n?"), "\n")
        val collection = options.effectiveCollection

        val cache = Memo()
        val passes = ArrayList<Pass>()
        var current = pass(input, options, collection, cache, keepLines) ?: return null
        passes += current
        var converged = current.text == input
        while (!converged && passes.size < MAX_ROUNDS) {
            val next = pass(current.text, options, collection, cache, StudioFormatter.forcedLines(current.blocks, current.assembled)) ?: break
            if (next.text == current.text) {
                converged = true
                break
            }
            passes += next
            current = next
        }
        if (!converged) {
            val lines = input.split("\n").size - if (input.endsWith("\n")) 1 else 0
            val kept = StudioKeptBlock(0 until lines, 0 until lines, setOf(StudioRewriteReason.NOT_IDEMPOTENT))
            val diagnostics = StudioStyleDiagnostics(1, 1, 0, 0, 0, 0, StudioFrontMatterStatus.KEPT, passes.size, false, bom, crlf, 0)
            return StudioStyleResult(input, listOf(kept), emptyList(), diagnostics, blockMap = listOf(StudioBlockMapping(0 until lines, 0 until lines)))
        }
        return result(passes, options, bom, crlf)
    }

    // ---- one pass -------------------------------------------------------------------------------------------------------

    /** One formatting pass: the decided blocks, their assembly and what was decided. */
    private class Pass(
        val blocks: List<AnalyzedBlock>,
        val assembled: StudioFormatter.Assembled,
        val dbRewrites: Map<Int, StudioDbRewrite>,
        val unstable: Map<Int, StudioDbRewrite>,
        val adopted: Int,
        val unverified: Set<Int>,
        val frontMatter: StudioFrontMatterStatus,
    ) {
        val text: String get() = assembled.text
    }

    /** What one [format] call computes more than once: its passes see the same blocks and documents again. */
    private class Memo {
        val safety = StudioSafety.Cache()
        private val emulations = HashMap<Triple<String, StudioCollection, Boolean>, StudioDbPath.Outcome>()
        private val segmentations = HashMap<String, StudioSegmentedDocument?>()

        fun emulate(text: String, collection: StudioCollection, sortProps: Boolean): StudioDbPath.Outcome =
            emulations.getOrPut(Triple(text, collection, sortProps)) { StudioDbPath.emulateOutcome(text, collection, sortProps) }

        fun segmented(text: String): StudioSegmentedDocument? = segmentations.getOrPut(text) { StudioStyle.segmented(text) }
    }

    private fun pass(text: String, options: StudioStyleOptions, collection: StudioCollection, cache: Memo, keepLines: Set<Int>): Pass? {
        val state = StudioFormatter.preservingState(text, cache.safety, keepLines) ?: return null
        val blocks = state.blocks.toMutableList()
        val fmIndex = blocks.indexOfFirst { it.block.kind == StudioBlockKind.FRONTMATTER }
        val fm = blocks.getOrNull(fmIndex)
        val fmUsable = fm != null && !fm.block.kept && fm.block.studioOutput != null
        val dbRewrites = HashMap<Int, StudioDbRewrite>()
        // `{{ expr }}` is text to comark and a binding component to Nuxt Content: the database path writes :binding{value="expr"}
        for (i in blocks.indices) {
            val b = blocks[i]
            if (b.block.kind != StudioBlockKind.BLOCK || b.block.kept || b.rendered == null || !hasBinding(b.rendered)) continue
            blocks[i] = AnalyzedBlock(b.block.copy(reasons = b.block.reasons + StudioRewriteReason.DB_PATH_REWRITE), b.rendered)
            dbRewrites[i] = StudioDbRewrite.TEXT
        }
        val candidates = blocks.indices.filter {
            val b = blocks[it]
            b.block.kind == StudioBlockKind.BLOCK && !b.block.kept && b.rendered != null && StudioJs.trim(b.rendered).isNotEmpty()
        }
        val anyBodyKept = blocks.any { it.block.kind == StudioBlockKind.BLOCK && it.block.kept }

        // the database path over the front matter and the rendered blocks
        val dbResult = databasePath(if (fmUsable) fm else null, candidates.map { blocks[it] }, collection, options.sortProps, cache)
        val db = dbResult.run
        val unstable = HashMap<Int, StudioDbRewrite>()
        var adopted = 0
        val unverified = HashSet<Int>()
        val used = db?.used?.toSet().orEmpty()
        candidates.indices.filter { it !in used }.forEach { unverified += candidates[it] }
        if (db != null) {
            val sSegments = db.input.segments
            val dSegments = db.output.segments
            if (sSegments.size != db.used.size || dSegments.size != db.used.size) {
                db.used.forEach { unverified += candidates[it] }
            } else {
                val again by lazy {
                    (cache.emulate(db.outputText, collection, options.sortProps) as? StudioDbPath.Outcome.Written)?.let {
                        cache.segmented(it.text)?.segments?.takeIf { s -> s.size == db.used.size }
                    }
                }
                for ((i, candidate) in db.used.withIndex()) {
                    val index = candidates[candidate]
                    val s = sSegments[i]
                    val d = dSegments[i]
                    if (s.source == d.source) continue
                    val rewrite = classify(s.nodes, d.nodes)
                    val block = blocks[index]
                    if (rewrite.damages) {
                        blocks[index] = AnalyzedBlock(block.block.copy(reasons = block.block.reasons + StudioRewriteReason.DB_PATH_REWRITE), block.rendered)
                        dbRewrites[index] = rewrite
                        continue
                    }
                    if (rewrite == StudioDbRewrite.ATTRIBUTE_ORDER && (anyBodyKept || db.used.size < candidates.size)) {
                        // the order depends on every element of the document, and the emulated document lacks some blocks
                        unverified += index
                        continue
                    }
                    val textPathKeeps = StudioPostProcess.padBlankLinesInComponents(StudioJs.trim(StudioRenderer.renderNodes(d.nodes))) == d.source
                    val dbPathKeeps = again?.let { it[i].source == d.source } ?: isolatedDbFixedPoint(d.source, options.sortProps, cache)
                    if (textPathKeeps && dbPathKeeps) {
                        blocks[index] = AnalyzedBlock(block.block.copy(studioOutput = d.source), d.source + "\n\n")
                        adopted++
                    } else {
                        unstable[index] = rewrite
                    }
                }
            }
        }

        // the front matter
        var fmStatus = when {
            fm == null -> StudioFrontMatterStatus.NONE
            fm.block.kept -> StudioFrontMatterStatus.KEPT
            else -> StudioFrontMatterStatus.REDUMPED
        }
        var synthetic: AnalyzedBlock? = null
        var fmUnverified = false
        if (fm == null || !fm.block.kept) {
            when (options.frontMatter) {
                StudioFrontMatterMode.KEEP -> if (fm != null) {
                    blocks[fmIndex] = AnalyzedBlock(fm.block.copy(studioOutput = fm.block.source), null)
                    fmStatus = StudioFrontMatterStatus.COPIED
                }
                StudioFrontMatterMode.REDUMP_ONLY -> {}
                StudioFrontMatterMode.STUDIO_RULES -> if (fm == null || fmUsable) {
                    // a body starting with `---` (a thematic break) reads as front matter until it is kept below
                    val leadingBreak = fm == null && state.assembled.text.startsWith("---")
                    val exact = db?.frontMatterData?.takeIf { !anyBodyKept && db.used.size == candidates.size && !leadingBreak }
                    val approximation = if (dbResult.noDocument || exact != null) {
                        null
                    } else {
                        val probe = blocks.toMutableList<AnalyzedBlock>()
                        var probeText = StudioFormatter.assemble(probe)
                        repeat(3) {
                            val culprit = StudioFormatter.frontMatterCulprit(probe, probeText) ?: return@repeat
                            probe[culprit] = AnalyzedBlock(probe[culprit].block.copy(reasons = probe[culprit].block.reasons + StudioRewriteReason.LEADING_THEMATIC_BREAK), probe[culprit].rendered)
                            probeText = StudioFormatter.assemble(probe)
                        }
                        approximateFrontMatter(probeText.text, collection)
                    }
                    val (data, status) = when {
                        dbResult.noDocument -> null to StudioFrontMatterStatus.NO_STUDIO_DOCUMENT
                        exact != null -> exact to StudioFrontMatterStatus.STUDIO_RULES
                        else -> approximation!!.data to approximation.status
                    }
                    if (status != null) fmStatus = status
                    if (approximation?.injects == true && collection.type == StudioCollection.Type.PAGE && headingBlocksKept(blocks)) {
                        // the title or description may come from a kept block, which Nuxt Content can read differently
                        fmUnverified = true
                    }
                    if (data != null) {
                        val yaml = StudioRenderer.frontmatterYaml(data)
                        val output = yaml?.let { "---\n$it\n---" } ?: ""
                        if (fm != null) {
                            blocks[fmIndex] = AnalyzedBlock(fm.block.copy(studioOutput = output), null)
                        } else if (output.isNotEmpty()) {
                            synthetic = AnalyzedBlock(StudioBlock(StudioBlockKind.FRONTMATTER, 0, 0, "", emptySet(), output), null)
                        }
                        if (output.isEmpty() && fm == null) fmStatus = StudioFrontMatterStatus.NONE
                    }
                }
            }
        }
        val shift = if (synthetic != null) 1 else 0
        val all = (listOfNotNull(synthetic) + blocks).toMutableList()
        val fmWritten = all.any { it.block.kind == StudioBlockKind.FRONTMATTER && (it.block.kept || it.block.studioOutput?.isNotEmpty() == true) }
        if (fmWritten) {
            // with front matter written first, a leading `---` break is Studio's own form again
            for ((i, b) in all.withIndex()) {
                if (b.block.reasons == setOf(StudioRewriteReason.LEADING_THEMATIC_BREAK)) {
                    all[i] = AnalyzedBlock(b.block.copy(reasons = emptySet()), b.rendered)
                }
            }
        }
        var assembled = StudioFormatter.assemble(all)
        repeat(3) {
            val culprit = StudioFormatter.frontMatterCulprit(all, assembled) ?: return@repeat
            val b = all[culprit]
            all[culprit] = AnalyzedBlock(b.block.copy(reasons = b.block.reasons + StudioRewriteReason.LEADING_THEMATIC_BREAK), b.rendered)
            unverified -= culprit - shift
            unstable -= culprit - shift
            assembled = StudioFormatter.assemble(all)
        }
        val unverifiedAll = unverified.map { it + shift }.toMutableSet()
        if (fmUnverified) all.indexOfFirst { it.block.kind == StudioBlockKind.FRONTMATTER }.takeIf { it >= 0 }?.let { unverifiedAll += it }
        return Pass(
            all, assembled,
            dbRewrites.mapKeys { it.key + shift }, unstable.mapKeys { it.key + shift }, adopted, unverifiedAll, fmStatus,
        )
    }

    private val BINDING = Regex("\\{\\{[\\s\\S]*?}}")

    /** [rendered] (a block as Studio's text path writes it) has `{{ ... }}` in its text, outside code. */
    private fun hasBinding(rendered: String): Boolean {
        if ("{{" !in rendered) return false
        val nodes = nodes(StudioJs.trim(rendered) + "\n") ?: return true
        fun visit(n: StudioNode): Boolean = when (n) {
            is StudioText -> BINDING.containsMatchIn(n.text)
            is StudioElement -> n.tag != "pre" && n.tag != "code" && n.children.any { visit(it) }
            else -> false
        }
        return nodes.any { visit(it) }
    }

    /**
     * Whether a kept block is among the blocks Nuxt Content's `contentHeading` reads the title and description from: the
     * first two body blocks that are not thematic breaks.
     */
    private fun headingBlocksKept(blocks: List<AnalyzedBlock>): Boolean = blocks
        .filter { it.block.kind == StudioBlockKind.BLOCK && (it.block.kept || StudioJs.trim(it.rendered ?: "").isNotEmpty()) }
        .filter { (if (it.block.kept) it.block.source else it.block.studioOutput) != "---" }
        .take(2)
        .any { it.block.kept }

    /**
     * The database path over a document of the front matter and some of the rendered blocks: [used] the indices of those
     * blocks (into the list passed to [databasePath]), the document's input and output segmented, and the front matter data
     * it wrote when the document had the front matter and every block.
     */
    private class DbRun(
        val used: List<Int>,
        val input: StudioSegmentedDocument,
        val outputText: String,
        val output: StudioSegmentedDocument,
        val frontMatterData: Map<String, Any?>?,
    )

    /** [run] `null` when the database path could not be emulated; [noDocument] when Studio has no document for the front matter. */
    private class DbResult(val run: DbRun?, val noDocument: Boolean)

    private fun databasePath(
        fm: AnalyzedBlock?,
        rendered: List<AnalyzedBlock>,
        collection: StudioCollection,
        sortProps: Boolean,
        cache: Memo,
    ): DbResult {
        val all = rendered.indices.toList()
        var noDocument = false
        fun attempt(withFm: Boolean, used: List<Int>): DbRun? {
            val blocks = (if (withFm && fm != null) listOf(fm) else emptyList()) + used.map { rendered[it] }
            val input = StudioFormatter.assemble(blocks).text
            return when (val outcome = cache.emulate(input, collection, sortProps)) {
                is StudioDbPath.Outcome.Written -> {
                    val inDoc = cache.segmented(input) ?: return null
                    val outDoc = cache.segmented(outcome.text) ?: return null
                    @Suppress("UNCHECKED_CAST")
                    val data = if ((withFm || fm == null) && used.size == all.size) (outDoc.frontmatter?.data as? Map<String, Any?>) ?: emptyMap() else null
                    DbRun(used, inDoc, outcome.text, outDoc, data)
                }
                is StudioDbPath.Outcome.NoDocument -> {
                    noDocument = true
                    null
                }
                is StudioDbPath.Outcome.Unsupported -> null
            }
        }
        attempt(true, all)?.let { return DbResult(it, false) }
        // leave out the blocks the database path cannot follow on their own
        val supported = all.filter { i ->
            cache.emulate(StudioJs.trim(rendered[i].rendered!!) + "\n", DATA_COLLECTION, sortProps) !is StudioDbPath.Outcome.Unsupported
        }
        val run = (if (supported.size < all.size && !noDocument) attempt(true, supported) else null) ?: attempt(false, supported)
        return DbResult(run, noDocument)
    }

    private fun segmented(text: String): StudioSegmentedDocument? = try {
        StudioTreeBuilder.buildSegmented(text)
    } catch (_: StudioTextPathException) {
        null
    } catch (_: StudioUnsupportedException) {
        null
    }

    /** D leaves [source] (one block) unchanged, emulated on the block alone. */
    private fun isolatedDbFixedPoint(source: String, sortProps: Boolean, cache: Memo): Boolean =
        (cache.emulate(source + "\n", DATA_COLLECTION, sortProps) as? StudioDbPath.Outcome.Written)?.text == source + "\n"

    /** [approximateFrontMatter]'s result; [injects]: the front matter lacks a title or a description, which the rules add. */
    private class Approximation(val data: Map<String, Any?>?, val status: StudioFrontMatterStatus?, val injects: Boolean = false)

    /**
     * Studio's front matter rules with the heading read from the text path's tree of [text]; where the text path fails on the
     * whole document (a block kept for invalid props YAML, say), from the trees of its top-level blocks.
     */
    private fun approximateFrontMatter(text: String, collection: StudioCollection): Approximation {
        val (yaml, nodes) = try {
            StudioTreeBuilder.build(text).let { it.frontmatterValue to it.nodes }
        } catch (_: StudioTextPathException) {
            null
        } catch (_: StudioUnsupportedException) {
            null
        } ?: segmented(text)?.let { doc ->
            val fm = doc.frontmatter
            if (fm != null && fm.error != null) return Approximation(null, null)
            (fm?.data ?: LinkedHashMap<String, Any?>()) to doc.segments.flatMap { it.nodes }
        } ?: return Approximation(null, null)
        val injects = yaml is Map<*, *> && (yaml["title"] == null || yaml["description"] == null)
        return try {
            Approximation(StudioDbPath.frontMatterFor(yaml, nodes, collection), StudioFrontMatterStatus.STUDIO_RULES_APPROXIMATE, injects)
        } catch (_: StudioDbFrontMatter.NoDocument) {
            Approximation(null, StudioFrontMatterStatus.NO_STUDIO_DOCUMENT)
        } catch (_: StudioDbPath.UnsupportedException) {
            Approximation(null, null)
        }
    }

    // ---- classifying a database-path rewrite ------------------------------------------------------------------------------

    /**
     * How the database path's block [d] differs from the text path's [t] (both as the text path parses them): the same
     * document written differently, or a different one. "The same" ignores what Nuxt Content's parse and Studio's writer
     * normalize without changing what the page shows: prop order, `:prop` JSON bindings, whitespace, paragraphs merged
     * with line breaks, bare URLs made links, relative `.md` links resolved, tabs in code expanded, a `#default` slot.
     */
    internal fun classify(t: List<StudioNode>, d: List<StudioNode>): StudioDbRewrite {
        if (textOf(t) != textOf(d)) return StudioDbRewrite.TEXT
        if (canonical(t, attributes = false) != canonical(d, attributes = false)) return StudioDbRewrite.STRUCTURE
        if (canonical(t, attributes = true) != canonical(d, attributes = true)) return StudioDbRewrite.ATTRIBUTES
        return if (canonical(t, attributes = true, sorted = false) != canonical(d, attributes = true, sorted = false)) {
            StudioDbRewrite.ATTRIBUTE_ORDER
        } else {
            StudioDbRewrite.MARKUP
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private val INLINE_TAGS = setOf("a", "strong", "em", "del", "span", "code", "img")

    private fun textOf(nodes: List<StudioNode>): String {
        val sb = StringBuilder()
        fun visit(n: StudioNode) {
            when (n) {
                is StudioText -> sb.append(n.text)
                is StudioComment -> {}
                is StudioElement -> {
                    if (n.tag == "br") sb.append(' ')
                    if (n.tag == "pre") (n.attrs["code"] as? String)?.let { sb.append(StudioDbBody.detab(it)).append(' ') }
                    if (n.tag == "pre" && n.attrs["code"] is String) return
                    // an element without text (an image, an empty inline component) still separates the text around it:
                    // whitespace removed next to it joins words on the page
                    if (n.children.isEmpty() && n.tag != "br") sb.append('\u0001')
                    n.children.forEach { visit(it) }
                    if (n.tag !in INLINE_TAGS) sb.append(' ')
                }
            }
        }
        nodes.forEach { visit(it) }
        return StudioJs.trim(sb.toString().replace(WHITESPACE, " "))
    }

    private fun canonical(nodes: List<StudioNode>, attributes: Boolean, sorted: Boolean = true): String {
        val sb = StringBuilder()
        fun visit(n: StudioNode) {
            when (n) {
                is StudioText -> sb.append(n.text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)"))
                is StudioComment -> {}
                is StudioElement -> when {
                    n.tag == "br" -> sb.append(' ')
                    // paragraphs: merged with line breaks in list items, unwrapped in components and slots by one parser
                    n.tag == "p" -> {
                        sb.append(' ')
                        n.children.forEach { visit(it) }
                        sb.append(' ')
                    }
                    n.tag == "template" && n.attrs["name"] == "default" && n.attrs.size == 1 -> n.children.forEach { visit(it) }
                    n.tag == "a" && isAutolink(n) -> n.children.forEach { visit(it) }
                    else -> {
                        sb.append('(').append(n.tag)
                        if (attributes) {
                            val entries = n.attrs.entries.filter { it.key != "\$" }.map { (k, v) -> canonicalAttribute(n.tag, k, v) }
                            for ((k, v) in if (sorted) entries.sortedBy { it.first } else entries) sb.append(' ').append(k).append('=').append(v)
                        }
                        sb.append(' ')
                        n.children.forEach { visit(it) }
                        sb.append(')')
                    }
                }
            }
        }
        nodes.forEach { visit(it) }
        return StudioJs.trim(sb.toString().replace(WHITESPACE, " ").replace(Regex(" ?([()]) ?"), "$1"))
    }

    private fun isAutolink(a: StudioElement): Boolean {
        val href = a.attrs["href"] as? String ?: return false
        if (a.attrs.keys.any { it != "href" }) return false
        val text = a.children.singleOrNull() as? StudioText ?: return false
        return href == text.text || href == "http://" + text.text || href == "https://" + text.text || href == "mailto:" + text.text
    }

    private fun canonicalAttribute(tag: String, key: String, value: Any?): Pair<String, String> {
        val name = key.removePrefix(":")
        var v: Any? = value
        if (v is String) {
            v = try {
                StudioJs.jsonParse(v)
            } catch (_: StudioJs.JsonSyntaxError) {
                v
            }
        }
        if (name == "class" && v is String) v = v.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        if (tag == "a" && name == "href" && v is String) v = StudioDbBody.normaliseLink(v)
        if (tag == "pre" && name == "code" && v is String) v = StudioDbBody.detab(StudioJs.trimEnd(v))
        return name to (StudioJs.jsonStringify(v) ?: "undefined")
    }

    // ---- the result -----------------------------------------------------------------------------------------------------

    private fun result(passes: List<Pass>, options: StudioStyleOptions, bom: Boolean, crlf: Boolean): StudioStyleResult {
        val last = passes.last()
        // per pass, the input lines each output line came from
        val origins = passes.map { lineOrigins(it) }
        fun inputRange(passIndex: Int, lines: IntRange): IntRange {
            var range = lines
            for (p in passIndex downTo 0) {
                val o = origins[p]
                var from = Int.MAX_VALUE
                var to = Int.MIN_VALUE
                for (l in range) o.getOrNull(l)?.let { from = minOf(from, it.first); to = maxOf(to, it.last) }
                // lines an earlier pass wrote from nothing (front matter it added): no input lines
                if (from > to) return 0 until 0
                range = from..to
            }
            return range
        }
        val owners = last.assembled.lineOwners
        val textLines = last.text.split("\n")
        fun outputLines(index: Int): IntRange {
            val first = owners.indexOf(index)
            var lastLine = owners.lastIndexOf(index)
            // a rendered block's lines include the blank line that separates it from the next one
            while (lastLine > first && textLines.getOrNull(lastLine)?.isEmpty() == true) lastLine--
            return if (first < 0) IntRange.EMPTY else first..lastLine
        }
        fun inputLines(b: StudioBlock): IntRange =
            if (passes.size == 1 || b.endLine <= b.startLine) b.startLine until b.endLine else inputRange(passes.size - 2, b.startLine until b.endLine)
        var kept = ArrayList<StudioKeptBlock>()
        for ((i, b) in last.blocks.withIndex()) {
            if (!b.block.kept) continue
            kept += StudioKeptBlock(outputLines(i), inputLines(b.block), b.block.reasons, last.dbRewrites[i])
        }
        var unstable = last.unstable.map { (i, r) -> StudioUnstableBlock(outputLines(i), r) }.sortedBy { it.outputLines.first }
        val unverified = last.unverified.sorted().map { i ->
            val b = last.blocks[i].block
            StudioUnverifiedBlock(outputLines(i), inputLines(b), b.kind == StudioBlockKind.FRONTMATTER)
        }.filter { !it.outputLines.isEmpty() }
        // the input lines of every output block; blocks from overlapping input lines (a block the next pass split) merged
        val mapping = ArrayList<StudioBlockMapping>()
        for ((i, b) in last.blocks.withIndex()) {
            val out = outputLines(i)
            if (out.isEmpty()) continue
            val inLines = inputLines(b.block)
            val previous = mapping.lastOrNull()
            if (previous != null && !inLines.isEmpty() && !previous.inputLines.isEmpty() && inLines.first <= previous.inputLines.last) {
                mapping[mapping.size - 1] = StudioBlockMapping(
                    previous.outputLines.first..out.last,
                    minOf(previous.inputLines.first, inLines.first)..maxOf(previous.inputLines.last, inLines.last),
                )
            } else {
                mapping += StudioBlockMapping(out, inLines)
            }
        }
        kept = ArrayList(kept.map { k -> if (leavesHtmlOpen(textLines, k)) k.copy(reasons = k.reasons + StudioRewriteReason.UNCLOSED_HTML_TAG) else k })
        val reported = HashSet<Int>()
        kept.forEach { reported += it.outputLines }
        unstable.forEach { reported += it.outputLines }
        unverified.forEach { reported += it.outputLines }
        var affected = affectedBlocks(last.text, mapping, reported)
        var text = last.text
        var restyled = 0
        var unverifiedOut = unverified
        var mappingOut: List<StudioBlockMapping> = mapping
        if (!options.rendersLikeStudio) {
            val r = restyle(text, kept.map { it.outputLines }, options.rendererStyle)
            text = r.text
            restyled = r.restyled
            kept = ArrayList(kept.map { it.copy(outputLines = r.map(it.outputLines)) })
            unstable = unstable.map { it.copy(outputLines = r.map(it.outputLines)) }
            unverifiedOut = unverified.map { it.copy(outputLines = r.map(it.outputLines)) }
            mappingOut = mapping.map { it.copy(outputLines = r.map(it.outputLines)) }
            affected = affected.map { a -> a.copy(outputLines = r.map(a.outputLines), causeOutputLines = a.causeOutputLines?.let { r.map(it) }) }
        }
        val diagnostics = StudioStyleDiagnostics(
            blocks = last.blocks.count { it.block.kind == StudioBlockKind.BLOCK || it.block.kept || it.block.studioOutput?.isNotEmpty() == true },
            keptBlocks = kept.size,
            dbPathKeptBlocks = kept.count { StudioRewriteReason.DB_PATH_REWRITE in it.reasons },
            dbAdoptedBlocks = passes.sumOf { it.adopted },
            unstableBlocks = unstable.size,
            dbUnverifiedBlocks = last.unverified.size,
            frontMatter = last.frontMatter,
            rounds = passes.size,
            converged = true,
            bomRemoved = bom,
            lineEndingsNormalized = crlf,
            restyledBlocks = restyled,
            affectedBlocks = affected.size,
        )
        return StudioStyleResult(text, kept, unstable, diagnostics, unverifiedOut, mappingOut, affected)
    }

    // ---- what Studio still changes around reported blocks ---------------------------------------------------------------

    /**
     * The blocks of [text] (Studio-style, before Custom options) that Studio's text path rewrites outside the [reported] lines:
     * each changed line that is not blank or reported, and each change of blank lines or insertion not next to a reported
     * line, marks the block that holds it (or the next one). The port of the text path is exact, so these are the blocks the
     * code editor will change on save because a damaged block before them runs into them.
     */
    private fun affectedBlocks(text: String, mapping: List<StudioBlockMapping>, reported: Set<Int>): List<StudioAffectedBlock> {
        val written = StudioFormatter.format(text) ?: return emptyList()
        if (written == text) return emptyList()
        val lines = text.split("\n")
        val touched = sortedSetOf<Int>()
        for (h in StudioLineDiff.hunks(lines, written.split("\n"))) {
            val deleted = h.start until h.start + h.deleted
            val content = deleted.filter { lines[it].isNotBlank() }
            if (content.isNotEmpty()) {
                content.filterTo(touched) { it !in reported }
                continue
            }
            if (!bordersReported(lines, h.start, h.deleted, reported)) touched += h.start
        }
        if (touched.isEmpty()) return emptyList()
        val result = LinkedHashMap<IntRange, StudioAffectedBlock>()
        for (line in touched) {
            val block = mapping.firstOrNull { line in it.outputLines } ?: mapping.firstOrNull { it.outputLines.first > line } ?: mapping.lastOrNull() ?: continue
            if (block.outputLines.any { it in reported } || block.outputLines in result) continue
            val cause = mapping.lastOrNull { it.outputLines.last < block.outputLines.first && it.outputLines.any { l -> l in reported } }?.outputLines
            result[block.outputLines] = StudioAffectedBlock(block.outputLines, block.inputLines, cause)
        }
        return result.values.toList()
    }

    /**
     * Whether a change of only blank lines (or an insertion) at lines `[start, start + deleted)` of [lines] sits next to a
     * [reported] block: the nearest line with content before or after it is reported (Studio's rewrite of that block adds or
     * drops the blank lines, or closes a construct the block leaves open).
     */
    internal fun bordersReported(lines: List<String>, start: Int, deleted: Int, reported: Set<Int>): Boolean {
        if ((start until start + deleted).any { it in reported }) return true
        val before = (start - 1 downTo 0).firstOrNull { lines[it].isNotBlank() }
        val after = (start + deleted until lines.size).firstOrNull { lines[it].isNotBlank() }
        return before != null && before in reported || after != null && after in reported
    }

    /** HTML elements without content: a start tag of one opens nothing. */
    private val VOID_ELEMENTS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "keygen", "link", "meta", "param", "source", "track", "wbr",
    )
    private val HTML_TAG = Regex("<(/?)([A-Za-z][A-Za-z0-9-]*)(?:\\s[^<>]*)?/?>")
    private val FENCE_LINE = Regex("^\\s{0,3}(`{3,}|~{3,})")
    private val CODE_SPAN = Regex("(`+)[\\s\\S]*?\\1")

    /**
     * Whether the kept [block], with raw HTML, opens an element that no later line of [lines] closes: HTML (parse5, which
     * rehype-raw uses in Nuxt Content) ignores `/>` on an element that is not void, so `<Counter />` opens one too, and the
     * database path then puts the rest of the document inside it.
     */
    private fun leavesHtmlOpen(lines: List<String>, block: StudioKeptBlock): Boolean {
        if (StudioRewriteReason.RAW_HTML !in block.reasons || block.outputLines.isEmpty()) return false
        val open = ArrayList<String>()
        // an end tag closes its element and every element still open inside it
        fun close(name: String) = open.lastIndexOf(name).takeIf { it >= 0 }?.let { open.subList(it, open.size).clear() }
        scanTags(lines, block.outputLines.first, block.outputLines.last + 1) { closing, name ->
            if (closing) close(name) else if (name !in VOID_ELEMENTS) open += name
        }
        if (open.isEmpty()) return false
        scanTags(lines, block.outputLines.last + 1, lines.size) { closing, name -> if (closing) close(name) }
        return open.isNotEmpty()
    }

    /** Calls [tag] for each HTML tag on lines `[from, to)` of [lines], outside code fences and code spans. */
    private fun scanTags(lines: List<String>, from: Int, to: Int, tag: (closing: Boolean, name: String) -> Unit) {
        var fence: String? = null
        for (i in from until minOf(to, lines.size)) {
            val line = lines[i]
            val marker = FENCE_LINE.find(line)?.groupValues?.get(1)
            if (fence != null) {
                if (marker != null && marker[0] == fence[0] && marker.length >= fence.length && line.trim() == marker) fence = null
                continue
            }
            if (marker != null) {
                fence = marker
                continue
            }
            for (m in HTML_TAG.findAll(line.replace(CODE_SPAN, ""))) tag(m.groupValues[1] == "/", m.groupValues[2].lowercase())
        }
    }

    /** For each output line of [pass], the input lines of the block that wrote it (`null` for separators and new lines). */
    private fun lineOrigins(pass: Pass): List<IntRange?> = pass.assembled.lineOwners.map { owner ->
        pass.blocks.getOrNull(owner)?.block?.takeIf { it.endLine > it.startLine }?.let { it.startLine until it.endLine }
    }

    // ---- Custom renderer options ----------------------------------------------------------------------------------------

    private class Restyled(val text: String, val restyled: Int, private val shifts: List<Pair<Int, Int>>) {
        /** [range] of the Studio-style text as lines of [text]. */
        fun map(range: IntRange): IntRange {
            if (range.isEmpty()) return range
            val start = range.first + shifts.filter { it.first <= range.first }.sumOf { it.second }
            // a block written again ends where its new lines end
            val end = range.last + shifts.filter { it.first <= range.last + 1 }.sumOf { it.second }
            return start..maxOf(start, end)
        }
    }

    /**
     * The top-level blocks of [text] outside [kept] written again with [style], each only where its new form parses to the
     * same tree as the Studio form.
     */
    private fun restyle(text: String, kept: List<IntRange>, style: StudioRenderer.Style): Restyled {
        val doc = segmented(text) ?: return Restyled(text, 0, emptyList())
        val lines = text.split("\n")
        val replacements = ArrayList<Pair<IntRange, String>>()
        for (segment in doc.segments) {
            val sourceLines = segment.source.split("\n")
            val start = doc.sourceLine(segment.startLine)
            val range = start until start + sourceLines.size
            if (kept.any { it.first <= range.last && range.first <= it.last }) continue
            if (range.last >= lines.size || lines.subList(range.first, range.last + 1) != sourceLines) continue
            val piece = StudioPostProcess.padBlankLinesInComponents(StudioJs.trim(StudioRenderer.renderNodes(segment.nodes, null, style)))
            if (piece == segment.source) continue
            val same = nodes(piece + "\n")?.let { it == nodes(segment.source + "\n") } ?: false
            if (same) replacements += range to piece
        }
        fun apply(chosen: List<Pair<IntRange, String>>): Restyled {
            val out = lines.toMutableList()
            val shifts = ArrayList<Pair<Int, Int>>()
            for ((range, piece) in chosen.asReversed()) {
                val newLines = piece.split("\n")
                for (i in range.reversed()) out.removeAt(i)
                out.addAll(range.first, newLines)
                shifts += (range.last + 1) to (newLines.size - (range.last - range.first + 1))
            }
            return Restyled(out.joinToString("\n"), chosen.size, shifts)
        }
        if (replacements.isEmpty()) return Restyled(text, 0, emptyList())
        // each block was checked on its own; the blocks around it can still change how it parses
        val expected = nodes(text) ?: return Restyled(text, 0, emptyList())
        val all = apply(replacements)
        if (nodes(all.text) == expected) return all
        return apply(replacements.filter { nodes(apply(listOf(it)).text) == expected })
    }

    private fun nodes(text: String): List<StudioNode>? = try {
        StudioTreeBuilder.build(text).nodes
    } catch (_: StudioTextPathException) {
        null
    } catch (_: StudioUnsupportedException) {
        null
    }
}
