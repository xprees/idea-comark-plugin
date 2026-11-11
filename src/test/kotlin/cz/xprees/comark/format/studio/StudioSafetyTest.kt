package cz.xprees.comark.format.studio

import cz.xprees.comark.format.studio.StudioRewriteReason.ADJACENT_LISTS
import cz.xprees.comark.format.studio.StudioRewriteReason.COMPONENT_IN_LIST_ITEM
import cz.xprees.comark.format.studio.StudioRewriteReason.COMPONENT_NAMED_A
import cz.xprees.comark.format.studio.StudioRewriteReason.ESCAPED_BRACE
import cz.xprees.comark.format.studio.StudioRewriteReason.FENCE_FILENAME_BRACKET
import cz.xprees.comark.format.studio.StudioRewriteReason.FENCE_HIGHLIGHTS
import cz.xprees.comark.format.studio.StudioRewriteReason.FENCE_IN_LIST_ITEM
import cz.xprees.comark.format.studio.StudioRewriteReason.FOOTNOTE
import cz.xprees.comark.format.studio.StudioRewriteReason.FRONTMATTER_BODY_KEY
import cz.xprees.comark.format.studio.StudioRewriteReason.FRONTMATTER_COMMENT
import cz.xprees.comark.format.studio.StudioRewriteReason.HEADING_ID
import cz.xprees.comark.format.studio.StudioRewriteReason.HTML_COMMENT
import cz.xprees.comark.format.studio.StudioRewriteReason.INDENTED_COMPONENT
import cz.xprees.comark.format.studio.StudioRewriteReason.INLINE_CODE_WITH_BACKTICK
import cz.xprees.comark.format.studio.StudioRewriteReason.INLINE_COMPONENT_OBJECT_PROPS
import cz.xprees.comark.format.studio.StudioRewriteReason.INVALID_YAML
import cz.xprees.comark.format.studio.StudioRewriteReason.LINK_REFERENCE_DEFINITION
import cz.xprees.comark.format.studio.StudioRewriteReason.MULTILINE_HEADING
import cz.xprees.comark.format.studio.StudioRewriteReason.MULTILINE_PROP
import cz.xprees.comark.format.studio.StudioRewriteReason.NESTED_TASK_LIST
import cz.xprees.comark.format.studio.StudioRewriteReason.NOT_IDEMPOTENT
import cz.xprees.comark.format.studio.StudioRewriteReason.RAW_HTML
import cz.xprees.comark.format.studio.StudioRewriteReason.REFERENCE_LINK
import cz.xprees.comark.format.studio.StudioRewriteReason.TABLE_ALIGNMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** [StudioSafety.classify] and [StudioFormatter.formatPreserving] on one example per [StudioRewriteReason]. */
class StudioSafetyTest {
    /** The reasons of every block of [text], in order (an empty set for a block Studio may rewrite). */
    private fun reasons(text: String): List<Set<StudioRewriteReason>> = StudioSafety.classify(text)!!.map { it.reasons }

    /** [text] has exactly one kept block, with exactly [expected] as reasons, and formatPreserving keeps it verbatim. */
    private fun assertKept(text: String, vararg expected: StudioRewriteReason) {
        val kept = StudioSafety.classify(text)!!.filter { it.kept }
        assertEquals(text, listOf(expected.toSet()), kept.map { it.reasons })
        val result = StudioFormatter.formatPreserving(text)!!
        assertEquals(kept, result.kept)
        assert(result.text.contains(kept.single().source)) { "kept block missing from\n${result.text}" }
        assertEquals("not a fixed point", result.text, StudioFormatter.formatPreserving(result.text)!!.text)
    }

    @Test
    fun keepsWhatStudioDamages() {
        assertKept("- item\n  ::note\n  in list\n  ::\n- next\n", COMPONENT_IN_LIST_ITEM)
        assertKept("Text\n\n<!-- a comment -->\n", HTML_COMMENT)
        assertKept("<div class=\"box\">\n  <p>Raw</p>\n</div>\n", RAW_HTML)
        assertKept("Text with <kbd>K</kbd> inside\n", RAW_HTML)
        assertKept("[r1]: https://example.com \"Title\"\n", LINK_REFERENCE_DEFINITION)
        assertKept("| a | b |\n|:--|--:|\n| 1 | 2 |\n", TABLE_ALIGNMENT)
        assertKept("```js {1,3}\ncode\n```\n", FENCE_HIGHLIGHTS)
        assertKept("```js [a[1\\]].js]\ncode\n```\n", FENCE_FILENAME_BRACKET)
        assertKept("## Title {#custom-id}\n", HEADING_ID)
        assertKept("Literal \\{ braces \\}\n", ESCAPED_BRACE)
        assertKept("Code ``a`b`` here\n", INLINE_CODE_WITH_BACKTICK)
        assertKept("Inline :chart{:data='{\"a\":1}'} in text\n", INLINE_COMPONENT_OBJECT_PROPS)
        assertKept("::page-section\n---\nstyles: |\n  color: red;\n\n  .x { }\n---\n::\n", MULTILINE_PROP)
        assertKept("First line\nsecond line\n===\n", MULTILINE_HEADING)
        assertKept("- [ ] Parent\n  - [ ] Child\n", NESTED_TASK_LIST)
        assertKept("- ```js\n  code\n  ```\n", FENCE_IN_LIST_ITEM)
        assertKept("::a\nx\n::\n", COMPONENT_NAMED_A)
        assertKept("::outer\n    ::inner\n    text\n    ::\n::\n", INDENTED_COMPONENT)
        assertKept("::card\n---\nkey: [\n---\nBody\n::\n", INVALID_YAML)
    }

    @Test
    fun keepsReferencesFootnotesAndTheirUses() {
        assertEquals(listOf(setOf(REFERENCE_LINK), setOf(LINK_REFERENCE_DEFINITION)), reasons("See [docs][d].\n\n[d]: https://example.com\n"))
        assertEquals(listOf(setOf(FOOTNOTE), setOf(FOOTNOTE)), reasons("Text[^1].\n\n[^1]: The note.\n"))
    }

    @Test
    fun keepsBothOfTwoNeighbouringListsOfOneKind() {
        assertEquals(listOf(setOf(ADJACENT_LISTS), setOf(ADJACENT_LISTS)), reasons("* a\n\n+ b\n"))
        assertEquals(listOf(setOf(ADJACENT_LISTS), setOf(ADJACENT_LISTS)), reasons("1) a\n2. b\n"))
        assertEquals(listOf(emptySet<StudioRewriteReason>(), emptySet()), reasons("- a\n\n1. b\n"))
    }

    @Test
    fun classifiesTheFrontMatter() {
        assertEquals(listOf(setOf(INVALID_YAML), emptySet()), reasons("---\ntitle: a\ntitle: b\n---\n\ntext\n"))
        assertEquals(listOf(setOf(FRONTMATTER_BODY_KEY), emptySet()), reasons("---\nbody: x\n---\n\ntext\n"))
        assertEquals(listOf(setOf(FRONTMATTER_COMMENT), emptySet()), reasons("---\ntitle: a # the title\n---\n\ntext\n"))
        // a `#` inside a quoted or block scalar is no comment
        assertEquals(listOf(emptySet<StudioRewriteReason>(), emptySet()), reasons("---\ntitle: 'a #b'\nx: |\n  # not a comment\n---\n\ntext\n"))
    }

    @Test
    fun flagsBlocksWhoseRewriteDoesNotConverge() {
        // Studio writes `-` (an item holding an empty list), which it then drops on the next save
        assertEquals("-\n", StudioFormatter.format("- -\n"))
        assertEquals("\n", StudioFormatter.format("-\n"))
        assertKept("Text\n\n- -\n", NOT_IDEMPOTENT)
        assertEquals("Text\n\n- -\n", StudioFormatter.formatPreserving("Text\n- -\n")!!.text)
    }

    @Test
    fun reportsBlockLinesInTheSource() {
        val blocks = StudioSafety.classify("---\ntitle: a\n---\n\n# Head\n\n<!-- c -->\n\nPara\nline\n")!!
        assertEquals(listOf(0 until 3, 4 until 5, 6 until 7, 8 until 10), blocks.map { it.startLine until it.endLine })
        assertEquals(listOf(StudioBlockKind.FRONTMATTER, StudioBlockKind.BLOCK, StudioBlockKind.BLOCK, StudioBlockKind.BLOCK), blocks.map { it.kind })
        assertEquals("<!-- c -->", blocks[2].source)
        assertEquals("# Head", blocks[1].studioOutput)
        assertEquals("---\ntitle: a\n---", blocks[0].studioOutput)
    }

    @Test
    fun formatsAroundKeptBlocks() {
        val result = StudioFormatter.formatPreserving("---\ntitle:   a\n---\n# Head\n<!-- keep   me -->\n* one\n* two\n\n\n| a | b |\n|:-:|--|\n| 1 | 2 |\n")!!
        assertEquals("---\ntitle: a\n---\n\n# Head\n\n<!-- keep   me -->\n\n- one\n- two\n\n| a | b |\n|:-:|--|\n| 1 | 2 |\n", result.text)
        assertEquals(listOf(setOf(HTML_COMMENT), setOf(TABLE_ALIGNMENT)), result.kept.map { it.reasons })
    }

    @Test
    fun matchesStudioWhereNothingIsKept() {
        val text = "---\ntitle: a\n---\n# A\nText right after\n\n* x\n* y\n\n__strong__ _em_\n"
        val result = StudioFormatter.formatPreserving(text)!!
        assertEquals(emptyList<StudioBlock>(), result.kept)
        assertEquals(StudioFormatter.format(text), result.text)
    }

    @Test
    fun keepsInvalidYamlWhereStudioCannotSave() {
        val text = "---\ntitle: a\ntitle: b\n---\n# A\nText\n"
        assertNull(StudioFormatter.format(text))
        assertEquals("---\ntitle: a\ntitle: b\n---\n\n# A\n\nText\n", StudioFormatter.formatPreserving(text)!!.text)
        val props = "::card\n---\nkey: [\n---\nBody\n::\n# A\nText\n"
        assertNull(StudioFormatter.format(props))
        assertEquals("::card\n---\nkey: [\n---\nBody\n::\n\n# A\n\nText\n", StudioFormatter.formatPreserving(props)!!.text)
    }

    @Test
    fun keepsCarriageReturnFilesAsLf() {
        val result = StudioFormatter.formatPreserving("---\r\ntitle: a # c\r\n---\r\n\r\n<!-- x -->\r\nText\r\n")!!
        assertEquals("---\ntitle: a # c\n---\n\n<!-- x -->\n\nText\n", result.text)
    }
}
