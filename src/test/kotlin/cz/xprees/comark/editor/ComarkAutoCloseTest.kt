package cz.xprees.comark.editor

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ComarkAutoCloseTest : BasePlatformTestCase() {
    /**
     * Presses Enter and returns the document with the caret marked. The fixture's editor is an injected one when the caret is in an
     * injected fragment (```` ```md ```` fences, props YAML); the host editor shows what the user sees. The indentation of the new
     * line is the formatter's business, so it is left out.
     */
    private fun enter(before: String, fileName: String = "test.mdc"): String {
        myFixture.configureByText(fileName, before)
        myFixture.type('\n')
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
        val text = editor.document.text
        val caret = editor.caretModel.offset
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val indentation = if (text.substring(lineStart, caret).isBlank()) lineStart else caret
        return text.substring(0, indentation) + "<caret>" + text.substring(caret)
    }

    fun testClosesComponentAtEndOfFile() {
        assertEquals("::alert\n<caret>\n::", enter("::alert<caret>"))
    }

    fun testClosesComponentWithAttributesAndContent() {
        assertEquals("::alert[Hi]{type=\"info\"}\n<caret>\n::\n", enter("::alert[Hi]{type=\"info\"}<caret>\n"))
    }

    fun testUsesTheOpenersColonCount() {
        assertEquals("::outer\n:::inner\n<caret>\n:::\n::", enter("::outer\n:::inner<caret>\n::"))
    }

    fun testUsesTheOpenersIndentation() {
        val result = enter("::card\n  :::badge{.x}<caret>\n::")
        val lines = result.lines()
        assertEquals(listOf("::card", "  :::badge{.x}"), lines.take(2))
        assertTrue("caret on the new line: $result", lines[2].trim() == "<caret>")
        assertEquals(listOf("  :::", "::"), lines.drop(3))
    }

    fun testTrailingWhitespaceAfterCaret() {
        val result = enter("::alert<caret>   ")
        assertTrue(result, result.startsWith("::alert\n"))
        assertTrue(result, result.endsWith("\n::"))
    }

    fun testClosesBeforeExistingContentOfAnUnclosedComponent() {
        assertEquals("Intro\n::alert\n<caret>\n::\nnot inside yet\n", enter("Intro\n::alert<caret>\nnot inside yet\n"))
    }

    fun testUnclosedComponentAroundAClosedOne() {
        // `::a` is not closed: the `::` belongs to `::b`
        assertEquals("::a\n<caret>\n::\n::b\nx\n::\n", enter("::a<caret>\n::b\nx\n::\n"))
    }

    fun testClosedComponentGetsNoSecondCloser() {
        assertEquals("::alert\n<caret>\ntext\n::\n", enter("::alert<caret>\ntext\n::\n"))
        assertEquals("::outer\n::inner\n<caret>\n::\n", enter("::outer\n::inner<caret>\n::\n"))
    }

    fun testEnterInsideAComponentBody() {
        assertEquals("::alert\ntext\n<caret>\n::\n", enter("::alert\ntext<caret>\n::\n"))
    }

    fun testCaretInsideTheOpenerLine() {
        assertEquals("::al\n<caret>ert", enter("::al<caret>ert"))
        assertFalse(enter("::alert{type=\"x\"<caret>}").contains("\n::"))
    }

    fun testNotInCodeFence() {
        assertEquals("```\n::alert\n<caret>\n```", enter("```\n::alert<caret>\n```"))
        assertFalse(enter("~~~\n  ::alert<caret>\n~~~").contains("::\n~~~"))
    }

    fun testNotInInjectedMarkdownFence() {
        // the Markdown plugin injects Markdown (here: Comark) into ```md fences
        assertEquals("```md\n::alert\n<caret>\n```", enter("```md\n::alert<caret>\n```"))
        assertEquals("::card\n```mdc\n::alert\n<caret>\n```\n::", enter("::card\n```mdc\n::alert<caret>\n```\n::"))
    }

    fun testNotInPropsYaml() {
        // `::alert` is YAML text here (the `::` after the props closes `::card`)
        assertEquals("::card\n---\n::alert\n<caret>\n---\n::", enter("::card\n---\n::alert<caret>\n---\n::"))
    }

    fun testNotInFrontMatter() {
        assertFalse(enter("---\n::alert<caret>\n---\n").contains("\n::\n"))
    }

    fun testInlineComponentIsNotABlock() {
        assertEquals(":badge\n<caret>", enter(":badge<caret>"))
    }

    fun testPlainMarkdownFileIsUntouched() {
        assertEquals("::alert\n<caret>", enter("::alert<caret>", fileName = "plain.md"))
    }

    fun testUndoRemovesNewLineAndCloser() {
        myFixture.configureByText("test.mdc", "::alert<caret>")
        myFixture.type('\n')
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        assertEquals("::alert", myFixture.editor.document.text)
    }
}
