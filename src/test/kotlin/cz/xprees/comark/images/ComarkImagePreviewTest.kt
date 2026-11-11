package cz.xprees.comark.images

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanelProvider
import org.intellij.plugins.markdown.ui.preview.jcef.HtmlSourceTextPreprocessor
import org.intellij.plugins.markdown.ui.preview.jcef.JCEFHtmlPanelProvider

/** Image paths in the Comark preview are mapped to the Nuxt image directories, through the same API the preview calls. */
class ComarkImagePreviewTest : NuxtImagesTestBase() {
    private fun page(file: VirtualFile): String = ReadAction.computeBlocking<String, RuntimeException> {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val provider = MarkdownHtmlPanelProvider.createFromInfo(JCEFHtmlPanelProvider().providerInfo)
        provider.sourceTextPreprocessor.preprocessText(project, document, file)
    }

    /** The preview of [text] as `site/content/page.md`, after the inlined stylesheet. */
    private fun preview(text: String, path: String = "site/content/page.md"): String = page(page(text, path)).substringAfter("</style>")

    /** The `md-src-pos` range of the first element opened by [tagStart], as document text. */
    private fun sourceOf(html: String, text: String, tagStart: String): String {
        val tag = html.indexOf(tagStart)
        assertTrue("no $tagStart in $html", tag >= 0)
        val range = Regex("""md-src-pos=["'](\d+)\.\.(\d+)["']""").find(html, tag)!!
        return text.substring(range.groupValues[1].toInt(), range.groupValues[2].toInt())
    }

    private fun site(nuxtConfig: String? = null) {
        nuxtProject(nuxtConfig = nuxtConfig)
        webp("site/public/threats/phishing.webp")
    }

    fun testMarkdownImageIsRelativeToTheDocument() {
        site()
        val text = "# Page\n\n![Phishing](/threats/phishing.webp)\n"
        val html = preview(text)
        assertTrue(html, html.contains("""src="../public/threats/phishing.webp""""))
        assertTrue(html, html.contains("""data-comark-src="/threats/phishing.webp""""))
        assertFalse(html, html.contains("md-do-not-process-path"))
        assertEquals("![Phishing](/threats/phishing.webp)", sourceOf(html, text, "<img"))
    }

    fun testRelativePathIsPercentEncoded() {
        nuxtProject()
        image("site/public/my shots/a+b.png")
        val html = preview("![x](</my shots/a+b.png>)\n", "site/content/guide/deep.md")
        assertTrue(html, html.contains("""src="../../public/my%20shots/a%2Bb.png""""))
    }

    fun testReferenceStyleAndRawHtmlImages() {
        site()
        val html = preview("![Phishing][pic]\n\n<img src=\"/threats/phishing.webp\" alt=\"raw\">\n\n[pic]: /threats/phishing.webp\n")
        assertEquals(html, 2, Regex("""src="\.\./public/threats/phishing\.webp"""").findAll(html).count())
    }

    fun testImageInsideComponent() {
        site()
        val html = preview("::card\n  ![a](/threats/phishing.webp)\n::\n")
        assertTrue(html, html.contains("""src="../public/threats/phishing.webp""""))
    }

    fun testComponentAttributeImageProp() {
        site()
        val text = "::hero{image=\"/threats/phishing.webp\" :src=\"img\" title=\"Hi\"}\nBody\n::\n"
        val html = preview(text)
        assertTrue(html, html.contains("""<div class="comark-prop-images"><img class="comark-prop-image" src="../public/threats/phishing.webp" alt="image""""))
        assertEquals("/threats/phishing.webp", sourceOf(html, text, "<img class=\"comark-prop-image\""))
        assertEquals(html, 1, Regex("comark-prop-image\"").findAll(html).count())
        assertTrue(html, html.indexOf("comark-prop-images") < html.indexOf("comark-component-body"))
    }

    fun testPropsBlockImageProp() {
        site()
        val text = "::card\n---\ntitle: Hello\ncover: /threats/phishing.webp\nimage:\n  src: '/threats/phishing.webp'\n---\nBody\n::\n"
        val html = preview(text)
        assertTrue(html, html.contains("""<pre class="comark-component-props""""))
        assertEquals(html, 2, Regex("""<img class="comark-prop-image" src="\.\./public/threats/phishing\.webp"""").findAll(html).count())
        assertTrue(html, html.contains("""title="image.src: /threats/phishing.webp""""))
        assertTrue(html, html.indexOf("comark-component-props") < html.indexOf("comark-prop-images"))
    }

    fun testNestedComponentPropsUseDocumentOffsets() {
        site()
        val text = "::outer\n  :::inner\n  ---\n  logo: /threats/phishing.webp\n  ---\n  Text\n  :::\n::\n"
        val html = preview(text)
        assertEquals("/threats/phishing.webp", sourceOf(html, text, "<img class=\"comark-prop-image\""))
    }

    fun testSlotAttributeImageProp() {
        site()
        val html = preview("::card\n#header{image=\"/threats/phishing.webp\"}\nTitle\n::\n")
        assertTrue(html, html.contains("""<img class="comark-prop-image" src="../public/threats/phishing.webp""""))
    }

    fun testUnresolvedPathIsLeftAlone() {
        site()
        val html = preview("![x](/missing.png)\n\n![y](images/rel.png)\n\n::hero{image=\"/missing.png\"}\n::\n")
        assertTrue(html, html.contains("""src="/missing.png""""))
        assertTrue(html, html.contains("comark-missing-image"))
        assertTrue(html, html.contains("""src="images/rel.png""""))
        assertFalse(html, html.contains("comark-prop-image"))
    }

    fun testCdnPreviewForPublicImagesOnly() {
        site(nuxtConfig = "app: { cdnURL: 'https://cdn.example.com' },\nimage: { dir: 'assets/images' },")
        image("site/assets/images/local.png")
        ComarkSettings.getInstance().previewImagesFromCdn = true
        val html = preview("![a](/threats/phishing.webp)\n\n![b](/local.png)\n\n::hero{image=\"/threats/phishing.webp\"}\n::\n")
        assertTrue(html, html.contains("""<img md-do-not-process-path src="https://cdn.example.com/threats/phishing.webp""""))
        assertTrue(html, html.contains("""src="../assets/images/local.png""""))
        assertTrue(html, html.contains("""<img class="comark-prop-image" md-do-not-process-path src="https://cdn.example.com/threats/phishing.webp""""))
    }

    fun testCdnPreviewWithDefaultImageDirectory() {
        site()
        ComarkSettings.getInstance().imageCdnUrl = "//cdn.example.com/"
        ComarkSettings.getInstance().previewImagesFromCdn = true
        val html = preview("![a](/threats/phishing.webp)\n")
        assertTrue(html, html.contains("""src="https://cdn.example.com/threats/phishing.webp""""))
    }

    fun testCdnUrlsStayLocalWithoutTheToggle() {
        site(nuxtConfig = "app: { cdnURL: 'https://cdn.example.com' },")
        val html = preview("![a](https://cdn.example.com/threats/phishing.webp)\n")
        assertTrue(html, html.contains("""src="../public/threats/phishing.webp""""))
    }

    fun testAliasIsRemote() {
        site(nuxtConfig = "image: { alias: { unsplash: 'https://images.unsplash.com' } },")
        val html = preview("![a](/unsplash/photo-1.jpg)\n")
        assertTrue(html, html.contains("""<img md-do-not-process-path src="https://images.unsplash.com/photo-1.jpg""""))
    }

    fun testDisabledLeavesImagesAlone() {
        site()
        ComarkSettings.getInstance().imagesEnabled = false
        val html = preview("![a](/threats/phishing.webp)\n\n::hero{image=\"/threats/phishing.webp\"}\n::\n")
        assertTrue(html, html.contains("""src="/threats/phishing.webp""""))
        assertFalse(html, html.contains("comark-prop-image"))
        assertFalse(html, html.contains("comark-missing-image"))
    }

    fun testPlainMarkdownIsUnchanged() {
        site()
        val file = page("# Title\n\n![a](/threats/phishing.webp)\n", "site/plain.md")
        val expected = ReadAction.computeBlocking<String, RuntimeException> {
            HtmlSourceTextPreprocessor().preprocessText(project, FileDocumentManager.getInstance().getDocument(file)!!, file)
        }
        assertEquals(expected, page(file))
    }
}
