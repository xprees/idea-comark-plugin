package cz.xprees.comark.editor

import com.intellij.codeInsight.daemon.impl.IdentifierHighlighterPassFactory
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.parser.ComarkLexer
import cz.xprees.comark.parser.ComarkTypes
import org.intellij.plugins.markdown.lang.MarkdownFileType

class ComarkPairMatchingTest : BasePlatformTestCase() {
    private fun configure(text: String) = myFixture.configureByText("test.mdc", text)

    /** Ranges the platform highlights as structural markers for the element at [offset]. */
    private fun markersAt(offset: Int): List<String> {
        val file = myFixture.file
        val ranges = CodeBlockSupportHandler.findMarkersRanges(file.findElementAt(offset))
        return ranges.map { it.substring(file.text) + "@" + it.startOffset }
    }

    private fun usagesAtCaret(): List<TextRange>? {
        val handler = HighlightUsagesHandler.createCustomHandler<PsiElement>(myFixture.editor, myFixture.file) ?: return null
        handler.computeUsages(handler.targets)
        return handler.readUsages
    }

    fun testOpenerAndCloserArePaired() {
        val text = "::alert{type=\"info\"}\ntext\n::\n"
        configure(text)
        val expected = listOf("::alert@0", "::@26")
        assertEquals(expected, markersAt(0)) // first colon
        assertEquals(expected, markersAt(3)) // name
        assertEquals(expected, markersAt(26)) // closer
        assertEquals(expected, markersAt(28)) // right after the closer (the line break)
        assertEquals(emptyList<String>(), markersAt(text.indexOf("text")))
        assertEquals(emptyList<String>(), markersAt(text.indexOf("type")))
    }

    fun testCaretRightAfterTheName() {
        configure("::alert\ntext\n::")
        assertEquals(listOf("::alert@0", "::@13"), markersAt(7))
    }

    fun testNestedComponentsWithTheSameMarker() {
        val text = "::a\n::b\nx\n::\n::\n"
        configure(text)
        assertEquals(listOf("::b@4", "::@10"), markersAt(4))
        assertEquals(listOf("::b@4", "::@10"), markersAt(10))
        assertEquals(listOf("::a@0", "::@13"), markersAt(13))
    }

    fun testColonCountsAndIndentation() {
        val text = "::outer\n  :::inner\n  text\n  :::\n::"
        configure(text)
        assertEquals(listOf(":::inner@10", ":::@28"), markersAt(text.indexOf(":::inner")))
        assertEquals(listOf(":::inner@10", ":::@28"), markersAt(28))
        assertEquals(listOf("::outer@0", "::@32"), markersAt(32))
    }

    fun testUnclosedComponentHasNoPair() {
        configure("::outer\n:::inner\n::")
        assertEquals(emptyList<String>(), markersAt(8)) // :::inner is never closed
        assertEquals(listOf("::outer@0", "::@17"), markersAt(0))
    }

    fun testMarkersInCodeFencesAreText() {
        configure("```\n::alert\n::\n```")
        assertEquals(emptyList<String>(), markersAt(4))
        assertEquals(emptyList<String>(), markersAt(12))
    }

    fun testPlainMarkdownHasNoPairs() {
        myFixture.configureByText("plain.md", "::alert\ntext\n::")
        assertEquals(emptyList<String>(), markersAt(0))
        assertNull(usagesAtCaret())
    }

    fun testHighlightUsagesOnOpenerAndCloser() {
        configure("::card\n:::inner\nx\n:::\n<caret>::\n")
        assertEquals(listOf(TextRange(0, 6), TextRange(22, 24)), usagesAtCaret())
        configure("::card\n:::in<caret>ner\nx\n:::\n::\n")
        assertEquals(listOf(TextRange(7, 15), TextRange(18, 21)), usagesAtCaret())
        configure("::card\n:::inner\nx<caret>\n:::\n::\n")
        assertNull(usagesAtCaret())
    }

    fun testHighlightUsagesActionHighlightsBoth() {
        configure("::ca<caret>rd\nx\n::\n")
        HighlightUsagesHandler.invoke(project, myFixture.editor, myFixture.file)
        val highlighted = myFixture.editor.markupModel.allHighlighters.map { TextRange(it.startOffset, it.endOffset) }.toSet()
        assertEquals(setOf(TextRange(0, 6), TextRange(9, 11)), highlighted)
    }

    /** End to end: the identifier highlighting that follows the caret highlights both markers. */
    fun testPairIsHighlightedAtCaret() {
        configure("::card\ntext\n::\n")
        IdentifierHighlighterPassFactory.doWithIdentifierHighlightingEnabled(project) {
            myFixture.editor.caretModel.moveToOffset(12) // the highlighting follows caret moves
            IdentifierHighlighterPassFactory.waitForIdentifierHighlighting(myFixture.editor)
            val highlighted = myFixture.editor.markupModel.allHighlighters
                .filter { it.textAttributesKey == EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES }
                .map { TextRange(it.startOffset, it.endOffset) }
                .toSet()
            assertEquals(setOf(TextRange(0, 6), TextRange(12, 14)), highlighted)
        }
    }

    fun testMoveCaretToMatchingBrace() {
        configure("::ca<caret>rd\ntext\n::\nafter")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_MATCH_BRACE)
        assertEquals(14, myFixture.caretOffset) // end of the closing marker
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_MATCH_BRACE)
        assertEquals(0, myFixture.caretOffset)
    }

    fun testMoveCaretToCodeBlockStartAndEnd() {
        configure("::card\nsome <caret>text\n::\n")
        myFixture.performEditorAction("EditorCodeBlockEnd")
        assertEquals(19, myFixture.caretOffset)
        myFixture.performEditorAction("EditorCodeBlockStart")
        assertEquals(0, myFixture.caretOffset)
    }

    fun testCodeBlockRangeOfUnclosedComponent() {
        configure("::card\ntext\n\n")
        val range = CodeBlockSupportHandler.findCodeBlockRange(myFixture.editor, myFixture.file)
        assertEquals(TextRange(0, 11), range)
    }

    /** With an editor highlighter that produces Comark tokens, `{}` and `[]` of opener lines match. */
    fun testBraceMatcherOnComarkTokens() {
        val text = "::alert[Some *content*]{type=\"info\" .big}\ntext\n::\n"
        val highlighter = LexerEditorHighlighter(ComarkTokensHighlighter(), EditorColorsManager.getInstance().globalScheme)
        highlighter.setText(text)
        val fileType = MarkdownFileType.INSTANCE

        val lbrace = highlighter.createIterator(text.indexOf('{'))
        assertEquals(ComarkTypes.ATTR_LBRACE, lbrace.tokenType)
        assertTrue(BraceMatchingUtil.isLBraceToken(lbrace, text, fileType))
        assertTrue(BraceMatchingUtil.matchBrace(text, fileType, lbrace, true))
        assertEquals(text.indexOf('}'), lbrace.start)

        val rbracket = highlighter.createIterator(text.indexOf(']'))
        assertEquals(ComarkTypes.CONTENT_RBRACKET, rbracket.tokenType)
        assertTrue(BraceMatchingUtil.matchBrace(text, fileType, rbracket, false))
        assertEquals(text.indexOf('['), rbracket.start)

        val matcher = ComarkBraceMatcher()
        assertTrue(matcher.isPairedBracesAllowedBeforeType(ComarkTypes.ATTR_LBRACE, null))
        assertTrue(matcher.isPairedBracesAllowedBeforeType(ComarkTypes.ATTR_LBRACE, ComarkTypes.ATTR_RBRACE))
        assertFalse(matcher.isPairedBracesAllowedBeforeType(ComarkTypes.ATTR_LBRACE, ComarkTypes.ATTR_NAME))
    }

    private class ComarkTokensHighlighter : SyntaxHighlighterBase() {
        override fun getHighlightingLexer() = ComarkLexer()

        override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = TextAttributesKey.EMPTY_ARRAY
    }
}
