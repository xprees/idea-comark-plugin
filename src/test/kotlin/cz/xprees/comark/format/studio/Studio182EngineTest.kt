package cz.xprees.comark.format.studio

import cz.xprees.comark.format.StudioEngine
import cz.xprees.comark.format.StudioEngine.V1_8_0
import cz.xprees.comark.format.StudioEngine.V1_8_2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release reaches every part of the engine that parses or renders: the formatter, the block-wise analysis
 * ([StudioSafety], [StudioFormatter.formatPreserving]) and the style ([StudioStyle]).
 * Each check shows output only nuxt-studio 1.8.2 (comark 0.9.2) writes, next to 1.8.0's.
 */
class Studio182EngineTest {
    private val data = StudioCollection.data()

    private fun style(text: String, release: StudioEngine) = StudioStyle.format(text, StudioStyleOptions(collection = data, release = release))!!

    @Test
    fun formatterFollowsTheRelease() {
        // comark 0.9 writes an empty link destination as <>
        assertEquals("A [x]() link\n", StudioFormatter.format("A [x]() link\n", V1_8_0))
        assertEquals("A [x](<>) link\n", StudioFormatter.format("A [x]() link\n", V1_8_2))
        // comark 0.9 keeps a heading's {#id}; 0.7 drops it
        assertEquals("## Custom\n\ntext\n", StudioFormatter.format("## Custom {#custom-anchor}\n\ntext\n", V1_8_0))
        assertEquals("## Custom {#custom-anchor}\n\ntext\n", StudioFormatter.format("## Custom {#custom-anchor}\n\ntext\n", V1_8_2))
    }

    @Test
    fun blockwiseAnalysisFollowsTheRelease() {
        assertEquals("A [x](<>) link\n", StudioFormatter.formatPreserving("A [x]() link\n", V1_8_2)!!.text)
        assertEquals("A [x]() link\n", StudioFormatter.formatPreserving("A [x]() link\n", V1_8_0)!!.text)
        // the blocks are rendered as one render of the document: the second `## A` gets comark 0.9's id `a-1` from the parse,
        // which the render counts as its own, so no {#a-1} is written
        val repeated = "# T\n\n## A\n\ntext\n\n## A\n"
        assertEquals(repeated, StudioFormatter.format(repeated, V1_8_2))
        assertEquals(repeated, StudioFormatter.formatBlockwise(repeated, V1_8_2))
        val result = StudioFormatter.formatPreserving(repeated, V1_8_2)!!
        assertEquals(repeated, result.text)
        assertTrue(result.kept.isEmpty())
    }

    @Test
    fun referenceDefinitionsAreBlocksOfTheirOwn() {
        // markdown-exit 2 strips a reference definition before comark sees it, so Studio 1.8.2 writes nothing for it
        val text = "Intro.\n\n[ref]: /url\n\nSee [ref] here.\n"
        assertEquals("Intro.\n\nSee [ref] here.\n", StudioFormatter.format(text, V1_8_2))
        val blocks = StudioSafety.classify(text, V1_8_2)!!
        assertEquals(listOf(0 to 1, 2 to 3, 4 to 5), blocks.map { it.startLine to it.endLine })
        assertEquals(setOf(StudioRewriteReason.LINK_REFERENCE_DEFINITION, StudioRewriteReason.DROPPED_BLOCK), blocks[1].reasons)
        assertEquals(listOf(false, true, false), blocks.map { it.kept })
        assertEquals(text, StudioFormatter.formatPreserving(text, V1_8_2)!!.text)
    }

    @Test
    fun styleFollowsTheRelease() {
        assertEquals("A [x](<>) link\n", style("A [x]() link\n", V1_8_2).text)
        assertEquals("A [x]() link\n", style("A [x]() link\n", V1_8_0).text)
    }
}
