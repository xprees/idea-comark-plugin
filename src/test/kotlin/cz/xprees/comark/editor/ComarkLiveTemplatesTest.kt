package cz.xprees.comark.editor

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ComarkLiveTemplatesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    private fun expand() = myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)

    private fun nextVariable() {
        val state = TemplateManagerImpl.getTemplateState(myFixture.editor)
        assertNotNull("no template is running", state)
        WriteCommandAction.runWriteCommandAction(project) { state!!.nextTab() }
    }

    private fun applicableTemplates(): List<String> =
        TemplateManagerImpl.listApplicableTemplates(TemplateActionContext.expanding(myFixture.file, myFixture.editor)).map { it.key }

    fun testTemplatesAreRegistered() {
        val settings = TemplateSettings.getInstance()
        for (key in listOf("::", "::-", "block", "datablock", "span")) {
            assertNotNull("template $key", settings.getTemplate(key, "Comark"))
        }
    }

    fun testSimpleBlock() {
        myFixture.configureByText("test.mdc", "Intro\n\nblock<caret>")
        expand()
        myFixture.type("alert")
        nextVariable()
        myFixture.type("Hello")
        myFixture.checkResult("Intro\n\n::alert\nHello<caret>\n::")
    }

    fun testColonAbbreviation() {
        myFixture.configureByText("test.mdc", "::<caret>")
        expand()
        myFixture.type("note")
        nextVariable()
        myFixture.checkResult("::note\n<caret>\n::")
    }

    fun testDataBlock() {
        myFixture.configureByText("test.mdc", "::-<caret>")
        expand()
        myFixture.type("card")
        nextVariable()
        myFixture.type("title: Hi")
        nextVariable()
        myFixture.checkResult("::card\n---\ntitle: Hi\n---\n<caret>\n::")
    }

    fun testDataBlockWordAbbreviation() {
        myFixture.configureByText("test.mdc", "datablock<caret>")
        expand()
        myFixture.checkResult("::<caret>\n---\n\n---\n\n::")
    }

    fun testSpan() {
        myFixture.configureByText("test.mdc", "Some span<caret>")
        expand()
        myFixture.type("text")
        nextVariable()
        myFixture.checkResult("Some [text]<caret>")
    }

    fun testSurroundSelectionWithBlock() {
        myFixture.configureByText("test.mdc", "<selection>Wrapped text</selection>")
        val template = TemplateSettings.getInstance().getTemplate("block", "Comark")!!
        val editor = myFixture.editor
        val selected = editor.selectionModel.selectedText!!
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.deleteString(editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd)
            TemplateManager.getInstance(project).startTemplate(editor, selected, template)
        }
        myFixture.type("note")
        myFixture.checkResult("::note\nWrapped text\n::")
    }

    fun testAvailableInMarkdownText() {
        myFixture.configureByText("test.mdc", "::card\nSome text bl<caret>\n::")
        assertContainsElements(applicableTemplates(), "block", "::", "span")
    }

    fun testNotInCodeFence() {
        myFixture.configureByText("test.mdc", "```\nblock<caret>\n```")
        assertDoesntContain(applicableTemplates(), "block", "::", "span")
        myFixture.configureByText("test.mdc", "Text\n\n    block<caret>\n")
        assertDoesntContain(applicableTemplates(), "block") // indented code
    }

    fun testNotInInjectedMarkdownFence() {
        // the Markdown plugin injects Markdown (here: Comark) into ```md fences; it's still example code of the document
        myFixture.configureByText("test.mdc", "```md\nblock<caret>\n```")
        assertDoesntContain(applicableTemplates(), "block", "::", "span")
    }

    fun testNotInInlineCode() {
        myFixture.configureByText("test.mdc", "Use `block<caret>` here")
        assertDoesntContain(applicableTemplates(), "block")
    }

    fun testNotInPropsYamlOrFrontMatter() {
        myFixture.configureByText("test.mdc", "::card\n---\ntitle: block<caret>\n---\n::")
        assertDoesntContain(applicableTemplates(), "block")
        myFixture.configureByText("test.mdc", "---\ntitle: block<caret>\n---\n")
        assertDoesntContain(applicableTemplates(), "block")
    }

    fun testNotInAttributes() {
        myFixture.configureByText("test.mdc", "::card{block<caret>}\n::")
        assertDoesntContain(applicableTemplates(), "block")
    }

    fun testNotInPlainMarkdown() {
        myFixture.configureByText("plain.md", "block<caret>")
        assertDoesntContain(applicableTemplates(), "block", "::")
        expand()
        assertFalse(myFixture.editor.document.text.contains("::"))
    }
}
