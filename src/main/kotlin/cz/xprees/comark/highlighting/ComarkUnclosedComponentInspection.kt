package cz.xprees.comark.highlighting

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.parentOfType
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.psi.ComarkBlockComponent

/**
 * Reports block components without a closing marker.
 *
 * Comark auto-closes such a component at the end of its parent (or of the document), so the document still renders, but almost
 * always not as intended: everything up to the parent's end silently becomes the component's content. Hence a weak warning,
 * with a fix that inserts the closing marker right after the component's last content line.
 */
class ComarkUnclosedComponentInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element !is ComarkBlockComponent || element.closeMarker != null) return
            val header = element.header ?: return
            val marker = header.markerElement ?: return
            val end = (element.nameElement ?: marker).textRange.endOffset
            val range = TextRange(marker.textRange.startOffset, end).shiftLeft(header.textRange.startOffset)
            val closing = ":".repeat(element.markerCount)
            holder.registerProblem(
                header,
                range,
                ComarkBundle.message("highlighting.inspection.unclosed.component.message", element.componentName ?: "", closing),
                InsertClosingMarkerFix(closing),
            )
        }
    }

    /** Inserts the closing marker on its own line after the component's content, indented like the opener. */
    class InsertClosingMarkerFix(private val closing: String) : LocalQuickFix {
        override fun getName(): String = ComarkBundle.message("highlighting.inspection.unclosed.component.fix", closing)

        override fun getFamilyName(): String = ComarkBundle.message("highlighting.inspection.unclosed.component.fix.family")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val component = descriptor.psiElement?.parentOfType<ComarkBlockComponent>(withSelf = true) ?: return
            if (component.closeMarker != null) return
            val document = component.containingFile.viewProvider.document ?: return
            val (offset, text) = closingInsertion(document.charsSequence, component.textRange, component.markerCount)
            document.insertString(offset, text)
        }

        companion object {
            /**
             * Where and what to insert to close the component spanning [range] of [text]: a line break, the opener's indentation and
             * [markerCount] colons, placed at the end of the component's last non-blank line (so blank lines and the parent's
             * closing marker that follow stay outside it).
             */
            fun closingInsertion(text: CharSequence, range: TextRange, markerCount: Int): Pair<Int, String> {
                var end = range.endOffset
                while (end > range.startOffset && text[end - 1].isWhitespace()) end--
                var lineEnd = end
                while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
                var lineStart = range.startOffset
                while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
                val indent = text.subSequence(lineStart, range.startOffset).takeWhile { it == ' ' || it == '\t' }
                return lineEnd to "\n$indent${":".repeat(markerCount)}"
            }
        }
    }
}
