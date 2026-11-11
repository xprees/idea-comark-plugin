package cz.xprees.comark.components

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import cz.xprees.comark.navigation.ComarkComponentReference
import cz.xprees.comark.psi.ComarkFile
import java.io.File

/** References from component names to `.vue` files, Find Usages of a component file and Rename of it. */
class ComponentFindUsagesTest : ComponentsTestBase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        myFixture.addFileToProject("site/components/content/Alert.vue", ALERT)
        myFixture.addFileToProject("site/components/content/prose/Card.vue", CARD)
        myFixture.addFileToProject("site/components/Badge.vue", BADGE)
    }

    private fun vueFile(path: String): PsiFile =
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("site/components/$path"))!!

    /** Opens `site/content/[name]` (Comark: its package depends on Nuxt Content) with [text]. */
    private fun page(text: String, name: String = "page.md"): PsiFile {
        val file = myFixture.addFileToProject("site/content/$name", text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertInstanceOf(myFixture.file, ComarkFile::class.java)
        return myFixture.file
    }

    private fun referenceAtCaret(): PsiReference? = myFixture.file.findReferenceAt(myFixture.caretOffset)

    private fun resolvedFileAtCaret(): String? = (referenceAtCaret()?.resolve() as? PsiFile)?.name

    /** Each usage as `line text` with the name in brackets, sorted by offset. */
    private fun usages(target: PsiFile): List<String> = myFixture.findUsages(target).map { usage ->
        val file = usage.file!!
        val range = usage.segment!!
        val text = file.text
        val lineStart = text.lastIndexOf('\n', range.startOffset - 1) + 1
        val lineEnd = text.indexOf('\n', range.endOffset).let { if (it < 0) text.length else it }
        range.startOffset to text.substring(lineStart, range.startOffset) + "[" + text.substring(range.startOffset, range.endOffset) + "]" +
            text.substring(range.endOffset, lineEnd)
    }.sortedBy { it.first }.map { it.second }

    // ---- references -------------------------------------------------------------------------------------------------

    fun testBlockNameResolvesToVueFile() {
        page("::al<caret>ert{type=\"info\"}\ntext\n::\n")
        assertEquals("Alert.vue", resolvedFileAtCaret())
        val reference = referenceAtCaret() as ComarkComponentReference
        assertTrue(reference.isSoft)
        assertEquals("alert", reference.rangeInElement.substring(reference.element.text))
    }

    fun testNestedBlockNameResolves() {
        page("::alert\n:::prose-ca<caret>rd\nx\n:::\n::\n")
        assertEquals("Card.vue", resolvedFileAtCaret())
    }

    fun testInlineNameResolves() {
        page("Some :ba<caret>dge[New]{color=\"x\"} text\n")
        assertEquals("Badge.vue", resolvedFileAtCaret())
        assertEquals("badge", referenceAtCaret()!!.canonicalText)
    }

    fun testInlineNameInComponentContentAndTableResolves() {
        page("::alert[see :car<caret>d]\n::\n")
        assertEquals("Card.vue", resolvedFileAtCaret())
        page("| a |\n|---|\n| :Prose<caret>Card |\n", "table.md")
        assertEquals("Card.vue", resolvedFileAtCaret())
    }

    fun testUnknownOrFilelessComponentIsSoftAndUnresolved() {
        myFixture.addFileToProject("site/.nuxt/component-meta.mjs", File("src/test/testData/components/component-meta.mjs").readText())
        assertNull(index().find("prose-a")!!.file)
        page("::prose-<caret>a\n::\n")
        val fileless = referenceAtCaret()!!
        assertTrue(fileless.isSoft)
        assertNull(fileless.resolve())
        page("Some :no<caret>pe here\n", "unknown.md")
        val unknown = referenceAtCaret()!!
        assertTrue(unknown.isSoft)
        assertNull(unknown.resolve())
    }

    fun testNoReferenceOnPlainTextOrCode() {
        page("The al<caret>ert word\n")
        assertNull(referenceAtCaret())
        page("Code `:al<caret>ert` here\n", "code.md")
        assertNull(referenceAtCaret())
    }

    // ---- Find Usages ------------------------------------------------------------------------------------------------

    private val alertPage = """
        # Intro :alert

        ::alert{type="info"}
        Some text with :badge and :alert[inline]{type="x"}.

        :::alert
        nested
        :::
        ::

        ::Alert
        ::

        The alert word, alert-ish text and :alerts here.
        Code `:alert` is not a usage.
    """.trimIndent() + "\n"

    fun testFindUsagesOfVueFile() {
        page(alertPage)
        assertEquals(
            listOf(
                "# Intro :[alert]",
                "::[alert]{type=\"info\"}",
                "Some text with :badge and :[alert][inline]{type=\"x\"}.",
                ":::[alert]",
                "::[Alert]",
            ),
            usages(vueFile("content/Alert.vue")),
        )
        assertEquals(listOf("Some text with :[badge] and :alert[inline]{type=\"x\"}."), usages(vueFile("Badge.vue")))
    }

    fun testFindUsagesFromUsage() {
        page(alertPage.replace("::Alert", "::Al<caret>ert"))
        val target = myFixture.elementAtCaret
        assertEquals("Alert.vue", (target as PsiFile).name)
        assertEquals(5, usages(target).size)
    }

    fun testFindUsagesOfPrefixedComponent() {
        page("::prose-card\n::\n\nAlias :card and :ProseCard[x], not :prose or prose card.\n")
        assertEquals(
            listOf("::[prose-card]", "Alias :[card] and :ProseCard[x], not :prose or prose card.", "Alias :card and :[ProseCard][x], not :prose or prose card."),
            usages(vueFile("content/prose/Card.vue")),
        )
    }

    fun testFindUsagesIgnoresPlainMarkdownOutsideNuxtContent() {
        page("::alert\n::\n")
        myFixture.addFileToProject("other/readme.md", "::alert\n::\n")
        assertEquals(listOf("::[alert]"), usages(vueFile("content/Alert.vue")))
    }

    fun testFindUsagesOfUnusedComponent() {
        page("Nothing but a badge and the words alert, prose and card.\n")
        assertEmpty(usages(vueFile("content/Alert.vue")))
        assertEmpty(usages(vueFile("Badge.vue")))
    }

    // ---- Rename -----------------------------------------------------------------------------------------------------

    fun testRenameVueFileUpdatesUsages() {
        page(alertPage)
        val readme = myFixture.addFileToProject("other/readme.md", "::alert\n::\n")
        myFixture.renameElement(vueFile("content/Alert.vue"), "Callout.vue")
        assertEquals(
            """
            # Intro :callout

            ::callout{type="info"}
            Some text with :badge and :callout[inline]{type="x"}.

            :::callout
            nested
            :::
            ::

            ::Callout
            ::

            The alert word, alert-ish text and :alerts here.
            Code `:alert` is not a usage.
            """.trimIndent() + "\n",
            myFixture.editor.document.text,
        )
        assertEquals("::alert\n::\n", readme.text)
        assertNotNull(myFixture.findFileInTempDir("site/components/content/Callout.vue"))
    }

    fun testRenameKeepsPrefixAliasAndStyle() {
        page("::prose-card\n::\n\n:::ProseCard\n:::\n\nAlias :card and :proseCard[x].\n")
        myFixture.renameElement(vueFile("content/prose/Card.vue"), "InfoPanel.vue")
        assertEquals(
            "::prose-info-panel\n::\n\n:::ProseInfoPanel\n:::\n\nAlias :info-panel and :proseInfoPanel[x].\n",
            myFixture.editor.document.text,
        )
    }

    fun testRenameSeveralUsagesInOneText() {
        page("::alert[see :badge and :badge]\n::\n\n:badge :badge{a=\"b\"} :badge\n")
        myFixture.renameElement(vueFile("Badge.vue"), "Chip.vue")
        assertEquals("::alert[see :chip and :chip]\n::\n\n:chip :chip{a=\"b\"} :chip\n", myFixture.editor.document.text)
    }

    fun testRenameFromUsage() {
        page("Inline :al<caret>ert here.\n\n::alert\n::\n")
        myFixture.renameElementAtCaret("Notice.vue")
        assertEquals("Inline :notice here.\n\n::notice\n::\n", myFixture.editor.document.text)
        assertNotNull(myFixture.findFileInTempDir("site/components/content/Notice.vue"))
    }
}
