package cz.xprees.comark.lang

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.FileViewProviderFactory
import com.intellij.psi.LanguageSubstitutor
import com.intellij.psi.PsiManager
import com.intellij.psi.MultiplePsiFilesPerDocumentFileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.templateLanguages.TemplateLanguageFileViewProvider
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.MarkdownElementTypes

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

/**
 * Creates the view provider of Comark files: a [ComarkFileViewProvider] (Comark + HTML), or a plain single-root provider in an IDE
 * without HTML support.
 */
class ComarkFileViewProviderFactory : FileViewProviderFactory {
    override fun createFileViewProvider(file: VirtualFile, language: Language?, manager: PsiManager, eventSystemEnabled: Boolean): FileViewProvider {
        val html = htmlLanguage()
        return if (html != null) ComarkFileViewProvider(manager, file, eventSystemEnabled, html) else SingleRootComarkFileViewProvider(manager, file, eventSystemEnabled)
    }

    private companion object {
        /** Looked up by id: HTML lives in the platform's XML modules, which the plugin doesn't depend on. */
        fun htmlLanguage(): Language? = Language.findLanguageByID("HTML")
    }
}

/**
 * View provider of Comark files, built like the Markdown plugin's own `DefaultMarkdownFileViewProvider`: the base language is
 * Comark and HTML is the template data language. The HTML tree is made of the Markdown `HTML_BLOCK_CONTENT` tokens of the Comark
 * lexer (raw HTML blocks such as `<div>` ... `</div>`); everything else, Comark component lines included, is an outer element of
 * it. That gives HTML blocks in Comark files the HTML highlighting, completion, inspections and TODOs (in `<!-- -->` comments)
 * that they have in Markdown files.
 *
 * The Markdown plugin's `MARKDOWN_TEMPLATE_DATA` element type is reused on purpose: its lexer comes from the view provider's base
 * language (so it is the Comark lexer here) and Markdown code that looks for its outer elements keeps recognising them.
 */
class ComarkFileViewProvider(
    manager: PsiManager,
    file: VirtualFile,
    eventSystemEnabled: Boolean,
    private val templateLanguage: Language,
) : MultiplePsiFilesPerDocumentFileViewProvider(manager, file, eventSystemEnabled), TemplateLanguageFileViewProvider {
    private val languages = setOf(ComarkLanguage, templateLanguage)

    override fun getBaseLanguage(): Language = ComarkLanguage

    override fun getLanguages(): Set<Language> = languages

    override fun getTemplateDataLanguage(): Language = templateLanguage

    override fun createFile(lang: Language): PsiFile? {
        val definition = LanguageParserDefinitions.INSTANCE.forLanguage(lang) ?: return null
        return when (lang) {
            ComarkLanguage -> definition.createFile(this)
            templateLanguage -> definition.createFile(this).also { (it as? PsiFileImpl)?.setContentElementType(MarkdownElementTypes.MARKDOWN_TEMPLATE_DATA) }
            else -> null
        }
    }

    override fun cloneInner(fileCopy: VirtualFile): MultiplePsiFilesPerDocumentFileViewProvider =
        ComarkFileViewProvider(manager, fileCopy, false, templateLanguage)
}

/** Fallback view provider for IDEs without HTML: a plain single-root provider whose base language is Comark. */
class SingleRootComarkFileViewProvider(manager: PsiManager, file: VirtualFile, eventSystemEnabled: Boolean) :
    SingleRootFileViewProvider(manager, file, eventSystemEnabled, ComarkLanguage) {
    override fun createCopy(copy: VirtualFile): SingleRootFileViewProvider = SingleRootComarkFileViewProvider(manager, copy, false)
}
