package cz.xprees.comark.navigation

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.reference.impl.providers.PsiFileReference
import com.intellij.util.ProcessingContext
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/**
 * References from page route links (`[install](/guide/install#setup)`) in Comark files of Nuxt Content packages to the content
 * files they render (`content/1.guide/2.install.md`), see [ComarkContentRoutes].
 *
 * The Markdown plugin also puts file references on such a destination (it takes `/guide/install` for a path from the content root),
 * which don't resolve; this reference spans the whole route and wins Go to Declaration whenever it resolves. Being a
 * [PsiFileReference], it is also the file the Markdown plugin's header anchor reference looks in, so `#setup` resolves to (and
 * completes) the target page's headings.
 */
class ComarkRouteReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(MarkdownLinkDestination::class.java), Provider())
    }

    private class Provider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            if (element !is MarkdownLinkDestination || element.containingFile !is ComarkFile) return PsiReference.EMPTY_ARRAY
            val link = ComarkContentRoutes.parseRouteLink(element.text, ElementManipulators.getValueTextRange(element))
                ?: return PsiReference.EMPTY_ARRAY
            if (ComarkContentRoutes.contentDirectory(element.containingFile) == null) return PsiReference.EMPTY_ARRAY
            return arrayOf(ComarkRouteReference(element, link.range, link.path))
        }
    }
}

/** Reference from the route [route] (at [range] in [element]) to its content file; resolves to nothing for unknown routes. */
class ComarkRouteReference(element: MarkdownLinkDestination, range: TextRange, val route: String) :
    PsiPolyVariantReferenceBase<MarkdownLinkDestination>(element, range, false), PsiFileReference {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val target = ComarkContentRoutes.resolve(element.containingFile, route) ?: return ResolveResult.EMPTY_ARRAY
        val file = PsiManager.getInstance(element.project).findFile(target) ?: return ResolveResult.EMPTY_ARRAY
        return PsiElementResolveResult.createResults(file)
    }

    /** Route completion: every page route of the package, with the page file as the type text. */
    override fun getVariants(): Array<Any> {
        val file = element.containingFile
        val content = ComarkContentRoutes.contentDirectory(file) ?: return emptyArray()
        return ComarkContentRoutes.routes(file.project, content).map { (route, page) ->
            LookupElementBuilder.create(route).withTypeText(page.path.removePrefix(content.path).removePrefix("/"), true)
        }.toTypedArray()
    }

    /**
     * Routes don't follow file names one to one (ordering prefixes, index pages, collection prefixes), so renaming or moving the
     * page leaves the link as it is.
     */
    override fun handleElementRename(newElementName: String): PsiElement = element

    override fun bindToElement(element: PsiElement): PsiElement = this.element
}
