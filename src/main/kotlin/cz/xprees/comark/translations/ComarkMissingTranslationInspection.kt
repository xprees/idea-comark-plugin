package cz.xprees.comark.translations

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.lang.ComarkLanguage
import java.util.Locale

/**
 * *Missing translation*: a localized Comark page ([ComarkTranslations]) that has no counterpart in some locale of its group. A weak
 * warning on the first line, with a *Create … translation* fix per missing locale.
 */
class ComarkMissingTranslationInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        // one report per file: not again for the HTML root of the same view provider
        if (file.language != ComarkLanguage) return null
        val virtualFile = file.originalFile.virtualFile ?: return null
        val translations = ComarkTranslations.getInstance(file.project).translationsOf(virtualFile) ?: return null
        val missing = translations.missing
        if (missing.isEmpty() || file.textLength == 0) return null
        val text = file.viewProvider.contents
        val end = text.indexOf('\n').let { if (it < 0) text.length else it }
        if (end == 0) return null
        val labels = missing.joinToString(", ") { it.uppercase(Locale.ROOT) }
        val fixes = missing.map { CreateTranslationFix(it) }.toTypedArray<LocalQuickFix>()
        return arrayOf(
            manager.createProblemDescriptor(
                file,
                TextRange(0, end),
                ComarkBundle.message("translations.inspection.message", labels),
                ProblemHighlightType.WEAK_WARNING,
                isOnTheFly,
                *fixes,
            ),
        )
    }

    /** Creates the page's translation in [locale] (asks for its path first). */
    class CreateTranslationFix(private val locale: String) : LocalQuickFix {
        override fun getFamilyName(): String = ComarkBundle.message("translations.inspection.fix.family")

        override fun getName(): String = ComarkBundle.message("translations.create", locale.uppercase(Locale.ROOT))

        override fun startInWriteAction(): Boolean = false

        // creates another file after a dialog: nothing to show in the preview of this one
        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement?.containingFile?.originalFile?.virtualFile ?: return
            val translations = ComarkTranslations.getInstance(project).translationsOf(file) ?: return
            ApplicationManager.getApplication().invokeLater({ ComarkTranslationCreator.promptAndCreate(project, translations, locale) }, project.disposed)
        }
    }
}
