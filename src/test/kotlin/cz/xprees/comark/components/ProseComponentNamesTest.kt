package cz.xprees.comark.components

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import cz.xprees.comark.meta.ComarkUnknownComponentInspection
import cz.xprees.comark.meta.ComponentNaming

/**
 * `Prose*` components written without their prefix (`::accordion-item` for `ProseAccordionItem`), the way Comark's renderer and
 * Nuxt UI's Nuxt Content mapping resolve them.
 */
class ProseComponentNamesTest : ComponentsTestBase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        myFixture.addFileToProject("site/components/prose/AccordionItem.vue", CARD)
        myFixture.addFileToProject("site/components/ProseCallout.vue", CARD)
        myFixture.addFileToProject("site/components/ProseNote.vue", CARD)
        myFixture.addFileToProject("site/components/Note.vue", BADGE)
        myFixture.addFileToProject("site/components/ProseP.vue", CARD)
    }

    private fun page(text: String): PsiFile {
        val file = myFixture.addFileToProject("site/content/page.md", text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.file
    }

    private fun vueFile(path: String): PsiFile =
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("site/components/$path"))!!

    fun testShortNames() {
        assertEquals("accordion-item", ComponentNaming.proseShortName("ProseAccordionItem"))
        assertEquals("callout", ComponentNaming.proseShortName("prose-callout"))
        assertNull(ComponentNaming.proseShortName("ProseP"))
        assertNull(ComponentNaming.proseShortName("ProseH2"))
        assertNull(ComponentNaming.proseShortName("Prose"))
        assertNull(ComponentNaming.proseShortName("Prosecco"))
        assertNull(ComponentNaming.proseShortName("Alert"))
    }

    fun testIndexFindsBothNames() {
        val index = index()
        assertEquals("ProseAccordionItem", index.find("accordion-item")?.name)
        assertEquals("ProseAccordionItem", index.find("AccordionItem")?.name)
        assertEquals("ProseAccordionItem", index.find("prose-accordion-item")?.name)
        assertEquals("ProseCallout", index.find("callout")?.name)
        // A component of the exact name wins over the prose one.
        assertEquals("Note", index.find("note")?.name)
        assertEquals("ProseNote", index.find("prose-note")?.name)
        // Prose renderers of Markdown tags are not components written by hand.
        assertNull(index.find("p"))
    }

    fun testCompletionOffersShortNames() {
        page("::<caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertContainsElements(items, "accordion-item", "callout", "note", "prose-note", "prose-p")
        assertDoesntContain(items, "prose-accordion-item", "prose-callout")
    }

    fun testCompletionInsertsShortName() {
        page("::accord<caret>")
        val items = myFixture.completeBasic()
        assertTrue(items == null || items.isEmpty())
        myFixture.checkResult("::accordion-item\n<caret>\n::")
    }

    fun testCompletionKeepsTypedProsePrefix() {
        page("::prose-accord<caret>")
        val items = myFixture.completeBasic()
        assertTrue(items == null || items.isEmpty())
        myFixture.checkResult("::prose-accordion-item\n<caret>\n::")
    }

    fun testPascalCaseCompletionInsertsShortName() {
        page("::Accord<caret>")
        val items = myFixture.completeBasic()
        if (items != null && items.isNotEmpty()) {
            myFixture.lookup.currentItem = items.first { it.lookupString == "accordion-item" }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
        myFixture.checkResult("::AccordionItem\n<caret>\n::")
    }

    fun testBothNamesAreKnown() {
        myFixture.enableInspections(ComarkUnknownComponentInspection())
        page(
            """
            ::accordion-item
            ::

            ::prose-accordion-item
            ::

            Inline :callout and :prose-callout.

            ::<weak_warning descr="Unknown component 'accordion-itme'">accordion-itme</weak_warning>
            ::
            """.trimIndent() + "\n",
        )
        myFixture.checkHighlighting(false, false, true)
    }

    fun testFindUsagesAndRenameCoverShortNames() {
        page("::accordion-item\n::\n\n::prose-accordion-item\n::\n\n:::AccordionItem\n:::\n")
        assertEquals(3, myFixture.findUsages(vueFile("prose/AccordionItem.vue")).size)
        myFixture.renameElement(vueFile("prose/AccordionItem.vue"), "Panel.vue")
        assertEquals("::panel\n::\n\n::prose-panel\n::\n\n:::Panel\n:::\n", myFixture.editor.document.text)
    }
}
