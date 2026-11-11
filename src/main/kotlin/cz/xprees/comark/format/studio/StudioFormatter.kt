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
    }

    /** [formatPreserving]'s result: the formatted [text] and the document's blocks, with the reasons for those kept verbatim. */
    data class PreservingResult(val text: String, val blocks: List<StudioBlock>) {
        val kept: List<StudioBlock> get() = blocks.filter { it.kept }
    }

    /**
     * Studio's rendering of [text], except that every block [StudioSafety] classifies (content Studio damages, or a rewrite
     * that does not converge) is copied from the source unchanged (newlines normalized to `\n`, trailing blank lines
     * dropped). The front matter and each top-level block are rendered on their own and joined as Studio joins them: the
     * front matter, a blank line, then the blocks' output concatenated (a kept block followed by a blank line), trimmed at
     * both ends, and a final newline. `padBlankLinesInComponents` runs over the result but never changes a kept block.
     *
     * The result is checked by formatting it again: a block whose output would change on the next pass (because of what
     * surrounds it) is kept as well, with [StudioRewriteReason.NOT_IDEMPOTENT], until the result is a fixed point.
     *
     * Where nothing is kept, the text is exactly [format]'s. Invalid YAML in the front matter or in a component's props
     * keeps that block instead of failing; `null` only when the document cannot be parsed at all.
     */
    fun formatPreserving(text: String): PreservingResult? {
        val analysis = StudioSafety.analyze(text) ?: return null
        var blocks = analysis.blocks
        repeat(MAX_REPAIRS) {
            val assembled = assemble(analysis, blocks)
            val again = StudioSafety.analyze(assembled.text)?.let { assemble(it, it.blocks).text }
            if (again == assembled.text) return PreservingResult(assembled.text, blocks.map { it.block })
            val line = firstDifferentLine(assembled.text, again ?: "")
            val culprit = assembled.lineOwners.getOrNull(line)?.takeIf { it >= 0 && !blocks[it].block.kept }
                ?: blocks.indices.firstOrNull { !blocks[it].block.kept && assembled.lineOwners.indexOf(it) > line }
                ?: blocks.indices.lastOrNull { !blocks[it].block.kept && assembled.lineOwners.indexOf(it) in 0 until line }
                ?: return PreservingResult(assembled.text, blocks.map { it.block })
            blocks = blocks.mapIndexed { i, b ->
                if (i != culprit) b else StudioSafety.AnalyzedBlock(b.block.copy(reasons = b.block.reasons + StudioRewriteReason.NOT_IDEMPOTENT), b.rendered)
            }
        }
        val assembled = assemble(analysis, blocks)
        return PreservingResult(assembled.text, blocks.map { it.block })
    }

    private const val MAX_REPAIRS = 16

    /**
     * Every block rendered as Studio renders it, none kept, assembled the way [formatPreserving] assembles blocks. Equal to
     * [format] wherever Studio's text path succeeds; the tests use it to show that block-wise rendering is Studio's.
     */
    internal fun formatBlockwise(text: String): String? {
        val analysis = StudioSafety.analyze(text) ?: return null
        if (analysis.blocks.any { it.block.kind == StudioBlockKind.FRONTMATTER && it.block.studioOutput == null }) return null
        if (StudioRewriteReason.INVALID_YAML in analysis.blocks.flatMap { it.block.reasons }) return null
        val blocks = analysis.blocks.map { StudioSafety.AnalyzedBlock(it.block.copy(reasons = emptySet()), it.rendered) }
        return assemble(analysis, blocks).text
    }

    private fun firstDifferentLine(a: String, b: String): Int {
        val la = a.split("\n")
        val lb = b.split("\n")
        var i = 0
        while (i < la.size && i < lb.size && la[i] == lb[i]) i++
        return i
    }

    /** The assembled text and, per output line, the index of the block that wrote it (-1 for separators). */
    private class Assembled(val text: String, val lineOwners: List<Int>)

    private fun assemble(analysis: StudioSafety.Analysis, blocks: List<StudioSafety.AnalyzedBlock>): Assembled {
        // the body: rendered blocks concatenated as comark concatenates its top-level output, kept blocks in between
        val body = StringBuilder()
        val owners = ArrayList<Pair<IntRange, Int>>() // character ranges of the body and the block that wrote them
        val kept = ArrayList<IntRange>()
        for ((index, b) in blocks.withIndex()) {
            if (b.block.kind != StudioBlockKind.BLOCK) continue
            val start = body.length
            if (b.block.kept) {
                if (body.isNotEmpty()) while (!body.endsWith("\n\n")) body.append('\n')
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
