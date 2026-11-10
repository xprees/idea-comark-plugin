package cz.xprees.comark

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.psi.ComarkPropsBlock
import cz.xprees.comark.psi.ComarkSlot
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownCodeFence
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownParagraph
import java.io.File

class ParserTest : BasePlatformTestCase() {
    override fun getTestDataPath() = "src/test/testData"

    private fun parse(text: String): ComarkFile {
        val file = myFixture.configureByText("test.mdc", text)
        assertInstanceOf(file, ComarkFile::class.java)
        ComarkTestUtil.assertConsistent(file)
        return file as ComarkFile
    }

    private fun ComarkFile.allComponents() = PsiTreeUtil.findChildrenOfType(this, ComarkBlockComponent::class.java).toList()

    fun testCorpusFilesAreConsistent() {
        File(testDataPath, "corpus").listFiles()!!.filter { it.isFile }.forEach { source ->
            val file = myFixture.configureByText(source.nameWithoutExtension + ".mdc", source.readText())
            ComarkTestUtil.assertConsistent(file)
        }
    }

    fun testSimpleComponent() {
        val file = parse("::alert{type=\"info\"}\nThis is **important**!\n::\n")
        val component = file.components.single()
        assertEquals("alert", component.componentName)
        assertEquals(2, component.markerCount)
        assertNotNull(component.closeMarker)
        assertEquals(listOf("type"), component.attributes!!.attributeList.map { it.attributeName })
        assertNotNull(PsiTreeUtil.findChildOfType(component, MarkdownParagraph::class.java))
    }

    fun testEmptyComponentAndText() {
        val file = parse("Before\n\n::divider\n::\n\nAfter")
        assertEquals(listOf("divider"), file.components.map { it.componentName })
        assertEquals(2, PsiTreeUtil.findChildrenOfType(file, MarkdownParagraph::class.java).size)
    }

    fun testComponentInterruptsParagraph() {
        val file = parse("Some text\n::note\nInside\n::\nmore text")
        assertEquals(listOf("note"), file.components.map { it.componentName })
    }

    fun testPropsBlock() {
        val file = parse("::card\n---\ntitle: Hello\nicon: x\n---\nBody\n::")
        val props = file.components.single().propsBlock
        assertNotNull(props)
        assertEquals("title: Hello\nicon: x\n", props!!.content!!.text)
    }

    fun testDashesLaterAreAThematicBreakNotProps() {
        val file = parse("::card\nBody\n\n---\n\nMore\n::")
        assertNull(file.components.single().propsBlock)
    }

    private fun ComarkFile.frontMatterHeaders() =
        PsiTreeUtil.collectElements(this) { it.node.elementType == MarkdownElementTypes.FRONT_MATTER_HEADER }.toList()

    fun testDashesAfterAComponentAreNotFrontMatter() {
        val file = parse("::divider\n::\n---\n\nParagraph after\n")
        assertEmpty(file.frontMatterHeaders())
        assertNotNull(PsiTreeUtil.findChildrenOfType(file, MarkdownParagraph::class.java).singleOrNull { it.text == "Paragraph after" })
    }

    fun testDashesAfterASlotLineAreNotFrontMatter() {
        val file = parse("::card\n#footer\n---\nFooter text\n::\n")
        assertEmpty(file.frontMatterHeaders())
        val slot = file.components.single().slots.single()
        assertNotNull(PsiTreeUtil.findChildrenOfType(slot, MarkdownParagraph::class.java).singleOrNull { it.text == "Footer text" })
    }

    fun testFrontMatterAtTheTopIsKept() {
        val file = parse("---\ntitle: x\n---\n\n::a\n::\n")
        assertEquals(1, file.frontMatterHeaders().size)
    }

    fun testBacktickYamlPropsFenceIsAPropsBlock() {
        val component = parse("::card\n```yaml [props]\ntitle: Hello\ncolor: primary\n```\nContent\n::\n").components.single()
        assertEquals("title: Hello\ncolor: primary\n", component.propsBlock!!.content!!.text)
        assertEmpty(PsiTreeUtil.findChildrenOfType(component, MarkdownCodeFence::class.java))
        assertNotNull(PsiTreeUtil.findChildrenOfType(component, MarkdownParagraph::class.java).singleOrNull { it.text == "Content" })
    }

    fun testTildeYmlPropsFenceInNestedComponentIsAPropsBlock() {
        val file = parse("::outer\n  :::inner\n  ~~~yml [props]\n  title: Hello\n  ~~~\n  Content\n  :::\n::\n")
        val inner = file.allComponents().single { it.componentName == "inner" }
        assertEquals("  title: Hello\n", inner.propsBlock!!.content!!.text)
    }

    fun testPlainYamlFenceStaysACodeFence() {
        val component = parse("::card\n```yaml\ntitle: Hello\n```\n::\n").components.single()
        assertNull(component.propsBlock)
        assertNotNull(PsiTreeUtil.findChildOfType(component, MarkdownCodeFence::class.java))
    }

    fun testSlots() {
        val file = parse("::card\n#header\n## Title\n\n#footer\nFooter\n::")
        val slots = file.components.single().slots
        assertEquals(listOf("header", "footer"), slots.map { it.slotName })
        assertNotNull(PsiTreeUtil.findChildOfType(slots[0], MarkdownHeader::class.java))
    }

    fun testHeadingIsNotASlot() {
        val file = parse("::card\n# Heading\n::")
        assertTrue(file.components.single().slots.isEmpty())
    }

    fun testNestedComponents() {
        val file = parse("::outer\nA\n\n:::inner{variant=\"compact\"}\nB\n:::\n\nC\n::")
        val outer = file.components.single()
        val inner = PsiTreeUtil.getChildrenOfTypeAsList(outer, ComarkBlockComponent::class.java).single()
        assertEquals("inner", inner.componentName)
        assertEquals(3, inner.markerCount)
    }

    fun testSameMarkerNesting() {
        val file = parse("::a\n::b\nx\n::\n::")
        val a = file.components.single()
        assertEquals("a", a.componentName)
        assertEquals("b", PsiTreeUtil.getChildrenOfTypeAsList(a, ComarkBlockComponent::class.java).single().componentName)
    }

    fun testIndentedChildrenAreNotCode() {
        val file = parse("::card\n  :::badge\n    Nested **content**\n  :::\n::")
        val badge = PsiTreeUtil.findChildrenOfType(file, ComarkBlockComponent::class.java).first { it.componentName == "badge" }
        val paragraph = PsiTreeUtil.findChildOfType(badge, MarkdownParagraph::class.java)
        assertNotNull("indented content must be a paragraph", paragraph)
        assertEquals("Nested **content**", paragraph!!.text.trim())
    }

    fun testFenceInsideComponentHidesMarkers() {
        val file = parse("::card\n```md\n::\n::fake\n```\n::")
        val card = file.components.single()
        assertNotNull(card.closeMarker)
        assertNotNull(PsiTreeUtil.findChildOfType(card, MarkdownCodeFence::class.java))
        assertEquals(1, file.allComponents().size)
    }

    fun testTopLevelFenceHidesComponents() {
        val file = parse("```\n::alert\n::\n```\n")
        assertTrue(file.components.isEmpty())
    }

    fun testIndentedCodeIsNotAComponent() {
        val file = parse("Text\n\n    ::alert\n    ::\n")
        assertTrue(file.components.isEmpty())
    }

    fun testUnclosedComponentRunsToEnd() {
        val file = parse("::unclosed\nstill inside\n\nmore")
        val component = file.components.single()
        assertNull(component.closeMarker)
        assertEquals(file.textLength, component.textRange.endOffset)
    }

    fun testInvalidNameIsText() {
        val file = parse("::8100\n::\n")
        assertTrue(file.components.isEmpty())
    }

    fun testShorthandContentAndBadRest() {
        val file = parse("::alert[Hello **there**]{.x} trailing\n::")
        val component = file.components.single()
        assertEquals("alert", component.componentName)
        assertNotNull(component.attributes)
    }

    fun testFrontMatterIsMarkdowns() {
        val file = parse("---\ntitle: x\n---\n\n::a\n::")
        assertEquals(1, file.components.size)
        assertTrue(file.text.startsWith("---"))
    }

    fun testTabsAndCrlf() {
        parse("::card\n\t:::inner\n\ttext\n\t:::\n::\n")
        parse("::card\r\ntext\r\n::\r\n")
    }

    fun testEmptyAndWhitespaceOnly() {
        parse("")
        parse("\n\n  \n")
        parse("::")
        parse(":::\n")
    }

    fun testNestedPropsAndSlots() {
        val file = parse("::card\n---\na: 1\n---\n#title\n  :::inner\n  ---\n  b: 2\n  ---\n  x\n  :::\n::")
        val card = file.components.single()
        val slot = card.slots.single()
        val inner = PsiTreeUtil.findChildOfType(slot, ComarkBlockComponent::class.java)!!
        assertNotNull(inner.propsBlock)
        assertEquals(2, PsiTreeUtil.findChildrenOfType(file, ComarkPropsBlock::class.java).size)
        assertEquals(1, PsiTreeUtil.findChildrenOfType(file, ComarkSlot::class.java).size)
    }

    fun testMdFileWithoutNuxtIsPlainMarkdown() {
        val file = myFixture.configureByText("plain.md", "::alert\n::")
        assertFalse(file is ComarkFile)
    }

    fun testMdFileInNuxtContentProjectIsComark() {
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        val file = myFixture.addFileToProject("site/content/index.md", "::alert\n::")
        assertInstanceOf(file, ComarkFile::class.java)
        val other = myFixture.addFileToProject("elsewhere/readme.md", "::alert\n::")
        assertFalse(other is ComarkFile)
    }
}
