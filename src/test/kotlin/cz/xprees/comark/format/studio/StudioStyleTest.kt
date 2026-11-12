package cz.xprees.comark.format.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StudioStyle] on readable examples: the Studio style, kept blocks, the front matter modes and the Custom options. */
class StudioStyleTest {
    private val page = StudioCollection.page("content/docs/getting-started.md")
    private val studio = StudioStyleOptions(collection = page)

    private fun format(text: String, options: StudioStyleOptions = studio): StudioStyleResult =
        assertNotNull(StudioStyle.format(text, options)).let { StudioStyle.format(text, options)!! }

    /** The result is left unchanged by both Studio save paths and by formatting it again. */
    private fun assertDualFixedPoint(r: StudioStyleResult, collection: StudioCollection = page) {
        assertEquals("text path", r.text, StudioFormatter.format(r.text))
        assertEquals("database path", r.text, StudioDbPath.emulate(r.text, collection))
        assertEquals("idempotent", r.text, StudioStyle.format(r.text, StudioStyleOptions(collection = collection))!!.text)
    }

    @Test
    fun pageFrontMatterFollowsStudiosDataRules() {
        val r = format("Some *intro* text.\n\n## Usage\n\nMore.\n")
        assertEquals("---\ntitle: Getting Started\ndescription: Some intro text.\n---\n\nSome *intro* text.\n\n## Usage\n\nMore.\n", r.text)
        assertEquals(StudioFrontMatterStatus.STUDIO_RULES, r.diagnostics.frontMatter)
        assertDualFixedPoint(r)
    }

    @Test
    fun titleComesFromTheFirstHeadingAndKeysAreOrderedBySchema() {
        val r = format("---\nzeta: 1\nnavigation: true\nempty: []\nnothing: null\nalpha: two\n---\n\n# Hello world\n\nFirst paragraph.\n")
        assertEquals(
            "---\ntitle: Hello world\ndescription: First paragraph.\nzeta: 1\nalpha: two\n---\n\n# Hello world\n\nFirst paragraph.\n",
            r.text,
        )
        assertDualFixedPoint(r)
    }

    @Test
    fun dataCollectionsGetNoInjectedKeys() {
        val data = StudioCollection.data("content/data/a.md")
        val r = format("---\nb: 1\na:   2\n---\nText\n", StudioStyleOptions(collection = data))
        assertEquals("---\nb: 1\na: 2\n---\n\nText\n", r.text)
        assertDualFixedPoint(r, data)
    }

    @Test
    fun frontMatterModes() {
        val input = "---\nb: 1\na:   2\n---\nText\n"
        assertEquals(input.replace("---\nText", "---\n\nText"), format(input, studio.copy(frontMatter = StudioFrontMatterMode.KEEP)).text)
        assertEquals("---\nb: 1\na: 2\n---\n\nText\n", format(input, studio.copy(frontMatter = StudioFrontMatterMode.REDUMP_ONLY)).text)
        assertEquals(
            "---\ntitle: Getting Started\ndescription: Text\nb: 1\na: 2\n---\n\nText\n",
            format(input, studio.copy(frontMatter = StudioFrontMatterMode.STUDIO_RULES)).text,
        )
        assertEquals(StudioFrontMatterStatus.COPIED, format(input, studio.copy(frontMatter = StudioFrontMatterMode.KEEP)).diagnostics.frontMatter)
    }

    /** A bare URL: Nuxt Content links it, and the link written out is the form both save paths keep. */
    @Test
    fun theDatabasePathFormIsWrittenWhereBothPathsKeepIt() {
        val r = format("# T\n\nVisit https://example.com today.\n")
        assertTrue(r.text, r.text.endsWith("Visit <https://example.com> today.\n"))
        assertEquals(1, r.diagnostics.dbAdoptedBlocks)
        assertDualFixedPoint(r)
    }

    @Test
    fun propsAreOrderedAsTheDatabasePathOrdersThem() {
        val r = format("# T\n\n::card{z=\"1\" a=\"2\"}\nBody\n::\n")
        assertTrue(r.text, r.text.endsWith("::card{a=\"2\" z=\"1\"}\nBody\n::\n"))
        assertDualFixedPoint(r)
        val unsorted = format("# T\n\n::card{z=\"1\" a=\"2\"}\nBody\n::\n", studio.copy(sortProps = false))
        assertTrue(unsorted.text, unsorted.text.endsWith("::card{z=\"1\" a=\"2\"}\nBody\n::\n"))
    }

    /** Studio's database path renames a `data-` attribute: a different document, so the block is kept and reported. */
    @Test
    fun blocksTheDatabasePathChangesAreKept() {
        val input = "# T\n\nA [word]{data-x=\"1\"} here.\n\n**strong**{.s}\n"
        val r = format(input)
        val kept = r.keptBlocks.single { StudioRewriteReason.DB_PATH_REWRITE in it.reasons }
        assertEquals(StudioDbRewrite.ATTRIBUTES, kept.dbRewrite)
        assertEquals("A [word]{data-x=\"1\"} here.", r.text.lines()[kept.outputLines.first])
        assertEquals(2 until 3, kept.inputLines)
        assertTrue(kept.messages.single(), kept.messages.single().startsWith("Nuxt Studio will rewrite this: its database path"))
        assertEquals(r.text, StudioStyle.format(r.text, studio)!!.text)
    }

    /** A tight nested list: the two save paths write it differently, so the text-path form is written and reported. */
    @Test
    fun blocksThePathsDisagreeOnUseTheTextPathForm() {
        val r = format("# T\n\n- a\n  - b\n- c\n")
        assertTrue(r.text, r.text.endsWith("- a\n  - b\n- c\n"))
        assertEquals(StudioDbRewrite.MARKUP, r.unstableBlocks.single().dbRewrite)
        assertEquals(r.text, StudioFormatter.format(r.text))
        assertEquals(r.text, StudioStyle.format(r.text, studio)!!.text)
    }

    @Test
    fun contentStudioDamagesIsKeptWithLineRanges() {
        val input = "---\r\ntitle: T\r\n---\r\n\r\nIntro text.\r\n\r\n<div class=\"x\">\r\nraw\r\n</div>\r\n\r\nAfter.\r\n"
        val r = format(input)
        assertEquals("---\ntitle: T\ndescription: Intro text.\n---\n\nIntro text.\n\n<div class=\"x\">\nraw\n</div>\n\nAfter.\n", r.text)
        val kept = r.keptBlocks.single()
        assertEquals(setOf(StudioRewriteReason.RAW_HTML), kept.reasons)
        assertEquals(7..9, kept.outputLines)
        assertEquals(6 until 9, kept.inputLines)
        assertEquals("Nuxt Studio will rewrite this: raw HTML, which Studio's database path converts to components", kept.messages.single())
        assertTrue(r.diagnostics.lineEndingsNormalized)
    }

    @Test
    fun byteOrderMarkAndCrlfAreDropped() {
        val r = format("﻿# Title\r\n\r\nText\r\n")
        assertEquals("---\ntitle: Title\ndescription: Text\n---\n\n# Title\n\nText\n", r.text)
        assertTrue(r.diagnostics.bomRemoved)
        assertTrue(r.diagnostics.lineEndingsNormalized)
        assertFalse(format(r.text).diagnostics.bomRemoved)
    }

    @Test
    fun customRendererOptions() {
        val options = StudioStyleOptions(
            collection = StudioCollection.data(),
            indentSize = 3,
            bulletMarker = '*',
            orderedDelimiter = ')',
            emphasisChar = '_',
            strongChar = '_',
            fenceChar = '~',
            propsBlockThreshold = 1,
        )
        assertFalse(options.isStudio)
        val input = "- *a* and **b**\n- c\n\n1. one\n2. two\n\n```js\nx\n```\n\n::outer\n:::inner\ntext\n:::\n::\n\n::card{a=\"1\" b=\"2\"}\nx\n::\n"
        val r = format(input, options)
        assertEquals(
            "* _a_ and __b__\n* c\n\n1) one\n2) two\n\n~~~js\nx\n~~~\n\n::outer\n   :::inner\n   text\n   :::\n::\n\n::card\n---\na: '1'\nb: '2'\n---\nx\n::\n",
            r.text,
        )
        assertEquals(r.text, StudioStyle.format(r.text, options)!!.text)
        // where the custom form would parse differently (intraword emphasis), the Studio form is kept
        assertEquals("a*b*c\n", format("a*b*c\n", options).text)
    }

    @Test
    fun studioOptionsAreTheDefaults() {
        assertTrue(StudioStyleOptions().isStudio)
        assertTrue(StudioStyleOptions.studio(page).isStudio)
        assertFalse(StudioStyleOptions(sortProps = false).isStudio)
        assertFalse(StudioStyleOptions(frontMatter = StudioFrontMatterMode.KEEP).isStudio)
    }

    /** A realistic 2000-line document formats well within the budget once warm (target 300 ms; asserted generously). */
    @Test
    fun performance() {
        val doc = realisticDocument(2000)
        assertTrue(doc.lines().size >= 2000)
        repeat(3) { StudioStyle.format(doc, studio) }
        var best = Long.MAX_VALUE
        repeat(3) {
            val start = System.nanoTime()
            StudioStyle.format(doc, studio)
            best = minOf(best, (System.nanoTime() - start) / 1_000_000)
        }
        val formatted = StudioStyle.format(doc, studio)!!.text
        var bestFormatted = Long.MAX_VALUE
        repeat(3) {
            val start = System.nanoTime()
            StudioStyle.format(formatted, studio)
            bestFormatted = minOf(bestFormatted, (System.nanoTime() - start) / 1_000_000)
        }
        println("StudioStyle.format, ${doc.lines().size}-line document: $best ms; already formatted: $bestFormatted ms")
        println(StudioStyle.format(doc, studio)!!.diagnostics)
        assertTrue("$best ms", best < 2000)
        assertTrue("$bestFormatted ms", bestFormatted < 2000)
    }

    private fun realisticDocument(lines: Int): String {
        val sb = StringBuilder("---\ntitle: Guide\ndescription: A long guide\nnavigation: true\n---\n\n# Guide\n\nIntro paragraph.\n\n")
        var i = 0
        while (sb.count { it == '\n' } < lines) {
            i++
            sb.append(
                """
                |## Section $i
                |
                |Some text with *emphasis*, **strong**, `code` and a [link](/docs/page-$i). It wraps
                |over two lines and mentions https://example.com/$i too.
                |
                |- first item
                |- second item with **bold**
                |  - nested item
                |- third item
                |
                |1. step one
                |2. step two
                |
                |::alert{type="info" icon="i-lucide-info"}
                |An alert with a [link](https://nuxt.com) inside.
                |::
                |
                |::card
                |---
                |title: Card $i
                |to: /docs/$i
                |icon: i-lucide-box
                |color: primary
                |---
                |Card content.
                |::
                |
                |```ts [file-$i.ts]
                |export const value$i = $i
                |```
                |
                || Name | Value |
                ||------|-------|
                || a    | $i    |
                |
                |> A quote with :badge[inline]{color="red"} component.
                |
                |
                """.trimMargin(),
            )
        }
        return sb.toString()
    }
}
