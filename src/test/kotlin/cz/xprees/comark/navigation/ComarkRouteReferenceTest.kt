package cz.xprees.comark.navigation

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.highlighting.ComarkMarkdownInspectionSuppressor
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/** Nuxt Content page routes in link destinations resolve to the content files they render. */
class ComarkRouteReferenceTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("site/package.json", """{ "devDependencies": { "@nuxt/content": "^3.0.0" } }""")
        myFixture.addFileToProject("site/content/index.md", "# Home\n")
        myFixture.addFileToProject("site/content/1.guide/index.md", "# Guide\n")
        myFixture.addFileToProject("site/content/1.guide/2.install.md", "# Install\n\n## Setup\n\n## Options {#custom-options}\n")
        myFixture.addFileToProject("site/content/2.about.mdc", "# About\n")
        myFixture.addFileToProject("site/content/3.data.yml", "title: Data\n")
        myFixture.addFileToProject("site/content/_partial.md", "Partial\n")
    }

    /** Opens [text] as `site/content/[name]` with the caret at `<caret>`. */
    private fun configure(text: String, name: String = "page.md") {
        val file = myFixture.addFileToProject("site/content/$name", text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        text.indexOf("<caret>").takeIf { it >= 0 }?.let(myFixture.editor.caretModel::moveToOffset)
    }

    private fun routeReferences(): List<ComarkRouteReference> =
        myFixture.file.let { file ->
            PsiTreeUtil.findChildrenOfType(file, MarkdownLinkDestination::class.java)
                .flatMap { it.references.filterIsInstance<ComarkRouteReference>() }
        }

    /** Content-relative path of the file [link] (a route link in a Comark file of the package) resolves to, or null. */
    private fun target(link: String): String? {
        configure("[x]($link)\n", "source-${link.hashCode().toUInt()}.md")
        val reference = routeReferences().singleOrNull() ?: return "<no reference>"
        val file = reference.resolve() as? PsiFile ?: return null
        return file.virtualFile.path.substringAfter("/site/content/")
    }

    fun testRoutesResolveToContentFiles() {
        assertEquals("index.md", target("/"))
        assertEquals("1.guide/index.md", target("/guide"))
        assertEquals("1.guide/index.md", target("/guide/"))
        assertEquals("1.guide/2.install.md", target("/guide/install"))
        assertEquals("1.guide/2.install.md", target("/guide/install#setup"))
        assertEquals("1.guide/2.install.md", target("/guide/install?tab=npm#setup"))
        assertEquals("2.about.mdc", target("/about"))
    }

    fun testUnknownRoutesDoNotResolve() {
        assertNull(target("/missing"))
        assertNull(target("/guide/missing"))
        assertNull(target("/1.guide/install"))
        // a dot in the last segment reads as a file extension: a file link, not a route
        assertEquals("<no reference>", target("/1.guide/2.install"))
        assertNull(target("/data"))
        assertNull(target("/partial"))
        assertNull(target("/Guide/Install"))
    }

    fun testNoReferencesForOtherLinks() {
        configure("[a](guide/install) [b](./about) [c](/guide/install.md) [d](https://example.com/guide) [e](//cdn.example.com/x) [f](#setup)\n")
        assertEmpty(routeReferences())
    }

    fun testNoReferencesOutsideNuxtContentPackages() {
        myFixture.addFileToProject("other/content/1.guide/2.install.md", "# Install\n")
        val file = myFixture.addFileToProject("other/content/page.mdc", "[x](/guide/install)\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertEmpty(routeReferences())
    }

    fun testReferenceRangeIsThePath() {
        configure("[x](/guide/install?tab=npm#setup)\n")
        val reference = routeReferences().single()
        assertEquals("/guide/install", reference.rangeInElement.substring(reference.element.text))
    }

    fun testGoToDeclarationOpensThePage() {
        configure("Read [the guide](/guide/ins<caret>tall#setup).\n")
        val target = TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted)
        assertInstanceOf(target, PsiFileSystemItem::class.java)
        assertEquals("2.install.md", (target as PsiFileSystemItem).name)
    }

    fun testAnchorsResolveInThePage() {
        myFixture.enableInspections(
            markdownInspection(ComarkMarkdownInspectionSuppressor.UNRESOLVED_HEADER_REFERENCE),
            markdownInspection(ComarkMarkdownInspectionSuppressor.UNRESOLVED_FILE_REFERENCE),
        )
        configure("[a](/guide/install#setup) [b](/guide/install#custom-options) [c](/guide/install#nope) [d](/missing#nope2)\n")
        val reported = myFixture.doHighlighting()
            .filter { it.description != null && it.severity >= HighlightSeverity.WEAK_WARNING }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }
        assertEquals(listOf("nope"), reported)
    }

    fun testRouteCompletion() {
        configure("[x](/gu<caret>)\n")
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings.orEmpty()
        assertContainsElements(lookups, "/guide", "/guide/install")
        assertDoesntContain(lookups, "/about", "/data", "/partial")
    }

    fun testAnchorCompletionUsesThePage() {
        configure("[x](/guide/install#<caret>)\n")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "setup")
    }

    fun testRouteSegments() {
        assertEquals("guide", ComarkContentRoutes.routeSegment("1.guide", true))
        assertEquals("install", ComarkContentRoutes.routeSegment("02.install.md", false))
        assertEquals("", ComarkContentRoutes.routeSegment("1.index.md", false))
        assertEquals("index", ComarkContentRoutes.routeSegment("index", true))
        assertEquals("draft-page", ComarkContentRoutes.routeSegment("Draft Page.draft.md", false))
        assertEquals("1.2.0", ComarkContentRoutes.routeSegment("1.2.0", true))
        assertNull(ComarkContentRoutes.routeSegment("navigation.yml", false))
        assertNull(ComarkContentRoutes.routeSegment(".hidden.md", false))
        assertNull(ComarkContentRoutes.routeSegment("_partial.md", false))
    }

    /** The Markdown inspections are internal classes, so they are instantiated from their registrations. */
    private fun markdownInspection(shortName: String): LocalInspectionTool =
        LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == shortName }.instantiateTool() as LocalInspectionTool
}
