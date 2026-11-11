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
            markdownInspection(ComarkMarkdownInspectionSuppressor.UNRESOLVED_FILE_REFERENCE),
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

    fun testLinkToHeadingWithInlineComponentUsesComarkId() {
        val text = "## Install :badge[New]{color=\"green\"}\n\n## 1. Setup [here]{.x}\n\n## Install :badge[New]\n\n" +
            "[a](#install-new) [b](#_1-setup-here) [c](#install-new-1) [d](#missing)\n"
        assertEquals(listOf("missing"), reported("anchors.mdc", text))
    }

    fun testNuxtContentRouteLinksAreNotUnresolvedFiles() {
        myFixture.addFileToProject("site/package.json", """{ "devDependencies": { "@nuxt/content": "^3.0.0" } }""")
        myFixture.addFileToProject("site/content/1.guide/2.install.md", "# Install\n")
        val page = myFixture.addFileToProject(
            "site/content/index.md",
            "# Home\n\nRead the [install guide](/guide/install#setup) or [this](/missing/logo.png).\n",
        )
        myFixture.configureFromExistingVirtualFile(page.virtualFile)
        val reported = myFixture.doHighlighting()
            .filter { it.description != null && it.severity >= HighlightSeverity.WEAK_WARNING }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }
        assertFalse(reported.toString(), reported.any { it == "guide" || it == "install" })
        assertTrue(reported.toString(), reported.any { it == "missing" || it == "logo.png" })
    }

    fun testRouteLinksOutsideNuxtContentAreStillChecked() {
        assertNotEmpty(reported("plain.mdc", "[x](/guide/install)\n"))
    }

    fun testIdsGivenAsAttributesAndPropsAreAnchors() {
        val text = "Read [this part]{id=\"part\"} first.\n\n::alert{id=note}\n::\n\n::card\n---\nid: 'intro' # top\n---\nText\n::\n\n" +
            "[a](#part) [b](#note) [c](#intro) [d](#missing)\n"
        assertEquals(listOf("missing"), reported("ids.mdc", text))
    }

    fun testLiteralBracesInsideSpanDefineNoAnchor() {
        assertEquals(listOf("x"), reported("span-anchor.mdc", "See [docs {#x}] here.\n\n[x](#x)\n"))
    }
}
