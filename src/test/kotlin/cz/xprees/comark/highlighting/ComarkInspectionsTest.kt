package cz.xprees.comark.highlighting

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ComarkInspectionsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(ComarkUnclosedComponentInspection(), ComarkIgnoredOpenerTextInspection())
    }

    private fun problems(text: String, fileName: String = "test.mdc"): List<HighlightInfo> {
        myFixture.configureByText(fileName, text)
        return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description != null }
    }

    private fun HighlightInfo.text() = myFixture.editor.document.getText(TextRange(startOffset, endOffset))

    private fun applyFix(before: String, fixName: String, after: String) {
        myFixture.configureByText("fix.mdc", before)
        myFixture.launchAction(myFixture.findSingleIntention(fixName))
        myFixture.checkResult(after)
    }

    fun testInspectionsAreRegisteredWithDescriptions() {
        for (shortName in listOf("ComarkUnclosedComponent", "ComarkIgnoredOpenerText")) {
            val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == shortName }
            // explicit getters: the same-named public fields hold only the raw attributes
            assertEquals("Comark", ep.getGroupDisplayName())
            assertFalse(ep.getDisplayName().isNullOrBlank())
            assertTrue(ep.enabledByDefault)
            val tool = ep.instantiateTool()
            assertEquals(shortName, tool.shortName)
            assertNotNull("description of $shortName", LocalInspectionToolWrapper(ep).loadDescription())
        }
    }

    fun testOnlyInComarkFiles() {
        assertEquals(emptyList<String>(), problems("::alert{.x} junk\ntext\n", "plain.md").map { it.text() })
    }

    // ---- Unclosed components ----------------------------------------------------------------------------------------

    fun testUnclosedComponentIsReported() {
        myFixture.configureByText(
            "test.mdc",
            "<weak_warning descr=\"Component 'alert' is not closed; it extends to the end of its parent (add '::')\">::alert</weak_warning>{type=\"info\"}\ntext\n",
        )
        myFixture.checkHighlighting(false, false, true)
    }

    fun testClosedComponentsAreFine() {
        assertEquals(emptyList<String>(), problems("::alert\ntext\n::\n\n::outer\n  :::inner\n  x\n  :::\n::\n").map { it.text() })
    }

    fun testNestedUnclosedComponent() {
        val problems = problems("::outer\n:::inner\ntext\n::\n")
        assertEquals(listOf(":::inner"), problems.map { it.text() })
        assertEquals(HighlightSeverity.WEAK_WARNING, problems.single().severity)
    }

    fun testInsertClosingMarker() {
        applyFix("::al<caret>ert\ntext\n", "Insert closing '::'", "::alert\ntext\n::\n")
    }

    fun testInsertClosingMarkerWithoutContentAtEof() {
        applyFix("::divider<caret>", "Insert closing '::'", "::divider\n::")
    }

    fun testInsertClosingMarkerBeforeParentClose() {
        applyFix(
            "::outer\n  :::inner<caret>\n  text\n\n::\n",
            "Insert closing ':::'",
            "::outer\n  :::inner\n  text\n  :::\n\n::\n",
        )
    }

    fun testInsertClosingMarkerSameDepthNesting() {
        // `::b` is closed by the first `::`, which leaves `::a` open
        applyFix("::a<caret>\n::b\nx\n::\n\nmore\n", "Insert closing '::'", "::a\n::b\nx\n::\n\nmore\n::\n")
    }

    fun testClosingInsertionKeepsIndentationCharacters() {
        // the opener's own indentation is copied as is (tabs included)
        val text = "x\n \t:::inner\n \ttext\n\n"
        val start = text.indexOf(":::")
        val (offset, insertion) = ComarkUnclosedComponentInspection.InsertClosingMarkerFix.closingInsertion(text, TextRange(start, text.length), 3)
        assertEquals(text.indexOf("text") + 4, offset)
        assertEquals("\n \t:::", insertion)
    }

    // ---- Ignored opener text ------------------------------------------------------------------------------------------

    fun testIgnoredOpenerTextIsReported() {
        val problems = problems("::alert{type=\"info\"} Read this first\n::\n")
        assertEquals(listOf("Read this first"), problems.map { it.text() })
        assertEquals(HighlightSeverity.WARNING, problems.single().severity)
        assertEquals("Text after the component's content and attributes is ignored", problems.single().description)
    }

    fun testRemoveIgnoredText() {
        applyFix("::alert{.x} trai<caret>ling\n::\n", "Remove ignored text", "::alert{.x}\n::\n")
    }

    fun testMoveContentBeforeAttributes() {
        applyFix("::badge{color=\"green\"} [Ne<caret>w]\n::\n", "Move content before attributes", "::badge[New]{color=\"green\"}\n::\n")
    }

    fun testMoveIsNotOfferedWhenContentExists() {
        myFixture.configureByText("fix.mdc", "::badge[Old]{.x} [Ne<caret>w]\n::\n")
        assertEmpty(myFixture.filterAvailableIntentions("Move content before attributes"))
        assertNotEmpty(myFixture.filterAvailableIntentions("Remove ignored text"))
    }
}
