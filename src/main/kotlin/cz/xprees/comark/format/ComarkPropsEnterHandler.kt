package cz.xprees.comark.format

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.injected.editor.EditorWindow
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import cz.xprees.comark.psi.ComarkPropsContent

/**
 * Enter inside a component's props block (`---` YAML right after an opener) indents the new line by the formatter's rules.
 *
 * With the YAML plugin installed, props are injected YAML, and the Enter delegates then see the injected YAML file and editor:
 * the YAML plugin indents relative to the injected fragment - which drops a nested component's indentation - and its
 * `YAMLEnterAtIndentHandler` (registered `order="first"`, with no id to order against) ends the delegate chain on a blank line
 * before any Comark delegate runs. Handling Enter one level up, for props blocks only, keeps their indentation consistent with
 * Reformat Code whether or not YAML is injected. Everywhere else, and whenever a lookup, a selection or several carets are active,
 * Enter goes to the original handler untouched.
 */
class ComarkPropsEnterHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        original.isEnabled(editor, caret, dataContext)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val host = (editor as? EditorWindow)?.delegate ?: editor
        val file = propsFile(host)
        if (file == null) {
            original.execute(editor, caret, dataContext)
            return
        }
        ApplicationManager.getApplication().runWriteAction { enterInProps(file, host) }
        host.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
    }

    /** The Comark file when Enter at the host editor's caret should be handled here, otherwise null. */
    private fun propsFile(editor: Editor): PsiFile? {
        val project = editor.project ?: return null
        if (editor.isViewer || editor.caretModel.caretCount > 1 || editor.selectionModel.hasSelection()) return null
        if (!CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER || LookupManager.getActiveLookup(editor) != null) return null
        val documentManager = PsiDocumentManager.getInstance(project)
        val file = documentManager.getPsiFile(editor.document) ?: return null
        if (!ComarkEnterIndent.isEnabledFor(file) || file.textLength == 0) return null
        documentManager.commitDocument(editor.document)
        val offset = editor.caretModel.offset
        val element = PsiUtilCore.getElementAtOffset(file, minOf(offset, file.textLength - 1))
        return file.takeIf { PsiTreeUtil.getParentOfType(element, ComarkPropsContent::class.java, false) != null }
    }

    private fun enterInProps(file: PsiFile, editor: Editor) {
        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence
        val blankLine = (lineStart until lineEnd).all { chars[it] == ' ' || chars[it] == '\t' }
        // On a blank line the new line is like this one, so this line's indentation by the Comark rules is the new line's
        val blankLineIndent = if (blankLine) ComarkEnterIndent.indentFor(file, document, line) else null
        // Whitespace between the caret and the end of the line would end up in front of the new line's indentation
        var tail = offset
        while (tail < lineEnd && (chars[tail] == ' ' || chars[tail] == '\t')) tail++
        if (tail == lineEnd) document.deleteString(offset, lineEnd)
        document.insertString(offset, "\n")
        val indent = (if (blankLine) blankLineIndent else ComarkEnterIndent.indentFor(file, document, line + 1)) ?: ""
        document.insertString(offset + 1, indent)
        editor.caretModel.moveToOffset(offset + 1 + indent.length)
    }
}
