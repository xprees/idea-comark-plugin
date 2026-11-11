package cz.xprees.comark.format

import cz.xprees.comark.testutil.withDialect
import cz.xprees.comark.lang.ComarkLanguage
import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.util.PsiTreeUtil
import cz.xprees.comark.psi.ComarkBlockComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.MarkdownFileType

/** Reformat Code on Comark files through [ComarkFormattingService]. */
class ComarkFormattingServiceTest : BasePlatformTestCase() {
    private var formatterWasEnabled = true

    override fun setUp() {
        super.setUp()
        formatterWasEnabled = ComarkSettings.getInstance().formatterEnabled
        ComarkSettings.getInstance().formatterEnabled = true
    }

    override fun tearDown() {
        try {
            ComarkSettings.getInstance().formatterEnabled = formatterWasEnabled
        } finally {
            super.tearDown()
        }
    }

    private fun configure(text: String, name: String = "test.mdc"): PsiFile = myFixture.configureByText(name, text)

    private fun reformat(file: PsiFile = myFixture.file) {
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
    }

    private fun reformatRange(start: Int, end: Int) {
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformatText(myFixture.file, start, end) }
    }

    /** Runs [action] with the Comark code style indent size set to [size]. */
    private fun withIndentSize(size: Int, action: () -> Unit) {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCommonSettings(ComarkLanguage).indentOptions!!.INDENT_SIZE = size
            action()
        }
    }

    private val nested = "::outer\ntext\n:::inner\ninner text\n:::\n::\n"

    fun testServiceHandlesComarkFilesOnly() {
        val comark = configure(nested)
        assertInstanceOf(comark, ComarkFile::class.java)
        assertTrue(ComarkFormattingService().canFormat(comark))
        val markdown = configure("# Plain", "plain.md")
        assertFalse(ComarkFormattingService().canFormat(markdown))
    }

    fun testWholeFileReformat() {
        withIndentSize(2) {
            configure("::outer\n   text  \n:::inner\n---\ntitle: x\nlinks:\n      - a\n---\ninner text\n:::\n::")
            reformat()
            myFixture.checkResult("::outer\ntext\n  :::inner\n  ---\n  title: x\n  links:\n    - a\n  ---\n  inner text\n  :::\n::\n")
        }
    }

    fun testIndentSizeComesFromTheComarkCodeStyle() {
        configure(nested)
        // Comark has its own code style (2 by default, like monarch-mdc), independent of the Markdown file type's indent options
        assertEquals(ComarkCodeStyleSettingsProvider.DEFAULT_INDENT, ComarkIndent.indentSize(myFixture.file))
        reformat()
        myFixture.checkResult("::outer\ntext\n  :::inner\n  inner text\n  :::\n::\n")
        withIndentSize(4) {
            configure(nested)
            assertEquals(4, ComarkIndent.indentSize(myFixture.file))
            reformat()
            myFixture.checkResult("::outer\ntext\n    :::inner\n    inner text\n    :::\n::\n")
        }
    }

    fun testMarkdownIndentSettingDoesNotApply() {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getIndentOptions(MarkdownFileType.INSTANCE).INDENT_SIZE = 8
            configure(nested)
            assertEquals(ComarkCodeStyleSettingsProvider.DEFAULT_INDENT, ComarkIndent.indentSize(myFixture.file))
        }
    }

    fun testReformatCodeProcessor() {
        withIndentSize(2) {
            configure(nested)
            ReformatCodeProcessor(myFixture.file, false).run()
            myFixture.checkResult("::outer\ntext\n  :::inner\n  inner text\n  :::\n::\n")
        }
    }

    fun testRangeReformatChangesOnlyTheSelectedLines() {
        withIndentSize(2) {
            val text = "::outer\n:::inner\none\ntwo\nthree\n:::\n::\n"
            configure(text)
            val start = text.indexOf("two")
            reformatRange(start, text.indexOf("three"))
            myFixture.checkResult("::outer\n:::inner\none\n  two\nthree\n:::\n::\n")
        }
    }

    fun testSelectionReformatWithReformatCodeProcessor() {
        withIndentSize(2) {
            val text = "::outer\n:::inner\none\ntwo\nthree\n:::\n::\n"
            configure(text)
            myFixture.editor.selectionModel.setSelection(text.indexOf("one"), text.indexOf("three"))
            ReformatCodeProcessor(myFixture.file, myFixture.editor.selectionModel).run()
            myFixture.checkResult("::outer\n:::inner\n  one\n  two\nthree\n:::\n::\n")
        }
    }

    fun testEditsAreMinimalSoMarkersOnTheContentSurvive() {
        withIndentSize(2) {
            val text = "::outer\n:::inner\n   some words here   \n:::\n::\n"
            configure(text)
            val document = myFixture.editor.document
            val start = text.indexOf("words")
            val marker = document.createRangeMarker(start, start + "words".length)
            myFixture.editor.caretModel.moveToOffset(text.indexOf("here"))
            reformat()
            assertTrue(marker.isValid)
            assertEquals("words", document.getText(marker.textRange))
            assertEquals(document.text.indexOf("here"), myFixture.editor.caretModel.offset)
            marker.dispose()
        }
    }

    fun testDisabledFormatterFallsBackToMarkdown() {
        withIndentSize(2) {
            val text = "::outer\n:::inner\n#    Title\n:::\n::\n"
            configure(text)
            ComarkSettings.getInstance().formatterEnabled = false
            assertFalse(ComarkFormattingService().canFormat(myFixture.file))
            reformat()
            // The Markdown formatter normalizes the header and leaves the component structure alone
            assertEquals("::outer\n:::inner\n# Title\n:::\n::\n", myFixture.editor.document.text)

            ComarkSettings.getInstance().formatterEnabled = true
            configure(text)
            reformat()
            myFixture.checkResult("::outer\n  :::inner\n  #    Title\n  :::\n::\n")
        }
    }

    fun testMarkdownTablePostFormattingStillRuns() {
        withIndentSize(2) {
            configure("| a | bbb |\n|---|---|\n| cccc | d |\n\n::card\n:::inner\n| x | yyy |\n|---|---|\n| zz | w |\n:::\n::\n")
            reformat()
            val result = myFixture.editor.document.text
            // the top level table is aligned by the Markdown plugin's table post-processor...
            assertTrue(result, result.startsWith("| a    | bbb |\n|------|-----|\n| cccc | d   |\n"))
            // ...the table inside the components is indented by the Comark formatter only
            assertTrue(result, result.contains("\n  | x | yyy |\n  |---|---|\n  | zz | w |\n"))
        }
    }

    fun testFormatterFailureLeavesTheDocumentUnchanged() {
        val text = "::outer\ntext\n::\n  ```js\nbad\n  ```\n"
        configure(text)
        reformat()
        myFixture.checkResult(text)
    }

    fun testMdFileInNuxtContentProjectIsFormatted() {
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        val file = myFixture.addFileToProject("site/content/index.md", nested)
        assertInstanceOf(file, ComarkFile::class.java)
        withIndentSize(2) {
            reformat(file)
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            assertEquals("::outer\ntext\n  :::inner\n  inner text\n  :::\n::\n", document.text)
        }
    }

    fun testRangeReformatOfWholeFileRangeAddsFinalLineBreak() {
        withIndentSize(2) {
            configure("::a\ntext\n::")
            reformatRange(0, myFixture.editor.document.textLength)
            myFixture.checkResult("::a\ntext\n::\n")
        }
    }

    // ---- deviations from monarch-mdc: content and structure are kept ----------------------------------------------

    private fun reformatted(text: String): String {
        configure(text)
        reformat()
        return myFixture.editor.document.text
    }

    fun testReformatKeepsAListItemComponentInsideTheListItem() {
        val text = "- item\n  ::block\n  text\n  ::\n"
        assertEquals(text, reformatted(text))
    }

    fun testListItemComponentIsFormattedAtTheListItemsContentColumn() {
        assertEquals(
            "- item\n  ::block\n  text\n    :::inner\n    ---\n    title: x\n    ---\n    more\n    :::\n  ::\n- next\n  - nested\n",
            reformatted("- item\n  ::block\n     text\n   :::inner\n   ---\n   title: x\n   ---\n   more\n   :::\n  ::\n- next\n  - nested\n"),
        )
        // nested lists: the component stays in the inner item, and the list goes on after it
        assertEquals(
            "- a\n  - b\n    ::c\n    text\n    ::\n  - d\n",
            reformatted("- a\n  - b\n    ::c\n      text\n    ::\n  - d\n"),
        )
        // inside a component
        assertEquals(
            "::outer\n- item\n  :::block\n  text\n  :::\n::\n",
            reformatted("::outer\n- item\n  :::block\n    text\n  :::\n::\n"),
        )
    }

    fun testReformatKeepsTabsInCodeFences() {
        val text = "::example\n```makefile\nall:\n\tgo build\n```\n::\n"
        assertEquals(text, reformatted(text))
        // the fence moves with its component; the content keeps its indentation relative to the fence
        assertEquals(
            "::a\n  :::example\n  ```makefile\n  all:\n  \tgo build\n  ```\n  :::\n::\n",
            reformatted("::a\n:::example\n```makefile\nall:\n\tgo build\n```\n:::\n::\n"),
        )
    }

    fun testReformatKeepsIndentedCodeBlocks() {
        val text = "Paragraph\n\n    code block\n    more code\n"
        assertEquals(text, reformatted(text))
        // inside components the code block keeps its indentation relative to the component's opener
        assertEquals(
            "::a\n  :::b\n  text\n\n      code  \n        more\n  :::\n::\n",
            reformatted("::a\n:::b\ntext\n\n    code  \n      more\n:::\n::\n"),
        )
    }

    fun testReformatKeepsHardLineBreaks() {
        val text = "first line  \nsecond line\n"
        assertEquals(text, reformatted(text))
        // trailing spaces that are no hard line break still go
        assertEquals("::a\ntext\n\nlast\n::\n", reformatted("::a\ntext  \n\nlast  \n::\n"))
        assertEquals("::a\n  :::b\n  one   \n  two\n  :::\n::\n", reformatted("::a\n:::b\none   \ntwo \n:::\n::\n"))
    }

    // ---- indentation is always spaces --------------------------------------------------------------------------

    fun testCodeStylePageOffersTheIndentSizeOnly() {
        val shown = ArrayList<String>()
        val consumer = object : CodeStyleSettingsCustomizable {
            override fun showAllStandardOptions() = fail("all options shown")
            override fun showStandardOptions(vararg optionNames: String) {
                shown += optionNames
            }
        }
        ComarkCodeStyleSettingsProvider().customizeSettings(consumer, LanguageCodeStyleSettingsProvider.SettingsType.INDENT_SETTINGS)
        assertEquals(listOf("INDENT_SIZE"), shown)
    }

    fun testTabIndentationWouldTurnNestedComponentsIntoCode() {
        // why "Use tab character" is not offered: Markdown counts a leading tab as four columns, so with the comark parser this is an
        // indented code block (Nuxt Content accepts it)
        withDialect(ComarkSettings.Dialect.COMARK) {
            val file = configure("::a\n\t:::b\n\ttext\n\t:::\n::\n")
            assertEquals(listOf("a"), PsiTreeUtil.findChildrenOfType(file, ComarkBlockComponent::class.java).map { it.componentName })
        }
        // ...and the formatter writes spaces, whatever the (hidden) option says
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCommonSettings(ComarkLanguage).indentOptions!!.USE_TAB_CHARACTER = true
            assertEquals("::a\n  :::b\n  text\n  :::\n::\n", reformatted("::a\n:::b\ntext\n:::\n::\n"))
        }
    }

    /** Indent size 4 (e.g. from EditorConfig): Nuxt Content keeps it, comark falls back to 2 so nested components stay components. */
    fun testIndentSizeFourPerDialect() {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCommonSettings(ComarkLanguage).indentOptions!!.INDENT_SIZE = 4
            val input = "::accordion\n:::item\nText\n:::\n::\n"
            val nuxt = reformatted(input)
            assertEquals("::accordion\n    :::item\n    Text\n    :::\n::\n", nuxt)
            // idempotent: the 4-space result still parses as nested components
            assertEquals(nuxt, reformatted(nuxt))
            withDialect(ComarkSettings.Dialect.COMARK) {
                assertEquals("::accordion\n  :::item\n  Text\n  :::\n::\n", reformatted(input))
            }
        }
    }

    // ---- Auto-Indent Lines uses the same indentation ------------------------------------------------------------

    private fun autoIndent(text: String, start: Int = 0, end: Int = text.length, name: String = "test.mdc"): String {
        configure(text, name)
        myFixture.editor.selectionModel.setSelection(start, end)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_AUTO_INDENT_LINES)
        return myFixture.editor.document.text
    }

    fun testAutoIndentLinesKeepsTheComarkIndentation() {
        val text = "::outer\n  :::inner\n  text\n  more\n  :::\n::\n"
        assertEquals("precondition: the text is formatted", text, ComarkFormatter.format(text))
        assertEquals(text, autoIndent(text))
    }

    fun testAutoIndentLinesIndentsLikeReformat() {
        val text = "::outer\n:::inner\n      text\n- item\n  ::block\n      body\n  ::\n:::\n::\n"
        assertEquals(reformatted(text), autoIndent(text))
    }

    fun testAutoIndentLinesChangesOnlyTheSelectedLines() {
        val text = "::outer\n:::inner\none\ntwo\n:::\n::\n"
        assertEquals("::outer\n:::inner\none\n  two\n:::\n::\n", autoIndent(text, text.indexOf("two"), text.indexOf("two") + 1))
    }

    fun testAutoIndentLinesKeepsContent() {
        val text = "::example\n```makefile\nall:\n\tgo build\n```\n::\n\nParagraph  \nwith a break\n\n    code block\n"
        assertEquals(text, autoIndent(text))
    }

    fun testAutoIndentLinesWithTheFormatterDisabledIsMarkdowns() {
        ComarkSettings.getInstance().formatterEnabled = false
        val text = "::outer\n  :::inner\n  text\n  :::\n::\n"
        // what the Markdown plugin's model does in a Markdown file
        val markdown = autoIndent(text, name = "plain.md")
        assertEquals(markdown, autoIndent(text))
    }
}
