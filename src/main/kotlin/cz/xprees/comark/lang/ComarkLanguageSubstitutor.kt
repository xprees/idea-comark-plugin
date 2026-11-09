package cz.xprees.comark.lang

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.FileViewProviderFactory
import com.intellij.psi.LanguageSubstitutor
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import cz.xprees.comark.settings.ComarkSettings

/**
 * Turns Markdown files into Comark files: `.mdc` always, `.md` when [ComarkSettings.mode] says so (by default when the file
 * belongs to a package that depends on Nuxt Content, Nuxt MDC or Comark, see [ComarkProjectDetector]).
 */
class ComarkLanguageSubstitutor : LanguageSubstitutor() {
    override fun getLanguage(file: VirtualFile, project: Project): Language? =
        if (isComarkFile(file, project)) ComarkLanguage else null

    companion object {
        const val MDC_EXTENSION = "mdc"

        fun isComarkFile(file: VirtualFile, project: Project): Boolean {
            if (project.isDisposed) return false
            if (file.extension.equals(MDC_EXTENSION, ignoreCase = true)) return true
            return when (ComarkSettings.getInstance().mode) {
                ComarkSettings.Mode.ALWAYS -> true
                ComarkSettings.Mode.NEVER -> false
                ComarkSettings.Mode.AUTO -> ComarkProjectDetector.getInstance(project).isComarkContext(file)
            }
        }
    }
}

/** View provider of Comark files: a plain single-root provider whose base language is Comark. */
class ComarkFileViewProviderFactory : FileViewProviderFactory {
    override fun createFileViewProvider(file: VirtualFile, language: Language?, manager: PsiManager, eventSystemEnabled: Boolean): FileViewProvider =
        ComarkFileViewProvider(manager, file, eventSystemEnabled)
}

class ComarkFileViewProvider(manager: PsiManager, file: VirtualFile, eventSystemEnabled: Boolean) :
    SingleRootFileViewProvider(manager, file, eventSystemEnabled, ComarkLanguage) {
    override fun createCopy(copy: VirtualFile): SingleRootFileViewProvider = ComarkFileViewProvider(manager, copy, false)
}
