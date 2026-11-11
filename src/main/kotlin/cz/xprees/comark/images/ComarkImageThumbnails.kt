package cz.xprees.comark.images

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import java.awt.Dimension
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Small pictures of image files for completion and documentation: 16px icons, scaled previews and pixel sizes, cached per file
 * version (URL plus modification stamp).
 *
 * Decoding uses `javax.imageio`, which reads PNG, JPEG, GIF and BMP. WebP, AVIF, ICO and SVG get the generic image icon: the
 * Images plugin is not a dependency, ImageIO has no decoder for them, and the platform's SVG renderer is internal API. Pixel sizes
 * are still known for WebP (from its RIFF header) and SVG (from `width`/`height`/`viewBox` of the root element).
 *
 * Files are read through the VFS only (no `java.io.File`), so non-local file systems work too.
 */
@Service(Service.Level.APP)
class ComarkImageThumbnails {
    private class Entry(val stamp: Long, val icon: Icon?, val dimension: Dimension?, val dimensionKnown: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The cached thumbnail icon of [file], without decoding anything; null when it has not been made yet (or cannot be). */
    fun cachedIcon(file: VirtualFile): Icon? = entries[file.url]?.takeIf { it.stamp == file.modificationStamp }?.icon

    /** A 16px picture of [file], or the generic image icon when it cannot be decoded. Reads the file the first time. */
    fun icon(file: VirtualFile): Icon {
        cachedIcon(file)?.let { return it }
        val icon = thumbnail(file, JBUIScale.scale(ICON_SIZE))?.let(::squareIcon)
        update(file) { old -> Entry(file.modificationStamp, icon ?: GENERIC, old?.dimension, old?.dimensionKnown == true) }
        return icon ?: GENERIC
    }

    /** The pixel size of [file], read from its header, or null when unknown. */
    fun dimensions(file: VirtualFile): Dimension? {
        entries[file.url]?.takeIf { it.stamp == file.modificationStamp && it.dimensionKnown }?.let { return it.dimension }
        val dimension = try {
            readDimensions(file)
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            null
        }
        update(file) { old -> Entry(file.modificationStamp, old?.icon, dimension, true) }
        return dimension
    }

    /** [file] scaled down to fit [maxPx] x [maxPx] (never scaled up), or null when ImageIO cannot decode it. Not cached. */
    fun thumbnail(file: VirtualFile, maxPx: Int = 240): BufferedImage? {
        if (!file.isValid || file.isDirectory || file.extension?.lowercase() !in DECODABLE || file.length > MAX_DECODE_BYTES) return null
        val image = try {
            ImageIO.read(ByteArrayInputStream(file.contentsToByteArray()))
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            thisLogger().debug("cannot decode ${file.path}", e)
            null
        } ?: return null
        return scale(image, maxPx)
    }

    /** Drops every cached picture (tests). */
    fun clear() = entries.clear()

    private fun update(file: VirtualFile, entry: (Entry?) -> Entry) {
        if (entries.size > MAX_ENTRIES) entries.clear() // a plain bound is enough: entries are cheap to make again
        entries.compute(file.url) { _, old -> entry(old?.takeIf { it.stamp == file.modificationStamp }) }
    }

    private fun readDimensions(file: VirtualFile): Dimension? {
        if (!file.isValid || file.isDirectory) return null
        return when (file.extension?.lowercase()) {
            "webp" -> file.inputStream.use { webpSize(it.readNBytes(WEBP_HEADER)) }
            "svg" -> file.inputStream.use { svgSize(String(it.readNBytes(SVG_HEADER), Charsets.UTF_8)) }
            in DECODABLE -> file.inputStream.use(::imageIoSize)
            else -> null
        }
    }

    private fun imageIoSize(stream: InputStream): Dimension? {
        val input = ImageIO.createImageInputStream(stream) ?: return null
        input.use {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                return Dimension(reader.getWidth(0), reader.getHeight(0))
            } finally {
                reader.dispose()
            }
        }
    }

    companion object {
        /** Shown for images without a thumbnail. */
        val GENERIC: Icon get() = AllIcons.FileTypes.Image

        private const val ICON_SIZE = 16
        private const val MAX_ENTRIES = 2000
        private const val MAX_DECODE_BYTES = 16L * 1024 * 1024
        private const val WEBP_HEADER = 30
        private const val SVG_HEADER = 4096
        private val DECODABLE = setOf("png", "jpg", "jpeg", "gif", "bmp")

        fun getInstance(): ComarkImageThumbnails = ApplicationManager.getApplication().getService(ComarkImageThumbnails::class.java)

        /** The canvas size of a WebP file from its first bytes (`VP8 `, `VP8L` or `VP8X` chunk), or null. */
        internal fun webpSize(bytes: ByteArray): Dimension? {
            if (bytes.size < 16 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return null
            val chunk = String(bytes, 12, 4, Charsets.US_ASCII)
            if (bytes.size < if (chunk == "VP8L") 25 else 30) return null
            fun u8(i: Int) = bytes[i].toInt() and 0xff
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
            return when (chunk) {
                // lossy: a key frame starts with the 9d 01 2a start code, then 14-bit width and height
                "VP8 " -> if (u8(23) == 0x9d && u8(24) == 0x01 && u8(25) == 0x2a) Dimension(u16(26) and 0x3fff, u16(28) and 0x3fff) else null
                // lossless: signature 0x2f, then 14 bits width-1 and 14 bits height-1
                "VP8L" -> if (u8(20) == 0x2f) {
                    val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
                    Dimension((bits and 0x3fff) + 1, ((bits shr 14) and 0x3fff) + 1)
                } else null
                // extended: 24-bit canvas width-1 and height-1
                "VP8X" -> Dimension(u24(24) + 1, u24(27) + 1)
                else -> null
            }
        }

        private val SVG_TAG = Regex("""<svg\b([^>]*)>""", RegexOption.IGNORE_CASE)
        private val VIEW_BOX = Regex("""\bviewBox\s*=\s*["']\s*[-\d.eE+]+[\s,]+[-\d.eE+]+[\s,]+([\d.eE+]+)[\s,]+([\d.eE+]+)\s*["']""")

        /** The size an SVG declares on its root element: `width`/`height` in px (or unitless), else the `viewBox` size. */
        internal fun svgSize(text: String): Dimension? {
            val attributes = SVG_TAG.find(text)?.groupValues?.get(1) ?: return null
            fun length(name: String): Double? =
                Regex("""(?:^|\s)$name\s*=\s*["']\s*([\d.]+)\s*(px)?\s*["']""").find(attributes)?.groupValues?.get(1)?.toDoubleOrNull()
            val width = length("width")
            val height = length("height")
            if (width != null && height != null) return Dimension(width.roundToInt(), height.roundToInt())
            val box = VIEW_BOX.find(attributes) ?: return null
            val w = box.groupValues[1].toDoubleOrNull() ?: return null
            val h = box.groupValues[2].toDoubleOrNull() ?: return null
            return when {
                width != null -> Dimension(width.roundToInt(), (width * h / w).roundToInt())
                height != null -> Dimension((height * w / h).roundToInt(), height.roundToInt())
                else -> Dimension(w.roundToInt(), h.roundToInt())
            }
        }

        private fun scale(image: BufferedImage, maxPx: Int): BufferedImage {
            val largest = max(image.width, image.height)
            if (largest <= maxPx || largest == 0) return image
            val width = max(1, image.width * maxPx / largest)
            val height = max(1, image.height * maxPx / largest)
            val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = scaled.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(image, 0, 0, width, height, null)
            } finally {
                g.dispose()
            }
            return scaled
        }

        /** Centres [image] on a transparent square so icons of different aspect ratios line up in the lookup list. */
        private fun squareIcon(image: BufferedImage): Icon {
            val size = JBUIScale.scale(ICON_SIZE)
            val fitted = scale(image, size)
            val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = canvas.createGraphics()
            try {
                g.drawImage(fitted, (size - fitted.width) / 2, (size - fitted.height) / 2, null)
            } finally {
                g.dispose()
            }
            return IconUtil.createImageIcon(canvas)
        }
    }
}
