package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.fileSet.FileSetDescriptor
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.settings.ComarkSettings

/** Comark formatting honours the platform's *Do not format* entries and formatter off/on markers. */
class ComarkFormatterControlTest : BasePlatformTestCase() {
    private val messy = "::outer\n:::inner\ntext\n:::\n::\n"
    private val formatted = "::outer\n  :::inner\n  text\n  :::\n::\n"

    /** A *Do not format* entry matching `.mdc` files. */
    private object MdcFiles : FileSetDescriptor {
        override fun matches(psiFile: PsiFile): Boolean = psiFile.name.endsWith(".mdc")
        override fun getType(): String = "glob"
        override fun getPattern(): String = "*.mdc"
        override fun setPattern(pattern: String?) {}
    }

    override fun setUp() {
        super.setUp()
        ComarkSettings.getInstance().formatterEnabled = true
    }

    private fun withSettings(configure: (CodeStyleSettings) -> Unit, action: () -> Unit) =
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            configure(settings)
            action()
        }

    private fun reformat(text: String): String {
        myFixture.configureByText("test.mdc", text)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        return myFixture.editor.document.text
    }

    private fun autoIndent(text: String): String {
        myFixture.configureByText("test.mdc", text)
        myFixture.editor.selectionModel.setSelection(0, text.length)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_AUTO_INDENT_LINES)
        return myFixture.editor.document.text
    }

    private fun markers(settings: CodeStyleSettings) {
        settings.FORMATTER_TAGS_ENABLED = true
    }

    fun testPreconditionMessyTextGetsFormatted() = assertEquals(formatted, reformat(messy))

    fun testDoNotFormatIsHonouredByReformat() = withSettings({ it.excludedFiles.addDescriptor(MdcFiles) }) {
        assertEquals(messy, reformat(messy))
    }

    fun testDoNotFormatIsHonouredByAutoIndent() = withSettings({ it.excludedFiles.addDescriptor(MdcFiles) }) {
        assertEquals(messy, autoIndent(messy))
    }

    fun testHtmlCommentMarkersProtectTheirLinesInReformat() = withSettings(::markers) {
        val text = "<!-- @formatter:off -->\n$messy<!-- @formatter:on -->\n\n$messy"
        assertEquals("<!-- @formatter:off -->\n$messy<!-- @formatter:on -->\n\n$formatted", reformat(text))
    }

    fun testHtmlCommentMarkersProtectTheirLinesInAutoIndent() = withSettings(::markers) {
        val text = "<!-- @formatter:off -->\n$messy<!-- @formatter:on -->\n\n$messy"
        assertEquals("<!-- @formatter:off -->\n$messy<!-- @formatter:on -->\n\n$formatted", autoIndent(text))
    }

    fun testMarkdownCommentMarkersAndUnclosedOffMarker() = withSettings(::markers) {
        val text = "$messy\n[//]: # (@formatter:off)\n$messy"
        assertEquals("$formatted\n[//]: # (@formatter:off)\n$messy", reformat(text))
    }

    fun testMarkersInsideCodeFencesDoNotCount() = withSettings(::markers) {
        val text = "```md\n<!-- @formatter:off -->\n```\n\n$messy"
        assertEquals("```md\n<!-- @formatter:off -->\n```\n\n$formatted", reformat(text))
    }

    fun testMarkersAreIgnoredWhenTheSettingIsOff() = withSettings({ it.FORMATTER_TAGS_ENABLED = false }) {
        val text = "<!-- @formatter:off -->\n$messy<!-- @formatter:on -->\n"
        assertEquals("<!-- @formatter:off -->\n$formatted<!-- @formatter:on -->\n", reformat(text))
    }

    fun testCustomAndRegexpMarkers() = withSettings({
        it.FORMATTER_TAGS_ENABLED = true
        it.FORMATTER_TAGS_ACCEPT_REGEXP = true
        it.FORMATTER_OFF_TAG = "fmt:\\s*(off|skip)"
        it.FORMATTER_ON_TAG = "fmt:\\s*on"
    }) {
        val text = "<!-- fmt: skip -->\n$messy<!-- fmt:on -->\n$messy"
        assertEquals("<!-- fmt: skip -->\n$messy<!-- fmt:on -->\n$formatted", reformat(text))
    }
}
