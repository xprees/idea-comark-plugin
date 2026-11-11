package cz.xprees.comark.images

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import cz.xprees.comark.highlighting.ComarkMarkdownInspectionSuppressor
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings

/** Missing image paths are reported once, in detected Nuxt projects, with a fix to the closest existing path. */
class ComarkMissingImageInspectionTest : NuxtImagesTestBase() {
    override fun setUp() {
        super.setUp()
        // from the registrations, so that the YAML one is the one that checks injected YAML
        myFixture.enableInspections(
            inspection("ComarkMissingImage"), inspection("ComarkMissingImageYaml"),
            inspection(ComarkMarkdownInspectionSuppressor.UNRESOLVED_FILE_REFERENCE),
        )
    }

    private fun project() {
        nuxtProject()
        webp("site/public/threats/phishing.webp")
        image("site/public/logo.png")
    }

    /** Opens [text] at [path], the caret at `<caret>`. */
    private fun configure(text: String, path: String = "site/content/page.md") {
        val file = page(text.replace("<caret>", ""), path)
        // the project detector re-parses open Markdown files after setUp's changes; let that happen first
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        myFixture.configureFromExistingVirtualFile(file)
        assertInstanceOf(myFixture.file, ComarkFile::class.java)
        text.indexOf("<caret>").takeIf { it >= 0 }?.let(myFixture.editor.caretModel::moveToOffset)
    }

    /** Each reported problem as `text (severity)`, in document order. */
    private fun reported(): List<String> = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
        .filter { it.description != null }
        .sortedBy { it.startOffset }
        .map { "${myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset))} (${it.severity.name})" }

    private fun missing(vararg paths: String) = paths.map { "$it (WEAK WARNING)" }

    /** Fixes are tried in a `.mdc` file: the fixture re-parses the text without its location, where only `.mdc` is Comark. */
    private fun applyFix(before: String, fixName: String, after: String) {
        configure(before, "site/content/fix.mdc")
        myFixture.launchAction(myFixture.findSingleIntention(fixName))
        assertEquals(after, myFixture.editor.document.text)
    }

    fun testRegisteredWithDescriptions() {
        for (shortName in listOf("ComarkMissingImage", "ComarkMissingImageYaml")) {
            val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == shortName }
            assertEquals("Comark", ep.getGroupDisplayName())
            assertFalse(ep.getDisplayName().isNullOrBlank())
            assertTrue(ep.enabledByDefault)
            assertEquals("WEAK WARNING", ep.level)
            assertEquals(shortName, ep.instantiateTool().shortName)
            assertNotNull("description of $shortName", LocalInspectionToolWrapper(ep).loadDescription())
        }
    }

    fun testMissingPathIsReportedOnceTogetherWithTheMarkdownInspection() {
        project()
        configure("![a](/threats/phishing.webp) ![b](/threats/phising.webp) ![c](./missing.png) [d](/docs/guide.pdf)\n")
        val problems = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description != null }
        val ours = problems.single { it.severity == HighlightSeverity.WEAK_WARNING }
        assertEquals("/threats/phising.webp", myFixture.editor.document.getText(TextRange(ours.startOffset, ours.endOffset)))
        assertEquals("Image '/threats/phising.webp' not found in public", ours.description)
        // relative paths and plain links stay with the Markdown plugin
        val markdown = problems.filter { it.severity != HighlightSeverity.WEAK_WARNING }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }
        assertTrue(markdown.toString(), "missing.png" in markdown && "guide.pdf" in markdown)
        assertFalse(markdown.toString(), markdown.any { "phis" in it || it == "threats" })
    }

    fun testEveryKindOfImageValue() {
        project()
        configure(
            "---\nimage: /og.png\ntitle: /not-an-image\n---\n\n![x][pic]\n\n[pic]: /pic.png\n\n" +
                "::hero{src=\"/hero.png\" title=\"/t.png\"}\n---\ncover: /cover.png\n---\nSee :img{src=\"/inline.png\"} and ![](/logo.png).\n::\n",
        )
        assertEquals(missing("/og.png", "/pic.png", "/hero.png", "/cover.png", "/inline.png"), reported())
    }

    fun testPrefixesAndQueryAreLeftOut() {
        nuxtProject(nuxtConfig = "app: { baseURL: '/docs/' },")
        image("site/public/logo.png")
        configure("![a](/docs/_ipx/w_200/logo.png?v=2) ![b](/docs/_ipx/w_200/nope.png?v=2#x)\n")
        assertEquals(missing("/nope.png"), reported())
    }

    fun testSilentCases() {
        nuxtProject(nuxtConfig = "image: { alias: { unsplash: 'https://images.unsplash.com' } },")
        configure(
            "![a](https://example.com/x.png) ![b](//cdn.example.com/x.png) ![c](/unsplash/photo.jpg) ![d](x.png) ![e](./x.png)\n\n" +
                "![f](/_nuxt/x.png) `![g](/x.png)`\n\n```md\n![h](/x.png)\n```\n\n::hero{:image=\"/x.png\" title=\"/x.png\"}\n::\n",
        )
        val reported = reported()
        assertFalse(reported.toString(), reported.any { "WEAK WARNING" in it })
        // an alias path is a remote image: the Markdown plugin doesn't report it either (relative paths stay with it)
        assertFalse(reported.toString(), reported.any { "unsplash" in it || "photo" in it })
    }

    fun testSilentWhenImagesAreDisabled() {
        project()
        ComarkSettings.getInstance().imagesEnabled = false
        configure("---\nimage: /og.png\n---\n\n![b](/nope.png) :img{src=\"/x.png\"}\n")
        assertFalse(reported().any { "WEAK WARNING" in it })
    }

    fun testSilentOutsideNuxtProjects() {
        image("other/public/logo.png")
        configure("![a](/nope.png) ::hero{src=\"/x.png\"}\n::\n", "other/page.mdc")
        assertFalse(reported().any { "WEAK WARNING" in it })
    }

    fun testNuxtContentDependencyAloneIsNuxt() {
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        configure("![a](/nope.png)\n")
        assertEquals(missing("/nope.png"), reported())
    }

    fun testNuxtConfigAloneIsNuxt() {
        myFixture.addFileToProject("site/nuxt.config.ts", "export default defineNuxtConfig({})\n")
        // without a Comark package only .mdc files are Comark
        configure("![a](/nope.png)\n", "site/content/page.mdc")
        assertEquals(missing("/nope.png"), reported())
    }

    fun testFixInDestination() {
        project()
        applyFix(
            "![a](/threats/phis<caret>ing.webp?v=1)\n",
            "Change to '/threats/phishing.webp'",
            "![a](/threats/phishing.webp?v=1)\n",
        )
    }

    fun testFixInAttribute() {
        project()
        applyFix(
            "::hero{src=\"/threats/phis<caret>ing.webp\"}\n::\n",
            "Change to '/threats/phishing.webp'",
            "::hero{src=\"/threats/phishing.webp\"}\n::\n",
        )
    }

    fun testFixInInlineComponent() {
        project()
        applyFix(
            "See :img{src=\"/lgo<caret>.png\"} here.\n",
            "Change to '/logo.png'",
            "See :img{src=\"/logo.png\"} here.\n",
        )
    }

    fun testFixInFrontMatter() {
        project()
        applyFix(
            "---\nimage: /threats/phis<caret>ing.webp\n---\n\n# Page\n",
            "Change to '/threats/phishing.webp'",
            "---\nimage: /threats/phishing.webp\n---\n\n# Page\n",
        )
    }

    fun testFixInPropsBlock() {
        project()
        applyFix(
            "::hero\n---\ncover: \"/threats/phis<caret>ing.webp\"\n---\n::\n",
            "Change to '/threats/phishing.webp'",
            "::hero\n---\ncover: \"/threats/phishing.webp\"\n---\n::\n",
        )
    }

    fun testNoFixWhenNothingIsClose() {
        project()
        configure("![a](/something/entirely-<caret>different.jpg)\n")
        assertEquals(missing("/something/entirely-different.jpg"), reported())
        assertEmpty(myFixture.filterAvailableIntentions("Change to"))
    }

    fun testClosestPath() {
        val entries = listOf("/logo.png", "/threats/phishing.webp", "/threats/malware.webp").map { ImageEntry(it, myFixture.tempDirFixture.getFile("")!!, dir) }
        assertEquals("/threats/phishing.webp", ComarkMissingImageInspection.closest("/threats/phising.webp", entries))
        assertEquals("/threats/phishing.webp", ComarkMissingImageInspection.closest("/threat/phishing.png", entries))
        assertEquals("/logo.png", ComarkMissingImageInspection.closest("/lgo.png", entries))
        assertNull(ComarkMissingImageInspection.closest("/team/alice.jpg", entries))
    }

    private val dir = ImageDir("/site/public", ImageDirKind.PUBLIC, "/site", "public", true)

    /** Instantiated from the registration (the Markdown inspections are internal classes). */
    private fun inspection(shortName: String): LocalInspectionTool =
        LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == shortName }.instantiateTool() as LocalInspectionTool
}
