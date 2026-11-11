package cz.xprees.comark.images

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import cz.xprees.comark.settings.ComarkSettings

/** Which directories image paths resolve against in the Nuxt project layouts the resolver supports. */
class NuxtImageResolverTest : NuxtImagesTestBase() {
    private fun labels(context: com.intellij.openapi.vfs.VirtualFile) = directories().forFile(context)!!.dirs.map { it.label }

    fun testNuxt3WithoutNuxtImageUsesThePublicDirectoryOnly() {
        nuxtProject(nuxtImage = false, nuxtConfig = "image: { dir: 'assets/images' },")
        image("site/public/threats/phishing.webp")
        image("site/assets/images/x.png")
        val page = page()
        assertEquals("site/public/threats/phishing.webp", resolved("/threats/phishing.webp", page))
        assertNull(resolved("/x.png", page))
        assertEquals(listOf("public"), labels(page))
        assertFalse(directories().forFile(page)!!.nuxtImage)
    }

    fun testDefaultNuxtImageDirectoryIsThePublicDirectoryListedOnce() {
        nuxtProject()
        image("site/public/a.png")
        val page = page()
        assertEquals("site/public/a.png", resolved("/a.png", page))
        assertEquals(listOf("public"), labels(page))
        assertEquals("public", directories().detectedLabel(page))
    }

    fun testNuxtImageDirectoryWinsOverPublic() {
        nuxtProject(nuxtConfig = "image: { dir: 'assets/images' },")
        image("site/assets/images/threats/a.png")
        image("site/public/threats/a.png")
        image("site/public/only-public.png")
        val page = page()
        assertEquals("site/assets/images/threats/a.png", resolved("/threats/a.png", page))
        assertEquals("site/public/only-public.png", resolved("/only-public.png", page))
        assertEquals(listOf("assets/images", "public"), labels(page))
        assertEquals("assets/images > public", directories().detectedLabel(page))
    }

    fun testNuxt4AppDirectory() {
        nuxtProject(nuxt4 = true, nuxtConfig = "image: { dir: 'assets/images' },")
        image("site/app/assets/images/a.png")
        image("site/public/b.png")
        val page = page()
        assertEquals(listOf("app/assets/images", "public"), labels(page))
        assertEquals("site/app/assets/images/a.png", resolved("/a.png", page))
        assertEquals("site/public/b.png", resolved("/b.png", page))
    }

    fun testNuxt4DefaultImageDirectoryFollowsTheSpecAndPublicStillWorks() {
        nuxtProject(nuxt4 = true)
        image("site/public/b.png")
        val page = page()
        assertEquals(listOf("app/public", "public"), labels(page))
        assertEquals(listOf(false, true), directories().forFile(page)!!.dirs.map { it.exists })
        assertEquals("site/public/b.png", resolved("/b.png", page))
    }

    fun testExplicitSrcDirWinsOverAppDirectory() {
        nuxtProject(nuxt4 = true, nuxtConfig = "srcDir: 'src/',\nimage: { dir: 'assets/images' },")
        image("site/src/assets/images/a.png")
        val page = page()
        assertEquals(listOf("src/assets/images", "public"), labels(page))
        assertEquals("site/src/assets/images/a.png", resolved("/a.png", page))
    }

    fun testNuxt3AppDirectoryWithRouterOptionsIsNoSourceDirectory() {
        nuxtProject(nuxtConfig = "image: { dir: 'assets/images' },")
        myFixture.addFileToProject("site/app/router.options.ts", "export default {}\n")
        image("site/assets/images/hero.png")
        val page = page()
        assertEquals(listOf("assets/images", "public"), labels(page))
        assertEquals("site/assets/images/hero.png", resolved("/hero.png", page))
    }

    fun testUnknownVersionFollowsTheNuxt4SourceDirectoryRule() {
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "nuxt": "latest", "@nuxt/image": "^1.8.0" } }""")
        myFixture.addFileToProject("site/nuxt.config.ts", "export default defineNuxtConfig({ image: { dir: 'img' } })\n")
        myFixture.addFileToProject("site/app/router.options.ts", "export default {}\n")
        myFixture.addFileToProject("site/pages/index.vue", "<template />\n")
        // only router options in app/ and Nuxt 3 sources at the root: the root is the srcDir
        assertEquals(listOf("img", "public"), labels(page()))
        myFixture.addFileToProject("other/package.json", """{ "dependencies": { "nuxt": "latest", "@nuxt/image": "^1.8.0" } }""")
        myFixture.addFileToProject("other/nuxt.config.ts", "export default defineNuxtConfig({ image: { dir: 'img' } })\n")
        myFixture.addFileToProject("other/app/pages/index.vue", "<template />\n")
        assertEquals(listOf("app/img", "public"), labels(page(path = "other/content/page.md")))
    }

    fun testNuxt3PublicDirectoryFollowsSrcDir() {
        nuxtProject(nuxtImage = false, nuxtConfig = "srcDir: 'src/',")
        image("site/src/public/logo.png")
        val page = page()
        assertEquals(listOf("src/public"), labels(page))
        assertEquals("site/src/public/logo.png", resolved("/logo.png", page))
    }

    fun testCompatibilityVersion4UsesTheNuxt4Layout() {
        nuxtProject(nuxtConfig = "srcDir: 'src/',\nfuture: { compatibilityVersion: 4 },")
        image("site/public/logo.png")
        val page = page()
        assertEquals(listOf("src/public", "public"), labels(page))
        assertEquals("site/public/logo.png", resolved("/logo.png", page))
    }

    fun testUnknownVersionWithExplicitSrcDirListsBothPublicDirectories() {
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        myFixture.addFileToProject("site/nuxt.config.ts", "export default defineNuxtConfig({ srcDir: 'src/' })\n")
        image("site/src/public/logo.png")
        val page = page()
        assertEquals(listOf("public", "src/public"), labels(page))
        assertEquals("site/src/public/logo.png", resolved("/logo.png", page))
    }

    fun testDirPublic() {
        nuxtProject(nuxtConfig = "dir: { public: 'static' },")
        image("site/static/a.png")
        val page = page()
        assertEquals(listOf("static"), labels(page))
        assertEquals("site/static/a.png", resolved("/a.png", page))
    }

    fun testDirPublicInNuxt4() {
        nuxtProject(nuxt4 = true, nuxtConfig = "dir: { public: 'static' },")
        assertEquals(listOf("app/static", "static"), labels(page()))
    }

    fun testLayersAndLocalExtendsWithRootPriority() {
        nuxtProject(nuxtConfig = "extends: ['./theme', 'github:org/remote-layer'],")
        myFixture.addFileToProject("site/layers/base/nuxt.config.ts", "export default defineNuxtConfig({ image: { dir: 'images' } })\n")
        myFixture.addFileToProject("site/theme/nuxt.config.ts", "export default defineNuxtConfig({ dir: { public: 'assets' } })\n")
        image("site/public/logo.png")
        image("site/layers/base/public/logo.png")
        image("site/layers/base/public/base-only.png")
        image("site/layers/base/images/hero.png")
        image("site/theme/assets/theme.png")
        val page = page()
        assertEquals(
            listOf("public", "layers/base/images", "theme/assets", "layers/base/public"),
            labels(page),
        )
        assertEquals("site/public/logo.png", resolved("/logo.png", page))
        assertEquals("site/layers/base/public/base-only.png", resolved("/base-only.png", page))
        assertEquals("site/layers/base/images/hero.png", resolved("/hero.png", page))
        assertEquals("site/theme/assets/theme.png", resolved("/theme.png", page))
        // a document inside a layer resolves against the app that uses it
        assertEquals("site/public/logo.png", resolved("/logo.png", page(path = "site/layers/base/content/index.md")))
    }

    fun testBaseUrlCdnUrlAndIpx() {
        nuxtProject(nuxtConfig = "app: { baseURL: '/docs/', cdnURL: 'https://cdn.example.com' },")
        image("site/public/threats/a.png")
        val page = page()
        assertEquals("site/public/threats/a.png", resolved("/docs/threats/a.png", page))
        assertEquals("site/public/threats/a.png", resolved("/threats/a.png", page))
        assertEquals("site/public/threats/a.png", resolved("https://cdn.example.com/threats/a.png?v=3", page))
        assertEquals("site/public/threats/a.png", resolved("/docs/_ipx/w_200&f_webp/threats/a.png", page))
        assertNull(resolved("https://other.example.com/threats/a.png", page))

        ComarkSettings.getInstance().imageCdnUrl = "https://images.example.org/"
        assertEquals("site/public/threats/a.png", resolved("https://images.example.org/threats/a.png", page))
        assertNull(resolved("https://cdn.example.com/threats/a.png", page))
    }

    fun testAliasesNeverResolve() {
        nuxtProject(nuxtConfig = "image: { alias: { unsplash: 'https://images.unsplash.com' } },")
        image("site/public/unsplash/a.png")
        val page = page()
        assertNull(resolved("/unsplash/a.png", page))
        assertEquals("https://images.unsplash.com", directories().forFile(page)!!.aliases["/unsplash"])
    }

    fun testRelativeAndEscapingPathsDoNotResolve() {
        nuxtProject()
        image("site/public/a.png")
        image("site/secret.png")
        val page = page()
        assertNull(resolved("a.png", page))
        assertNull(resolved("/../secret.png", page))
        assertNull(resolved("/public", page))
    }

    fun testSettingsOverrideListReplacesDetection() {
        nuxtProject(nuxtConfig = "image: { dir: 'assets/images' },")
        image("docs-images/a.png")
        image("site/static/a.png")
        image("site/static/b.png")
        image("site/public/c.png")
        val page = page()
        ComarkSettings.getInstance().imageDirectories = "# comment\ndocs-images\nmissing\nsite/static\n"
        assertEquals(listOf("docs-images", "site/static"), labels(page))
        assertEquals("docs-images/a.png", resolved("/a.png", page))
        assertEquals("site/static/b.png", resolved("/b.png", page))
        assertNull(resolved("/c.png", page))
    }

    fun testDisabled() {
        nuxtProject()
        image("site/public/a.png")
        val page = page()
        ComarkSettings.getInstance().imagesEnabled = false
        assertNull(directories().forFile(page))
        assertNull(resolved("/a.png", page))
        assertEquals(emptyList<ImageEntry>(), directories().listImages(page))
        ComarkSettings.getInstance().imagesEnabled = true
        assertEquals("site/public/a.png", resolved("/a.png", page))
    }

    fun testOutsideNuxtProjects() {
        nuxtProject()
        myFixture.addFileToProject("app/package.json", """{ "dependencies": { "vue": "^3.0.0" } }""")
        image("app/public/a.png")
        image("public/a.png")
        val vue = page(path = "app/content/index.md")
        assertNull(resolved("/a.png", vue))
        assertFalse(directories().isNuxt(vue))
        val other = page(path = "other/readme.md")
        assertNull(resolved("/a.png", other))
        assertFalse(directories().isNuxt(other))
        assertTrue(directories().isNuxt(page()))
    }

    fun testContentOnlyManifestCountsAsNuxt() {
        myFixture.addFileToProject("site/package.json", """{ "dependencies": { "@nuxt/content": "^3.0.0" } }""")
        image("site/public/a.png")
        val page = page()
        assertTrue(directories().isNuxt(page))
        assertEquals("site/public/a.png", resolved("/a.png", page))
    }

    fun testPathForAndListImages() {
        nuxtProject(nuxtConfig = "image: { dir: 'assets/images' },")
        val shadowing = image("site/assets/images/threats/a.png")
        val shadowed = image("site/public/threats/a.png")
        val other = image("site/public/b.webp")
        myFixture.addFileToProject("site/public/robots.txt", "")
        image("site/public/node_modules/x.png")
        val page = page()
        assertEquals("/threats/a.png", directories().pathFor(shadowing, page))
        assertEquals("/threats/a.png", directories().pathFor(shadowed, page))
        assertEquals("/b.webp", directories().pathFor(other, page))
        assertNull(directories().pathFor(page, page))
        val images = directories().listImages(page)
        assertEquals(listOf("/threats/a.png" to "assets/images", "/b.webp" to "public"), images.map { it.path to it.dir.label })
        assertEquals(shadowing, images[0].file)
    }

    fun testConfigAndManifestEditsAreSeen() {
        nuxtProject(nuxtImage = false, nuxtConfig = "dir: { public: 'static' },")
        image("site/static/a.png")
        image("site/assets/images/b.png")
        val page = page()
        assertEquals("site/static/a.png", resolved("/a.png", page))
        val before = directories().tracker.modificationCount

        val config = myFixture.findFileInTempDir("site/nuxt.config.ts")
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(config, "export default defineNuxtConfig({ image: { dir: 'assets/images' } })\n") }
        assertNull(resolved("/a.png", page))
        assertNull(resolved("/b.png", page))
        assertTrue(directories().tracker.modificationCount > before)

        val manifest = myFixture.findFileInTempDir("site/package.json")
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(manifest, """{ "dependencies": { "nuxt": "^3.0.0", "@nuxt/image": "^1.0.0" } }""") }
        assertEquals("site/assets/images/b.png", resolved("/b.png", page))
    }

    fun testNewDirectoriesAndImagesAreSeen() {
        nuxtProject(nuxtConfig = "image: { dir: 'assets/images' },")
        image("site/public/a.png")
        val page = page()
        assertEquals(listOf("/a.png"), directories().listImages(page).map { it.path })
        assertFalse(directories().forFile(page)!!.dirs.first().exists)
        val before = directories().tracker.modificationCount

        image("site/assets/images/new.png")
        assertTrue(directories().tracker.modificationCount > before)
        assertTrue(directories().forFile(page)!!.dirs.first().exists)
        assertEquals("site/assets/images/new.png", resolved("/new.png", page))
        image("site/public/later.png")
        assertEquals(listOf("/new.png", "/a.png", "/later.png"), directories().listImages(page).map { it.path })
    }

    fun testUnrelatedFilesKeepTheCaches() {
        nuxtProject(nuxtConfig = "")
        image("site/public/a.png")
        val page = page()
        val listing = directories().listImages(page)
        val before = directories().tracker.modificationCount
        myFixture.addFileToProject("site/.output/server/index.mjs", "")
        myFixture.addFileToProject("site/content/other.md", "# Other\n")
        assertSame(listing, directories().listImages(page))
        // another project's manifest (outside this project's directory) changes nothing here
        val outside = myFixture.tempDirFixture.getFile("")!!.parent
        val other = WriteAction.computeAndWait<com.intellij.openapi.vfs.VirtualFile, Throwable> {
            outside.createChildDirectory(this, "other-project-${System.nanoTime()}")
        }
        try {
            assertFalse(com.intellij.openapi.vfs.VfsUtilCore.isAncestor(myFixture.tempDirFixture.getFile("")!!, other, false))
            WriteAction.runAndWait<Throwable> { VfsUtil.saveText(other.createChildData(this, "package.json"), "{}") }
        } finally {
            WriteAction.runAndWait<Throwable> { other.delete(this) }
        }
        assertEquals(before, directories().tracker.modificationCount)
        image("site/public/b.png")
        assertEquals(listOf("/a.png", "/b.png"), directories().listImages(page).map { it.path })
    }

    fun testNewLayerIsSeen() {
        nuxtProject(nuxtConfig = "")
        val page = page()
        assertEquals(listOf("public"), labels(page))
        myFixture.addFileToProject("site/layers/extra/nuxt.config.ts", "export default defineNuxtConfig({})\n")
        image("site/layers/extra/public/x.png")
        assertEquals("site/layers/extra/public/x.png", resolved("/x.png", page))
    }
}
