package cz.xprees.comark.images

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import java.io.ByteArrayOutputStream

/**
 * An image source as written in a document, normalised to the path Nuxt serves it under.
 *
 * @property raw the value as written (`https://cdn.example.com/docs/_ipx/w_200/threats/x.webp?v=2`).
 * @property path the decoded public path (`/threats/x.webp`): what is looked up in the public and image directories.
 * @property pathRange the part of [raw] that spells [path]: after CDN, base URL and `_ipx` prefixes, before `?query` and `#hash`.
 * Renames and moves replace only this range, so prefixes and queries survive.
 * @property remoteUrl for an `image.alias` path, the remote URL it stands for; such paths never resolve to a local file.
 */
data class ImageSrc(val raw: String, val path: String, val pathRange: TextRange, val remoteUrl: String? = null)

/** Normalisation of image URLs the way a Nuxt site (with Nuxt Image) serves them. Pure functions, no project needed. */
object ImagePaths {
    /** Extensions (lower case) of files treated as images. */
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp")

    private val IPX = Regex("""^/_ipx/[^/]+(/.*)$""")
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")

    /**
     * Normalises [raw] (a Markdown destination, attribute or YAML value): strips `<…>`, whitespace, `?query` and `#hash`, then a
     * [cdnURL] prefix, a [baseURL] prefix and an `/_ipx/<modifiers>` prefix, and percent-decodes the rest. Paths starting with an
     * [aliases] key (`/unsplash/…`) become remote. Returns null for anything that is not a site-absolute path: relative paths,
     * protocol-relative `//host/…`, and URLs with a scheme other than the CDN's.
     */
    fun normalize(raw: String, baseURL: String? = null, cdnURL: String? = null, aliases: Map<String, String> = emptyMap()): ImageSrc? {
        var start = 0
        var end = raw.length
        while (start < end && raw[start].isWhitespace()) start++
        while (end > start && raw[end - 1].isWhitespace()) end--
        if (end - start >= 2 && raw[start] == '<' && raw[end - 1] == '>') {
            start++
            end--
        }
        val cut = raw.substring(start, end).indexOfAny(charArrayOf('?', '#'))
        if (cut >= 0) end = start + cut
        if (start >= end) return null

        val cdn = cdnURL?.trim()?.trimEnd('/').orEmpty()
        if (cdn.isNotEmpty() && raw.startsWith("$cdn/", start) && start + cdn.length < end) {
            start += cdn.length
        } else if (raw.startsWith("//", start) || SCHEME.containsMatchIn(raw.substring(start, end)) || raw[start] != '/') {
            return null
        }

        val base = baseURL?.trim()?.trim('/').orEmpty()
        if (base.isNotEmpty() && raw.startsWith("/$base/", start)) start += base.length + 1
        IPX.find(raw.substring(start, end))?.let { start += it.groups[1]!!.range.first }

        val written = raw.substring(start, end)
        val path = decode(written.replace("&amp;", "&"))
        if (path.length < 2 || path.startsWith("//")) return null
        val range = TextRange(start, end)
        for ((key, url) in aliases) {
            val prefix = "/" + key.trim('/')
            if (prefix.length > 1 && (path == prefix || path.startsWith("$prefix/"))) {
                return ImageSrc(raw, path, range, url.trimEnd('/') + path.substring(prefix.length))
            }
        }
        return ImageSrc(raw, path, range)
    }

    /** Percent-encodes the characters of a relative URL path that a browser or the Markdown preview would misread. */
    fun encodeRelative(path: String): String = buildString(path.length) {
        for (c in path) {
            when (c) {
                '%' -> append("%25")
                '+' -> append("%2B")
                ' ' -> append("%20")
                '#' -> append("%23")
                '?' -> append("%3F")
                '"' -> append("%22")
                else -> append(c)
            }
        }
    }

    /** True for a file name (or path) with an [IMAGE_EXTENSIONS] extension. */
    fun isImageName(name: String): Boolean = name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    fun isImageFile(file: VirtualFile): Boolean = !file.isDirectory && isImageName(file.name)

    /** Decodes `%XX` escapes (UTF-8); unlike `URLDecoder`, `+` stays a plus. Malformed escapes are kept as written. */
    private fun decode(text: String): String {
        if ('%' !in text) return text
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length && isHex(text[i + 1]) && isHex(text[i + 2])) {
                bytes.write(text.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                val next = if (Character.isHighSurrogate(c) && i + 1 < text.length) i + 2 else i + 1
                bytes.write(text.substring(i, next).toByteArray(Charsets.UTF_8))
                i = next
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
