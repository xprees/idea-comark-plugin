package cz.xprees.comark

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SyntaxTraverser
import cz.xprees.comark.parser.ComarkLexer
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

object ComarkTestUtil {
    /** Structural invariants every Comark parse must satisfy. */
    fun assertConsistent(file: PsiFile) {
        val text = file.text
        assertEquals("PSI text must round-trip", file.viewProvider.document?.text ?: text, text)
        for (element in SyntaxTraverser.psiTraverser(file)) {
            if (element is PsiWhiteSpace) {
                assertTrue("whitespace element holds text: '${element.text}' at ${element.textRange}", element.text.isBlank())
            }
        }
        assertLexerCovers(text)
    }

    fun assertLexerCovers(text: CharSequence) {
        val lexer = ComarkLexer()
        lexer.start(text, 0, text.length, 0)
        var expected = 0
        while (lexer.tokenType != null) {
            assertEquals("tokens must be contiguous", expected, lexer.tokenStart)
            assertTrue("empty token ${lexer.tokenType} at $expected", lexer.tokenEnd > lexer.tokenStart)
            expected = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals("tokens must cover the text", text.length, expected)
    }
}
