package cz.xprees.comark.navigation

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import cz.xprees.comark.meta.ComarkComponentService
import cz.xprees.comark.meta.ComponentNaming
import cz.xprees.comark.psi.ComarkFile

/**
 * Finds the usages of a Vue component in Comark documents, for Find Usages and Rename of its `.vue` file.
 *
 * The platform's own search looks for the file name (`Alert`) as a word, which misses kebab-case usages (`::prose-alert`, whose
 * words in the index are `prose` and `alert`). This searcher looks up every name the component is registered under, finds the
 * files containing its kebab-case words or its PascalCase word (case-insensitively, so camelCase usages are found too) and keeps
 * the component name references there that resolve to the file. It runs in a read action in smart mode (the word index) and
 * stays within the search scope; any other element is left to the other searchers.
 */
class ComarkComponentReferenceSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = parameters.elementToSearch
        if (!ComarkComponentReferences.mayTarget(target)) return
        val virtualFile = (target as PsiFile).virtualFile ?: return
        val project = parameters.project
        val names = ComarkComponentService.getInstance(project).index().components
            .filter { it.file == virtualFile }
            .flatMap { it.allNames }
        if (names.isEmpty()) return
        val scope = parameters.effectiveSearchScope
        val files = when (scope) {
            is GlobalSearchScope -> candidateFiles(project, scope, names).mapNotNull { PsiManager.getInstance(project).findFile(it) }
            is LocalSearchScope -> scope.scope.mapNotNull { it.containingFile }.distinct()
            else -> emptyList()
        }
        for (file in files) {
            ProgressManager.checkCanceled()
            if (file !is ComarkFile) continue
            for (reference in ComarkComponentReferences.allReferences(file)) {
                if (scope is LocalSearchScope && !scope.containsRange(file, reference.absoluteRange)) continue
                if (reference.isReferenceTo(target) && !consumer.process(reference)) return
            }
        }
    }

    /** Files in [scope] containing one of [names] in kebab-case (all of its words) or as one word. */
    private fun candidateFiles(project: Project, scope: GlobalSearchScope, names: List<String>): Set<VirtualFile> {
        val helper = PsiSearchHelper.getInstance(project)
        val texts = names.flatMap { listOf(ComponentNaming.kebabCase(it), it) }.filter { it.isNotEmpty() }.distinct()
        val files = LinkedHashSet<VirtualFile>()
        for (text in texts) {
            helper.processCandidateFilesForText(scope, UsageSearchContext.ANY, false, text) { files += it; true }
        }
        return files
    }
}
