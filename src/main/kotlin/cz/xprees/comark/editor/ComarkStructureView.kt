package cz.xprees.comark.editor

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.psi.ComarkSlot
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownBlockQuote
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownList
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownListItem
import javax.swing.Icon

/**
 * Structure view of Comark files: Markdown headers in their hierarchy, with block components and slots as nodes of their own.
 *
 * It replaces the Markdown structure view for Comark files (one factory per language is used) rather than extending it, because the
 * Markdown one cannot be reused here: it finds the children of a header through the Markdown file element type, so in a Comark file
 * it lists the top level headers only, without the headers nested under them, and nothing inside components. Headers are presented
 * by the Markdown PSI itself, so they look the same as in a Markdown file.
 */
class ComarkStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val file = psiFile as? ComarkFile ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = ComarkStructureViewModel(file, editor)

            override fun isRootNodeShown(): Boolean = false
        }
    }
}

private class ComarkStructureViewModel(file: ComarkFile, editor: Editor?) :
    StructureViewModelBase(file, editor, ComarkStructureElement(file)), StructureViewModel.ElementInfoProvider {
    init {
        withSuitableClasses(ComarkBlockComponent::class.java, ComarkSlot::class.java, MarkdownHeader::class.java)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = false

    /** The node for the caret: the innermost component or slot, or the closest header before the caret in it. */
    override fun findAcceptableElement(element: PsiElement?): Any? {
        var current = element ?: return null
        while (current !is PsiFile) {
            if (current is MarkdownHeader || current is ComarkSlot || current is ComarkBlockComponent) return current
            val parent = current.parent ?: return null
            if (parent is ComarkFile || parent is ComarkSlot || parent is ComarkBlockComponent) {
                return ComarkOutline.headerBefore(current) ?: parent
            }
            current = parent
        }
        return current
    }
}

private class ComarkStructureElement(element: PsiElement) : PsiTreeElementBase<PsiElement>(element) {
    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        element?.let(ComarkOutline::children).orEmpty().map(::ComarkStructureElement)

    override fun getPresentableText(): String? = (element as? NavigationItem)?.presentation?.presentableText ?: (element as? PsiFile)?.name

    override fun getLocationString(): String? = (element as? NavigationItem)?.presentation?.locationString

    override fun getIcon(open: Boolean): Icon? = (element as? NavigationItem)?.presentation?.getIcon(open) ?: super.getIcon(open)
}

/**
 * The outline of a Comark document. A container (the file, a component, a slot) lists its top level headers, components and slots;
 * a header lists what its section holds: the next level of headers and the components before them. This mirrors the Markdown
 * structure view (which also looks for headers inside lists and block quotes) with components and slots as extra containers.
 */
internal object ComarkOutline {
    fun children(element: PsiElement): List<PsiElement> = when (element) {
        is ComarkFile, is ComarkBlockComponent, is ComarkSlot -> collect(element.firstChild, floor = 0, inSection = false)
        is MarkdownHeader -> collect(element.nextSibling, floor = element.level, inSection = true)
        else -> emptyList()
    }

    /** The closest header before [element] among its siblings, i.e. the innermost header section [element] is in. */
    fun headerBefore(element: PsiElement): MarkdownHeader? {
        var previous = element.prevSibling
        while (previous != null) {
            if (previous is MarkdownHeader) return previous
            if (previous is ComarkSlot) return null
            previous = previous.prevSibling
        }
        return null
    }

    /**
     * Walks siblings from [first]. A header deeper than the last one taken belongs to that one; components and slots are taken only
     * while no header has been taken yet. In a header's section ([inSection]) a header of level [floor] or higher, a slot or the
     * closing marker ends the walk.
     */
    private fun collect(first: PsiElement?, floor: Int, inSection: Boolean): List<PsiElement> {
        val result = ArrayList<PsiElement>()
        var openLevel = Int.MAX_VALUE

        fun walk(start: PsiElement?): Boolean {
            var element = start
            while (element != null) {
                ProgressManager.checkCanceled()
                when {
                    element is MarkdownHeader -> {
                        if (element.level <= floor) return false
                        if (element.level <= openLevel) {
                            result += element
                            openLevel = element.level
                        }
                    }

                    element is ComarkSlot -> {
                        if (inSection) return false
                        result += element
                        openLevel = Int.MAX_VALUE
                    }

                    element is ComarkBlockComponent -> if (openLevel == Int.MAX_VALUE) result += element
                    element.elementType == ComarkTypes.COMPONENT_CLOSE -> if (inSection) return false
                    element is MarkdownList || element is MarkdownListItem || element is MarkdownBlockQuote -> if (!walk(element.firstChild)) return false
                }
                element = element.nextSibling
            }
            return true
        }

        walk(first)
        return result
    }
}
