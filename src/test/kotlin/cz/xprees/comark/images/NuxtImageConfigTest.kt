package cz.xprees.comark.images

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text parsing of `nuxt.config.*` and image URL normalisation; no fixture needed. */
class NuxtImageConfigTest {
    private fun config(body: String) = NuxtImageConfig.parse("export default defineNuxtConfig({\n$body\n})\n")

    @Test
    fun srcDirPublicDirAndImageDirAtTheirOwnDepth() {
        val info = config(
            """
            srcDir: 'src/',
            image: { screens: { sm: 320, md: 640 }, dir: "assets/images", quality: 80 },
            dir: { public: `static` },
            """.trimIndent(),
        )
        assertEquals("src/", info.srcDir)
        assertEquals("static", info.publicDir)
        assertEquals("assets/images", info.imageDir)
    }

    @Test
    fun imageDirIsNotTakenForDirPublic() {
        val info = config("image: { dir: 'assets/images' }")
        assertNull(info.publicDir)
        assertEquals("assets/images", info.imageDir)
        val nested = config("vite: { dir: { public: 'nope' } }, nitro: { srcDir: 'server' }")
        assertNull(nested.publicDir)
        assertNull(nested.srcDir)
    }

    @Test
    fun aliasesKeepOnlyRemoteUrls() {
        val info = config("image: { alias: { unsplash: 'https://images.unsplash.com', '/blog': 'https://blog.example.com/', local: '/remote/blog' } }")
        assertEquals(mapOf("/unsplash" to "https://images.unsplash.com", "/blog" to "https://blog.example.com/"), info.imageAliases)
    }

    @Test
    fun appBaseUrlAndCdnUrl() {
        val info = config("app: { baseURL: '/docs/', cdnURL: 'https://cdn.example.com', head: { title: 'x' } }")
        assertEquals("/docs/", info.baseURL)
        assertEquals("https://cdn.example.com", info.cdnURL)
        assertNull(config("app: { baseURL: '/' }").baseURL)
        assertNull(config("app: { baseURL: process.env.BASE_URL, cdnURL: process.env.CDN }").baseURL)
        assertNull(config("app: { baseURL: process.env.BASE_URL || '/' }").baseURL)
    }

    @Test
    fun extendsKeepsLocalLayersOnly() {
        val info = config("extends: ['./theme', '../shared-layer', 'github:org/repo', '@scope/layer', ['~~/layers/x', { install: true }], 'https://x.dev/l']")
        assertEquals(listOf("./theme", "../shared-layer", "~~/layers/x"), info.extends)
        assertEquals(listOf("../base"), config("extends: '../base'").extends)
    }

    @Test
    fun commentsQuotesTrailingCommasAndCalls() {
        val info = NuxtImageConfig.parse(
            """
            import { resolve } from 'node:path'
            // srcDir: 'commented'
            export default defineNuxtConfig({
              /* dir: { public: 'old' }, */
              modules: ['@nuxt/content', "@nuxt/image"],
              srcDir: resolve(__dirname, 'source'), // the sources
              app: { cdnURL: 'https://cdn.example.com/a//b' },
            })
            """.trimIndent(),
        )
        assertEquals("source", info.srcDir)
        assertNull(info.publicDir)
        assertTrue(info.hasImageModule)
        assertEquals("https://cdn.example.com/a//b", info.cdnURL)
        assertFalse(config("modules: ['@nuxt/content']").hasImageModule)
    }

    @Test
    fun plainExportedObjectAndEmptyConfig() {
        assertEquals("static", NuxtImageConfig.parse("module.exports = { dir: { public: 'static' } }").publicDir)
        assertEquals(NuxtConfigInfo(), NuxtImageConfig.parse("export default defineNuxtConfig({})"))
        assertEquals(NuxtConfigInfo(), NuxtImageConfig.parse(""))
    }

    @Test
    fun manifests() {
        assertTrue(NuxtImageConfig.manifestHasNuxtImage("""{ "devDependencies": { "@nuxt/image": "^1.8.0" } }"""))
        assertTrue(NuxtImageConfig.manifestHasNuxtImage("""{ "dependencies": { "@nuxt/image-edge": "latest" } }"""))
        assertFalse(NuxtImageConfig.manifestHasNuxtImage("""{ "dependencies": { "@nuxt/image-other": "1" } }"""))
        assertTrue(NuxtImageConfig.manifestIsNuxt("""{ "dependencies": { "nuxt": "^4.0.0" } }"""))
        assertTrue(NuxtImageConfig.manifestIsNuxt("""{ "dependencies": { "@nuxt/content": "^3.0.0" } }"""))
        assertFalse(NuxtImageConfig.manifestIsNuxt("""{ "dependencies": { "vue": "^3.0.0" } }"""))
    }

    // ---- ImagePaths -------------------------------------------------------------------------------------------------

    private fun path(raw: String, base: String? = null, cdn: String? = null) = ImagePaths.normalize(raw, base, cdn)?.path

    @Test
    fun plainPathsQueriesAndEncoding() {
        assertEquals("/threats/a.webp", path("/threats/a.webp"))
        assertEquals("/threats/a.webp", path("/threats/a.webp?v=1#x"))
        assertEquals("/threats/a b+c.webp", path("/threats/a%20b+c.webp"))
        assertEquals("/čaj.png", path("/%C4%8Daj.png"))
        assertEquals("/x y.png", path("</x y.png>"))
        assertEquals("/a%zz.png", path("/a%zz.png"))
    }

    @Test
    fun cdnBaseUrlAndIpxPrefixes() {
        assertEquals("/threats/a.webp", path("https://cdn.example.com/threats/a.webp", cdn = "https://cdn.example.com/"))
        assertEquals("/threats/a.webp", path("/docs/threats/a.webp", base = "/docs/"))
        assertEquals("/threats/a.webp", path("/threats/a.webp", base = "/docs/"))
        assertEquals("/docsy/a.webp", path("/docsy/a.webp", base = "/docs"))
        assertEquals("/threats/a.webp", path("/_ipx/w_200&f_webp/threats/a.webp"))
        assertEquals("/threats/a.webp", path("/_ipx/w_200&amp;f_webp/threats/a.webp"))
        assertEquals("/threats/a.webp", path("/_ipx/_/threats/a.webp"))
        assertEquals("/threats/a.webp", path("https://cdn.example.com/docs/_ipx/s_100x50/threats/a.webp?v=2", "/docs/", "https://cdn.example.com"))
        assertEquals("/threats/a.webp", path("//cdn.example.com/threats/a.webp", cdn = "//cdn.example.com"))
    }

    @Test
    fun pathRangeCoversOnlyThePath() {
        val raw = "https://cdn.example.com/docs/_ipx/w_200/threats/a.webp?v=2"
        val src = ImagePaths.normalize(raw, "/docs", "https://cdn.example.com")!!
        assertEquals("/threats/a.webp", src.pathRange.substring(raw))
        assertEquals(TextRange(0, 6), ImagePaths.normalize("/a.png")!!.pathRange)
        val wrapped = "</a b.png>"
        assertEquals("/a b.png", ImagePaths.normalize(wrapped)!!.pathRange.substring(wrapped))
    }

    @Test
    fun notSiteAbsolute() {
        assertNull(path("threats/a.webp"))
        assertNull(path("./a.webp"))
        assertNull(path("//host/a.webp"))
        assertNull(path("https://example.com/a.webp"))
        assertNull(path("https://example.com/a.webp", cdn = "https://cdn.example.com"))
        assertNull(path("data:image/png;base64,xx"))
        assertNull(path("/"))
        assertNull(path(""))
        assertNull(path("#top"))
    }

    @Test
    fun aliasesAreRemote() {
        val src = ImagePaths.normalize("/unsplash/photo-1.jpg?w=10", aliases = mapOf("/unsplash" to "https://images.unsplash.com/"))!!
        assertEquals("https://images.unsplash.com/photo-1.jpg", src.remoteUrl)
        assertEquals("/unsplash/photo-1.jpg", src.path)
        assertNull(ImagePaths.normalize("/unsplashed/a.jpg", aliases = mapOf("unsplash" to "https://u.dev"))!!.remoteUrl)
    }

    @Test
    fun encodeRelativeAndExtensions() {
        assertEquals("../public/a%20b%2Bc%25%23%3F.png", ImagePaths.encodeRelative("../public/a b+c%#?.png"))
        assertTrue(ImagePaths.isImageName("x/Hero.WEBP"))
        assertTrue(ImagePaths.isImageName("a.avif"))
        assertFalse(ImagePaths.isImageName("a.md"))
        assertFalse(ImagePaths.isImageName("png"))
    }

    // ---- ImageKeys and ImageValueLocator ----------------------------------------------------------------------------

    @Test
    fun imageKeys() {
        for (key in listOf("src", "image", "img", "icon", "cover", "poster", "logo", "avatar", "thumbnail", "heroImage", "logoSrc", "thumbnail-src", "cover_image", "og:image")) {
            assertTrue(key, ImageKeys.isImageProp(key))
        }
        for (key in listOf(":src", "v-bind:image", "title", "link", "imageAlt", "srcset", "to", "")) assertFalse(key, ImageKeys.isImageProp(key))
        assertTrue(ImageKeys.isImageKeyPath(listOf("seo", "image")))
        assertTrue(ImageKeys.isImageKeyPath(listOf("image", "src")))
        assertTrue(ImageKeys.isImageKeyPath(listOf("image", "url")))
        assertTrue(ImageKeys.isImageKeyPath(listOf("og:image")))
        assertFalse(ImageKeys.isImageKeyPath(listOf("title")))
        assertFalse(ImageKeys.isImageKeyPath(listOf("navigation", "url")))
    }

    @Test
    fun yamlValues() {
        val text = """
            title: /not-an-image.png
            image: /a.png # the hero
            'og:image': "/og.png"
            og:image:alt: x
            seo:
              image: '/seo.png'
              title: /x.png
            cover: { src: /cover.png, alt: Cover }
            body: |
              image: /in-block.png
            gallery:
              - /g1.png
              - src: /g2.png
                alt: two
            """.trimIndent()
        val values = ImageValueLocator.yamlValues(text, 10, ImageKeys::isImageKeyPath)
        assertEquals(
            listOf("image=/a.png", "og:image=/og.png", "seo.image=/seo.png", "cover.src=/cover.png", "gallery.src=/g2.png"),
            values.map { "${it.key}=${it.value}" },
        )
        values.forEach { assertEquals(it.value, it.range.shiftLeft(10).substring(text)) }
        val all = ImageValueLocator.yamlValues(text) { it.firstOrNull() == "gallery" }.map { it.value }
        assertEquals(listOf("/g1.png", "/g2.png", "two"), all)
    }

    @Test
    fun attributeValues() {
        val text = """::hero{image="/a.png" :src="img" logoSrc='/l\'s.png' poster=/p.png title="/t.png" cover={x} icon=i-lucide-x}"""
        val scan = cz.xprees.comark.parser.ComarkSyntax.scanAttributes(text, text.indexOf('{'))!!
        val values = ImageValueLocator.attributeValues(text, scan, 100)
        assertEquals(listOf("image=/a.png", "logoSrc=/l's.png", "poster=/p.png", "icon=i-lucide-x"), values.map { "${it.key}=${it.value}" })
        assertEquals("/a.png", values[0].range.shiftLeft(100).substring(text))
        assertEquals("""/l\'s.png""", values[1].range.shiftLeft(100).substring(text))
    }
}
