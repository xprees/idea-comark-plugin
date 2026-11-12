package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [ComarkStudioRewriteInspection] and [ComarkStudioConversionInspection]. */
class ComarkStudioInspectionsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(ComarkStudioRewriteInspection(), ComarkStudioConversionInspection())
    }

    private fun withStyle(preset: ComarkStylePreset, action: () -> Unit) {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            ComarkCodeStyleSettings.getInstance(settings).STYLE_PRESET = preset
            action()
        }
    }

    /** The problems in [text], without the notes on blocks the formatter could not check (see [testUnverifiedBlocksAreReportedAsNotChecked]). */
    private fun problems(text: String): List<HighlightInfo> = allProblems(text).filter { !it.description.startsWith("Nuxt Studio may") }

    private fun allProblems(text: String): List<HighlightInfo> {
        myFixture.configureByText("page.mdc", text)
        return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description != null }
    }

    private fun HighlightInfo.text() = myFixture.editor.document.getText(TextRange(startOffset, endOffset))

    private fun applyFix(before: String, fixName: String, after: String) {
        myFixture.configureByText("fix.mdc", before)
        myFixture.launchAction(myFixture.findSingleIntention(fixName))
        myFixture.checkResult(after)
    }

    fun testRegisteredWithDescriptions() {
        for (shortName in listOf("ComarkStudioRewrite", "ComarkStudioConversion")) {
            val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == shortName }
            assertEquals("Comark", ep.getGroupDisplayName())
            assertFalse(ep.getDisplayName().isNullOrBlank())
            assertTrue(ep.enabledByDefault)
            assertEquals(shortName, ep.instantiateTool().shortName)
            assertNotNull("description of $shortName", LocalInspectionToolWrapper(ep).loadDescription())
        }
    }

    // ---- Nuxt Studio will rewrite this ------------------------------------------------------------------------------------

    fun testKeptBlockIsReportedOnItsFirstLine() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val problems = problems("# Title\n\n<div>\n*raw*  html\n</div>\n\nText :x\n")
        val problem = problems.single()
        assertEquals("<div>", problem.text())
        assertEquals(HighlightSeverity.WEAK_WARNING, problem.severity)
        assertTrue(problem.description, problem.description.startsWith("Nuxt Studio will rewrite this: raw HTML"))
    }

    fun testCustomStyleToo() = withStyle(ComarkStylePreset.CUSTOM) {
        assertEquals(listOf("<!-- note -->"), problems("Text\n\n<!-- note -->\n").map { it.text() })
    }

    fun testNotInTheVsCodeStyle() = withStyle(ComarkStylePreset.VSCODE) {
        assertEquals(emptyList<String>(), problems("<div>\nx\n</div>\n\n:smile: &copy;\n").map { it.text() })
    }

    fun testNothingToReport() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        assertEquals(emptyList<String>(), problems("# Title\n\nSome *text*\n\n- a\n- b\n").map { it.text() })
    }

    fun testBlocksBetweenFormatterMarkersAreNotReported() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        assertEquals(emptyList<String>(), problems("Text\n\n<!-- @formatter:off -->\n<div>\nx\n</div>\n<!-- @formatter:on -->\n").map { it.text() })
    }

    fun testSuppressForThisBlock() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        applyFix(
            "Text\n\n<di<caret>v>\nx\n</div>\n\nMore\n",
            "Suppress for this block (formatter off/on comments)",
            "Text\n\n<!-- @formatter:off -->\n<div>\nx\n</div>\n<!-- @formatter:on -->\n\nMore\n",
        )
        assertEquals(emptyList<String>(), myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description != null }.map { it.text() })
    }

    fun testRemoveTableAlignment() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val before = "| a | b | c |\n| :-- | :-: | --: |\n| 1 | 2 | 3 |\n"
        myFixture.configureByText("fix.mdc", before)
        val problem = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single { it.description != null }
        assertTrue(problem.description, problem.description.contains("alignment"))
        myFixture.editor.caretModel.moveToOffset(problem.startOffset)
        myFixture.launchAction(myFixture.findSingleIntention("Remove column alignment"))
        myFixture.checkResult("| a | b | c |\n| --- | --- | --- |\n| 1 | 2 | 3 |\n")
    }

    fun testRemoveHeadingId() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        applyFix("# Ti<caret>tle {#custom}\n\nText\n", "Remove the heading id (Nuxt Studio drops it)", "# Title\n\nText\n")
    }

    // ---- conversions ----------------------------------------------------------------------------------------------------

    fun testEmojiAndEntitiesAreReported() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val problems = problems("Hi :smile: and :not_an_emoji: and &copy; and `:smile: &copy;` and \\:smile:\n\n- item :rocket:\n")
        assertEquals(listOf(":smile:", "&copy;", ":rocket:"), problems.map { it.text() })
        assertEquals("Nuxt Studio writes ':smile:' as \uD83D\uDE04", problems[0].description)
    }

    fun testCodeAndLinkDestinationsAreNotReported() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        assertEquals(emptyList<String>(), problems("```\n:smile: &copy;\n```\n\n[link](/a?b=1&amp;c=2)\n").map { it.text() })
    }

    fun testReplaceEmoji() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        applyFix("Hi :sm<caret>ile: there\n", "Replace with \uD83D\uDE04", "Hi \uD83D\uDE04 there\n")
    }

    fun testReplaceEntityWithStudiosEscape() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        applyFix("a &co<caret>py; b\n", "Replace with ©", "a © b\n")
        applyFix("a &l<caret>t;tag> b\n", "Replace with \\<", "a \\<tag> b\n")
        applyFix("a &#1<caret>69; b\n", "Replace with ©", "a © b\n")
    }

    fun testReplaceEmojiBeforeClosingSyntax() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        // Studio escapes a `]` or `*` right after the token only when it is text; here it closes the emphasis, link or label
        applyFix("Some *hi :sm<caret>ile:* there\n", "Replace with \uD83D\uDE04", "Some *hi \uD83D\uDE04* there\n")
        applyFix("[link :sm<caret>ile:](/x) there\n", "Replace with \uD83D\uDE04", "[link \uD83D\uDE04](/x) there\n")
        applyFix("Hello :badge[wow :sm<caret>ile:] end\n", "Replace with \uD83D\uDE04", "Hello :badge[wow \uD83D\uDE04] end\n")
        applyFix("Some _hi :sm<caret>ile:_ there\n", "Replace with \uD83D\uDE04", "Some _hi \uD83D\uDE04_ there\n")
        val problem = problems("Some *hi :smile:* there\n").single { it.description.startsWith("Nuxt Studio writes") }
        assertEquals("Nuxt Studio writes ':smile:' as \uD83D\uDE04", problem.description)
    }

    fun testUnverifiedBlocksAreReportedAsNotChecked() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val problems = allProblems("---\ntitle: T\ndescription: D\n---\n\nSee :notanemoji: here\n")
        val problem = problems.single()
        assertEquals("See :notanemoji: here", problem.text())
        assertTrue(problem.description, problem.description.startsWith("Nuxt Studio may rewrite this"))
    }

    fun testBlocksADamagedBlockRunsIntoAreReported() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val problems = allProblems("---\ntitle: T\ndescription: D\n---\n\n::a\n::\n\n> - two\n")
        val affected = problems.single { it.text() == "> - two" }
        assertTrue(affected.description, affected.description.startsWith("Nuxt Studio will rewrite this"))
        assertTrue(affected.description, "block at line 6" in affected.description)
        assertTrue(problems.any { it.text() == "::a" })
    }

    fun testAnUnclosedHtmlTagIsNamedInTheWarning() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        val problem = allProblems("---\ntitle: T\ndescription: D\n---\n\n<Counter :count=\"1\" />\n\nText after\n").single()
        assertTrue(problem.description, "never closed" in problem.description)
    }

    fun testConversionsOfTheAdapter() {
        val text = "x &amp;amp; :+1: &unknown; &#x27; :a:smile:"
        val conversions = StudioStyleAdapter.conversions(text, 0, text.length)
        assertEquals(listOf("&amp;", ":+1:", "&#x27;", ":smile:"), conversions.map { it.token })
        // `&amp;` before `amp;` must stay escaped, or it would become another reference
        assertEquals("\\&", conversions[0].replacement)
        assertEquals("'", conversions[2].replacement)
    }
}
