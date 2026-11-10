package cz.xprees.comark.editor

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownElementTypes

/**
 * Live template context of Comark documents (the `Comark` group: `::`, `::-`, `block`, `datablock`, `span`, ported from the VS Code
 * extension's snippets).
 *
 * Only Comark files qualify, and only where Markdown text is written: not in code (fences, indented code, inline code), front matter,
 * component props YAML or `{attributes}`. Inside injected YAML the template file is the injected YAML file, which is not a Comark
 * file either.
 */
class ComarkTemplateContextType : TemplateContextType(ComarkBundle.message("live.template.context.comark")) {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? ComarkFile ?: return false
        // the content of a ```md fence is an injected Markdown (Comark) file, but still a code fence of the document
        if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return false
        val offset = templateActionContext.startOffset
        // at a line end the element at the offset is the line break, which may lie outside the block the text before it is in
        val atOffset = file.findElementAt(offset)
        val element = atOffset?.takeUnless { it.isBlankLeaf() } ?: (if (offset > 0) file.findElementAt(offset - 1) else null) ?: atOffset
        return element == null || !isInsideNonMarkdown(element)
    }

    private fun isInsideNonMarkdown(element: PsiElement): Boolean =
        PsiTreeUtil.findFirstParent(element, false) { it is ComarkFile || it.elementType in EXCLUDED }.let { it != null && it !is ComarkFile }

    private companion object {
        val EXCLUDED = TokenSet.create(
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.CODE_SPAN,
            MarkdownElementTypes.FRONT_MATTER_HEADER,
            ComarkTypes.PROPS_BLOCK,
            ComarkTypes.ATTRIBUTES,
        )
    }
}
