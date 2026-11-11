package cz.xprees.comark.images

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.refactoring.rename.BindablePsiReference
import com.intellij.util.IncorrectOperationException
import cz.xprees.comark.highlighting.ComarkInlineSyntax
import cz.xprees.comark.meta.ComponentUsages
import cz.xprees.comark.navigation.ComarkComponentReferences
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.psi.ComarkPropsContent
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.MarkdownElementTypes

/**
 * An image path in a Comark document (`![](/threats/x.webp)`, `{src="/x.webp"}`, `image: /x.webp`) referring to the image file the
 * Nuxt site serves under it, see [NuxtImageDirectories]. [rangeInElement] covers only the path part of the value ([ImageSrc.pathRange]):
 * CDN, base URL and `/_ipx/…` prefixes and `?query` / `#hash` stay out of it, so a rename or move keeps them.
 *
 * The reference is soft while it doesn't resolve: missing images are reported by the missing-image inspection, not as unresolved
 * references. Once it resolves it is hard, so it wins over the Markdown plugin's per-segment file references on the same destination
 * (which never resolve to `public/`) when Go to Declaration picks one of them. It is
 * deliberately neither a `FileReference` nor a `FileReferenceOwner`: the Markdown plugin's unresolved file reference inspection reports
 * every unresolved reference of that kind, and its own path references on the same destination are silenced instead (see
 * [cz.xprees.comark.highlighting.ComarkMarkdownInspectionSuppressor]). Being a [BindablePsiReference], a rename or move hands it the
 * file itself, and the new path comes from the file's new location.
 */
class ComarkImageReference(element: PsiElement, rangeInElement: TextRange, val src: ImageSrc, val context: VirtualFile) :
    PsiReferenceBase<PsiElement>(element, rangeInElement, true), BindablePsiReference {

    override fun resolve(): PsiElement? {
        val image = NuxtImageDirectories.getInstance(element.project).resolve(context, src.raw)?.file ?: return null
        return PsiManager.getInstance(element.project).findFile(image)
    }

    override fun isSoft(): Boolean = resolve() == null

    override fun isReferenceTo(element: PsiElement): Boolean =
        ComarkImageReferences.mayTarget(element) && element.manager.areElementsEquivalent(resolve(), element)

    override fun getVariants(): Array<Any> = emptyArray()

    /** [newElementName] is the new name of the resolved image, which stays in its directory: only the last path segment changes. */
    override fun handleElementRename(newElementName: String): PsiElement {
        return replacePath(src.path.substringBeforeLast('/', "") + "/" + newElementName)
    }

    /** Points the path at the image [element] where it is now: the path its (new) location is served under. */
    override fun bindToElement(element: PsiElement): PsiElement {
        val file = (element as? PsiFileSystemItem)?.virtualFile ?: throw IncorrectOperationException("Not an image file: $element")
        val path = NuxtImageDirectories.getInstance(element.project).pathFor(file, context) ?: return this.element
        return replacePath(path)
    }

    /**
     * Writes [path] over the path part. A second rewrite of the same value (the Markdown plugin's own file reference may resolve to the
     * same file and be bound too) finds the new path already there and leaves it alone.
     */
    private fun replacePath(path: String): PsiElement {
        val written = rangeInElement.substring(element.text)
        val newText = ComarkImageReferences.encode(path, keepSpaces = ' ' in written)
        if (written == newText || ImagePaths.normalize(written)?.path == path) return element
        return ComarkImageReferences.replace(element, rangeInElement, newText)
    }
}

/** Creates [ComarkImageReference]s and finds those that the Comark file itself hosts. */
object ComarkImageReferences {
    /** True when [element] can be the target of an image reference: an image file. */
    fun mayTarget(element: PsiElement?): Boolean =
        element is PsiFile && element.virtualFile?.let(ImagePaths::isImageFile) ?: ImagePaths.isImageName(element.name)

    /** True when image paths in [file] (a Comark file, not an example in a code fence) get references. */
    fun enabled(file: PsiFile?): Boolean =
        file is ComarkFile && ComarkSettings.getInstance().imagesEnabled && !ComarkComponentReferences.isExample(file)

    /**
     * The reference of the image path [raw], written at [valueRange] of [host], seen from the document [context]; null for values
     * that are no local site paths (relative or remote URLs, `image.alias` paths) or when images are off.
     */
    fun create(host: PsiElement, valueRange: TextRange, raw: String, context: VirtualFile): ComarkImageReference? {
        val roots = NuxtImageDirectories.getInstance(host.project).forFile(context) ?: return null
        val src = ImagePaths.normalize(raw, roots.baseURL, roots.cdnURL, roots.aliases) ?: return null
        if (src.remoteUrl != null) return null
        return ComarkImageReference(host, src.pathRange.shiftRight(valueRange.startOffset), src, context)
    }

    /** The document the image paths of [file] (a Comark file, or YAML injected into one) are resolved from. */
    fun contextOf(file: PsiFile): VirtualFile? = ComponentUsages.contextFile(file)

    /**
     * Image references the Comark [file] itself holds, cached until it changes: attributes of inline components (`:img{src="/x.png"}`,
     * which are text inside Markdown, not Comark PSI) and, where no YAML is injected (the YAML plugin is optional), image values of
     * props blocks and the front matter.
     */
    fun fileHosted(file: ComarkFile): List<ComarkImageReference> {
        if (!enabled(file)) return emptyList()
        // lets the resolver notice changed settings (and bump its tracker) before the cache is consulted
        contextOf(file)?.let(NuxtImageDirectories.getInstance(file.project)::forFile)
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(computeFileHosted(file), file, NuxtImageDirectories.getInstance(file.project).tracker)
        }
    }

    /** The file-hosted reference at [offset] of [file]; the offset right after the path counts too. */
    fun fileHostedAt(file: ComarkFile, offset: Int): ComarkImageReference? =
        fileHosted(file).firstOrNull { offset in it.rangeInElement.startOffset..it.rangeInElement.endOffset }

    /**
     * Replaces [range] of [host] with [text]: through the host's manipulator when it has one (link destinations, attribute values,
     * YAML scalars), else in the leaf or the document holding it.
     */
    fun replace(host: PsiElement, range: TextRange, text: String): PsiElement {
        if (ElementManipulators.getManipulator(host) != null) {
            try {
                return ElementManipulators.handleContentChange(host, range, text) ?: host
            } catch (_: IncorrectOperationException) {
                // e.g. a destination split over several leaves: edit the text instead
            }
        }
        return ComarkComponentReferences.replaceText(host, range, text)
    }

    /** [path] as written into a document: characters that would end or split a URL are percent-encoded. */
    internal fun encode(path: String, keepSpaces: Boolean): String = buildString(path.length) {
        for (c in path) {
            when (c) {
                '%' -> append("%25")
                '#' -> append("%23")
                '?' -> append("%3F")
                ' ' -> append(if (keepSpaces) " " else "%20")
                else -> append(c)
            }
        }
    }

    private fun computeFileHosted(file: ComarkFile): List<ComarkImageReference> {
        val context = contextOf(file) ?: return emptyList()
        val text = file.viewProvider.contents
        val references = ArrayList<ComarkImageReference>()
        fun add(values: List<ImageValue>) {
            for (value in values) create(file, value.range, value.range.subSequence(text).toString(), context)?.let(references::add)
        }
        val injections = InjectedLanguageManager.getInstance(file.project)
        fun injected(host: PsiElement) = host is PsiLanguageInjectionHost && !injections.getInjectedPsiFiles(host).isNullOrEmpty()

        frontMatter(file)?.takeUnless(::injected)?.let { header ->
            add(ImageValueLocator.yamlValues(header.text, header.textRange.startOffset, ImageKeys::isImageKeyPath))
        }
        PsiTreeUtil.processElements(file) { element ->
            when {
                ComarkInlineSyntax.isInlineHolder(element) -> {
                    val holderText = element.text
                    for (component in ComarkInlineSyntax.scan(element).components) {
                        val scan = component.attributes?.scan ?: continue
                        add(ImageValueLocator.attributeValues(holderText, scan, element.textRange.startOffset))
                    }
                }

                element is ComarkPropsContent && !injected(element) ->
                    add(ImageValueLocator.yamlValues(element.text, element.textRange.startOffset, ImageKeys::isImageKeyPath))
            }
            true
        }
        return references
    }

    /** The front matter at the top of [file] (`FRONT_MATTER_HEADER` is experimental API, so only its element type is used). */
    private fun frontMatter(file: ComarkFile): PsiElement? =
        generateSequence(file.firstChild) { it.firstChild }.firstOrNull { it.elementType == MarkdownElementTypes.FRONT_MATTER_HEADER }
}
