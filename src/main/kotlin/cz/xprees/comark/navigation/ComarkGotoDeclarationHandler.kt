package cz.xprees.comark.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import cz.xprees.comark.meta.ComarkComponent
import cz.xprees.comark.meta.ComarkComponentService
import cz.xprees.comark.meta.ComponentUsage
import cz.xprees.comark.meta.ComponentUsages
import cz.xprees.comark.psi.ComarkFile

/**
 * Go to Declaration (Ctrl+Click) for Comark components: from a block or inline component name to its `.vue` file, from an
 * attribute or props-block key to the prop's declaration, and from a `#slot` to the `<slot name>` (or `defineSlots` member)
 * that renders it.
 *
 * A declaration handler rather than references for props and slots, because inline components are not PSI elements of their
 * own: they are text inside Markdown paragraphs, which the handler can look at for any offset. Prop and slot positions come
 * from parsing the target file at navigation time, so they are right even while the `.vue` file has unsaved edits. Component
 * names also have references ([ComarkComponentReference]), which Find Usages and Rename use.
 */
class ComarkGotoDeclarationHandler : GotoDeclarationHandler, DumbAware {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        if (file !is ComarkFile && ComponentUsages.propsHost(file) == null) return null
        val usage = ComponentUsages.find(file, offset) ?: return null
        val project = file.project
        val component = ComarkComponentService.getInstance(project).index().find(usage.componentName, ComponentUsages.contextFile(file)) ?: return null
        return declaration(project, component, usage)?.let { arrayOf(it) }
    }

    companion object {
        /** Where [usage] of [component] is declared: the component's file, or the prop / slot inside it (the file as a fallback). */
        fun declaration(project: Project, component: ComarkComponent, usage: ComponentUsage): PsiElement? {
            val virtualFile = component.file?.takeIf { it.isValid } ?: return null
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
            if (usage is ComponentUsage.Name) return psiFile
            val info = ComarkComponentService.getInstance(project).sfcInfo(virtualFile) ?: return psiFile
            val text = psiFile.viewProvider.contents
            val target = when (usage) {
                is ComponentUsage.Prop -> info.findProp(usage.propName)?.let { declared ->
                    val symbol = ComarkSymbol(component, prop = component.findProp(usage.propName) ?: declared)
                    // props of an imported interface are declared in that TypeScript file
                    val declaringFile = declared.file?.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) }
                    if (declaringFile != null) {
                        source(declaringFile, declaringFile.viewProvider.contents, declared.offset, declared.name.length, symbol)
                    } else {
                        source(psiFile, text, declared.offset, declared.name.length, symbol)
                    }
                }

                is ComponentUsage.Slot -> info.findSlot(usage.slotName)?.let { declared ->
                    // `<slot>` without a name points at the tag name, named slots at the name
                    val length = if (text.startsWith("slot", declared.offset)) 4 else declared.name.length
                    source(psiFile, text, declared.offset, length, ComarkSymbol(component, slot = component.findSlot(usage.slotName) ?: declared))
                }

                is ComponentUsage.Name -> null
            }
            return target ?: psiFile
        }

        private fun source(file: PsiFile, text: CharSequence, offset: Int, length: Int, symbol: ComarkSymbol): PsiElement? =
            if (offset >= 0 && offset + length <= text.length) ComarkSourceElement(file, offset, length, symbol) else null
    }
}
