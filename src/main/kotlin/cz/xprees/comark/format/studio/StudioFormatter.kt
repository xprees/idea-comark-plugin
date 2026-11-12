package cz.xprees.comark.format.studio

import cz.xprees.comark.format.JsText

/**
 * The Nuxt Studio text-path formatter T(x): what Nuxt Studio 1.8.0 writes when a document is saved from its code editor
 * (`contentFromMarkdownDocument(documentFromContent(x))`), computed without Node: [StudioTreeBuilder] builds the comark
 * tree and [StudioPipeline] renders it.
 */
object StudioFormatter {
    /** T([text]), or `null` when Studio's text path would throw (invalid YAML, for example) or this port cannot follow it. */
    fun format(text: String): String? = try {
        StudioPipeline.render(StudioTreeBuilder.build(text))
    } catch (_: StudioTextPathException) {
        null
    } catch (_: StudioUnsupportedException) {
        null
    } catch (_: StackOverflowError) {
        // a backstop: trees deeper than StudioTreeBuilder.MAX_TREE_DEPTH are declined before rendering
        null
    }

    /** [formatPreserving]'s result: the formatted [text] and the document's blocks, with the reasons for those kept verbatim. */
    data class PreservingResult(val text: String, val blocks: List<StudioBlock>) {
        val kept: List<StudioBlock> get() = blocks.filter { it.kept }
    }

    /**
     * Studio's rendering of [text], except that every block [StudioSafety] classifies (content Studio damages, or a rewrite
     * that does not converge) is copied from the source unchanged (newlines normalized to `\n`, trailing blank lines
     * dropped). The front matter and each top-level block are rendered on their own and joined as Studio joins them: the
     * front matter, a blank line, then the blocks' output concatenated (a kept block set off by a blank line, or by the
     * source's own blank lines from a kept block before it), trimmed at both ends, and a final newline. `padBlankLinesInComponents` runs over the result but never changes a kept block.
     *
     * The result is checked by formatting it again: a block whose output would change on the next pass (because of what
     * surrounds it) is kept as well, with [StudioRewriteReason.NOT_IDEMPOTENT], until the result is a fixed point.
     *
     * When that does not settle within [MAX_REPAIRS] rounds, every block whose output differs from its source is kept.
     *
     * Where nothing is kept, the text is exactly [format]'s. Invalid YAML in the front matter or in a component's props
     * keeps that block instead of failing; `null` only when the document cannot be parsed at all.
     */
    fun formatPreserving(text: String): PreservingResult? = try {
        formatPreservingChecked(text)
    } catch (_: StackOverflowError) {
        // a backstop: blocks deeper than StudioTreeBuilder.MAX_TREE_DEPTH are kept without rendering them
        null
    }

    private fun formatPreservingChecked(text: String): PreservingResult? {
        val state = preservingState(text) ?: return null
        return PreservingResult(state.assembled.text, state.blocks.map { it.block })
    }

    /** [formatPreserving]'s final decision: the blocks (kept or rendered) and their assembly. */
    internal class PreservingState(val blocks: List<StudioSafety.AnalyzedBlock>, val assembled: Assembled)

    /** [formatPreserving] with the analyzed blocks it settled on, for [StudioStyle]; `null` when [text] cannot be parsed. */
    internal fun preservingState(text: String, cache: StudioSafety.Cache? = null, forcedLines: Set<Int> = emptySet()): PreservingState? {
        val analysis = StudioSafety.analyze(text, cache) ?: return null
        var blocks = force(analysis.blocks, forcedLines)
        for (round in 0 until MAX_REPAIRS) {
            blocks = keepLeadingBreak(blocks)
            val assembled = assemble(blocks)
            // analyze is deterministic: when nothing changed, the next pass is this one
            val again = if (round == 0 && assembled.text == text) {
                text
            } else {
                StudioSafety.analyze(assembled.text, cache)?.let { assemble(keepLeadingBreak(force(it.blocks, forcedLines(blocks, assembled)))).text }
            }
            if (again == assembled.text) return PreservingState(blocks, assembled)
            val culprit = culprit(blocks, assembled, firstDifferentLine(assembled.text, again ?: "")) ?: break
            blocks = blocks.mapIndexed { i, b -> if (i != culprit) b else notIdempotent(b) }
        }
        // no fixed point found by single repairs: keep every block Studio would change
        blocks = blocks.map { b -> if (!b.block.kept && b.block.studioOutput != b.block.source) notIdempotent(b) else b }
        return PreservingState(blocks, assemble(blocks))
    }

    /** [blocks] with every block that holds one of [lines] kept with [StudioRewriteReason.FORMATTER_OFF]. */
    internal fun force(blocks: List<StudioSafety.AnalyzedBlock>, lines: Set<Int>): List<StudioSafety.AnalyzedBlock> {
        if (lines.isEmpty()) return blocks
        return blocks.map { b ->
            val range = b.block.startLine until maxOf(b.block.endLine, b.block.startLine + 1)
            if (range.none { it in lines }) b
            else StudioSafety.AnalyzedBlock(b.block.copy(reasons = b.block.reasons + StudioRewriteReason.FORMATTER_OFF), b.rendered)
        }
    }

    /** The lines of [assembled] that blocks kept with [StudioRewriteReason.FORMATTER_OFF] wrote. */
    internal fun forcedLines(blocks: List<StudioSafety.AnalyzedBlock>, assembled: Assembled): Set<Int> {
        val lines = HashSet<Int>()
        for ((l, owner) in assembled.lineOwners.withIndex()) {
            if (owner >= 0 && StudioRewriteReason.FORMATTER_OFF in blocks[owner].block.reasons) lines += l
        }
        return lines
    }

    /**
     * When [assembled] has no front matter of its own but starts with a `---` line that a later `---` line closes (a thematic
     * break Studio writes as `---`): the block to keep verbatim, the rendered one that writes the first line, else the one
     * that writes the closing line. Nuxt Content and Studio would read the lines between as front matter.
     */
    /**
     * [blocks] with a leading thematic break kept ([StudioRewriteReason.LEADING_THEMATIC_BREAK]) where their assembly would
     * otherwise start with a `---` line that a later `---` line closes, which the next save reads as front matter.
     */
    internal fun keepLeadingBreak(blocks: List<StudioSafety.AnalyzedBlock>): List<StudioSafety.AnalyzedBlock> {
        var result = blocks
        repeat(3) {
            val culprit = frontMatterCulprit(result, assemble(result)) ?: return result
            result = result.mapIndexed { i, b ->
                if (i != culprit) b else StudioSafety.AnalyzedBlock(b.block.copy(reasons = b.block.reasons + StudioRewriteReason.LEADING_THEMATIC_BREAK), b.rendered)
            }
        }
        return result
    }

    internal fun frontMatterCulprit(blocks: List<StudioSafety.AnalyzedBlock>, assembled: Assembled): Int? {
        val fm = blocks.firstOrNull { it.block.kind == StudioBlockKind.FRONTMATTER }
        if (fm != null && (fm.block.kept || fm.block.studioOutput?.isNotEmpty() == true)) return null
        val text = assembled.text
        if (!text.startsWith("---")) return null
        val close = text.indexOf("\n---")
        if (close < 0) return null
        val closeLine = text.substring(0, close + 1).count { it == '\n' }
        for (line in intArrayOf(0, closeLine)) {
            val owner = assembled.lineOwners.getOrNull(line) ?: continue
            if (owner >= 0 && blocks[owner].block.kind == StudioBlockKind.BLOCK && !blocks[owner].block.kept) return owner
        }
        return null
    }

    private fun notIdempotent(b: StudioSafety.AnalyzedBlock) =
        StudioSafety.AnalyzedBlock(b.block.copy(reasons = b.block.reasons + StudioRewriteReason.NOT_IDEMPOTENT), b.rendered)

    /**
     * The block to keep when the next pass first changes output line [line]: the block that wrote the line, or, for a
     * separator line between blocks, the nearest block that is not kept yet, the one after the line on a tie. `null` when
     * every block is kept.
     */
    private fun culprit(blocks: List<StudioSafety.AnalyzedBlock>, assembled: Assembled, line: Int): Int? {
        assembled.lineOwners.getOrNull(line)?.takeIf { it >= 0 && !blocks[it].block.kept }?.let { return it }
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        for ((l, owner) in assembled.lineOwners.withIndex()) {
            if (owner < 0 || blocks[owner].block.kept) continue
            val distance = if (l >= line) l - line else line - l
            if (distance < bestDistance || distance == bestDistance && l > line) {
                best = owner
                bestDistance = distance
            }
        }
        return best ?: blocks.indices.firstOrNull { !blocks[it].block.kept }
    }

    private const val MAX_REPAIRS = 16

    private val separations = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Boolean>()

    /** Whether [a] and [b], two top-level blocks, stay those two blocks with one blank line between them. */
    private fun staySeparate(a: String, b: String): Boolean {
        if (separations.size > 4096) separations.clear()
        return separations.getOrPut(a to b) {
            try {
                val segments = StudioTreeBuilder.buildSegmented(a + "\n\n" + b + "\n").let { if (it.frontmatter != null) null else it.segments }
                segments != null && segments.size == 2 && segments[0].source == a && segments[1].source == b
            } catch (_: StudioTextPathException) {
                false
            } catch (_: StudioUnsupportedException) {
                false
            }
        }
    }

    /**
     * Every block rendered as Studio renders it, none kept, assembled the way [formatPreserving] assembles blocks. Equal to
     * [format] wherever Studio's text path succeeds; the tests use it to show that block-wise rendering is Studio's.
     */
    internal fun formatBlockwise(text: String): String? {
        val analysis = StudioSafety.analyze(text) ?: return null
        if (analysis.blocks.any { it.block.kind == StudioBlockKind.FRONTMATTER && it.block.studioOutput == null }) return null
        if (StudioRewriteReason.INVALID_YAML in analysis.blocks.flatMap { it.block.reasons }) return null
        val blocks = analysis.blocks.map { StudioSafety.AnalyzedBlock(it.block.copy(reasons = emptySet()), it.rendered) }
        return assemble(blocks).text
    }

    private fun firstDifferentLine(a: String, b: String): Int {
        val la = a.split("\n")
        val lb = b.split("\n")
        var i = 0
        while (i < la.size && i < lb.size && la[i] == lb[i]) i++
        return i
    }

    /** The assembled text and, per output line, the index of the block that wrote it (-1 for separators). */
    internal class Assembled(val text: String, val lineOwners: List<Int>)

    /**
     * Joins [blocks] as [formatPreserving] does: the front matter's [StudioBlock.studioOutput] (or its source when kept),
     * then the body blocks' renderings (or their sources when kept).
     */
    internal fun assemble(blocks: List<StudioSafety.AnalyzedBlock>): Assembled {
        // the body: rendered blocks concatenated as comark concatenates its top-level output, kept blocks in between
        val body = StringBuilder()
        val owners = ArrayList<Pair<IntRange, Int>>() // character ranges of the body and the block that wrote them
        val kept = ArrayList<IntRange>()
        var previous: StudioBlock? = null
        for ((index, b) in blocks.withIndex()) {
            if (b.block.kind != StudioBlockKind.BLOCK) continue
            val start = body.length
            val before = previous
            previous = b.block
            if (b.block.kept) {
                if (before?.kept == true) {
                    // between two kept blocks, one blank line as Studio writes, where the source has any; the source's own
                    // blank lines where one would join the two (an empty list item, then a list), and between the blocks of a
                    // formatter off/on region
                    body.setLength(body.length - 2)
                    val gap = maxOf(b.block.startLine - before.endLine, 0)
                    val bothOff = StudioRewriteReason.FORMATTER_OFF in before.reasons && StudioRewriteReason.FORMATTER_OFF in b.block.reasons
                    val blankLines = if (gap > 1 && !bothOff && staySeparate(before.source, b.block.source)) 1 else gap
                    repeat(blankLines + 1) { body.append('\n') }
                } else if (body.isNotEmpty()) {
                    while (!body.endsWith("\n\n")) body.append('\n')
                }
                val from = body.length
                body.append(b.block.source)
                kept += from until body.length
                owners += (from until body.length) to index
                body.append("\n\n")
            } else {
                body.append(b.rendered ?: "")
                owners += (start until body.length) to index
            }
        }
        fun isKept(i: Int) = kept.any { i in it }
        var from = 0
        while (from < body.length && JsText.isWhitespace(body[from]) && !isKept(from)) from++
        var to = body.length
        while (to > from && JsText.isWhitespace(body[to - 1]) && !isKept(to - 1)) to--

        val fm = blocks.firstOrNull { it.block.kind == StudioBlockKind.FRONTMATTER }
        val fmIndex = blocks.indexOf(fm)
        val prefix = when {
            fm == null -> ""
            fm.block.kept -> fm.block.source + "\n\n"
            else -> fm.block.studioOutput?.takeIf { it.isNotEmpty() }?.let { it + "\n\n" } ?: ""
        }
        val out = prefix + body.substring(from, to)
        // owner and frozen flag of every output line
        val lineStarts = ArrayList<Int>().apply { add(0); out.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        val lineOwners = MutableList(lineStarts.size) { -1 }
        val frozen = BooleanArray(lineStarts.size)
        fun lineOf(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        if (prefix.isNotEmpty()) {
            for (l in 0..lineOf(prefix.length - 2)) {
                lineOwners[l] = fmIndex
                frozen[l] = fm?.block?.kept == true
            }
        }
        for ((range, index) in owners) {
            val a = maxOf(range.first, from) - from + prefix.length
            val z = minOf(range.last + 1, to) - from + prefix.length
            if (z <= a) continue
            for (l in lineOf(a)..lineOf(z - 1)) lineOwners[l] = index
        }
        for (range in kept) {
            val a = range.first - from + prefix.length
            val z = range.last + 1 - from + prefix.length
            if (z <= a) continue
            for (l in lineOf(a)..lineOf(z - 1)) frozen[l] = true
        }
        val original = out.split("\n")
        val padded = StudioPostProcess.padBlankLinesInComponents(out).split("\n").toMutableList()
        for (l in padded.indices) if (frozen[l]) padded[l] = original[l]
        return Assembled(padded.joinToString("\n") + "\n", lineOwners)
    }
}
