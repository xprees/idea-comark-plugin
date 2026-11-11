package cz.xprees.comark.highlighting

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.elementType
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.parser.ComarkSyntax
import cz.xprees.comark.parser.ComarkTypes

/**
 * Reports text after a block component opener's `[content]` / `{attrs}` (`::alert{.x} trailing`, `::alert{.x}[content]`).
 *
 * Comark parses the opener as name, optional content, optional attributes and silently drops whatever follows, so the text is
 * lost from the rendered page. The fix removes it; when it is a `[content]` written after the attributes, the fix moves it in
 * front of them instead, which is what the author meant.
 */
class ComarkIgnoredOpenerTextInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element.elementType != ComarkTypes.BAD_CHARACTER) return
            val fixes = buildList {
                if (canMoveContent(element)) add(MoveContentBeforeAttributesFix())
                add(RemoveIgnoredTextFix())
            }
            holder.registerProblem(element, ComarkBundle.message("highlighting.inspection.ignored.opener.text.message"), *fixes.toTypedArray())
        }
    }

    /** Deletes the ignored text and the blanks before it. */
    class RemoveIgnoredTextFix : LocalQuickFix {
        override fun getFamilyName(): String = ComarkBundle.message("highlighting.inspection.ignored.opener.text.remove")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            val document = element.containingFile.viewProvider.document ?: return
            val range = element.textRange
            var start = range.startOffset
            val chars = document.charsSequence
            while (start > 0 && (chars[start - 1] == ' ' || chars[start - 1] == '\t')) start--
            document.deleteString(start, range.endOffset)
        }
    }

    /** Turns `::name{attrs} [content]` into `::name[content]{attrs}`. */
    class MoveContentBeforeAttributesFix : LocalQuickFix {
        override fun getFamilyName(): String = ComarkBundle.message("highlighting.inspection.ignored.opener.text.move")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            if (!canMoveContent(element)) return
            val attributes = element.prevSiblingOfType(ComarkTypes.ATTRIBUTES) ?: return
            val document = element.containingFile.viewProvider.document ?: return
            val content = element.text
            val insertAt = attributes.textRange.startOffset
            val end = element.textRange.endOffset
            val chars = document.charsSequence
            var start = element.textRange.startOffset
            while (start > attributes.textRange.endOffset && (chars[start - 1] == ' ' || chars[start - 1] == '\t')) start--
            // delete the trailing content (and the blanks before it) first: the insertion point lies before it and doesn't move
            document.deleteString(start, end)
            document.insertString(insertAt, content)
        }
    }

    companion object {
        /** True when [element] is exactly a `[...]` after the attributes of an opener that has no content of its own. */
        private fun canMoveContent(element: PsiElement): Boolean {
            val text = element.text
            if (text.length < 2 || text[0] != '[' || text[text.length - 1] != ']') return false
            if (ComarkSyntax.findClosingBracket(text, 0) != text.length - 1) return false
            val header = element.parent ?: return false
            if (header.node.findChildByType(ComarkTypes.CONTENT_LBRACKET) != null) return false
            return element.prevSiblingOfType(ComarkTypes.ATTRIBUTES) != null
        }

        private fun PsiElement.prevSiblingOfType(type: IElementType): PsiElement? {
            var sibling = prevSibling
            while (sibling != null) {
                if (sibling.elementType == type) return sibling
                sibling = sibling.prevSibling
            }
            return null
        }
    }
}
