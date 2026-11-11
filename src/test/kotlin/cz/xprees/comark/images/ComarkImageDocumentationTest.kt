package cz.xprees.comark.images

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.testFramework.PlatformTestUtil
import cz.xprees.comark.navigation.ComarkDocumentationTargetProvider
import cz.xprees.comark.settings.ComarkSettings
import java.awt.image.BufferedImage

/** Quick documentation of image paths: thumbnail, sizes and directory of the image, or where a missing one was looked for. */
class ComarkImageDocumentationTest : NuxtImagesTestBase() {
    private lateinit var phishing: VirtualFile

    override fun setUp() {
        super.setUp()
        nuxtProject(nuxtConfig = "image: { dir: 'assets/images' },")
        phishing = image("site/public/threats/phishing.png")
        image("site/assets/images/hero.png", 600, 300)
        webp("site/public/logo.webp", 40, 30)
    }

    /** Opens [text] as [path] with the caret at `<caret>`. */
    private fun configure(text: String, path: String = "site/content/page.md") {
        val file = page(text.replace("<caret>", ""), path)
        // the project detector re-parses open Markdown files after setUp's changes; let that happen first
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
    }

    /** The image target at the caret, asked with the host file and offset (as the component documentation is). */
    private fun targetAt(text: String, path: String = "site/content/page.md"): ComarkImageDocumentationTarget? {
        configure(text, path)
        return ComarkImageDocumentationTargetProvider().documentationTargets(myFixture.file, myFixture.caretOffset)
            .singleOrNull() as ComarkImageDocumentationTarget?
    }

    private fun html(target: ComarkImageDocumentationTarget?): String {
        assertNotNull("no documentation target", target)
        assertInstanceOf(target!!.computeDocumentation(), DocumentationResult.Documentation::class.java)
        return target.render().first
    }

    private fun size(path: String) = StringUtil.formatFileSize(myFixture.tempDirFixture.getFile(path)!!.length)

    fun testDestination() {
        val target = targetAt("![a](/threats/phi<caret>shing.png)\n")
        val html = html(target)
        assertTrue(html, html.contains("/threats/phishing.png"))
        assertTrue(html, html.contains("src=\"${ComarkImageDocumentationTarget.THUMBNAIL_KEY}\""))
        assertTrue(html, html.contains("<img "))
        assertTrue(html, html.contains("3 × 2 px"))
        assertTrue(html, html.contains(size("site/public/threats/phishing.png")))
        assertTrue(html, html.contains(">public<"))
        assertTrue(html, html.contains("site/public/threats/phishing.png"))
        val thumbnail = target!!.render().second[ComarkImageDocumentationTarget.THUMBNAIL_KEY] as BufferedImage
        assertEquals(3 to 2, thumbnail.width to thumbnail.height)
    }

    fun testEveryHost() {
        for ((index, text) in listOf(
            "![a][pic]\n\n[pic]: /threats/phi<caret>shing.png\n",
            "::hero{image=\"/threats/phi<caret>shing.png\"}\n::\n",
            "Inline :img{src=\"/threats/phi<caret>shing.png\"} here\n",
            "::hero\n---\nimage: /threats/phi<caret>shing.png\n---\n::\n",
            "---\ntitle: X\nimage: /threats/phi<caret>shing.png\n---\n\n# X\n",
            "![a](/threats/phishing.png<caret>)\n", // right after the path
        ).withIndex()) {
            val target = targetAt(text, "site/content/host$index.md")
            assertNotNull(text, target)
            assertEquals(text, phishing, target!!.file)
            assertEquals(text, "public", target.dir?.label)
        }
    }

    fun testInjectedYamlFileAndOffset() {
        configure("::hero\n---\nimage: /threats/phi<caret>shing.png\n---\n::\n")
        val injections = InjectedLanguageManager.getInstance(project)
        val injected = injections.findInjectedElementAt(myFixture.file, myFixture.caretOffset)!!
        val start = injected.textRange.startOffset
        val local = start + myFixture.caretOffset - injections.injectedToHost(injected, start)
        val target = ComarkImageDocumentationTargetProvider().documentationTargets(injected.containingFile, local).single()
        assertEquals(phishing, (target as ComarkImageDocumentationTarget).file)
    }

    fun testImageDirectoryAndScaledThumbnail() {
        val target = targetAt("![a](/he<caret>ro.png)\n")
        val html = html(target)
        assertTrue(html, html.contains("600 × 300 px"))
        assertTrue(html, html.contains("assets/images"))
        assertTrue(html, html.contains("width=\"240\"") && html.contains("height=\"120\""))
        val thumbnail = target!!.render().second.values.single() as BufferedImage
        assertEquals(240 to 120, thumbnail.width to thumbnail.height)
    }

    fun testWebpHasSizeButNoThumbnail() {
        val target = targetAt("::hero{src=\"/lo<caret>go.webp\"}\n::\n")
        val (html, images) = target!!.render()
        assertTrue(html, html.contains("40 × 30 px"))
        assertFalse(html, html.contains("<img"))
        assertEmpty(images.keys)
    }

    fun testPrefixesAndQueryAreNotPartOfThePath() {
        val target = targetAt("![a](/threats/phi<caret>shing.png?w=20#x)\n")
        assertEquals("/threats/phishing.png", target!!.path)
        assertEquals(phishing, target.file)
    }

    fun testMissingImage() {
        val target = targetAt("![a](/threats/nope<caret>.png)\n")
        assertNull(target!!.file)
        val html = html(target)
        assertTrue(html, html.contains("Not found in assets/images, public"))
        assertTrue(target.computeDocumentationHint(), target.computeDocumentationHint().contains("not found"))
        assertNull(target.navigatable)
    }

    fun testNoTargetOutsideNuxtOrWithoutImagePath() {
        image("other/public/logo.png")
        assertNull(targetAt("![a](/no<caret>pe.png)\n", "other/page.mdc"))
        for ((index, text) in listOf(
            "[a](/threats/phi<caret>shing.png)\n",
            "![a](threats/phi<caret>shing.png)\n",
            "![a](https://example.com/phi<caret>shing.png)\n",
            "::hero{title=\"/threats/phi<caret>shing.png\"}\n::\n",
            "`![a](/threats/phi<caret>shing.png)`\n",
            "Some te<caret>xt\n",
        ).withIndex()) {
            assertNull(text, targetAt(text, "site/content/none$index.md"))
        }
    }

    fun testDisabled() {
        ComarkSettings.getInstance().imagesEnabled = false
        assertNull(targetAt("![a](/threats/phi<caret>shing.png)\n"))
    }

    fun testHintPresentationPointerAndNavigation() {
        val target = targetAt("![a](/threats/phi<caret>shing.png)\n")!!
        val hint = target.computeDocumentationHint()
        assertTrue(hint, hint.contains("/threats/phishing.png"))
        assertTrue(hint, hint.contains("3 × 2 px"))
        assertTrue(hint, hint.contains(size("site/public/threats/phishing.png")))
        assertTrue(hint, hint.contains("public"))
        assertEquals("/threats/phishing.png", target.computePresentation().presentableText)
        assertSame(target, target.createPointer().dereference())
        assertEquals(phishing, (target.navigatable as OpenFileDescriptor).file)
    }

    fun testProviderIsRegisteredBeforeComponentDocumentation() {
        val providers = ExtensionPointName<DocumentationTargetProvider>("com.intellij.platform.backend.documentation.targetProvider").extensionList
        val images = providers.indexOfFirst { it is ComarkImageDocumentationTargetProvider }
        val components = providers.indexOfFirst { it is ComarkDocumentationTargetProvider }
        assertTrue(images >= 0)
        assertTrue("$images < $components", images < components)
    }
}
