package cz.xprees.comark.images

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFileSystemItem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import cz.xprees.comark.completion.ComarkCompletionAutoPopupHandler
import cz.xprees.comark.settings.ComarkSettings
import java.awt.Dimension

/** Image paths complete in every place a Comark document writes one, merged across the image directories. */
class ComarkImageCompletionTest : NuxtImagesTestBase() {
    override fun setUp() {
        super.setUp()
        ComarkImageThumbnails.getInstance().clear()
        nuxtProject(nuxtConfig = "image: { dir: 'assets/images' },")
        image("site/assets/images/hero.png", 32, 16)
        image("site/public/hero.png") // shadowed by assets/images/hero.png
        image("site/public/threats/phishing.png")
        webp("site/public/logo.webp", 40, 30)
        VfsTestUtil.createFile(myFixture.tempDirFixture.getFile("")!!, "site/public/robots.txt", "User-agent: *\n")
    }

    /** Opens [text] as `site/content/[name]` with the caret at `<caret>` and completes there. */
    private fun complete(text: String, name: String = "page.md"): List<String> {
        configure(text, name)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun configure(text: String, name: String = "page.md") {
        val file = page(text.replace("<caret>", ""), "site/content/$name")
        // the project detector re-parses open Markdown files after setUp's changes; let that happen first
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
    }

    private fun item(lookupString: String): LookupElement = myFixture.lookupElements!!.first { it.lookupString == lookupString }

    private fun assertImages(items: List<String>) = assertContainsElements(items, "/hero.png", "/threats/phishing.png", "/logo.webp")

    fun testImagesOfAllDirectoriesAreMergedAndShadowedOnesListedOnce() {
        val items = complete("![](/<caret>)\n")
        assertImages(items)
        assertEquals(1, items.count { it == "/hero.png" })
        assertDoesntContain(items, "/robots.txt")
        assertEquals("assets/images", LookupElementPresentation.renderElement(item("/hero.png")).typeText)
        assertEquals("public", LookupElementPresentation.renderElement(item("/threats/phishing.png")).typeText)
    }

    fun testMarkdownPathVariantsDoNotDuplicateImages() {
        // public/ as a resource root lets the Markdown plugin offer its own variants for the same files
        val publicDir = myFixture.tempDirFixture.getFile("site/public")!!
        PsiTestUtil.addSourceRoot(module, publicDir, org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE)
        try {
            val items = complete("![](/<caret>)\n")
            assertImages(items)
            val ours = myFixture.lookupElements!!.filter { it.`object` is ImageEntry }
            val offered = ours.mapTo(HashSet()) { (it.`object` as ImageEntry).file }
            val others = myFixture.lookupElements!!.filter { it.`object` !is ImageEntry }
            assertEquals(ours.size, ours.map { it.lookupString }.distinct().size)
            assertTrue(others.map { it.lookupString }.toString(), others.none { it.lookupString in ours.map(LookupElement::getLookupString) })
            assertTrue(others.none { (it.psiElement as? PsiFileSystemItem)?.virtualFile in offered })
            // nor as the shadowed public/hero.png, which Nuxt never serves
            assertTrue(others.none { (it.psiElement as? PsiFileSystemItem)?.virtualFile?.path?.endsWith("/public/hero.png") == true })
            assertContainsElements(others.map { it.lookupString }, "threats") // directories and other files stay
        } finally {
            PsiTestUtil.removeSourceRoot(module, publicDir)
        }
    }

    fun testThumbnailAndSizeAreRenderedLazily() {
        complete("![](/<caret>)\n")
        val hero = item("/hero.png")
        assertSame(ComarkImageThumbnails.GENERIC, LookupElementPresentation.renderElement(hero).icon)
        val presentation = expensive(hero)
        val icon = presentation.icon!!
        assertNotSame(ComarkImageThumbnails.GENERIC, icon)
        assertTrue(icon.iconWidth in 16..32 && icon.iconWidth == icon.iconHeight)
        assertEquals(" 32×16", presentation.tailText)
        assertEquals("assets/images", presentation.typeText)
        // WebP: no decoder, but the size comes from the header
        val logo = expensive(item("/logo.webp"))
        assertSame(ComarkImageThumbnails.GENERIC, logo.icon)
        assertEquals(" 40×30", logo.tailText)
        // the next completion shows the cached thumbnail right away
        complete("![](/<caret>)\n", "other.md")
        assertNotSame(ComarkImageThumbnails.GENERIC, LookupElementPresentation.renderElement(item("/hero.png")).icon)
    }

    @Suppress("UNCHECKED_CAST")
    private fun expensive(item: LookupElement): LookupElementPresentation {
        val renderer = item.expensiveRenderer as LookupElementRenderer<LookupElement>
        return LookupElementPresentation().also { renderer.renderElement(item, it) }
    }

    fun testReferenceDefinition() {
        assertImages(complete("![a][pic]\n\n[pic]: /<caret>\n"))
    }

    fun testComponentAttributes() {
        assertImages(complete("::hero{image=\"/<caret>\"}\n::\n"))
        assertImages(complete("::hero{title=\"x\" thumbnail-src='<caret>'}\n::\n", "b.md"))
        assertImages(complete("Text :avatar{src=\"/<caret>\"} more\n", "c.md"))
    }

    fun testPropsBlock() {
        assertImages(complete("::hero\n---\ntitle: Hi\ncover: /<caret>\n---\n::\n"))
        assertImages(complete("::hero\n---\nimage:\n  src: /<caret>\n---\n::\n", "b.md"))
    }

    fun testFrontMatter() {
        assertImages(complete("---\ntitle: Page\nimage: /<caret>\n---\n\n# Page\n"))
        assertImages(complete("---\nseo:\n  title: x\n  image: '/<caret>'\n---\n", "b.md"))
        assertImages(complete("---\n'og:image': /<caret>\n---\n", "c.md"))
    }

    fun testNoImagesForOtherValues() {
        assertDoesntContain(complete("---\ntitle: /<caret>\n---\n"), "/hero.png")
        assertDoesntContain(complete("::hero{title=\"/<caret>\"}\n::\n", "b.md"), "/hero.png")
        assertDoesntContain(complete("::hero{:image=\"/<caret>\"}\n::\n", "c.md"), "/hero.png")
        assertDoesntContain(complete("[link](/<caret>)\n", "d.md"), "/hero.png")
        assertDoesntContain(complete("`![](/<caret>)`\n", "e.md"), "/hero.png")
        assertDoesntContain(complete("```md\n![](/<caret>)\n```\n", "f.md"), "/hero.png")
        assertDoesntContain(complete("::hero{image=\"img/<caret>\"}\n::\n", "g.md"), "/hero.png")
    }

    fun testDisabled() {
        ComarkSettings.getInstance().imagesEnabled = false
        assertDoesntContain(complete("![](/<caret>)\n"), "/hero.png")
    }

    fun testSelectingInsertsTheFullPath() {
        configure("![](/thr<caret>)\n")
        assertNull(myFixture.completeBasic()) // the only match is inserted
        myFixture.checkResult("![](/threats/phishing.png<caret>)\n")

        configure("::hero{image=\"/<caret>\"}\n::\n", "b.md")
        myFixture.completeBasic()
        myFixture.lookup.currentItem = item("/logo.webp")
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("::hero{image=\"/logo.webp<caret>\"}\n::\n")
    }

    fun testPathsAreEncodedInDestinations() {
        image("site/public/my photo.png")
        configure("![](/my<caret>)\n")
        assertNull(myFixture.completeBasic())
        myFixture.checkResult("![](/my%20photo.png<caret>)\n")
        configure("::hero{image=\"/my<caret>\"}\n::\n", "b.md")
        assertNull(myFixture.completeBasic())
        myFixture.checkResult("::hero{image=\"/my photo.png<caret>\"}\n::\n")
    }

    fun testBaseUrlPrefix() {
        val config = myFixture.tempDirFixture.getFile("site/nuxt.config.ts")!!
        WriteAction.run<Throwable> { VfsUtil.saveText(config, "export default defineNuxtConfig({ app: { baseURL: '/docs/' } })\n") }
        invalidate()
        val items = complete("![](/docs/<caret>)\n")
        assertContainsElements(items, "/docs/threats/phishing.png", "/docs/hero.png")
    }

    fun testAutoPopupOnSlash() {
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopupImage("![](/"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopupImage("See ![Logo](</"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopupImage("[pic]: /"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopupImage("::hero{title=\"x\" image=\"/"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopupImage("cover: /"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopupImage("  - src: '/"))
        assertTrue(ComarkCompletionAutoPopupHandler.shouldPopupImage("'og:image': /"))
        assertFalse(ComarkCompletionAutoPopupHandler.shouldPopupImage("[link](/"))
        assertFalse(ComarkCompletionAutoPopupHandler.shouldPopupImage("title: /"))
        assertFalse(ComarkCompletionAutoPopupHandler.shouldPopupImage("::hero{title=\"/"))
        assertFalse(ComarkCompletionAutoPopupHandler.shouldPopupImage("a/"))
    }

    fun testYamlValueSite() {
        fun keys(text: String) = ImageValueLocator.yamlValueSite(text.replace("|", ""), text.indexOf('|'))?.keys
        assertEquals(listOf("image"), keys("title: x\nimage: /a|"))
        assertEquals(listOf("seo", "image"), keys("seo:\n  title: x\n  image: \"/|"))
        assertEquals(listOf("gallery"), keys("gallery:\n- /a.png\n- /|"))
        assertEquals(listOf("gallery"), keys("gallery:\n  - /|"))
        assertEquals(listOf("images", "src"), keys("images:\n  - alt: a\n    src: /|"))
        assertEquals(listOf("og:image"), keys("og:image: /|"))
        assertNull(keys("image:|"))
        assertEquals("/th", ImageValueLocator.yamlValueSite("cover: /th", 10)?.prefix)
    }

    fun testHeaderSizes() {
        assertEquals(Dimension(4, 3), ComarkImageThumbnails.webpSize(webp("site/x.webp").contentsToByteArray()))
        assertEquals(Dimension(120, 60), ComarkImageThumbnails.svgSize("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120px\" height=\"60\">"))
        assertEquals(Dimension(24, 12), ComarkImageThumbnails.svgSize("<?xml version=\"1.0\"?>\n<svg viewBox=\"0 0 24 12\">"))
        assertEquals(Dimension(48, 24), ComarkImageThumbnails.svgSize("<svg width=\"48\" viewBox=\"0 0 24 12\">"))
        assertNull(ComarkImageThumbnails.svgSize("<svg width=\"100%\">"))
        assertNull(ComarkImageThumbnails.webpSize(ByteArray(30)))
    }
}
