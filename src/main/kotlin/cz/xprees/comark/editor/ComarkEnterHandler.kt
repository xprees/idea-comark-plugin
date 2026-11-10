package cz.xprees.comark.editor

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import cz.xprees.comark.parser.ComarkSyntax
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkComponentHeader
import cz.xprees.comark.psi.ComarkFile

/**
 * Closes block components on Enter: pressing Enter at the end of an opener line (`::name...`, `:::name...`) of a component that has no
 * closing marker inserts one with the opener's indentation and colon count below the new line, so the caret ends up inside the
 * component:
 * ```
 * ::alert{type="info"}|      ->      ::alert{type="info"}
 *                                    |
 *                                    ::
 * ```
 * The closing marker is added below the opener line before the platform handles Enter, so the new line gets its usual indentation
 * and Markdown's own Enter handling stays untouched. Whether the component is closed is the parser's call (it already knows
 * about nesting, fences and colon counts); a line that merely looks like an opener (in a code fence, in props YAML) is not a
 * component in the PSI and is left alone. Neither is the content of a ```` ```md ```` fence, although the Markdown plugin injects it
 * as Markdown (Comark in a Comark file), as it's example code.
 */
class ComarkEnterHandler : EnterHandlerDelegate {
    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): Result {
        if (file !is ComarkFile || editor.isViewer || !editor.document.isWritable) return Result.Continue
        // A ```md / ```mdc fence holds an injected Markdown (Comark) fragment: example code, which is left as typed.
        if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return Result.Continue
        val closer = closerToInsert(file, editor.document, caretOffset.get()) ?: return Result.Continue
        editor.document.insertString(closer.offset, closer.text)
        return Result.Continue
    }

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): Result = Result.Continue

    /** Text to insert at [offset] (the end of the opener line) to close the component. */
    internal class Closer(val offset: Int, val text: String)

    internal companion object {
        /** The closing marker to add when Enter is pressed at [caret], or null when the caret is not at the end of an unclosed opener. */
        fun closerToInsert(file: ComarkFile, document: Document, caret: Int): Closer? {
            if (caret < 0 || caret > document.textLength) return null
            val text = document.charsSequence
            val line = document.getLineNumber(caret)
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            // only at the end of the line, and only on something that looks like an opener (cheap checks before touching the PSI)
            if (!ComarkSyntax.isBlank(text, caret, lineEnd)) return null
            val contentStart = ComarkSyntax.skipSpaces(text, lineStart, lineEnd)
            val opener = ComarkSyntax.parseOpener(text, contentStart, lineEnd) ?: return null
            if (caret < opener.nameEnd) return null

            val documentManager = PsiDocumentManager.getInstance(file.project)
            if (!documentManager.isCommitted(document)) documentManager.commitDocument(document)
            val marker = file.findElementAt(contentStart) ?: return null
            if (marker.elementType != ComarkTypes.COMPONENT_MARKER) return null
            val component = (marker.parent as? ComarkComponentHeader)?.parent as? ComarkBlockComponent ?: return null
            if (component.closeMarker != null) return null

            val indent = text.subSequence(lineStart, contentStart)
            return Closer(lineEnd, "\n" + indent + ":".repeat(opener.markerCount))
        }
    }
}
