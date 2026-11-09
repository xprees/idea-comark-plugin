package cz.xprees.comark.compat

import com.intellij.codeInsight.editorActions.fillParagraph.ParagraphFillHandler
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownParagraph

/**
 * Fill Paragraph for Comark files.
 *
 * The fill handler lookup falls back from Comark to the Markdown plugin's handler, but that one only accepts files whose language
 * is exactly Markdown, so the action was disabled in Comark files. This handler applies the same rules (fill the Markdown paragraph
 * at the caret, joining its text tokens) to Comark files. The Markdown handler is internal and its hooks are protected, so it can't
 * be delegated to.
 */
class ComarkParagraphFillHandler : ParagraphFillHandler() {
    override fun isAvailableForFile(psiFile: PsiFile?): Boolean = psiFile is ComarkFile

    override fun isAvailableForElement(element: PsiElement?): Boolean =
        element != null && PsiTreeUtil.getParentOfType(element, MarkdownParagraph::class.java, false) != null

    override fun atWhitespaceToken(element: PsiElement?): Boolean =
        super.atWhitespaceToken(element) || element?.elementType == MarkdownTokenTypes.WHITE_SPACE

    override fun isBunchOfElement(element: PsiElement?): Boolean = element != null && element.elementType != MarkdownTokenTypes.TEXT
}
