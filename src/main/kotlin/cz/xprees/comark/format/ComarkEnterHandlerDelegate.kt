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
 * Enter in the two places of a Comark file where the Markdown plugin's Enter handler would not let the Comark indentation apply.
 * It runs before the Markdown plugin's handlers (`order="first"`, as its list continuation delegate has no id to order against)
 * and handles exactly these cases; in every other case, including blank lines in lists and at the top level, it steps aside.
 *
 * - A blank line of a component body or props block. The Markdown handler deliberately inserts a bare line break when the caret
 *   line is blank, before any line indent provider is asked (in Markdown, indentation of a new paragraph would turn it into a
 *   code block or list continuation). Comark component content is indented by the formatter, so there the new line should
 *   start at the component's content indentation like after any other line.
 * - Any line of a component inside a list item ([ComarkEnterIndent.isListItemComponentContent]). The Markdown handler sees the
 *   list item around the component and starts a new list item (`- `) inside the component body.
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
        val blank = (lineStart until lineEnd).all { chars[it] == ' ' || chars[it] == '\t' }

        if (blank) {
            // The new line will be like this blank one, so this line's indentation by the Comark rules is the new line's
            val indent = ComarkEnterIndent.indentFor(file, document, line) ?: return Result.Continue
            // Whitespace after the caret would end up in front of the new line's indentation
            document.deleteString(offset, lineEnd)
            editor.caretModel.moveToOffset(offset)
            EditorModificationUtil.insertStringAtCaret(editor, "\n" + indent)
            caretOffset.set(editor.caretModel.offset)
            return Result.Stop
        }

        if (!ComarkEnterIndent.isListItemComponentContent(file, document, line)) return Result.Continue
        // The text after the caret moves to the new line, without the whitespace in front of it
        var restStart = offset
        while (restStart < lineEnd && (chars[restStart] == ' ' || chars[restStart] == '\t')) restStart++
        document.deleteString(offset, restStart)
        document.insertString(offset, "\n")
        val indent = ComarkEnterIndent.indentFor(file, document, line + 1).orEmpty()
        document.insertString(offset + 1, indent)
        editor.caretModel.moveToOffset(offset + 1 + indent.length)
        caretOffset.set(editor.caretModel.offset)
        return Result.Stop
    }
}
