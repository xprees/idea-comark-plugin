package cz.xprees.comark.images

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parents
import cz.xprees.comark.highlighting.ComarkMarkdownInspectionSuppressor
import cz.xprees.comark.psi.ComarkAttributeValue
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings
import org.jetbrains.yaml.psi.YAMLScalar
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/** Image paths in Comark documents refer to the files Nuxt serves under them, wherever the path is written. */
class ComarkImageReferenceTest : NuxtImagesTestBase() {
    override fun setUp() {
        super.setUp()
        nuxtProject()
        image("site/public/threats/phishing.png")
        image("site/public/logo.png")
    }

    /** Opens [text] as `site/content/[name]`, the caret at `<caret>`. */
    private fun configure(text: String, name: String = "page.md") {
        val file = page(text.replace("<caret>", ""), "site/content/$name")
        myFixture.configureFromExistingVirtualFile(file)
        text.indexOf("<caret>").takeIf { it >= 0 }?.let(myFixture.editor.caretModel::moveToOffset)
    }

    /** The reference on the first occurrence of [marker] (one character into it). */
    private fun referenceAt(marker: String): ComarkImageReference? {
        val offset = myFixture.editor.document.text.indexOf(marker)
        assertTrue("no $marker", offset >= 0)
        return imageRefsAt(offset + 1).singleOrNull()
    }

    /** Text of the reference's range, and the fixture-relative path of its target (or null). */
    private fun describe(reference: ComarkImageReference?): Pair<String, String?>? {
        reference ?: return null
        val target = (reference.resolve() as? PsiFile)?.virtualFile?.path?.substringAfter("/src/")
        return reference.rangeInElement.substring(reference.element.text) to target
    }

    fun testMarkdownImageDestinations() {
        configure("![a](/threats/phishing.png) ![b](/logo.png \"Logo\") ![c](</logo.png>) ![d](/missing.png)\n")
        assertEquals("/threats/phishing.png" to "site/public/threats/phishing.png", describe(referenceAt("/threats")))
        assertEquals("/logo.png" to "site/public/logo.png", describe(referenceAt("/logo.png \"")))
        assertEquals("/logo.png" to "site/public/logo.png", describe(referenceAt("/logo.png>")))
        assertEquals("/missing.png" to null, describe(referenceAt("/missing")))
    }

    fun testReferenceDefinitions() {
        configure("![a][pic] ![b][Other Pic]\n\n[pic]: /threats/phishing.png\n[other  pic]: /logo\n[file]: /logo.png\n[page]: /guide/install\n")
        assertEquals("site/public/threats/phishing.png", describe(referenceAt("/threats"))?.second)
        assertEquals("/logo" to null, describe(referenceAt("/logo\n")))
        assertEquals("site/public/logo.png", describe(referenceAt("/logo.png"))?.second)
        assertNull(referenceAt("/guide"))
    }

    fun testPlainLinksAndOtherPathsHaveNoReferences() {
        configure(
            "[a](/logo.png) ![b](logo.png) ![c](./logo.png) ![d](https://example.com/logo.png) ![e](//cdn.example.com/logo.png)\n\n" +
                "`![f](/logo.png)`\n\n```md\n![g](/logo.png)\n```\n",
        )
        assertEmpty(imageRefs())
    }

    fun testComponentAttributes() {
        configure("::hero{src=\"/logo.png\" heroImage='/threats/phishing.png' :image=\"/logo.png\" title=\"/logo.png\" thumbnail-src=/logo.png}\n::\n")
        val references = imageRefs()
        assertEquals(listOf("/logo.png", "/threats/phishing.png", "/logo.png"), references.map { it.rangeInElement.substring(it.element.text) })
        assertTrue(references.all { it.element is ComarkAttributeValue && it.resolve() != null })
    }

    fun testSlotAttributes() {
        configure("::hero\n#media{src=\"/logo.png\"}\nText\n::\n")
        assertEquals("site/public/logo.png", describe(referenceAt("/logo.png"))?.second)
    }

    fun testInlineComponentAttributes() {
        configure("Hello :avatar{src=\"/logo.png\" alt=\"/logo.png\"} and :icon{name=\"i-lucide-x\"}.\n\n| a |\n|---|\n| :img{src='/threats/phishing.png'} |\n")
        assertEquals("/logo.png" to "site/public/logo.png", describe(referenceAt("/logo.png\" alt")))
        assertNull(referenceAt("/logo.png\"}"))
        assertEquals("site/public/threats/phishing.png", describe(referenceAt("/threats"))?.second)
        assertTrue(referenceAt("/logo.png\" alt")!!.element is ComarkFile)
    }

    fun testPropsBlocks() {
        configure("::hero\n---\ncover: /logo.png\ntitle: /logo.png\nimage:\n  src: \"/threats/phishing.png\"\n---\n  :::card\n  ---\n  avatar: /logo.png\n  ---\n  :::\n::\n")
        assertEquals("/logo.png" to "site/public/logo.png", describe(referenceAt("/logo.png\ntitle")))
        assertNull(referenceAt("/logo.png\nimage"))
        assertEquals("site/public/threats/phishing.png", describe(referenceAt("/threats"))?.second)
        assertEquals("site/public/logo.png", describe(referenceAt("/logo.png\n  ---"))?.second)
        assertTrue(referenceAt("/logo.png\ntitle")!!.element is YAMLScalar)
    }

    fun testFrontMatter() {
        configure("---\ntitle: /logo.png\nimage: /logo.png\nog:image: /threats/phishing.png\nseo:\n  image: /missing.png\n---\n\n# Page\n")
        assertNull(referenceAt("/logo.png\nimage"))
        assertEquals("site/public/logo.png", describe(referenceAt("/logo.png\nog"))?.second)
        assertEquals("site/public/threats/phishing.png", describe(referenceAt("/threats"))?.second)
        assertEquals("/missing.png" to null, describe(referenceAt("/missing")))
    }

    fun testRangeLeavesOutPrefixesAndQuery() {
        invalidate()
        myFixture.addFileToProject("site/nuxt.config.ts", "export default defineNuxtConfig({ app: { baseURL: '/docs/' } })\n")
        invalidate()
        configure("![a](/docs/_ipx/w_200&f_webp/threats/phishing.png?v=2#x)\n")
        assertEquals("/threats/phishing.png" to "site/public/threats/phishing.png", describe(referenceAt("/threats")))
    }

    fun testFileHostsOnlyWhatNoInjectionCovers() {
        configure("---\nimage: /logo.png\n---\n\n::hero\n---\ncover: /logo.png\n---\nSee :img{src=\"/logo.png\"}.\n::\n")
        val hosted = ComarkImageReferences.fileHosted(myFixture.file as ComarkFile)
        assertEquals(listOf("/logo.png\"}"), hosted.map { myFixture.editor.document.text.substring(it.rangeInElement.startOffset, it.rangeInElement.endOffset + 2) })
        assertEquals(3, imageRefs().size)
    }

    fun testDisabled() {
        ComarkSettings.getInstance().imagesEnabled = false
        configure("![a](/logo.png) ::hero{src=\"/logo.png\"}\n::\n")
        assertEmpty(imageRefs())
    }

    fun testCtrlClickOpensTheImage() {
        for (text in listOf(
            "![a](/lo<caret>go.png)\n",
            "::hero{src=\"/lo<caret>go.png\"}\n::\n",
            "Hi :avatar{src=\"/lo<caret>go.png\"}.\n",
        )) {
            configure(text, "click-${text.hashCode().toUInt()}.md")
            val target = TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted)
            assertEquals(text, "logo.png", (target as? PsiFile)?.name)
        }
    }

    fun testCtrlClickFromYaml() {
        configure("::hero\n---\nimage: /lo<caret>go.png\n---\n::\n")
        assertEquals("logo.png", (myFixture.elementAtCaret as? PsiFile)?.name)
    }

    fun testMarkdownUnresolvedFileWarningIsSuppressedForImages() {
        val inspection = LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .single { it.shortName == ComarkMarkdownInspectionSuppressor.UNRESOLVED_FILE_REFERENCE }.instantiateTool() as LocalInspectionTool
        myFixture.enableInspections(inspection)
        configure("![a](/threats/phishing.png) ![b](/missing/nope.png) ![c](./missing.png) [d](/missing/link.png)\n")
        val reported = myFixture.doHighlighting()
            .filter { it.description != null && it.severity >= HighlightSeverity.WEAK_WARNING }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }
        assertFalse(reported.toString(), reported.any { it == "threats" || it == "phishing.png" || it == "nope.png" })
        assertTrue(reported.toString(), reported.any { it == "missing.png" })
        assertTrue(reported.toString(), reported.any { it == "link.png" })
    }

    fun testDestinationHasTheMarkdownReferencesToo() {
        configure("![a](/logo.png)\n")
        val destination = myFixture.file.findElementAt(myFixture.editor.document.text.indexOf("/logo"))!!
            .parents(withSelf = true).filterIsInstance<MarkdownLinkDestination>().first()
        assertEquals(1, destination.references.filterIsInstance<ComarkImageReference>().size)
    }
}
