package cz.xprees.comark.images

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.model.Pointer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parents
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.psi.ComarkFile
import java.awt.Image

/**
 * Quick documentation (Ctrl+Q, hover) for Nuxt image paths in Comark documents: image destinations, reference definitions,
 * attribute values, inline component attributes, and values of props blocks and the front matter (injected as YAML or not). It
 * shows a thumbnail, the pixel and byte size and the directory serving the image; a path no image directory serves in a Nuxt
 * project gets the directories that were searched.
 *
 * Registered before the component documentation, so an image value of a prop documents the image rather than the prop.
 */
class ComarkImageDocumentationTargetProvider : DocumentationTargetProvider, DumbAware {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val reference = referenceAt(file, offset) ?: return emptyList()
        return listOfNotNull(ComarkImageDocumentationTarget.of(reference))
    }

    companion object {
        /**
         * The image reference at [offset] of [file]: a Comark file (its own PSI, the YAML injected at the offset, or a reference the
         * file holds itself) or YAML injected into one. The offset right after the path counts too.
         */
        internal fun referenceAt(file: PsiFile, offset: Int): ComarkImageReference? {
            if (file is ComarkFile) {
                if (!ComarkImageReferences.enabled(file)) return null
                val injections = InjectedLanguageManager.getInstance(file.project)
                injections.findInjectedElementAt(file, offset)?.let { injected ->
                    // the platform may hand over the host file even when the caret is in injected YAML
                    val start = injected.textRange.startOffset
                    val local = start + offset - injections.injectedToHost(injected, start)
                    return referenceIn(injected.containingFile, local)
                }
                return referenceIn(file, offset) ?: ComarkImageReferences.fileHostedAt(file, offset)
            }
            if (InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file) !is ComarkFile) return null
            return referenceIn(file, offset)
        }

        /** An image reference on [offset]'s leaf (or the one before it) or its parents, below the file. */
        private fun referenceIn(file: PsiFile, offset: Int): ComarkImageReference? {
            for (at in intArrayOf(offset, offset - 1)) {
                val leaf = file.findElementAt(at) ?: continue
                for (element in leaf.parents(withSelf = true)) {
                    if (element is PsiFile) break
                    val start = element.textRange.startOffset
                    element.references.firstOrNull { reference ->
                        reference is ComarkImageReference && reference.rangeInElement.shiftRight(start).let { offset in it.startOffset..it.endOffset }
                    }?.let { return it as ComarkImageReference }
                }
            }
            return null
        }
    }
}

/**
 * The documentation of the image path [path]: the image [file] found in [dir], or, when [file] is null, a path none of the
 * [searched] directories serves. The data is a snapshot (no PSI), so a hard pointer is safe; "go to source" opens the image.
 */
class ComarkImageDocumentationTarget(
    private val project: Project,
    val path: String,
    val file: VirtualFile?,
    val dir: ImageDir?,
    private val searched: List<String>,
) : DocumentationTarget {
    /** [Pointer] is experimental API, but [DocumentationTarget] requires it; there is no other way to provide a target. */
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override val navigatable: Navigatable?
        get() = file?.takeIf { it.isValid }?.let { OpenFileDescriptor(project, it) }

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(path)
        .icon(file?.let(ComarkImageThumbnails.getInstance()::cachedIcon) ?: ComarkImageThumbnails.GENERIC)
        .locationText(dir?.label, null)
        .presentation()

    /** One line for the Ctrl+hover hint: `/x.webp — 1200 × 630 px, 84 KB, public`. */
    override fun computeDocumentationHint(): String {
        val builder = HtmlBuilder().append(HtmlChunk.text(path).bold()).append(" – ")
        val image = file?.takeIf { it.isValid }
        if (image == null) return builder.append(ComarkBundle.message("doc.image.missing.hint")).toString()
        val details = listOfNotNull(pixels(image), StringUtil.formatFileSize(image.length), dir?.label)
        return builder.append(details.joinToString(", ")).toString()
    }

    override fun computeDocumentation(): DocumentationResult {
        val (html, images) = render()
        return DocumentationResult.documentation(html).images(images)
    }

    /** The documentation HTML and the thumbnail it shows, keyed by its `<img src>` (Swing HTML cannot load VFS URLs or WebP). */
    internal fun render(): Pair<String, Map<String, Image>> {
        val definition = HtmlBuilder().append(HtmlChunk.text(path).bold())
        val content = HtmlBuilder()
        val sections = HtmlBuilder()
        val images = HashMap<String, Image>()
        val image = file?.takeIf { it.isValid }
        if (image == null) {
            val where = searched.joinToString(", ").ifEmpty { ComarkBundle.message("inspection.missing.image.no.directories") }
            content.append(HtmlChunk.p().addText(ComarkBundle.message("doc.image.missing", where)))
        } else {
            ComarkImageThumbnails.getInstance().thumbnail(image, THUMBNAIL_PX)?.let { thumbnail ->
                images[THUMBNAIL_KEY] = thumbnail
                content.append(
                    HtmlChunk.tag("img").attr("src", THUMBNAIL_KEY).attr("width", thumbnail.width).attr("height", thumbnail.height)
                        .attr("alt", image.name),
                )
            }
            pixels(image)?.let { sections.append(section(ComarkBundle.message("doc.image.dimensions"), it)) }
            sections.append(section(ComarkBundle.message("doc.image.bytes"), StringUtil.formatFileSize(image.length)))
            dir?.let { sections.append(section(ComarkBundle.message("doc.image.directory"), it.label)) }
            sections.append(section(ComarkBundle.message("doc.image.file"), location(image)))
        }
        val result = HtmlBuilder()
        result.append(definition.wrapWith(DocumentationMarkup.PRE_ELEMENT).wrapWith(DocumentationMarkup.DEFINITION_ELEMENT))
        if (!content.isEmpty) result.append(content.wrapWith(DocumentationMarkup.CONTENT_ELEMENT))
        if (!sections.isEmpty) result.append(sections.wrapWith(DocumentationMarkup.SECTIONS_TABLE))
        return result.toString() to images
    }

    private fun pixels(image: VirtualFile): String? =
        ComarkImageThumbnails.getInstance().dimensions(image)?.let { ComarkBundle.message("doc.image.pixels", it.width.toString(), it.height.toString()) }

    private fun location(image: VirtualFile): String =
        project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(image, it) } ?: image.presentableUrl

    private fun section(title: String, text: String): HtmlChunk = HtmlChunk.tag("tr").children(
        DocumentationMarkup.SECTION_HEADER_CELL.child(HtmlChunk.text("$title:")),
        DocumentationMarkup.SECTION_CONTENT_CELL.child(HtmlChunk.text(text)),
    )

    companion object {
        internal const val THUMBNAIL_KEY = "comark-image-thumbnail"
        internal const val THUMBNAIL_PX = 240

        /**
         * The target of [reference]: its image, or, when it does not resolve in a detected Nuxt project, a missing image (the same
         * paths [ComarkMissingImageInspection] reports). Null for unresolved paths elsewhere, which may be served by something the
         * plugin doesn't know.
         */
        fun of(reference: ComarkImageReference): ComarkImageDocumentationTarget? {
            val project = reference.element.project
            val directories = NuxtImageDirectories.getInstance(project)
            val path = reference.src.path
            directories.resolve(reference.context, reference.src.raw)?.let { return ComarkImageDocumentationTarget(project, path, it.file, it.dir, emptyList()) }
            val roots = directories.forFile(reference.context)?.takeIf { it.isNuxt } ?: return null
            if (path.startsWith("/_nuxt/")) return null
            return ComarkImageDocumentationTarget(project, path, null, null, roots.dirs.map { it.label })
        }
    }
}
