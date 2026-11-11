package cz.xprees.comark.images

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
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
import cz.xprees.comark.psi.ComarkAttributeValue
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.psi.ComarkPropsContent
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

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

    /** For a reference the Comark file holds itself: follows its range while earlier edits of the file shift it ([tracked]). */
    private var marker: RangeMarker? = null

    override fun getRangeInElement(): TextRange {
        val marker = marker ?: return super.getRangeInElement()
        return if (marker.isValid) marker.textRange else super.getRangeInElement()
    }

    /**
     * This reference, or for one held by the Comark file (its range is absolute) a copy whose range follows edits of the document:
     * a refactoring edits several paths of one file through the references it found before any edit.
     */
    fun tracked(): ComarkImageReference {
        val file = element as? PsiFile ?: return this
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return this
        val range = super.getRangeInElement()
        if (range.endOffset > document.textLength) return this
        return ComarkImageReference(element, range, src, context).also { copy ->
            copy.marker = document.createRangeMarker(range).apply { isGreedyToLeft = true; isGreedyToRight = true }
        }
    }

    /**
     * The image this reference last resolved to. A directory rename or move binds the reference after the image has moved with its
     * directory, when the written path no longer resolves; the [VirtualFile] keeps its identity and tells where the image is now.
     */
    @Volatile
    private var lastResolved: VirtualFile? = null

    override fun resolve(): PsiElement? {
        val image = NuxtImageDirectories.getInstance(element.project).resolve(context, src.raw)?.file ?: return null
        lastResolved = image
        return PsiManager.getInstance(element.project).findFile(image)
    }

    /** The image file this reference resolves to, or null; for searches that don't need PSI. */
    fun resolveFile(): VirtualFile? = NuxtImageDirectories.getInstance(element.project).resolve(context, src.raw)?.file?.also { lastResolved = it }

    override fun isSoft(): Boolean = resolve() == null

    override fun isReferenceTo(element: PsiElement): Boolean =
        ComarkImageReferences.mayTarget(element) && element.manager.areElementsEquivalent(resolve(), element)

    override fun getVariants(): Array<Any> = emptyArray()

    /**
     * [newElementName] is the new name of the resolved image, which stays in its directory: only the last path segment changes. When
     * the rename is of a directory holding the image ([ComarkImageRenameTarget]), its segment of the path changes instead.
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        val renamed = ComarkImageRenameTarget.of(element.project)
        if (renamed is PsiDirectory) return renameDirectory(renamed.virtualFile, newElementName)
        return replacePath(src.path.substringBeforeLast('/', "") + "/" + newElementName)
    }

    /** Renames the segment of [directory] (not renamed yet, the image is still in it) to [newName]; any other path stays. */
    private fun renameDirectory(directory: VirtualFile, newName: String): PsiElement {
        val directories = NuxtImageDirectories.getInstance(element.project)
        val image = directories.resolve(context, src.raw)?.file ?: return element
        if (!VfsUtilCore.isAncestor(directory, image, true)) return element
        val path = directories.pathFor(image, context) ?: return element
        val directoryPath = directories.pathFor(directory, context)?.takeIf { path.startsWith("$it/") } ?: return element
        return replacePath(directoryPath.substringBeforeLast('/') + "/" + newName + path.removePrefix(directoryPath))
    }

    /**
     * Points the path at the image [element] where it is now: the path its (new) location is served under. For a renamed or moved
     * directory holding the image ([ComarkImageReferenceSearcher] finds those references), the image's new path below it. A
     * location no image directory serves leaves the path as written.
     */
    override fun bindToElement(element: PsiElement): PsiElement {
        val file = (element as? PsiFileSystemItem)?.virtualFile ?: throw IncorrectOperationException("Not an image file: $element")
        val directories = NuxtImageDirectories.getInstance(element.project)
        val image = if (file.isDirectory) imageIn(file) ?: return this.element else file
        val path = directories.pathFor(image, context) ?: return this.element
        return replacePath(path)
    }

    /**
     * The image this reference names inside [directory], which has just been renamed or moved: the file it resolved to before, or else
     * the file below [directory] at the end of the written path, trying the longest tail first (the directory deepest in the path).
     */
    private fun imageIn(directory: VirtualFile): VirtualFile? {
        lastResolved?.takeIf { it.isValid && VfsUtilCore.isAncestor(directory, it, true) }?.let { return it }
        val segments = src.path.removePrefix("/").split('/')
        for (start in 1 until segments.size) {
            val tail = segments.drop(start).joinToString("/")
            directory.findFileByRelativePath(tail)?.takeIf { !it.isDirectory && ImagePaths.isImageFile(it) }?.let { return it }
        }
        return null
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
     * Every image reference in [file]: on image destinations and attribute values, held by the file ([fileHosted]) and on the YAML
     * injected into its props blocks and front matter. Each written value has one reference.
     */
    fun all(file: ComarkFile): List<ComarkImageReference> {
        if (!enabled(file)) return emptyList()
        val references = ArrayList<ComarkImageReference>()
        val injections = InjectedLanguageManager.getInstance(file.project)
        // the YAML references live on scalars; the YAML PSI classes are optional here, so every injected element is asked
        fun injected(host: PsiElement) {
            if (host !is PsiLanguageInjectionHost) return
            injections.enumerate(host) { injected, _ ->
                PsiTreeUtil.processElements(injected) { element -> element.references.filterIsInstanceTo(references); true }
            }
        }
        frontMatter(file)?.let(::injected)
        PsiTreeUtil.processElements(file) { element ->
            when (element) {
                is MarkdownLinkDestination, is ComarkAttributeValue -> element.references.filterIsInstanceTo(references)
                is ComarkPropsContent -> injected(element)
            }
            true
        }
        references += fileHosted(file)
        return references
    }

    /**
     * Replaces [range] of [host] with [text]: through the host's manipulator when it has one (link destinations, attribute values,
     * YAML scalars), else in the leaf or the document holding it.
     */
    fun replace(host: PsiElement, range: TextRange, text: String): PsiElement {
        // the Markdown destination manipulator edits only the first leaf, which breaks on a destination split over several
        // (`/_ipx/w_200&f_webp/x.png`); those are edited as text
        val oneLeaf = host.containingFile?.findElementAt(host.textRange.startOffset)?.textRange?.contains(host.textRange) == true
        if (oneLeaf && ElementManipulators.getManipulator(host) != null) {
            try {
                return ElementManipulators.handleContentChange(host, range, text) ?: host
            } catch (_: IncorrectOperationException) {
                // edit the text instead
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
