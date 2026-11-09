package cz.xprees.comark.format

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile

/**
 * Enter on a blank line of a Comark component body or props block.
 *
 * The Markdown plugin's Enter handler deliberately inserts a bare line break when the caret line is blank, before any line
 * indent provider is asked (in Markdown, indentation of a new paragraph would turn it into a code block or list continuation).
 * Comark component content is indented by the formatter, so there the new line should start at the component's content
 * indentation like after any other line. This delegate runs before Markdown's handler (`order="before MarkdownEnterHandler"`)
 * and handles exactly that case; in every other case, including blank lines in lists and at the top level, it steps aside.
 */
class ComarkEnterHandlerDelegate : EnterHandlerDelegate {
    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): Result {
        if (!ComarkEnterIndent.isEnabledFor(file) || !CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER) return Result.Continue
        if (editor.caretModel.caretCount > 1 || editor.selectionModel.hasSelection()) return Result.Continue
        val document = editor.document
        val offset = caretOffset.get()
        if (offset !in 0..document.textLength) return Result.Continue
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence
        for (i in lineStart until lineEnd) if (chars[i] != ' ' && chars[i] != '\t') return Result.Continue

        // The new line will be like this blank one, so this line's indentation by the Comark rules is the new line's
        val indent = ComarkEnterIndent.indentFor(file, document, line) ?: return Result.Continue
        // Whitespace after the caret would end up in front of the new line's indentation
        document.deleteString(offset, lineEnd)
        editor.caretModel.moveToOffset(offset)
        EditorModificationUtil.insertStringAtCaret(editor, "\n" + indent)
        caretOffset.set(editor.caretModel.offset)
        return Result.Stop
    }
}
