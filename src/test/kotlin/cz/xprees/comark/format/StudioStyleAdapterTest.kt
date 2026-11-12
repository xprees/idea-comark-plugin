package cz.xprees.comark.format

import cz.xprees.comark.format.studio.StudioFrontMatterMode
import cz.xprees.comark.format.studio.StudioOracleCases
import cz.xprees.comark.format.studio.StudioStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StudioStyleAdapter]: the engine's Nuxt Studio style ([StudioStyle]) with the IDE's options and collection information. */
class StudioStyleAdapterTest {
    /** A data collection: no title or description is injected, so the examples show the body alone. */
    private val data = StudioCollection("data", StudioCollection.Type.DATA)

    private fun format(text: String, options: StudioStyleOptions = StudioStyleOptions.STUDIO): String =
        StudioStyleAdapter.format(text, StudioStyleContext("page.md", data, options))!!.text

    @Test
    fun nuxtStudioStyleIsTheEnginesDualFixedPoint() {
        val text = "# Title\nSome *text*\n* a\n* b\n\n<div>raw</div>\n\n::card{a=\"1\" b=\"2\" c=\"3\" d=\"4\"}\nbody\n::\n"
        val result = StudioStyleAdapter.format(text, StudioStyleContext("page.md"))!!
        assertEquals(StudioStyle.format(text, cz.xprees.comark.format.studio.StudioStyleOptions.studio(cz.xprees.comark.format.studio.StudioCollection.defaults("page.md")))!!.text, result.text)
        // a page with Studio's defaults gets the title and description Studio's database path adds
        assertEquals(
            "---\ntitle: Title\ndescription: Some text\n---\n\n# Title\n\nSome *text*\n\n- a\n- b\n\n<div>raw</div>\n\n" +
                "::card\n---\na: '1'\nb: '2'\nc: '3'\nd: '4'\n---\nbody\n::\n",
            result.text,
        )
        // the raw HTML block is kept, with a reason and a message (without the "Nuxt Studio will rewrite this" prefix)
        val kept = result.keptBlocks.single()
        assertEquals(5..5, kept.lines)
        assertEquals(listOf("RAW_HTML"), kept.reasons)
        assertEquals("raw HTML, which Studio's database path converts to components", kept.message)
        // blocks are lines of the input
        assertEquals(listOf(0..0, 1..1, 2..3, 5..5, 7..9), result.blocks.map { it.first..it.last })
    }

    @Test
    fun unreadableDocument() {
        // front matter Studio cannot load keeps that block; only a document that cannot be parsed at all gives null
        val result = StudioStyleAdapter.format("---\na: [\n---\n# x\n", StudioStyleContext("page.md"))!!
        assertEquals(listOf("INVALID_YAML"), result.keptBlocks.single().reasons)
    }

    @Test
    fun frontMatterDataRules() {
        val text = "---\ntitle:   Hello\ntags: [a, b]\nnavigation: true\nempty: []\nnone: null\n---\n# Hello\n\nIntro text.\n"
        // a page: description added, navigation: true, empty lists and nulls dropped
        assertEquals(
            "---\ntitle: Hello\ndescription: Intro text.\ntags:\n  - a\n  - b\n---\n\n# Hello\n\nIntro text.\n",
            StudioStyleAdapter.format(text, StudioStyleContext("page.md"))!!.text,
        )
        // a data collection has no title, description or navigation columns: nothing is added and navigation is plain data
        assertEquals("---\ntitle: Hello\ntags:\n  - a\n  - b\nnavigation: true\n---\n\n# Hello\n\nIntro text.\n", format(text))
        // the other front matter modes
        assertEquals(
            "---\ntitle: Hello\ntags:\n  - a\n  - b\nnavigation: true\nempty: []\nnone: null\n---\n\n# Hello\n\nIntro text.\n",
            StudioStyleAdapter.format(text, StudioStyleContext("page.md", null, StudioStyleOptions(frontMatter = FrontMatterMode.REDUMP)))!!.text,
        )
        assertEquals(
            text.replace("---\n# Hello", "---\n\n# Hello"),
            StudioStyleAdapter.format(text, StudioStyleContext("page.md", null, StudioStyleOptions(frontMatter = FrontMatterMode.KEEP)))!!.text,
        )
    }

    @Test
    fun titleFromTheFileName() {
        assertEquals(
            "---\ntitle: Getting Started\ndescription: Just text.\n---\n\nJust text.\n",
            StudioStyleAdapter.format("Just text.\n", StudioStyleContext("1.getting-started.md"))!!.text,
        )
    }

    @Test
    fun schemaOrderAndDefaults() {
        val blog = StudioCollection(
            "blog",
            StudioCollection.Type.PAGE,
            listOf(
                StudioSchemaField("date", StudioFieldType.DATE),
                StudioSchemaField("author"),
                StudioSchemaField("tags", StudioFieldType.JSON),
                StudioSchemaField("draft", StudioFieldType.BOOLEAN, true, false),
            ),
        )
        val text = "---\ntags: [x]\nzz: 1\nauthor: Me\ndate: 2024-01-02\ntitle: T\n---\n# T\n\nBody.\n"
        // id, title, then the columns sorted by name (with the schema default), then the other keys
        assertEquals(
            "---\ntitle: T\nauthor: Me\ndate: '2024-01-02'\ndescription: Body.\ndraft: false\ntags:\n  - x\nzz: 1\n---\n\n# T\n\nBody.\n",
            StudioStyleAdapter.format(text, StudioStyleContext("post.md", blog))!!.text,
        )
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
        assertEquals("_em_ and **strong**\n", format("*em* and **strong**\n", StudioStyleOptions(emphasisMarker = '_')))
        assertEquals("*em* and __strong__\n", format("*em* and **strong**\n", StudioStyleOptions(strongMarker = '_')))
        val options = StudioStyleOptions(emphasisMarker = '_', strongMarker = '_')
        // link destinations and attributes are not text
        assertEquals("[_a_](https://x.dev/*y*) :badge{label=\"*z*\"}\n", format("[*a*](https://x.dev/*y*) :badge{label=\"*z*\"}\n", options))
        // across the lines of a paragraph
        assertEquals("one _two\nthree_ four\n", format("one *two\nthree* four\n", options))
        // `_` cannot mark emphasis inside a word, so that paragraph keeps Studio's `*`
        val intraword = "Some *em* and snake*case*.\n"
        assertEquals(intraword, format(intraword, options))
    }

    @Test
    fun fenceMarker() {
        val options = StudioStyleOptions(fenceMarker = '~')
        assertEquals("~~~ts\nconst a = 1\n~~~\n", format("```ts\nconst a = 1\n```\n", options))
        // code containing a tilde fence keeps backticks
        assertEquals("```\n~~~\n```\n", format("```\n~~~\n```\n", options))
        // inside a component
        assertEquals("::c\n~~~js\nx\n~~~\n::\n", format("::c\n```js\nx\n```\n::\n", options))
    }

    @Test
    fun indentSize() {
        val text = "::outer\ntext\n:::inner\ninner text\n::::deep\ndeep text\n::::\n:::\n::\n"
        val studio = "::outer\ntext\n\n  :::inner\n  inner text\n\n    ::::deep\n    deep text\n    ::::\n  :::\n::\n"
        assertEquals(studio, format(text))
        assertEquals(
            "::outer\ntext\n\n   :::inner\n   inner text\n\n      ::::deep\n      deep text\n      ::::\n   :::\n::\n",
            format(text, StudioStyleOptions(indentSize = 3)),
        )
        // from 4 on, nested components would read as indented code: the block keeps Studio's indentation
        assertEquals(studio, format(text, StudioStyleOptions(indentSize = 4)))
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
        // Studio's database path orders the props (by frequency, then by name)
        assertEquals("::card{icon=\"y\" title=\"x\"}\nbody\n::\n", format(text))
        assertEquals(text, format(text, StudioStyleOptions(sortProps = false)))
        assertEquals("::card\n---\nicon: 'y'\ntitle: x\n---\nbody\n::\n", format(text, StudioStyleOptions(propsInlineMax = 1)))
        // more than Studio's three inline props
        assertEquals(
            "::card{a=\"1\" b=\"2\" c=\"3\" d=\"4\"}\nbody\n::\n",
            format("::card\n---\na: '1'\nb: '2'\nc: '3'\nd: '4'\n---\nbody\n::\n", StudioStyleOptions(propsInlineMax = 5)),
        )
        // bound props, booleans, ids and classes as Studio writes them in YAML
        assertEquals(
            "::c\n---\nflag: true\n'n': 3\nid: i\nclass: a b\n---\nx\n::\n",
            format("::c{flag :n=\"3\" .a.b #i}\nx\n::\n", StudioStyleOptions(propsInlineMax = 0)),
        )
    }

    @Test
    fun keptBlocksAreNeverRewritten() {
        val text = "* a\n\n<div>\n* raw *html*\n</div>\n"
        assertEquals("+ a\n\n<div>\n* raw *html*\n</div>\n", format(text, StudioStyleOptions(bulletMarker = '+', emphasisMarker = '_')))
    }

    @Test
    fun customOptionsReachTheEngine() {
        val options = StudioStyleAdapter.engineOptions(
            StudioStyleContext(
                "x.md",
                null,
                StudioStyleOptions(3, '*', ')', '_', '_', '~', 2, 5, false, FrontMatterMode.REDUMP),
            ),
        )
        assertEquals(3, options.indentSize)
        assertEquals('*', options.bulletMarker)
        assertEquals(')', options.orderedDelimiter)
        assertEquals('_', options.emphasisChar)
        assertEquals('_', options.strongChar)
        assertEquals('~', options.fenceChar)
        assertEquals(5, options.propsBlockThreshold)
        assertFalse(options.sortProps)
        assertEquals(StudioFrontMatterMode.REDUMP_ONLY, options.frontMatter)
        assertEquals("x.md", options.effectiveCollection.fileName)
        // the Nuxt Studio preset is exactly the engine's Studio style
        assertTrue(StudioStyleAdapter.engineOptions(StudioStyleContext("x.md")).isStudio)
    }

    /**
     * Reformatting again gives the same text: every document of the oracle corpus and the random documents, in the Nuxt Studio
     * style and in the custom style with several option sets (the blank line option, applied after the engine, included).
     */
    @Test
    fun customStyleIsStable() {
        val inputs = StudioOracleCases.cases.map { it["input"].asString } + StudioOracleCases.textVectors.map { it["input"].asString }
        fun unstable(options: StudioStyleOptions): Set<String> = inputs.filterTo(HashSet()) { input ->
            val once = StudioStyleAdapter.format(input, StudioStyleContext("page.md", null, options)) ?: return@filterTo false
            StudioStyleAdapter.format(once.text, StudioStyleContext("page.md", null, options))?.text != once.text
        }
        val studio = unstable(StudioStyleOptions.STUDIO)
        assertTrue("unstable in the Nuxt Studio style: " + studio.take(3), studio.isEmpty())
        val optionSets = listOf(
            StudioStyleOptions(
                indentSize = 4, bulletMarker = '*', orderedDelimiter = ')', emphasisMarker = '_', strongMarker = '_',
                fenceMarker = '~', maxBlankLines = 2, propsInlineMax = 1, sortProps = false, frontMatter = FrontMatterMode.KEEP,
            ),
            StudioStyleOptions(bulletMarker = '+', emphasisMarker = '_', propsInlineMax = 0, frontMatter = FrontMatterMode.REDUMP),
            StudioStyleOptions(indentSize = 3, strongMarker = '_', maxBlankLines = 3, propsInlineMax = 6),
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
