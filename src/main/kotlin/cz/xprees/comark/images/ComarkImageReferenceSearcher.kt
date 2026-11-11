package cz.xprees.comark.images

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.fileTypes.ExtensionFileNameMatcher
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import cz.xprees.comark.lang.NuxtPaths
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/**
 * Finds the image paths in Comark documents ([ComarkImageReference]) that name an image file, for Find Usages, Rename and Move of the
 * file; for a directory inside an image directory, those naming an image below it, so renaming or moving the directory rewrites them.
 *
 * The platform's own search looks the file name up as words, which misses paths written percent-encoded, and a path names no
 * directory by itself, so the references below a directory are never its usages. This searcher reads every Markdown file
 * in the search scope whose text mentions the name, and keeps the image references there that resolve to the target (or below it).
 * References the platform finds too (image destinations, attribute values) are reported once: ReferencesSearch drops a reference to
 * the same range found twice. Any other element is left to the other searchers.
 */
class ComarkImageReferenceSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        if (!ComarkSettings.getInstance().imagesEnabled) return
        val target = parameters.elementToSearch
        val virtualFile = when (target) {
            is PsiDirectory -> target.virtualFile.takeIf { isInImageDirectory(parameters.project, it) }
            is PsiFile -> target.virtualFile?.takeIf { ImagePaths.isImageFile(it) && isInImageDirectory(parameters.project, it) }
            else -> null
        } ?: return
        val project = parameters.project
        val scope = parameters.effectiveSearchScope
        val names = spellings(virtualFile.name)
        val files = when (scope) {
            is GlobalSearchScope -> candidateFiles(project, scope, names)
            is LocalSearchScope -> scope.scope.mapNotNull { it.containingFile }.distinct()
            else -> emptyList()
        }
        for (file in files) {
            ProgressManager.checkCanceled()
            if (file !is ComarkFile) continue
            for (reference in ComarkImageReferences.all(file)) {
                if (scope is LocalSearchScope && !scope.containsRange(file, reference.absoluteRange)) continue
                val image = reference.resolveFile() ?: continue
                val matches = if (virtualFile.isDirectory) VfsUtilCore.isAncestor(virtualFile, image, true) else image == virtualFile
                if (matches && !consumer.process(reference.tracked())) return
            }
        }
    }

    /** True when [file] lies below one of the image directories of its own Nuxt project. */
    private fun isInImageDirectory(project: Project, file: VirtualFile): Boolean {
        val roots = NuxtImageDirectories.getInstance(project).forFile(file) ?: return false
        return roots.dirs.any { file.path != it.path && NuxtPaths.isUnder(file.path, it.path) }
    }

    /**
     * Comark files in [scope] whose text contains one of [names] (case-insensitively: a cheap filter, the references decide). Markdown
     * files are looked up by the extensions of the Markdown file type.
     */
    private fun candidateFiles(project: Project, scope: GlobalSearchScope, names: List<String>): List<PsiFile> {
        val manager = PsiManager.getInstance(project)
        val fileType = MarkdownLanguage.INSTANCE.associatedFileType ?: return emptyList()
        val extensions = FileTypeManager.getInstance().getAssociations(fileType).filterIsInstance<ExtensionFileNameMatcher>().map { it.extension }
        val files = ArrayList<PsiFile>()
        for (extension in extensions.distinct()) {
            for (file in FilenameIndex.getAllFilesByExt(project, extension, scope)) {
                ProgressManager.checkCanceled()
                val psi = manager.findFile(file) as? ComarkFile ?: continue
                val text = psi.viewProvider.contents
                if (names.any { text.contains(it, ignoreCase = true) }) files += psi
            }
        }
        return files
    }

    /** How a path segment [name] may be written: as is, or percent-encoded. */
    private fun spellings(name: String): List<String> =
        listOf(name, ComarkImageReferences.encode(name, keepSpaces = false), java.net.URLEncoder.encode(name, Charsets.UTF_8)).distinct()
}
