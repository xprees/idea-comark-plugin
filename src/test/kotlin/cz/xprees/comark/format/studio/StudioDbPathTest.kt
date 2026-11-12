package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioCollection.ColumnType
import cz.xprees.comark.format.studio.StudioDbPath.Outcome
import cz.xprees.comark.format.studio.StudioDbPath.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Readable examples of [StudioDbPath]; [StudioDbPathDifferentialTest] checks it against Studio itself. Every expected text
 * here is what Nuxt Studio 1.8.0's database path writes (recorded with `src/test/node/studio-oracle`).
 */
class StudioDbPathTest {
    @Test
    fun titleFromTheFileName() {
        val titles = mapOf(
            "content/docs/page.md" to "Page",
            "content/docs/1.getting-started.md" to "Getting Started",
            "content/guide/2.api-reference.draft.md" to "Api Reference",
            "content/blog/2024-01-15-hello-world.md" to "2024 01 15 Hello World",
            "content/docs/my_file name.md" to "MyFile Name",
            "content/docs/1.2.0.md" to "120",
            "content/index.md" to "",
        )
        for ((file, title) in titles) {
            val base = StudioCollection.page(file).baseName
            assertEquals(file, title, StudioDbFrontMatter.generateTitle(StudioDbFrontMatter.refineUrlPart(base)))
        }
    }

    @Test
    fun pageFrontMatterIsInjected() {
        val y = "# Heading text\n\nFirst paragraph of the page.\n"
        assertEquals(
            "---\ntitle: Heading text\ndescription: First paragraph of the page.\n---\n\n$y",
            StudioDbPath.emulate(y, StudioCollection.page("content/docs/page.md")),
        )
        assertEquals("---\ntitle: Page\n---\n\n## Section\n", StudioDbPath.emulate("## Section\n", StudioCollection.page("content/docs/page.md")))
        assertEquals("## Section\n", StudioDbPath.emulate("## Section\n", StudioCollection.data("content/docs/page.md")))
    }

    @Test
    fun schemaColumnsComeFirstAndAreConverted() {
        val collection = StudioCollection.page(
            "content/index.md",
            listOf(
                StudioCollection.Field("date", ColumnType.DATE),
                StudioCollection.Field("order", ColumnType.NUMBER),
                StudioCollection.Field.withDefault("draft", ColumnType.BOOLEAN, false),
            ),
        )
        assertEquals(
            // columns first (title, then the others sorted), the meta keys after them
            "---\ntitle: T\ndate: '2024-01-15'\ndescription: Body.\ndraft: false\norder: 5\nextra: x\n---\n\nBody.\n",
            StudioDbPath.emulate("---\nextra: x\ntitle: T\ndate: '2024-01-15T10:20:30Z'\norder: '5'\n---\n\nBody.\n", collection),
        )
        assertEquals(
            Outcome.NoDocument("Invalid date value"),
            StudioDbPath.emulateOutcome("---\ndate: 2024-02-30\n---\n\nBody.\n", collection),
        )
    }

    @Test
    fun bodyTransformations() {
        val data = StudioCollection.data()
        // attributes ordered by document-wide frequency, `:count` unbound
        assertEquals(
            "::card{n=\"3\" a=\"2\" b=\"1\"}\nHi\n::\n",
            StudioDbPath.emulate("::card{b=\"1\" a=\"2\" :n=\"3\"}\nHi\n::\n", data),
        )
        // bare URLs become links, relative .md links are rewritten
        assertEquals(
            "See <https://example.com> and [guide](./guide).\n",
            StudioDbPath.emulate("See https://example.com and [guide](./guide.md).\n", data),
        )
        // loose list item paragraphs merge with a hard break; code tabs are expanded; highlights are spread
        assertEquals("- a  \nb\n- c\n", StudioDbPath.emulate("- a\n\n  b\n- c\n", data))
        assertEquals("```js { ,1,3}\na   b\n```\n", StudioDbPath.emulate("```js {1,3}\na\tb\n```\n", data))
    }

    @Test
    fun declinesWhatItDoesNotFollow() {
        fun reason(y: String) = (StudioDbPath.emulateOutcome(y, StudioCollection.data()) as Outcome.Unsupported).reason
        assertEquals(Reason.NOT_T_NORMAL, reason("Text   \n"))
        assertEquals(Reason.RAW_HTML, reason("Text <span>x</span> here\n"))
        assertEquals(Reason.HTML_COMMENT, reason("<!-- c -->\n\nText\n"))
        assertEquals(Reason.COMPONENT_HR, reason("::card\n---\n\nText\n::\n"))
        assertNull(StudioDbPath.emulate("<div>\nx\n</div>\n"))
    }

    @Test
    fun unflattensLikeFlat() {
        assertEquals(mapOf("a" to mapOf("b" to 1.0)), StudioFlat.unflatten(mapOf("a.b" to 1.0)))
        assertEquals(mapOf("a" to listOf("x")), StudioFlat.unflatten(mapOf("a.0" to "x")))
        // a nested object with an integer-like key becomes an array
        assertEquals(mapOf("m" to listOf("v")), StudioFlat.unflatten(mapOf("m" to mapOf("0" to "v"))))
    }
}
