package cz.xprees.comark.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.DocumentUtil
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings

/**
 * Opens the completion popup where component completion is the obvious next step but no letter has been typed yet: after the
 * `::` of a block component, after the `{` that starts a component's attributes, and after a space between attributes. (Letters
 * open the popup anyway; `#` deliberately does not, since it usually starts a heading.)
 */
class ComarkCompletionAutoPopupHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ':' && charTyped != '{' && charTyped != ' ') return Result.CONTINUE
        if (file !is ComarkFile || !ComarkSettings.getInstance().componentCompletion) return Result.CONTINUE
        val document = editor.document
        val offset = editor.caretModel.offset
        val lineStart = DocumentUtil.getLineStartOffset(offset, document)
        val before = document.immutableCharSequence.subSequence(lineStart, offset).toString() + charTyped
        if (shouldPopup(before)) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }

    companion object {
        private val BLOCK_MARKER = Regex("""^[ \t]*:{2,}$""")

        /** A block opener or inline component name (with optional `[content]`) right before an attribute group's `{`. */
        private val COMPONENT_ATTRIBUTES = Regex("""(?:^[ \t]*:{2,}[ \t]*|(?:^|[ \t*_\[\]})>]):)[A-Za-z$][\w$.-]*(?:\[[^\]\n]*])?\{([^}]*)$""")

        /** Whether the line text up to and including the typed character asks for the completion popup. */
        internal fun shouldPopup(before: String): Boolean {
            if (BLOCK_MARKER.matches(before)) return true
            val attributes = COMPONENT_ATTRIBUTES.find(before) ?: return false
            val inside = attributes.groupValues[1]
            if (inside.count { it == '"' } % 2 != 0 || inside.count { it == '\'' } % 2 != 0) return false // typing a value
            if (inside.isEmpty() || inside == ":" || inside.endsWith(" :")) return true // first attribute, or a `:binding`
            return inside.last() == ' ' && inside.trimEnd().lastOrNull() != '='
        }
    }
}
