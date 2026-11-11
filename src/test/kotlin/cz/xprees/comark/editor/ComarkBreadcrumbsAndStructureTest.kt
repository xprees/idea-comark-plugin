package cz.xprees.comark.editor

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.breadcrumbs.BreadcrumbsUtil
import com.intellij.xml.breadcrumbs.BreadcrumbsUtilEx
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

class ComarkBreadcrumbsAndStructureTest : BasePlatformTestCase() {
    private val document = """
        |# Guide
        |
        |Intro
        |
        |## Setup
        |
        |::card{title="Install"}
        |---
        |icon: x
        |---
        |Default slot
        |
        |### In card
        |
        |#footer
        |  :::button{to="/next"}
        |  Next
        |  :::
        |::
        |
        |### Details
        |
        |# Reference
        |""".trimMargin()

    private fun crumbs(text: String): List<String> {
        myFixture.configureByText("test.mdc", text)
        return myFixture.breadcrumbsAtCaret.map { it.text }
    }

    fun testProviderIsUsedForComarkOnly() {
        assertInstanceOf(BreadcrumbsUtil.getInfoProvider(ComarkLanguage), ComarkBreadcrumbsProvider::class.java)
        assertFalse(BreadcrumbsUtil.getInfoProvider(MarkdownLanguage.INSTANCE) is ComarkBreadcrumbsProvider)
        // Markdown hides its (empty) breadcrumbs by default; Comark shows them
        assertTrue(BreadcrumbsUtilEx.isBreadcrumbsShownFor(ComarkLanguage))
    }

    fun testBreadcrumbsShowComponentAndSlotNesting() {
        assertEquals(listOf("card", "#footer", "button"), crumbs(document.replace("  Next", "  Ne<caret>xt")))
        assertEquals(listOf("card"), crumbs(document.replace("Default slot", "Default <caret>slot")))
        assertEquals(listOf("card"), crumbs(document.replace("---\nicon", "-<caret>--\nicon")))
        assertEquals(emptyList<String>(), crumbs(document.replace("Intro", "In<caret>tro")))
    }

    fun testBreadcrumbTooltipAndIcon() {
        myFixture.configureByText("test.mdc", "::card{title=\"x\"}\n#footer{.small}\ntext\n::")
        val component = (myFixture.file as ComarkFile).components.single()
        val provider = ComarkBreadcrumbsProvider()
        assertEquals("::card{title=\"x\"}", provider.getElementTooltip(component))
        assertEquals("#footer{.small}", provider.getElementTooltip(component.slots.single()))
        assertNotNull(provider.getElementIcon(component))
    }

    private fun structureModel(fileName: String, text: String): StructureViewModel {
        val file = myFixture.configureByText(fileName, text)
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(myFixture.editor)
        Disposer.register(testRootDisposable, model)
        return model
    }

    private fun StructureViewTreeElement.dump(depth: Int = 0, out: StringBuilder = StringBuilder()): String {
        if (depth > 0) out.append("  ".repeat(depth - 1)).append(presentation.presentableText).append('\n')
        for (child in children) (child as StructureViewTreeElement).dump(depth + 1, out)
        return out.toString()
    }

    fun testStructureViewShowsHeadersComponentsAndSlots() {
        val model = structureModel("test.mdc", document)
        assertEquals(
            """
            |Guide
            |  Setup
            |    card
            |      In card
            |      #footer
            |        button
            |    Details
            |Reference
            |""".trimMargin(),
            model.root.dump(),
        )
    }

    fun testStructureViewSelectsElementAtCaret() {
        val model = structureModel("test.mdc", document.replace("  Next", "  Ne<caret>xt"))
        assertEquals("button", (model.currentEditorElement as ComarkBlockComponent).componentName)

        val inSection = structureModel("test.mdc", document.replace("Intro", "In<caret>tro"))
        assertEquals("Guide", (inSection.currentEditorElement as MarkdownHeader).name)

        val inCard = structureModel("test.mdc", document.replace("Default slot", "Default <caret>slot"))
        assertEquals("card", (inCard.currentEditorElement as ComarkBlockComponent).componentName)
    }

    fun testMarkdownStructureViewIsUnchanged() {
        val model = structureModel("plain.md", "# A\n\n## B\n\n::card\n::\n\n# C\n")
        assertFalse(myFixture.file is ComarkFile)
        assertEquals("A\n  B\nC\n", model.root.dump())
    }
}
