package cz.xprees.comark.editor

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import cz.xprees.comark.ComarkIcons
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkSlot
import javax.swing.Icon

/**
 * Breadcrumbs (and sticky lines, which use the same provider) for Comark files: the components and slots around the caret, e.g.
 * `card > #footer > button`.
 *
 * The platform picks the provider registered for the file's own language before one for a base language, so this one is used for
 * Comark files instead of the Markdown provider, which shows nothing.
 */
class ComarkBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(ComarkLanguage)

    override fun acceptElement(element: PsiElement): Boolean = element is ComarkBlockComponent || element is ComarkSlot

    override fun getElementInfo(element: PsiElement): String = when (element) {
        is ComarkBlockComponent -> element.componentName ?: "::"
        is ComarkSlot -> "#" + element.slotName.orEmpty()
        else -> ""
    }

    override fun getElementIcon(element: PsiElement): Icon? = if (element is ComarkBlockComponent) ComarkIcons.COMPONENT else null

    /** The opener (or slot) line as written, attributes included. */
    override fun getElementTooltip(element: PsiElement): String? = when (element) {
        is ComarkBlockComponent -> element.header?.text
        is ComarkSlot -> {
            val text = element.containingFile.viewProvider.contents
            val start = element.textRange.startOffset
            var end = start
            while (end < text.length && text[end] != '\n') end++
            text.subSequence(start, end).toString().trim()
        }

        else -> null
    }
}
