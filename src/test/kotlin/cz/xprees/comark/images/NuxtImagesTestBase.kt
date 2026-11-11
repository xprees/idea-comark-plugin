package cz.xprees.comark.images

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.lang.ComarkProjectDetector
import cz.xprees.comark.settings.ComarkSettings
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Fixture projects laid out like Nuxt sites with images. The image settings are restored and the resolver's caches dropped after
 * each test, because light tests share one project (and its services) across tests.
 */
abstract class NuxtImagesTestBase : BasePlatformTestCase() {
    private var savedEnabled = true
    private var savedDirectories = ""
    private var savedCdn = ""
    private var savedPreviewFromCdn = false

    override fun setUp() {
        super.setUp()
        val settings = ComarkSettings.getInstance()
        savedEnabled = settings.imagesEnabled
        savedDirectories = settings.imageDirectories
        savedCdn = settings.imageCdnUrl
        savedPreviewFromCdn = settings.previewImagesFromCdn
        settings.imagesEnabled = true
        settings.imageDirectories = ""
        settings.imageCdnUrl = ""
        settings.previewImagesFromCdn = false
        invalidate()
    }

    override fun tearDown() {
        try {
            val settings = ComarkSettings.getInstance()
            settings.imagesEnabled = savedEnabled
            settings.imageDirectories = savedDirectories
            settings.imageCdnUrl = savedCdn
            settings.previewImagesFromCdn = savedPreviewFromCdn
            invalidate()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    protected fun directories(): NuxtImageDirectories = NuxtImageDirectories.getInstance(project)

    protected fun invalidate() {
        directories().invalidate()
        ComarkProjectDetector.getInstance(project).invalidate()
    }

    /**
     * A Nuxt project in [root]: a `package.json` depending on Nuxt and Nuxt Content (and Nuxt Image when [nuxtImage]), an `app/`
     * directory when [nuxt4], and a `nuxt.config.ts` with [nuxtConfig] (the object literal's body) when given.
     */
    protected fun nuxtProject(root: String = "site", nuxtImage: Boolean = true, nuxt4: Boolean = false, nuxtConfig: String? = null) {
        val image = if (nuxtImage) """, "@nuxt/image": "^1.8.0"""" else ""
        myFixture.addFileToProject("$root/package.json", """{ "dependencies": { "nuxt": "^3.15.0", "@nuxt/content": "^3.0.0"$image } }""")
        if (nuxt4) myFixture.addFileToProject("$root/app/app.vue", "<template><NuxtPage /></template>\n")
        if (nuxtConfig != null) myFixture.addFileToProject("$root/nuxt.config.ts", "export default defineNuxtConfig({\n$nuxtConfig\n})\n")
    }

    /** A [width] x [height] PNG at [path] (relative to the fixture root). */
    protected fun image(path: String, width: Int = 3, height: Int = 2): VirtualFile {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it) }
        return VfsTestUtil.createFile(myFixture.tempDirFixture.getFile("")!!, path, bytes.toByteArray())
    }

    /** A lossless WebP header (no pixels) declaring [width] x [height], at [path]. */
    protected fun webp(path: String, width: Int = 4, height: Int = 3): VirtualFile {
        val bits = (width - 1) or ((height - 1) shl 14)
        val payload = byteArrayOf(0x2f, bits.toByte(), (bits shr 8).toByte(), (bits shr 16).toByte(), (bits shr 24).toByte())
        val bytes = "RIFF".toByteArray() + le32(4 + 8 + payload.size) + "WEBPVP8L".toByteArray() + le32(payload.size) + payload
        return VfsTestUtil.createFile(myFixture.tempDirFixture.getFile("")!!, path, bytes)
    }

    /** Adds a document at [path] and returns its file. */
    protected fun page(text: String = "# Page\n", path: String = "site/content/page.md"): VirtualFile =
        myFixture.addFileToProject(path, text).virtualFile

    /** The resolved file of [raw] seen from [context], as a path relative to the fixture root, or null. */
    protected fun resolved(raw: String, context: VirtualFile): String? {
        val file = directories().resolve(context, raw)?.file ?: return null
        val root = myFixture.tempDirFixture.getFile("")!!.path
        return file.path.removePrefix("$root/")
    }

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
}
