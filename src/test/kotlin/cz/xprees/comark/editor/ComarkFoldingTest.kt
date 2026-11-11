package cz.xprees.comark.editor

import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.editor.FoldRegion
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownLanguage

class ComarkFoldingTest : BasePlatformTestCase() {
    /** A fold as the editor shows it: the folded text and its placeholder. */
    private data class Fold(val text: String, val placeholder: String, val start: Int, val end: Int)

    /** Builds the editor's fold regions the way the IDE does (all builders, overlap checks of the folding model). */
    private fun folds(text: String, fileName: String = "test.mdc"): List<Fold> {
        myFixture.configureByText(fileName, text)
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        return myFixture.editor.foldingModel.allFoldRegions.map { it.toFold() }
    }

    private fun FoldRegion.toFold() = Fold(document.charsSequence.subSequence(startOffset, endOffset).toString(), placeholderText, startOffset, endOffset)

    private fun List<Fold>.withText(text: String): Fold {
        val matches = filter { it.text == text }
        assertEquals("expected one fold of '$text' in ${map { it.text to it.placeholder }}", 1, matches.size)
        return matches.single()
    }

    private fun List<Fold>.assertNoFold(text: String) {
        assertTrue("unexpected fold of '$text' in ${map { it.text }}", none { it.text == text })
    }

    fun testFoldingBuilderResolution() {
        val comarkBuilders = LanguageFolding.INSTANCE.allForLanguage(ComarkLanguage)
        assertEquals(listOf(ComarkFoldingBuilder::class.java), comarkBuilders.map { it.javaClass })
        assertTrue("Markdown files must keep the Markdown builder only", LanguageFolding.INSTANCE.allForLanguage(MarkdownLanguage.INSTANCE).none { it is ComarkFoldingBuilder })
    }

    fun testComponentFoldKeepsOpenerAndCloserVisible() {
        val text = "::alert{type=\"info\"}\nThis is **important**!\n::\n"
        val fold = folds(text).withText("\nThis is **important**!\n")
        assertEquals("...", fold.placeholder)
        assertEquals("::alert{type=\"info\"}", text.substring(0, fold.start))
        assertTrue(text.substring(fold.end).startsWith("::"))
    }

    fun testIndentedCloserIsPartOfTheFold() {
        val text = "::card\n  :::badge\n  Nested\n  :::\n::"
        val folds = folds(text)
        folds.withText("\n  Nested\n  ")
        folds.withText("\n  :::badge\n  Nested\n  :::\n")
    }

    fun testUnclosedComponentFoldsToItsLastLine() {
        folds("::unclosed\nstill inside\n\nmore\n\n").withText("\nstill inside\n\nmore")
    }

    fun testEmptyComponentAndOneLinerHaveNoFold() {
        val folds = folds("::divider\n::\n\n::alert[Hi]\n::\n")
        assertTrue(folds.toString(), folds.isEmpty())
    }

    fun testNestedComponentsFoldIndependently() {
        val text = "::outer\nA\n\n:::inner{variant=\"compact\"}\nB\n:::\n\nC\n::\n"
        val folds = folds(text)
        val outer = folds.withText("\nA\n\n:::inner{variant=\"compact\"}\nB\n:::\n\nC\n")
        val inner = folds.withText("\nB\n")
        assertTrue(outer.start < inner.start && inner.end < outer.end)
    }

    fun testSameMarkerNesting() {
        val folds = folds("::a\n::b\nx\ny\n::\n::\n")
        folds.withText("\nx\ny\n")
        folds.withText("\n::b\nx\ny\n::\n")
    }

    fun testSlotsFold() {
        val text = "::card\n#header\n## Title\n\n#footer{.small}\nFooter\ntext\n::\n"
        val folds = folds(text)
        assertEquals("...", folds.withText("\n## Title").placeholder)
        assertEquals("...", folds.withText("\nFooter\ntext").placeholder)
    }

    fun testPropsBlockFoldShowsKeys() {
        val text = "::card\n---\ntitle: Hello\nicon: i-lucide-x\nitems:\n  - a\n---\nBody\n::\n"
        val fold = folds(text).withText("\ntitle: Hello\nicon: i-lucide-x\nitems:\n  - a\n")
        assertEquals(" title, icon, items ", fold.placeholder)
    }

    fun testNestedPropsBlockFold() {
        val text = "::card\n  :::inner\n  ---\n  b: 2\n  c: 3\n  ---\n  x\n  :::\n::"
        assertEquals(" b, c ", folds(text).withText("\n  b: 2\n  c: 3\n  ").placeholder)
    }

    fun testMarkdownFoldsStillWorkInComarkFiles() {
        val text = """
            |---
            |title: Page
            |---
            |
            |# Title
            |
            |```js
            |let a = 1
            |```
            |
            |::card
            |- one
            |- two
            |
            || a | b |
            ||---|---|
            || 1 | 2 |
            |::
            |
            |> quote
            |> more
            |""".trimMargin()
        val folds = folds(text)
        assertTrue(folds.withText("---\ntitle: Page\n---").placeholder.startsWith("front matter"))
        assertTrue(folds.withText("```js\nlet a = 1\n```").placeholder.startsWith("code fence"))
        assertTrue(folds.withText("- one\n- two").placeholder.startsWith("unordered list"))
        assertTrue(folds.withText("| a | b |\n|---|---|\n| 1 | 2 |").placeholder.startsWith("table"))
        assertTrue(folds.withText("> quote\n> more").placeholder.startsWith("block quote"))
        // the component around the list and table folds too
        folds.withText("\n- one\n- two\n\n| a | b |\n|---|---|\n| 1 | 2 |\n")
    }

    fun testHeaderSectionsFollowTheHierarchy() {
        val text = "# A\n\nintro\n\n## B\n\ntext\n\n## C\n\nmore\n\n# D\n\nend\n"
        val folds = folds(text)
        assertEquals("h1: # A", folds.withText("# A\n\nintro\n\n## B\n\ntext\n\n## C\n\nmore").placeholder)
        assertEquals("h2: ## B", folds.withText("## B\n\ntext").placeholder)
        folds.withText("## C\n\nmore")
        folds.withText("# D\n\nend")
    }

    fun testHeaderSectionInsideComponentStopsAtTheClosingMarker() {
        // The Markdown builder alone folds the last header of the document up to the end of the file, across the closing marker.
        val text = "# Top\n\n::card\n## Inside\ntext\n::\n\nafter\n"
        val folds = folds(text)
        folds.withText("## Inside\ntext")
        folds.withText("\n## Inside\ntext\n")
        folds.withText("# Top\n\n::card\n## Inside\ntext\n::\n\nafter")
        folds.assertNoFold("## Inside\ntext\n::\n\nafter")
    }

    fun testHeaderSectionsInSlotsStopAtTheNextSlot() {
        val text = "::card\n#header\n## Title\nsub\n\n#footer\nFooter\n::\n"
        val folds = folds(text)
        folds.withText("## Title\nsub")
        folds.withText("\n## Title\nsub")
    }

    fun testComponentsInsideCodeFencesDoNotFold() {
        val folds = folds("```md\n::alert\ntext\n::\n```\n")
        assertEquals(listOf("```md\n::alert\ntext\n::\n```"), folds.map { it.text })
    }

    fun testPlainMarkdownFileIsUntouched() {
        val file = myFixture.configureByText("plain.md", "# A\n\n::alert\ntext\n::\n\n## B\n\nx\n")
        assertFalse(file is ComarkFile)
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        val folds = myFixture.editor.foldingModel.allFoldRegions.map { it.toFold() }
        assertTrue(folds.none { it.placeholder == "..." && it.text.startsWith("\ntext") })
        // the Markdown builder's own header section (it includes the final line break in Markdown files)
        folds.withText("# A\n\n::alert\ntext\n::\n\n## B\n\nx\n")
    }

    fun testComponentInsideAListItemFolds() {
        val folds = folds("- item\n  ::block\n  ---\n  title: x\n  ---\n  text\n  more\n    :::inner\n    nested\n    :::\n  ::\n- next\n")
        folds.withText("\n  ---\n  title: x\n  ---\n  text\n  more\n    :::inner\n    nested\n    :::\n  ")
        assertEquals(" title ", folds.withText("\n  title: x\n  ").placeholder)
        folds.withText("\n    nested\n    ")
    }

    fun testPropsSummary() {
        assertEquals("title, icon", ComarkFoldingBuilder.propsSummary("title: x\nicon: y\n"))
        assertEquals("b", ComarkFoldingBuilder.propsSummary("  b: 1\n    c: 2\n"))
        assertEquals("quoted key", ComarkFoldingBuilder.propsSummary("\"quoted key\": 1\n# comment: no\n"))
        assertEquals("...", ComarkFoldingBuilder.propsSummary("- a\n- b\n"))
        assertEquals("...", ComarkFoldingBuilder.propsSummary(""))
        val long = (1..20).joinToString("\n") { "key$it: v" }
        assertTrue(ComarkFoldingBuilder.propsSummary(long).endsWith("..."))
    }
}
