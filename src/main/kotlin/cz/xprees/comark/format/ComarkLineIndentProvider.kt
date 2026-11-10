package cz.xprees.comark.format

import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import cz.xprees.comark.lang.ComarkLanguage
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/**
 * Indents the line Enter creates in a Comark file (see [ComarkEnterIndent] for the rules), and otherwise gives exactly the
 * indentation a Markdown file would get.
 *
 * The platform asks only the first line indent provider that accepts the language at the caret. In a Comark file that language
 * is always Comark - the Markdown elements inside it are narrowed to the file's dialect - so the Markdown plugin's own provider
 * (which accepts only `MarkdownLanguage`, and is what keeps list continuation from being indented twice) is never asked there.
 * This provider therefore takes Comark and, whenever the Comark rules do not apply, asks the provider a Markdown file would
 * get, passing it the Markdown language.
 */
class ComarkLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language?.isKindOf(ComarkLanguage) == true

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        val document = editor.document
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document)
        if (file != null && ComarkEnterIndent.isEnabledFor(file) && offset in 0..document.textLength) {
            ComarkEnterIndent.indentFor(file, document, document.getLineNumber(offset))?.let { return it }
        }
        return markdownProvider()?.getLineIndent(project, editor, MarkdownLanguage.INSTANCE, offset)
    }

    /** The provider the platform would ask for a Markdown file (the Markdown plugin's, or the formatter based fallback). */
    private fun markdownProvider(): LineIndentProvider? =
        EP_NAME.extensionList.firstOrNull { it !is ComarkLineIndentProvider && it.isSuitableFor(MarkdownLanguage.INSTANCE) }

    private companion object {
        /** `LineIndentProviderEP` keeps its extension point private and offers no way to look past the first match. */
        val EP_NAME = ExtensionPointName.create<LineIndentProvider>("com.intellij.lineIndentProvider")
    }
}
