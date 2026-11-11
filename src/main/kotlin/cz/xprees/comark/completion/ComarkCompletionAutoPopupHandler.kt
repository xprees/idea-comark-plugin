package cz.xprees.comark.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.DocumentUtil
import cz.xprees.comark.images.ImageKeys
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings

/**
 * Opens the completion popup where component completion is the obvious next step but no letter has been typed yet: after the
 * `::` of a block component, after the `{` that starts a component's attributes, and after a space between attributes. (Letters
 * open the popup anyway; `#` deliberately does not, since it usually starts a heading.) A `/` that starts an image path opens it
 * too: in `![alt](/`, `[label]: /`, an image-like attribute (`src="/`) or after an image-like YAML key (`cover: /`), also in YAML
 * injected into the document.
 */
class ComarkCompletionAutoPopupHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ':' && charTyped != '{' && charTyped != ' ' && charTyped != '/') return Result.CONTINUE
        val settings = ComarkSettings.getInstance()
        val popup = if (charTyped == '/') {
            settings.imagesEnabled && InjectedLanguageManager.getInstance(project).getTopLevelFile(file) is ComarkFile &&
                shouldPopupImage(lineBefore(editor, charTyped))
        } else {
            file is ComarkFile && settings.componentCompletion && shouldPopup(lineBefore(editor, charTyped))
        }
        if (popup) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }

    /** The current line up to the caret, plus the character being typed. */
    private fun lineBefore(editor: Editor, charTyped: Char): String {
        val document = editor.document
        val offset = editor.caretModel.offset
        val lineStart = DocumentUtil.getLineStartOffset(offset, document)
        return document.immutableCharSequence.subSequence(lineStart, offset).toString() + charTyped
    }

    companion object {
        private val BLOCK_MARKER = Regex("""^[ \t]*:{2,}$""")

        /** A block opener or inline component name (with optional `[content]`) right before an attribute group's `{`. */
        private val COMPONENT_ATTRIBUTES = Regex("""(?:^[ \t]*:{2,}[ \t]*|(?:^|[ \t*_\[\]})>]):)[A-Za-z$][\w$.-]*(?:\[[^\]\n]*])?\{([^}]*)$""")

        private val IMAGE_DESTINATION = Regex("""(?:!\[[^\]]*]\([ \t]*<?|^[ \t]{0,3}\[[^\]]+]:[ \t]*<?)/$""")
        private val IMAGE_ATTRIBUTE = Regex("""[{\s]([A-Za-z_$][\w$.:-]*)=["']?/$""")
        private val IMAGE_YAML_KEY = Regex("""^[ \t]*(?:-[ \t]+)*("[^"]*"|'[^']*'|[^\s"'#\[{][^#]*?)[ \t]*:[ \t]+["']?/$""")

        /** Whether the line text up to and including a typed `/` starts an image path that image completion would complete. */
        internal fun shouldPopupImage(before: String): Boolean {
            if (IMAGE_DESTINATION.containsMatchIn(before)) return true
            IMAGE_ATTRIBUTE.find(before)?.let { if (ImageKeys.isImageProp(it.groupValues[1])) return true }
            val key = IMAGE_YAML_KEY.find(before)?.groupValues?.get(1)?.trim('"', '\'') ?: return false
            // nested `src:` / `url:` count when their parent is image-like, which the line alone cannot tell; the contributor decides
            return ImageKeys.isImageKeyPath(listOf(key)) || key.lowercase() in setOf("src", "url", "path")
        }

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
