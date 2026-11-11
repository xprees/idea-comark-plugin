package cz.xprees.comark.images

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.comark.preview.InlineComarkRewriter
import cz.xprees.comark.settings.ComarkSettings

/** Where the preview should load an image from. */
sealed class PreviewImage {
    /** A local file, as a percent-encoded path relative to the document's directory. */
    data class Local(val relativeSrc: String) : PreviewImage()

    /** An absolute URL (the CDN, or an `image.alias` target) the preview loads as is. */
    data class Remote(val url: String) : PreviewImage()

    /** A site-absolute path in a Nuxt project that no image directory holds. */
    data object Missing : PreviewImage()
}

/**
 * Maps the image sources written in the Comark document [file] to what the Markdown preview should load.
 *
 * The preview's JCEF panel resolves a `src` starting with `/` against the project's base directory, not against the Nuxt public
 * or image directories, so `/threats/x.webp` would show as a broken image. A local hit is therefore given as a path relative to
 * the document's directory, which the panel resolves and serves through its own static server (with its usual trust rules);
 * never `file://` or an absolute OS path, which the page cannot load or which break on Windows. Returns null for sources it
 * leaves alone: relative paths, other URLs, and unresolved paths outside Nuxt projects (where the panel's own project-root
 * lookup may still find them).
 */
class ComarkPreviewImages(project: Project, private val file: VirtualFile) : (String) -> PreviewImage? {
    private val directories = NuxtImageDirectories.getInstance(project)
    private val roots: ImageRoots? by lazy { ReadAction.compute<ImageRoots?, RuntimeException> { directories.forFile(file) } }

    override fun invoke(raw: String): PreviewImage? {
        val roots = roots ?: return null
        val src = ImagePaths.normalize(raw, roots.baseURL, roots.cdnURL, roots.aliases) ?: return null
        src.remoteUrl?.let { return PreviewImage.Remote(it) }
        val hit = ReadAction.compute<ResolvedImage?, RuntimeException> { directories.resolve(file, raw) } ?: return if (roots.isNuxt) PreviewImage.Missing else null
        val cdn = roots.cdnURL?.trim()?.trimEnd('/').orEmpty()
        if (ComarkSettings.getInstance().previewImagesFromCdn && cdn.isNotEmpty() && hit.dir.isPublic) {
            // a public file is served from the CDN root as is (Nuxt's `app.cdnURL` replaces the base URL)
            return PreviewImage.Remote((if (cdn.startsWith("//")) "https:$cdn" else cdn) + src.path)
        }
        val base = file.parent?.path ?: return null
        val relative = FileUtil.getRelativePath(base, hit.file.path, '/') ?: return null
        return PreviewImage.Local(ImagePaths.encodeRelative(relative))
    }
}

/** Rewrites image sources in preview HTML with a [PreviewImage] mapper, and renders component image props as thumbnails. */
object PreviewImageRewriter {
    /** Tells the Markdown preview not to resolve an `<img>` source itself (its value for remote URLs; written as a literal). */
    const val DO_NOT_PROCESS = "md-do-not-process-path"

    /** An `<img>` tag; quoted values may hold `>` (the renderer writes `<…>` destinations as they are). */
    private val IMG_TAG = Regex("""<img\b(?:[^>"']|"[^"]*"|'[^']*')*>""", RegexOption.IGNORE_CASE)
    private val SRC_ATTR = Regex("""(\ssrc\s*=\s*)(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val CLASS_ATTR = Regex("""\sclass\s*=""", RegexOption.IGNORE_CASE)

    /**
     * [html] with the `src` of every `<img>` mapped by [map]: local hits get the relative path (the original value is kept in
     * `data-comark-src`), remote ones the URL plus [DO_NOT_PROCESS], missing ones the `comark-missing-image` class; others are
     * left as they are.
     */
    fun rewrite(html: String, map: (String) -> PreviewImage?): String {
        if (!html.contains("<img", ignoreCase = true)) return html
        return IMG_TAG.replace(html) { match ->
            ProgressManager.checkCanceled()
            val tag = match.value
            if (tag.contains(DO_NOT_PROCESS)) return@replace tag
            val src = SRC_ATTR.find(tag) ?: return@replace tag
            val written = src.groups[2]?.value ?: src.groups[3]?.value ?: return@replace tag
            val prefix = src.groupValues[1]
            when (val image = map(unescape(written))) {
                is PreviewImage.Local -> tag.replaceRange(
                    src.range,
                    "$prefix\"${escape(image.relativeSrc)}\" data-comark-src=\"${escape(unescape(written))}\"",
                )

                is PreviewImage.Remote -> {
                    val replaced = tag.replaceRange(src.range, "$prefix\"${escape(image.url)}\"")
                    "<img $DO_NOT_PROCESS" + replaced.substring(4)
                }

                PreviewImage.Missing ->
                    if (CLASS_ATTR.containsMatchIn(tag)) tag else "<img class=\"comark-missing-image\"" + tag.substring(4)

                null -> tag
            }
        }
    }

    /**
     * The thumbnails of the image props [values] that [map] finds (local or remote), or "" when there are none. Each carries the
     * value's document range as `md-src-pos`, so clicking it scrolls the editor to the value.
     */
    fun propImagesHtml(values: List<ImageValue>, map: (String) -> PreviewImage?): String {
        if (values.isEmpty()) return ""
        val out = StringBuilder()
        for (value in values) {
            ProgressManager.checkCanceled()
            val (src, remote) = when (val image = map(value.value)) {
                is PreviewImage.Local -> image.relativeSrc to false
                is PreviewImage.Remote -> image.url to true
                else -> continue
            }
            out.append("<img class=\"comark-prop-image\"")
            if (remote) out.append(' ').append(DO_NOT_PROCESS)
            out.append(" src=\"").append(escape(src)).append('"')
            out.append(" alt=\"").append(escape(value.key)).append('"')
            out.append(" title=\"").append(escape("${value.key}: ${value.value}")).append('"')
            out.append(" md-src-pos=\"").append(value.range.startOffset).append("..").append(value.range.endOffset).append("\">")
        }
        if (out.isEmpty()) return ""
        return "<div class=\"comark-prop-images\">$out</div>"
    }

    private fun escape(value: String) = InlineComarkRewriter.escape(value)

    /** Resolves the entities the Markdown renderer and HTML authors write in attribute values. */
    private fun unescape(value: String): String {
        if ('&' !in value) return value
        return value.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
