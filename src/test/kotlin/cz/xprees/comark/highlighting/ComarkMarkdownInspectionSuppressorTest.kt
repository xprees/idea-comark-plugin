package cz.xprees.comark.highlighting

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Markdown plugin's link inspections must not report Comark spans or anchors defined by Comark attributes. */
class ComarkMarkdownInspectionSuppressorTest : BasePlatformTestCase() {
    /** The Markdown inspections are internal classes, so they are instantiated from their registrations. */
    private fun markdownInspection(shortName: String): LocalInspectionTool =
        LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == shortName }.instantiateTool() as LocalInspectionTool

    override fun setUp() {
        super.setUp()
        // short links (`[text]`) are only validated with this registry key; spans look exactly like them
        Registry.get("markdown.validate.short.links").setValue(true, testRootDisposable)
        myFixture.enableInspections(
            markdownInspection(ComarkMarkdownInspectionSuppressor.UNRESOLVED_LINK_LABEL),
            markdownInspection(ComarkMarkdownInspectionSuppressor.UNRESOLVED_HEADER_REFERENCE),
        )
    }

    /** Texts of the reported problems. */
    private fun reported(fileName: String, text: String): List<String> {
        myFixture.configureByText(fileName, text)
        return myFixture.doHighlighting()
            .filter { it.description != null && it.severity >= HighlightSeverity.WEAK_WARNING }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }
    }

    fun testSpansAreNotUnresolvedLabels() {
        val text = "Hello [World]{.a} and [plain] span, :badge[New][label] here.\n\nA [real][missing] reference.\n"
        assertEquals(listOf("[missing]"), reported("a.mdc", text))
    }

    fun testPlainMarkdownStillReportsLabels() {
        assertEquals(listOf("[World]"), reported("plain.md", "Hello [World]\n"))
    }

    fun testCheckHighlightingMarkup() {
        myFixture.configureByText("b.mdc", "Hello [World]{.a} and [real]<warning descr=\"Cannot resolve link label [missing]\">[missing]</warning>\n")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testAnchorsDefinedByAttributes() {
        val text = """
            # Product docs {#docs}

            # Intro {.big}

            Some [text]{#marker} and

            ::alert{#note}
            hi
            ::

            [a](#docs) [b](#intro) [c](#marker) [d](#note) [e](#nowhere)
        """.trimIndent()
        val reported = reported("c.mdc", text)
        assertEquals(listOf("nowhere"), reported.map { it.removePrefix("#") })
    }

    fun testAnchorsInAnotherComarkFile() {
        myFixture.addFileToProject("docs/target.mdc", "# Target {#custom}\n")
        val source = myFixture.addFileToProject("docs/source.mdc", "[ok](target.mdc#custom) [bad](target.mdc#other)\n")
        myFixture.configureFromExistingVirtualFile(source.virtualFile)
        val reported = myFixture.doHighlighting()
            .filter { it.description != null && it.severity >= HighlightSeverity.WEAK_WARNING }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }
        assertEquals(listOf("other"), reported.map { it.removePrefix("#") })
    }

    fun testSlug() {
        assertEquals("getting-started", ComarkAnchors.slug(" Getting  Started! "))
        assertEquals("a_b-c", ComarkAnchors.slug("A_b-C"))
        assertEquals("čeština", ComarkAnchors.slug("Čeština"))
    }
}
