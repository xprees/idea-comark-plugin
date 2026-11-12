package cz.xprees.comark.format

import cz.xprees.comark.format.studio.StudioFormatter
import cz.xprees.comark.format.studio.StudioOracleCases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StudioStyleAdapter] and the custom options ([StudioStyleRewriter]). */
class StudioStyleAdapterTest {
    private fun format(text: String, options: StudioStyleOptions = StudioStyleOptions.STUDIO): String =
        StudioStyleAdapter.format(text, StudioStyleContext("page.md", null, options))!!.text

    @Test
    fun nuxtStudioStyleIsTheEnginesPreservingTextPath() {
        val text = "# Title\nSome *text*\n* a\n* b\n\n<div>raw</div>\n\n::card{a=\"1\" b=\"2\" c=\"3\" d=\"4\"}\nbody\n::\n"
        val result = StudioStyleAdapter.format(text, StudioStyleContext("page.md"))!!
        assertEquals(StudioFormatter.formatPreserving(text)!!.text, result.text)
        assertEquals(
            "# Title\n\nSome *text*\n\n- a\n- b\n\n<div>raw</div>\n\n::card\n---\na: '1'\nb: '2'\nc: '3'\nd: '4'\n---\nbody\n::\n",
            result.text,
        )
        // the raw HTML block is kept, with a reason and a message
        val kept = result.keptBlocks.single()
        assertEquals(5..5, kept.lines)
        assertEquals(listOf("RAW_HTML"), kept.reasons)
        assertTrue(kept.message, kept.message.contains("raw HTML"))
        assertEquals(listOf(0..0, 1..1, 2..3, 5..5, 7..9), result.blocks.map { it.first..it.last })
    }

    @Test
    fun unreadableDocument() {
        // a component prop YAML Studio cannot load keeps that block; only a document that cannot be parsed at all gives null
        val result = StudioStyleAdapter.format("---\na: [\n---\n# x\n", StudioStyleContext("page.md"))!!
        assertEquals(listOf("INVALID_YAML"), result.keptBlocks.single().reasons)
    }

    @Test
    fun listMarkers() {
        val options = StudioStyleOptions(bulletMarker = '*', orderedDelimiter = ')')
        assertEquals("* one\n* two\n  1) nested\n  2) more\n", format("- one\n- two\n  1. nested\n  2. more\n", options))
        assertEquals("+ a\n\n> + quoted\n", format("- a\n\n> - quoted\n", StudioStyleOptions(bulletMarker = '+')))
        // escaped text that looks like a marker is not a list item
        assertEquals("\\- not a list\n", format("\\- not a list\n", options))
    }

    @Test
    fun emphasisMarkers() {
        val options = StudioStyleOptions(emphasisMarker = '_', strongMarker = '_')
        assertEquals(
            "Some _em_, __strong__ and ___both___, snake*case* and `code *x*` and \\*literal\\*.\n",
            format("Some *em*, **strong** and ***both***, snake*case* and `code *x*` and \\*literal\\*.\n", options),
        )
        // only one of the two
        assertEquals("_em_ and **strong**\n", format("*em* and **strong**\n", StudioStyleOptions(emphasisMarker = '_')))
        assertEquals("*em* and __strong__\n", format("*em* and **strong**\n", StudioStyleOptions(strongMarker = '_')))
        // link destinations and attributes are not text
        assertEquals("[_a_](https://x.dev/*y*) :badge{label=\"*z*\"}\n", format("[*a*](https://x.dev/*y*) :badge{label=\"*z*\"}\n", options))
        // across the lines of a paragraph
        assertEquals("one _two\nthree_ four\n", format("one *two\nthree* four\n", options))
    }

    @Test
    fun fenceMarker() {
        val options = StudioStyleOptions(fenceMarker = '~')
        assertEquals("~~~ts\nconst a = 1\n~~~\n", format("```ts\nconst a = 1\n```\n", options))
        // longer than any tilde run inside
        assertEquals("~~~~\n~~~\n~~~~\n", format("```\n~~~\n```\n", options))
        // inside a component
        assertEquals("::c\n~~~js\nx\n~~~\n::\n", format("::c\n```js\nx\n```\n::\n", options))
    }

    @Test
    fun indentSize() {
        val text = "::outer\ntext\n:::inner\ninner text\n::::deep\ndeep text\n::::\n:::\n::\n"
        assertEquals("::outer\ntext\n\n  :::inner\n  inner text\n\n    ::::deep\n    deep text\n    ::::\n  :::\n::\n", format(text))
        assertEquals(
            "::outer\ntext\n\n    :::inner\n    inner text\n\n        ::::deep\n        deep text\n        ::::\n    :::\n::\n",
            format(text, StudioStyleOptions(indentSize = 4)),
        )
    }

    @Test
    fun blankLines() {
        val text = "# A\n\n\n\nfirst\n\nsecond\n\n\n- item\n"
        assertEquals("# A\n\nfirst\n\nsecond\n\n- item\n", format(text))
        assertEquals("# A\n\n\nfirst\n\nsecond\n\n\n- item\n", format(text, StudioStyleOptions(maxBlankLines = 2)))
        assertEquals("# A\n\n\n\nfirst\n\nsecond\n\n\n- item\n", format(text, StudioStyleOptions(maxBlankLines = 5)))
    }

    @Test
    fun props() {
        val text = "::card{title=\"x\" icon=\"y\"}\nbody\n::\n"
        assertEquals(text, format(text))
        assertEquals("::card{icon=\"y\" title=\"x\"}\nbody\n::\n", format(text, StudioStyleOptions(sortProps = true)))
        assertEquals("::card\n---\ntitle: x\nicon: 'y'\n---\nbody\n::\n", format(text, StudioStyleOptions(propsInlineMax = 1)))
        assertEquals("::card\n---\nicon: 'y'\ntitle: x\n---\nbody\n::\n", format(text, StudioStyleOptions(propsInlineMax = 0, sortProps = true)))
        // bound props, booleans, ids and classes as Studio writes them in YAML
        assertEquals(
            "::c\n---\nflag: true\n'n': 3\nclass: a b\nid: i\n---\nx\n::\n",
            format("::c{flag :n=\"3\" .a.b #i}\nx\n::\n", StudioStyleOptions(propsInlineMax = 0)),
        )
        // a YAML props block is sorted by its top-level keys
        assertEquals(
            "::d\n---\nalpha:\n  - 1\n  - 2\nzeta: 1\n---\ny\n::\n",
            format("::d\n---\nzeta: 1\nalpha: [1, 2]\n---\ny\n::\n", StudioStyleOptions(sortProps = true)),
        )
    }

    @Test
    fun frontMatter() {
        val text = "---\ntitle:   Hello\ntags: [a, b]\n---\n# Hello\n"
        assertEquals("---\ntitle: Hello\ntags:\n  - a\n  - b\n---\n\n# Hello\n", format(text))
        assertEquals("---\ntitle:   Hello\ntags: [a, b]\n---\n\n# Hello\n", format(text, StudioStyleOptions(keepFrontMatter = true)))
    }

    @Test
    fun keptBlocksAreNeverRewritten() {
        val text = "* a\n\n<div>\n* raw *html*\n</div>\n"
        val result = format(text, StudioStyleOptions(bulletMarker = '+', emphasisMarker = '_'))
        assertTrue(result, result.startsWith("+ a\n"))
        assertTrue(result, result.contains("<div>\n* raw *html*\n</div>"))
    }

    /**
     * Reformatting again gives the same text: every document of the oracle corpus and the random documents, in the custom style
     * with several option sets, is stable wherever the Nuxt Studio style itself is.
     */
    @Test
    fun customStyleIsStable() {
        val inputs = StudioOracleCases.cases.map { it["input"].asString } + StudioOracleCases.textVectors.map { it["input"].asString }
        fun unstable(options: StudioStyleOptions): Set<String> = inputs.filterTo(HashSet()) { input ->
            val once = StudioStyleAdapter.format(input, StudioStyleContext("page.md", null, options)) ?: return@filterTo false
            StudioStyleAdapter.format(once.text, StudioStyleContext("page.md", null, options))?.text != once.text
        }
        val studio = unstable(StudioStyleOptions.STUDIO)
        val optionSets = listOf(
            StudioStyleOptions(
                indentSize = 4, bulletMarker = '*', orderedDelimiter = ')', emphasisMarker = '_', strongMarker = '_',
                fenceMarker = '~', maxBlankLines = 2, propsInlineMax = 1, sortProps = true, keepFrontMatter = true,
            ),
            StudioStyleOptions(bulletMarker = '+', emphasisMarker = '_', propsInlineMax = 0, sortProps = true),
            StudioStyleOptions(indentSize = 3, strongMarker = '_', maxBlankLines = 3),
        )
        for (options in optionSets) {
            val extra = unstable(options) - studio
            assertTrue(
                "unstable in $options:\n" + extra.take(3).joinToString("\n=====\n") { input ->
                    val once = StudioStyleAdapter.format(input, StudioStyleContext("page.md", null, options))!!.text
                    "$input\n----- once\n$once\n----- again\n" + StudioStyleAdapter.format(once, StudioStyleContext("page.md", null, options))?.text
                },
                extra.isEmpty(),
            )
        }
    }
}
