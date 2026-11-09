package cz.xprees.comark.meta

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.elementType
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

/**
 * Flags block and inline components that no metadata source knows. Off by default and only a weak warning: a project can
 * render components the plugin cannot see (registered by a module, resolved at runtime), so it only speaks up when at least one
 * component was discovered, which means discovery works for this project at all.
 *
 * Registered for Markdown so its visitor also sees the Markdown paragraphs of Comark files, where inline components live.
 * Markdown injected into code fences (examples) is not checked.
 */
class ComarkUnknownComponentInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? ComarkFile ?: return PsiElementVisitor.EMPTY_VISITOR
        // Markdown examples in code fences of a Comark file are Comark too, but their components are illustrations
        if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return PsiElementVisitor.EMPTY_VISITOR
        val index = ComarkComponentService.getInstance(holder.project).index()
        if (index.isEmpty) return PsiElementVisitor.EMPTY_VISITOR
        val context = file.originalFile.virtualFile
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val type = element.elementType
                when {
                    type == ComarkTypes.COMPONENT_NAME -> check(element, element.text, TextRange(0, element.textLength))
                    type == MarkdownElementTypes.PARAGRAPH || type == MarkdownElementTypes.TABLE_CELL || type == ComarkTypes.INLINE_CONTENT ||
                        element is MarkdownHeader -> checkInline(element)
                }
            }

            private fun checkInline(element: PsiElement) {
                val text = file.viewProvider.contents
                val range = element.textRange
                for (component in InlineComponentSyntax.findAll(text, range.startOffset, range.endOffset)) {
                    // `:smile:` is an emoji shortcode when comark's emoji plugin is on (its rule runs before components)
                    if (component.contentOpen < 0 && component.attributes == null && text.getOrNull(component.nameEnd) == ':') continue
                    val leaf = file.findElementAt(component.nameStart) ?: continue
                    if (ComponentUsages.isInsideNonContent(leaf)) continue
                    check(element, component.name(text), TextRange(component.nameStart, component.nameEnd).shiftLeft(range.startOffset))
                }
            }

            private fun check(element: PsiElement, name: String, rangeInElement: TextRange) {
                if (index.find(name, context) != null) return
                holder.registerProblem(element, rangeInElement, ComarkBundle.message("inspection.unknown.component.message", name))
            }
        }
    }
}
