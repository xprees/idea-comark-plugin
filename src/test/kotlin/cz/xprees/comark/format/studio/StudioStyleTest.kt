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

    private val data = StudioCollection.data("content/data/a.md")
    private val dataOptions = StudioStyleOptions(collection = data)

    private fun keptReasons(r: StudioStyleResult) = r.keptBlocks.map { it.reasons }

    @Test
    fun taskItemsWithANestedListAreKept() {
        // Studio's text path writes "- [ ] - Nested item": the item's text is lost and a ticked box unticked
        for (x in listOf("- [ ] Buy milk\n  - Nested item\n", "- [x] Fast: :smile:\n  * Naive\n")) {
            val r = format(x, dataOptions)
            assertEquals(x, r.text)
            assertEquals(listOf(setOf(StudioRewriteReason.NESTED_TASK_LIST)), keptReasons(r))
        }
    }

    @Test
    fun malformedAttributesAreKept() {
        // the text path cuts the value at the inner quote; the database path escapes the whole component into text
        val x = "::callout{title=\"Say \"hi\" now\" icon=\"i-x\"}\nBody\n::\n"
        for (options in listOf(dataOptions, studio)) {
            val r = format(x, options)
            assertTrue(r.text, r.text.endsWith(x))
            assertEquals(listOf(setOf(StudioRewriteReason.MALFORMED_ATTRIBUTE)), keptReasons(r))
            assertEquals(0, r.diagnostics.dbAdoptedBlocks)
        }
    }

    @Test
    fun attributesAfterPlainTextAreKept() {
        val r = format("## Install {#custom} now\n\nText\n", dataOptions)
        assertEquals("## Install {#custom} now\n\nText\n", r.text)
        assertEquals(listOf(setOf(StudioRewriteReason.DETACHED_ATTRIBUTES)), keptReasons(r))
    }

    @Test
    fun aLeadingThematicBreakIsNotWrittenAsFrontMatter() {
        // Studio writes *** and ___ as ---: the first two would then enclose the code block as front matter
        val x = "***\n\n```bash [nuxt.config.ts]\n{ \"a\": 1 }\n```\n\n___\n\nText\n"
        val expected = "***\n\n```bash [nuxt.config.ts]\n{ \"a\": 1 }\n```\n\n---\n\nText\n"
        val modes = listOf(
            dataOptions,
            StudioStyleOptions(collection = page, frontMatter = StudioFrontMatterMode.KEEP),
            StudioStyleOptions(collection = page, frontMatter = StudioFrontMatterMode.REDUMP_ONLY),
        )
        for (options in modes) {
            val r = format(x, options)
            assertEquals(options.frontMatter.name, expected, r.text)
            assertEquals(listOf(setOf(StudioRewriteReason.LEADING_THEMATIC_BREAK)), keptReasons(r))
            assertEquals(0..0, r.keptBlocks.single().outputLines)
            assertEquals(StudioFrontMatterStatus.NONE, r.diagnostics.frontMatter)
            assertEquals(expected, format(r.text, options).text)
        }
        // with front matter written first, the break is Studio's ---
        assertTrue(format(x).text.startsWith("---\ntitle: Getting Started\n---\n\n---\n"))
        // a document that already starts with --- keeps the later break that would close it
        val r = format("---\n\nText\n\n***\n", dataOptions)
        assertEquals("---\n\nText\n\n***\n", r.text)
        assertTrue(r.keptBlocks.isNotEmpty())
    }

    @Test
    fun aSoftBreakTheDatabasePathRemovesIsKept() {
        // D writes "Some **bold**![img](/i.svg) after": the space between the words and the image is lost
        val r = format("Some **bold**\n![img](/i.svg) after\n", dataOptions)
        assertEquals("Some **bold**\n![img](/i.svg) after\n", r.text)
        assertEquals(listOf(setOf(StudioRewriteReason.DB_PATH_REWRITE)), keptReasons(r))
        assertEquals(StudioDbRewrite.TEXT, r.keptBlocks.single().dbRewrite)
        assertEquals(0, r.diagnostics.dbAdoptedBlocks)
        // control: a soft break between two text runs is left alone by both paths
        assertEquals(emptyList<Any>(), format("Some **bold**\nnext **word**\n", dataOptions).keptBlocks)
    }

    @Test
    fun bindingsAreKeptAndUnverifiedBlocksReported() {
        // the database path writes {{ $doc.title }} as :binding{value="$doc.title"}
        val r = format("Hello {{ \$doc.title }} world\n\nSee :notanemoji: here\n", dataOptions)
        assertEquals("Hello {{ \$doc.title }} world\n\nSee :notanemoji: here\n", r.text)
        assertEquals(listOf(setOf(StudioRewriteReason.DB_PATH_REWRITE)), keptReasons(r))
        assertEquals(StudioDbRewrite.TEXT, r.keptBlocks.single().dbRewrite)
        // the emulation does not follow the shortcode-like text: reported, not silently passed
        assertEquals(listOf(StudioUnverifiedBlock(2..2, 2..2)), r.unverifiedBlocks)
        assertEquals(1, r.diagnostics.dbUnverifiedBlocks)
    }

    @Test
    fun frontMatterRulesApplyWhenTheTextPathFailsOnAKeptBlock() {
        // the duplicate props key makes the text path throw on the whole document; the rules still apply around it
        val r = format("# Title\n\n::note\n---\na: 1\na: 2\n---\nText\n::\n")
        assertEquals("---\ntitle: Title\n---\n\n# Title\n\n::note\n---\na: 1\na: 2\n---\nText\n::\n", r.text)
        assertEquals(StudioFrontMatterStatus.STUDIO_RULES_APPROXIMATE, r.diagnostics.frontMatter)
        assertEquals(listOf(setOf(StudioRewriteReason.INVALID_YAML)), keptReasons(r))
        // nulls and empty values are dropped there too
        val withNulls = format("---\nnavigation: null\nlayout: []\ntitle: T\ndescription: D\n---\n\n::note\n---\na: 1\na: 2\n---\nText\n::\n")
        assertTrue(withNulls.text, withNulls.text.startsWith("---\ntitle: T\ndescription: D\n---\n"))
    }

    @Test
    fun frontMatterFromAKeptBlockIsReportedUnverified() {
        // Nuxt Content reads the malformed component as a paragraph and takes it as the description; the approximation cannot
        val r = format("# Title\n\n::callout{to=\"a \"q\"\"}\n```ts {1-3}\nx\n```\n::\n")
        assertEquals(StudioFrontMatterStatus.STUDIO_RULES_APPROXIMATE, r.diagnostics.frontMatter)
        val fm = r.unverifiedBlocks.single { it.frontMatter }
        assertEquals(0..2, fm.outputLines)
        // a page that already has both keys gets nothing injected, so nothing to report
        val complete = format("---\ntitle: T\ndescription: D\n---\n\n# Title\n\n::callout{to=\"a \"q\"\"}\nx\n::\n")
        assertTrue(complete.unverifiedBlocks.none { it.frontMatter })
    }

    @Test
    fun frontMatterThatIsNotAMappingIsKept() {
        // the database path spreads a string or list into numbered keys ('0': a, '1': b)
        for (x in listOf("---\n- a\n- b\n---\n\nText\n", "---\njust a string\n---\n\nText\n")) {
            val r = format(x, dataOptions)
            assertEquals(x, r.text)
            assertEquals(listOf(setOf(StudioRewriteReason.FRONTMATTER_NOT_MAPPING)), keptReasons(r))
            assertEquals(StudioFrontMatterStatus.KEPT, r.diagnostics.frontMatter)
        }
    }

    @Test
    fun oneBlankLineBetweenTwoKeptBlocks() {
        val r = format("| a | b |\n|:--|--:|\n| 1 | 2 |\n\n\n| c | d |\n|:--|--:|\n| 3 | 4 |\n", dataOptions)
        assertEquals("| a | b |\n|:--|--:|\n| 1 | 2 |\n\n| c | d |\n|:--|--:|\n| 3 | 4 |\n", r.text)
        assertEquals(listOf(0..2, 4..6), r.keptBlocks.map { it.outputLines })
        assertEquals(listOf(0..2, 5..7), r.keptBlocks.map { it.inputLines })
    }

    @Test
    fun keepLinesKeepTheirBlocksVerbatim() {
        val x = "Intro __a__\n\n\n<!-- @formatter:off -->\n# Keep #\n<!-- @formatter:on -->\n"
        val r = StudioStyle.format(x, dataOptions, setOf(3, 4, 5))!!
        assertEquals("Intro **a**\n\n<!-- @formatter:off -->\n# Keep #\n<!-- @formatter:on -->\n", r.text)
        assertTrue(r.keptBlocks.any { StudioRewriteReason.FORMATTER_OFF in it.reasons && it.inputLines == 4..4 })
        assertEquals(listOf(0..0, 3..3, 4..4, 5..5), r.blockMap.map { it.inputLines })
        assertEquals(listOf(0..0, 2..2, 3..3, 4..4), r.blockMap.map { it.outputLines })
    }

    @Test
    fun blocksTheTextPathRewritesBecauseOfAKeptBlockAreReported() {
        // Studio writes the kept HTML without the closing fence line's blank line, so the heading after it joins the backticks
        val x = "```html\n<component>\n```\n\n<component>\n</component>\n```\n\n## Markdown\n"
        val r = StudioStyle.format(x, dataOptions)!!
        assertEquals(x, r.text)
        assertEquals(listOf(4..6), r.keptBlocks.map { it.outputLines })
        assertEquals(listOf(StudioAffectedBlock(8..8, 8..8, 4..6)), r.affectedBlocks)
        assertEquals(1, r.diagnostics.affectedBlocks)

        // a component named a becomes an empty link that the next block runs into
        val a = StudioStyle.format("::a\n::\n\n> - two\n", dataOptions)!!
        assertEquals(listOf(StudioAffectedBlock(3..3, 3..3, 0..1)), a.affectedBlocks)

        // nothing is reported where the text path leaves the other blocks alone
        assertEquals(emptyList<StudioAffectedBlock>(), StudioStyle.format("<div>x</div>\n\n## Markdown\n", dataOptions)!!.affectedBlocks)
    }

    @Test
    fun anHtmlTagThatIsNeverClosedIsReported() {
        fun reasons(x: String) = StudioStyle.format(x, dataOptions)!!.keptBlocks.flatMap { it.reasons }.toSet()
        // HTML ignores the /> of a non-void element, so <Counter /> stays open to the end of the document
        assertTrue(StudioRewriteReason.UNCLOSED_HTML_TAG in reasons("<Counter :count=\"1\" />\n\nText after\n"))
        assertTrue(StudioRewriteReason.UNCLOSED_HTML_TAG in reasons("<section>\n\nText after\n"))
        // closed in the block, later in the document, by an ancestor's end tag, or void
        assertFalse(StudioRewriteReason.UNCLOSED_HTML_TAG in reasons("<div>x</div>\n\nText after\n"))
        assertFalse(StudioRewriteReason.UNCLOSED_HTML_TAG in reasons("<details>\n<summary>S</summary>\n\nBody\n\n</details>\n"))
        assertFalse(StudioRewriteReason.UNCLOSED_HTML_TAG in reasons("<component>\n  <child/>\n</component>\n\nText after\n"))
        assertFalse(StudioRewriteReason.UNCLOSED_HTML_TAG in reasons("Text with <br/>, <img src=\"/a.png\"> and `<b>` code.\n\nAfter\n"))
        // a tag inside a code fence is not HTML
        assertFalse(StudioRewriteReason.UNCLOSED_HTML_TAG in reasons("<div>x</div>\n\n```html\n<section>\n```\n"))
    }

    @Test
    fun customOptionsReachComponentsWithChildrenAtAWideIndent() {
        // with indent 4 the nested component would read as code: the block keeps Studio's two spaces but still gets the
        // props layout, instead of falling back to Studio's form (up to 3 props inline)
        val nested = "::card\n---\ntitle: A\n---\nParent text\n\n  :::badge\n  ---\n  color: red\n  ---\n  Child\n  :::\n::\n"
        val block = StudioStyleOptions(collection = data, indentSize = 4, propsBlockThreshold = 0)
        assertEquals(nested, StudioStyle.format("::card{title=\"A\"}\nParent text\n\n:::badge{color=\"red\"}\nChild\n:::\n::\n", block)!!.text)
        // already in that form: unchanged (before, the YAML blocks turned back into inline props)
        assertEquals(nested, StudioStyle.format(nested, block)!!.text)
        val deep = "::tabs{default=\"a\"}\n:::tab{label=\"A\"}\n::::alert{type=\"info\"}\nDeep\n::::\n:::\n::\n"
        assertEquals(
            "::tabs\n---\ndefault: a\n---\n  :::tab\n  ---\n  label: A\n  ---\n    ::::alert\n    ---\n    type: info\n    ---\n    Deep\n    ::::\n  :::\n::\n",
            StudioStyle.format(deep, block)!!.text,
        )
        // and the other options too
        val underscore = StudioStyleOptions(collection = data, indentSize = 4, emphasisChar = '_')
        assertEquals(
            "::card\nSome _text_\n\n  :::badge\n  Child\n  :::\n::\n",
            StudioStyle.format("::card\nSome *text*\n\n:::badge\nChild\n:::\n::\n", underscore)!!.text,
        )
        // without nested components the wide indent is used as before
        assertEquals("::card\n---\ntitle: A\n---\nBody\n::\n", StudioStyle.format("::card{title=\"A\"}\nBody\n::\n", block)!!.text)
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
