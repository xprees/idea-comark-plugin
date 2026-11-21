package cz.xprees.comark.navigation

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.BindablePsiReference
import com.intellij.util.IncorrectOperationException
import cz.xprees.comark.highlighting.ComarkInlineSyntax
import cz.xprees.comark.images.ComarkImageRenameTarget
import cz.xprees.comark.meta.ComarkComponentService
import cz.xprees.comark.meta.ComponentNaming
import cz.xprees.comark.meta.ComponentUsages
import cz.xprees.comark.parser.ComarkInlineScanner
import cz.xprees.comark.psi.ComarkComponentName
import cz.xprees.comark.psi.ComarkFile

/**
 * A component name in a Comark document (`::prose-alert`, `:::ProseAlert`, inline `:badge`) referring to the component's `.vue`
 * file, which is what Find Usages and Rename work with. [componentName] is the name as written.
 *
 * The reference is soft: a component that is unknown, or known only from nuxt-component-meta or remote metadata without a file,
 * resolves to nothing and is never reported as an error. Renaming the file rewrites the name in the style it was written in (see
 * [ComponentNaming.renamedUsage]); the reference is a [BindablePsiReference], so a rename hands it the renamed file itself and the
 * new name comes from the file's actual location.
 */
class ComarkComponentReference(
    element: PsiElement,
    rangeInElement: TextRange,
    val componentName: String,
    /** For an inline name: the Markdown text holding it (shared by its names) and its position among its inline components. */
    private val anchor: InlineAnchor? = null,
    private val indexInHolder: Int = -1,
    /** The component file this usage was found for when a directory above it was searched (Move or Rename of the directory). */
    private val boundFile: VirtualFile? = null,
) : PsiReferenceBase<PsiElement>(element, rangeInElement, true), BindablePsiReference {

    /** This usage, found for [file] while searching a directory above it. */
    fun boundTo(file: VirtualFile): ComarkComponentReference =
        ComarkComponentReference(element, rangeInElement, componentName, anchor, indexInHolder, file)

    override fun resolve(): PsiElement? {
        val host = element
        val file = host.containingFile ?: return null
        val project = host.project
        val component = ComarkComponentService.getInstance(project).index().find(componentName, ComponentUsages.contextFile(file)) ?: return null
        val virtualFile = component.file?.takeIf { it.isValid } ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    override fun isReferenceTo(element: PsiElement): Boolean =
        ComarkComponentReferences.mayTarget(element) && element.manager.areElementsEquivalent(resolve(), element)

    override fun getVariants(): Array<Any> = emptyArray()

    /**
     * [newElementName] is the new name of the resolved `.vue` file, which stays in its directory, or, for a usage found through a
     * directory above the file, the directory's new name.
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        val renamed = ComarkImageRenameTarget.of(element.project) as? PsiDirectory
        if (boundFile != null && renamed != null && VfsUtilCore.isAncestor(renamed.virtualFile, boundFile, true)) {
            val path = ComarkComponentService.getInstance(element.project).componentPath(boundFile) ?: return element
            val below = VfsUtilCore.getRelativePath(boundFile, renamed.virtualFile) ?: return element
            if (!path.endsWith("/$below")) return element
            val parent = path.removeSuffix("/$below").substringBeforeLast('/', "")
            return renameTo(if (parent.isEmpty()) "$newElementName/$below" else "$parent/$newElementName/$below")
        }
        val target = (resolve() as? PsiFile)?.virtualFile ?: return element
        val path = ComarkComponentService.getInstance(element.project).componentPath(target) ?: return element
        val directory = path.substringBeforeLast('/', "")
        return renameTo(if (directory.isEmpty()) newElementName else "$directory/$newElementName")
    }

    /**
     * Points the usage at the `.vue` file [element] (after it was renamed or moved), or at a moved directory above the file it was
     * found for: the name becomes the one the file's location gives.
     */
    override fun bindToElement(element: PsiElement): PsiElement {
        val file = when {
            // a moved directory above the component file
            element is PsiDirectory -> boundFile?.takeIf { it.isValid && VfsUtilCore.isAncestor(element.virtualFile, it, true) }
            ComarkComponentReferences.mayTarget(element) -> (element as PsiFile).virtualFile
            else -> throw IncorrectOperationException("Not a Vue component file: $element")
        } ?: return this.element
        val path = ComarkComponentService.getInstance(element.project).componentPath(file) ?: return this.element
        return renameTo(path)
    }

    private fun renameTo(relativePath: String): PsiElement {
        val newName = ComponentNaming.renamedUsage(componentName, relativePath) ?: return element
        if (newName == componentName) return element
        val range = currentRange() ?: return element
        return ComarkComponentReferences.replaceText(element, range, newName) { old, new -> if (anchor?.holder == old) anchor.holder = new }
    }

    /**
     * Where the name is now. A refactoring can rewrite other usages of the same document before this one (Move retargets them
     * front to back), and an inline name's range is an offset in the file, so it is found again from its holder, which keeps its
     * place in the tree when an edit before it shifts it. Null when the name is gone.
     */
    private fun currentRange(): TextRange? {
        val host = element
        if (!host.isValid) return null
        val holder = anchor?.holder
        if (holder != null && holder.isValid) {
            val component = ComarkComponentReferences.inlineComponents(holder).getOrNull(indexInHolder)
            if (component != null) {
                val base = holder.textRange.startOffset - host.textRange.startOffset
                val range = TextRange(base + component.nameStart, base + component.nameEnd)
                if (range.endOffset <= host.textLength && range.substring(host.text) == componentName) return range
            }
        }
        return rangeInElement.takeIf { it.endOffset <= host.textLength && it.substring(host.text) == componentName }
    }
}

/**
 * The Markdown text holding inline component names, shared by their references. Renaming a name can replace the holder itself
 * (a single leaf), and the names after it in the same text then find themselves in its replacement.
 */
class InlineAnchor(var holder: PsiElement)

/** Creates [ComarkComponentReference]s for block component names and for inline component names in Markdown text. */
object ComarkComponentReferences {
    private const val VUE_SUFFIX = ".vue"

    /** True when [element] can be the target of a component reference: a `.vue` file. */
    fun mayTarget(element: PsiElement?): Boolean = element is PsiFile && element.name.endsWith(VUE_SUFFIX, ignoreCase = true)

    /** The reference of a block component name, or null in Markdown injected into a code fence (examples, not usages). */
    fun blockReference(name: ComarkComponentName): ComarkComponentReference? {
        if (name.textLength == 0) return null
        val file = name.containingFile as? ComarkFile ?: return null
        if (isExample(file)) return null
        return ComarkComponentReference(name, TextRange(0, name.textLength), name.text)
    }

    /** The reference of the inline component name at [offset] of [file] (the offset right after the name counts too). */
    fun inlineReferenceAt(file: ComarkFile, offset: Int): ComarkComponentReference? {
        if (isExample(file)) return null
        for (candidate in listOf(offset, offset - 1)) {
            if (candidate < 0) continue
            val holder = file.findElementAt(candidate)?.let(ComarkInlineSyntax::holderOf) ?: continue
            val base = holder.textRange.startOffset
            val names = inlineNames(holder)
            val index = names.indexOfFirst { offset - base in it.nameStart..it.nameEnd }.takeIf { it >= 0 } ?: continue
            return inlineReference(file, InlineAnchor(holder), names[index], index)
        }
        return null
    }

    /** References of every inline component name in [file], cached until the file changes. */
    fun inlineReferences(file: ComarkFile): List<ComarkComponentReference> {
        if (isExample(file)) return emptyList()
        return CachedValuesManager.getCachedValue(file) {
            val references = ArrayList<ComarkComponentReference>()
            PsiTreeUtil.processElements(file) { element ->
                if (ComarkInlineSyntax.isInlineHolder(element)) {
                    val anchor = InlineAnchor(element)
                    for ((index, component) in inlineNames(element).withIndex()) references += inlineReference(file, anchor, component, index)
                }
                true
            }
            CachedValueProvider.Result.create<List<ComarkComponentReference>>(references, file)
        }
    }

    /** Every component name reference in [file]: block component names, then inline ones. */
    fun allReferences(file: ComarkFile): List<ComarkComponentReference> =
        PsiTreeUtil.collectElementsOfType(file, ComarkComponentName::class.java).mapNotNull(::blockReference) + inlineReferences(file)

    /**
     * Replaces [range] of [host] with [newText] through the PSI: the leaf holding the range gets new text. A name split over
     * several leaves, which Markdown's inline parsing can produce, is replaced in the document instead. [onLeafReplaced] is told
     * which leaf replaced which.
     */
    fun replaceText(host: PsiElement, range: TextRange, newText: String, onLeafReplaced: (PsiElement, PsiElement) -> Unit = { _, _ -> }): PsiElement {
        val absolute = range.shiftRight(host.textRange.startOffset)
        val file = host.containingFile
        val leaf = file.findElementAt(absolute.startOffset)
        val node = leaf?.node
        if (node is LeafElement && leaf.textRange.contains(absolute)) {
            val replaced = node.replaceWithText(absolute.shiftLeft(leaf.textRange.startOffset).replace(leaf.text, newText))
            onLeafReplaced(leaf, replaced.psi)
            return if (leaf == host) replaced.psi else host
        }
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: throw IncorrectOperationException("No document for $file")
        documents.doPostponedOperationsAndUnblockDocument(document)
        document.replaceString(absolute.startOffset, absolute.endOffset, newText)
        documents.commitDocument(document)
        return if (host.isValid) host else file
    }

    /**
     * Inline components of [holder] that are component usages. `:smile:` is left out: with comark's emoji plugin it is a
     * shortcode, and the unknown component inspection skips it for the same reason.
     */
    internal fun inlineComponents(holder: PsiElement): List<ComarkInlineScanner.InlineComponent> = inlineNames(holder)

    private fun inlineNames(holder: PsiElement): List<ComarkInlineScanner.InlineComponent> {
        val text = holder.text
        return ComarkInlineSyntax.scan(holder).components.filter { component ->
            component.nameEnd > component.nameStart &&
                (component.hasContent || component.attributes != null || text.getOrNull(component.nameEnd) != ':')
        }
    }

    private fun inlineReference(file: ComarkFile, anchor: InlineAnchor, component: ComarkInlineScanner.InlineComponent, index: Int): ComarkComponentReference {
        val base = anchor.holder.textRange.startOffset
        val range = TextRange(base + component.nameStart, base + component.nameEnd)
        return ComarkComponentReference(file, range, range.subSequence(file.viewProvider.contents).toString(), anchor, index)
    }

    /** Markdown injected into a code fence of a Comark file is Comark too, but its components are illustrations. */
    internal fun isExample(file: PsiFile): Boolean = InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
}
