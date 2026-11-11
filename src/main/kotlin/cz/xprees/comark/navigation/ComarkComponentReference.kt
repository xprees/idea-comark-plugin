package cz.xprees.comark.navigation

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
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
class ComarkComponentReference(element: PsiElement, rangeInElement: TextRange, val componentName: String) :
    PsiReferenceBase<PsiElement>(element, rangeInElement, true), BindablePsiReference {

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

    /** [newElementName] is the new name of the resolved `.vue` file, which stays in its directory. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val target = (resolve() as? PsiFile)?.virtualFile ?: return element
        val path = ComarkComponentService.getInstance(element.project).componentPath(target) ?: return element
        val directory = path.substringBeforeLast('/', "")
        return renameTo(if (directory.isEmpty()) newElementName else "$directory/$newElementName")
    }

    /** Points the usage at the `.vue` file [element] (after it was renamed): the name becomes the one its location gives. */
    override fun bindToElement(element: PsiElement): PsiElement {
        if (!ComarkComponentReferences.mayTarget(element)) throw IncorrectOperationException("Not a Vue component file: $element")
        val file = (element as PsiFile).virtualFile ?: return this.element
        val path = ComarkComponentService.getInstance(element.project).componentPath(file) ?: return this.element
        return renameTo(path)
    }

    private fun renameTo(relativePath: String): PsiElement {
        val newName = ComponentNaming.renamedUsage(componentName, relativePath) ?: return element
        if (newName == componentName) return element
        return ComarkComponentReferences.replaceText(element, rangeInElement, newName)
    }
}

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
            val component = inlineNames(holder).firstOrNull { offset - base in it.nameStart..it.nameEnd } ?: continue
            return inlineReference(file, holder, component)
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
                    for (component in inlineNames(element)) references += inlineReference(file, element, component)
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
     * several leaves, which Markdown's inline parsing can produce, is replaced in the document instead.
     */
    fun replaceText(host: PsiElement, range: TextRange, newText: String): PsiElement {
        val absolute = range.shiftRight(host.textRange.startOffset)
        val file = host.containingFile
        val leaf = file.findElementAt(absolute.startOffset)
        val node = leaf?.node
        if (node is LeafElement && leaf.textRange.contains(absolute)) {
            val replaced = node.replaceWithText(absolute.shiftLeft(leaf.textRange.startOffset).replace(leaf.text, newText))
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
    private fun inlineNames(holder: PsiElement): List<ComarkInlineScanner.InlineComponent> {
        val text = holder.text
        return ComarkInlineSyntax.scan(holder).components.filter { component ->
            component.nameEnd > component.nameStart &&
                (component.hasContent || component.attributes != null || text.getOrNull(component.nameEnd) != ':')
        }
    }

    private fun inlineReference(file: ComarkFile, holder: PsiElement, component: ComarkInlineScanner.InlineComponent): ComarkComponentReference {
        val base = holder.textRange.startOffset
        val range = TextRange(base + component.nameStart, base + component.nameEnd)
        return ComarkComponentReference(file, range, range.subSequence(file.viewProvider.contents).toString())
    }

    /** Markdown injected into a code fence of a Comark file is Comark too, but its components are illustrations. */
    private fun isExample(file: PsiFile): Boolean = InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
}
