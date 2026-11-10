package cz.xprees.comark.compat

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.ide.structureView.newStructureView.StructureViewComponent
import com.intellij.lang.Language
import com.intellij.lang.folding.LanguageFolding
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiTodoSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.index.HeaderAnchorIndex
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/**
 * Audit of the Markdown plugin features in Comark (`.mdc`) files: every test exercises one Markdown feature through the same
 * entry point the IDE uses (editor actions, typed handlers, inspections, completion, references, indices, ...).
 */
class MarkdownCompatAuditTest : BasePlatformTestCase() {
    private fun mdc(text: String, name: String = "test.mdc"): ComarkFile {
        val file = myFixture.configureByText(name, text)
        assertInstanceOf(file, ComarkFile::class.java)
        return file as ComarkFile
    }

    private fun action(id: String) = ActionManager.getInstance().getAction(id) ?: error("no action $id")

    @Suppress("UNCHECKED_CAST")
    private fun inspection(className: String) = Class.forName(className) as Class<out LocalInspectionTool>

    // ---- Editing ---------------------------------------------------------------------------------------------------

    fun testEnterContinuesList() {
        mdc("- first<caret>\n")
        myFixture.type('\n')
        myFixture.checkResult("- first\n- <caret>\n")
    }

    fun testEnterContinuesListInsideComponent() {
        mdc("::card\n- first<caret>\n::\n")
        myFixture.type('\n')
        myFixture.checkResult("::card\n- first\n- <caret>\n::\n")
    }

    fun testTabIndentsListItem() {
        mdc("- first\n- <caret>second\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_TAB)
        assertEquals("- first\n  - second\n", myFixture.editor.document.text)
    }

    fun testShiftTabUnindentsListItem() {
        mdc("- first\n  - <caret>second\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_UNINDENT_SELECTION)
        assertEquals("- first\n- second\n", myFixture.editor.document.text)
    }

    /** Runs [edit] on [text] as a Markdown and as a Comark file and returns both results, which should be equal. */
    private fun parity(text: String, edit: () -> Unit): Pair<String, String> {
        myFixture.configureByText("parity.md", text)
        edit()
        val markdown = myFixture.editor.document.text
        mdc(text, "parity.mdc")
        edit()
        return markdown to myFixture.editor.document.text
    }

    fun testBackspaceInListBehavesLikeMarkdown() {
        val (markdown, comark) = parity("- first\n  - <caret>second\n") { myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE) }
        assertEquals(markdown, comark)
        val (emptyMarkdown, emptyComark) = parity("- first\n- <caret>\n") { myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE) }
        assertEquals(emptyMarkdown, emptyComark)
    }

    fun testTableTypingBehavesLikeMarkdown() {
        val (markdown, comark) = parity("| a | b |\n|---|---|\n| x<caret> | y |\n") { myFixture.type("longer") }
        assertEquals(markdown, comark)
        val (enterMarkdown, enterComark) = parity("| a | b |\n|---|---|\n| x<caret> | y |\n") { myFixture.type('\n') }
        assertEquals(enterMarkdown, enterComark)
    }

    fun testToggleBold() {
        mdc("Some <selection>word</selection> here\n")
        myFixture.testAction(action("org.intellij.plugins.markdown.ui.actions.styling.ToggleBoldAction"))
        myFixture.checkResult("Some **word** here\n", true)
    }

    fun testToggleItalicCodeStrikethrough() {
        mdc("a <selection>b</selection> c\n")
        myFixture.testAction(action("org.intellij.plugins.markdown.ui.actions.styling.ToggleItalicAction"))
        val italic = myFixture.editor.document.text
        assertTrue(italic, italic.contains("_b_") || italic.contains("*b*"))
        mdc("a <selection>b</selection> c\n", "code.mdc")
        myFixture.testAction(action("org.intellij.plugins.markdown.ui.actions.styling.ToggleCodeSpanAction"))
        assertEquals("a `b` c\n", myFixture.editor.document.text)
        mdc("a <selection>b</selection> c\n", "strike.mdc")
        myFixture.testAction(action("org.intellij.plugins.markdown.ui.actions.styling.ToggleStrikethroughAction"))
        assertEquals("a ~~b~~ c\n", myFixture.editor.document.text)
    }

    fun testFillParagraph() {
        mdc("<caret>" + "word ".repeat(40).trim() + "\n")
        myFixture.performEditorAction("FillParagraph")
        assertTrue("paragraph must be wrapped:\n" + myFixture.editor.document.text, myFixture.editor.document.text.trim().lines().size > 1)
    }

    fun testGenerateTableOfContents() {
        mdc("<caret>\n# One\n\n## Two\n")
        val presentation = myFixture.testAction(action("Markdown.GenerateTableOfContents"))
        assertTrue(presentation.isEnabledAndVisible)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("[Two](#two)"))
    }

    // ---- Code insight ----------------------------------------------------------------------------------------------

    fun testHeaderAnchorCompletion() {
        mdc("# My Header\n\n[link](#<caret>)\n")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "my-header")
    }

    fun testHeaderAnchorResolvesInFile() {
        mdc("# My Header\n\n[link](#my-header) [bad](#missing)\n")
        myFixture.enableInspections(inspection("org.intellij.plugins.markdown.model.psi.headers.UnresolvedHeaderReferenceInspection"))
        assertEquals(listOf("missing"), myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.text })
    }

    fun testHeaderAnchorResolvesAcrossFiles() {
        myFixture.addFileToProject("other.mdc", "::card\n## Inside Card\n::\n\n# Top Level\n")
        mdc("[a](other.mdc#inside-card) [b](other.mdc#top-level) [c](other.mdc#nope)\n")
        myFixture.enableInspections(inspection("org.intellij.plugins.markdown.model.psi.headers.UnresolvedHeaderReferenceInspection"))
        assertEquals(listOf("nope"), myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.text })
    }

    fun testFileLinkResolvesAndUnresolvedIsReported() {
        myFixture.addFileToProject("other.md", "# Other\n")
        mdc("[ok](other.md) [bad](missing.md)\n")
        myFixture.enableInspections(inspection("org.intellij.plugins.markdown.lang.references.paths.MarkdownUnresolvedFileReferenceInspection"))
        val texts = myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.text }
        assertTrue(texts.toString(), texts.any { it.contains("missing.md") })
        assertFalse(texts.toString(), texts.any { it.contains("other.md") })
        val destination = PsiTreeUtil.findChildrenOfType(myFixture.file, MarkdownLinkDestination::class.java).first()
        val resolved = destination.references.mapNotNull { it?.resolve() }
        assertTrue("[ok](other.md) must resolve: ${destination.references.toList()}", resolved.any { it is PsiFile && it.name == "other.md" })
    }

    fun testIncorrectTableFormattingInspection() {
        mdc("| a | b |\n|---|---|\n| xxxxxxx | y |\n")
        myFixture.enableInspections(inspection("org.intellij.plugins.markdown.editor.tables.inspections.MarkdownIncorrectTableFormattingInspection"))
        val infos = myFixture.doHighlighting()
        assertTrue(infos.map { it.description }.toString(), infos.any { it.description?.contains("format", ignoreCase = true) == true })
    }

    fun testStructureViewShowsHeaders() {
        mdc("# One\n\n::card\n## Two\n::\n")
        myFixture.testStructureView { component: StructureViewComponent ->
            PlatformTestUtil.expandAll(component.tree)
            val text = PlatformTestUtil.print(component.tree, false)
            assertTrue(text, text.contains("One"))
        }
    }

    fun testMarkdownFolding() {
        mdc("# Title\n\n```kotlin\nval a = 1\nval b = 2\n```\n\n- a\n- b\n")
        val file = myFixture.file
        val builder = LanguageFolding.INSTANCE.forLanguage(file.language)
        val regions = LanguageFolding.buildFoldingDescriptors(builder, file, myFixture.editor.document, false)
            .map { myFixture.editor.document.getText(it.range) }
        assertTrue("the Markdown folding builder runs: $regions", regions.any { it.contains("val a = 1") })
    }

    fun testTodoInComments() {
        // `[//]: # (...)` comments are indexed by the Markdown TODO indexer, `<!-- -->` ones are found through the HTML tree
        val file = mdc("Text\n\n<!-- TODO fix this -->\n\n[//]: # (TODO other)\n\n::card\n[//]: # (TODO in a component)\n::\n")
        val todos = PsiTodoSearchHelper.getInstance(project).findTodoItems(file)
        assertEquals(3, todos.size)
    }

    private fun injectedLanguageAt(file: ComarkFile, marker: String): String? {
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, file.text.indexOf(marker))
        return injected?.containingFile?.language?.id?.lowercase()
    }

    fun testFrontMatterYamlInjection() {
        val file = mdc("---\ntitle: Hello\n---\n\nText\n")
        assertEquals("yaml", injectedLanguageAt(file, "Hello"))
    }

    fun testCodeFenceInjection() {
        val file = mdc("::card\n```yaml\nkey: value\n```\n::\n")
        assertEquals("yaml", injectedLanguageAt(file, "value"))
    }

    fun testHeadersAreIndexed() {
        myFixture.addFileToProject("indexed.mdc", "::card\n## Card Title\n::\n")
        val headers = HeaderAnchorIndex.collectHeaders(project, GlobalSearchScope.allScope(project), "card-title")
        assertEquals(listOf("indexed.mdc"), headers.map { it.containingFile.name })
        assertTrue(headers.all { it.containingFile is ComarkFile })
    }

    // ---- Preview and HTML ------------------------------------------------------------------------------------------

    fun testPreviewEditorIsOffered() {
        val file = myFixture.addFileToProject("doc.mdc", "# Hi\n").virtualFile
        val providers = FileEditorProviderManager.getInstance().getProviderList(project, file).map { it.editorTypeId }
        assertTrue(providers.toString(), providers.contains("split-provider[text-editor;markdown-preview-editor]"))
    }

    fun testPreviewEditorIsOfferedForNuxtContentMarkdown() {
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        val file = myFixture.addFileToProject("site/content/index.md", "::alert\n<b>x</b>\n::\n")
        assertInstanceOf(file, ComarkFile::class.java)
        val providers = FileEditorProviderManager.getInstance().getProviderList(project, file.virtualFile).map { it.editorTypeId }
        assertTrue(providers.toString(), providers.contains("split-provider[text-editor;markdown-preview-editor]"))
        assertTrue(file.viewProvider.languages.any { it.id == "HTML" })
    }

    fun testHtmlBlockIsHtml() {
        val file = mdc("Text\n\n<div class=\"note\">\nhello\n</div>\n\n::card\nIn card\n::\n")
        val html = Language.findLanguageByID("HTML")!!
        assertTrue(file.viewProvider.languages.toString(), html in file.viewProvider.languages)
        val offset = file.text.indexOf("class")
        val element = file.viewProvider.findElementAt(offset, html)
        assertEquals("div", PsiTreeUtil.getParentOfType(element, XmlTag::class.java)?.name)
        // Component lines are not HTML: they are outer elements of the HTML tree
        val componentOffset = file.text.indexOf("::card")
        assertNull(PsiTreeUtil.getParentOfType(file.viewProvider.findElementAt(componentOffset, html), XmlTag::class.java))
        assertEquals("Comark", file.viewProvider.findElementAt(componentOffset)!!.language.id)
    }


    fun testReformatWithHtmlBlocks() {
        val markdownText = "# Title\n\n<div class=\"note\">\n<b>bold</b>\n</div>\n\n* item\n"
        val (markdown, comark) = parity(markdownText) {
            WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        }
        assertEquals(markdown, comark)
        mdc("<div>\n<b>x</b>\n</div>\n\n::card{a=\"1\"}\n<span>in card</span>\n::\n", "components.mdc")
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("::card{a=\"1\"}") && text.contains("<span>in card</span>"))
    }

    // ---- Known limitations -----------------------------------------------------------------------------------------
    // These Markdown features check that the file language is exactly Markdown (`isMarkdownLanguage()`), which a dialect can't
    // satisfy. The tests pin the current behaviour so that the README's list of limitations is revisited when it changes.

    private fun isEnabled(actionId: String): Boolean {
        val original = action(actionId)
        // update() only: the actions would open dialogs or popups when performed
        val probe = object : AnAction() {
            override fun update(e: AnActionEvent) = original.update(e)

            override fun getActionUpdateThread() = original.actionUpdateThread

            override fun actionPerformed(e: AnActionEvent) = Unit
        }
        return myFixture.testAction(probe).isEnabledAndVisible
    }

    fun testLimitationCreateLinkAndInsertImageActions() {
        mdc("Some <selection>word</selection> here\n")
        assertFalse(isEnabled("Markdown.Styling.CreateLink"))
        assertFalse(isEnabled("org.intellij.plugins.markdown.ui.actions.styling.InsertImageAction"))
        myFixture.configureByText("plain.md", "Some <selection>word</selection> here\n")
        assertTrue("the same actions work in Markdown files", isEnabled("Markdown.Styling.CreateLink"))
    }

    fun testLimitationTableColumnAndRowActions() {
        mdc("| a | b |\n|---|---|\n| x<caret> | y |\n")
        assertFalse(isEnabled("Markdown.Table.InsertTableColumn.InsertAfter"))
        assertFalse(isEnabled("Markdown.Table.InsertRow.InsertBelow"))
        myFixture.configureByText("plain.md", "| a | b |\n|---|---|\n| x<caret> | y |\n")
        assertTrue("the same actions work in Markdown files", isEnabled("Markdown.Table.InsertTableColumn.InsertAfter"))
    }

    fun testLimitationHtmlAnchorsAreNotLinkTargets() {
        mdc("<a id=\"custom\"></a>\n\n[x](#custom)\n")
        myFixture.enableInspections(inspection("org.intellij.plugins.markdown.model.psi.headers.UnresolvedHeaderReferenceInspection"))
        assertEquals(listOf("custom"), myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.text })
    }
}
