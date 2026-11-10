package cz.xprees.comark.highlighting

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.highlighting.MarkdownHighlighterColors
import org.intellij.plugins.markdown.highlighting.MarkdownSyntaxHighlighter
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

/**
 * Checks what an editor opened on a file actually highlights with: Comark files keep the Markdown file type and only their
 * language is substituted, so the Comark highlighter must still be picked up through the substituted language.
 */
class EditorHighlighterTest : BasePlatformTestCase() {
    private val text = "# Title\n\n::alert{type=\"info\"}\nSome **text**\n::\n"

    private fun tokenAt(offset: Int): Pair<IElementType, Array<TextAttributesKey>> {
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(offset)
        return iterator.tokenType to iterator.textAttributesKeys
    }

    private fun assertComarkHighlighted() {
        val name = myFixture.editor.document.text.indexOf("alert")
        val (type, keys) = tokenAt(name)
        assertEquals(ComarkTypes.COMPONENT_NAME, type)
        assertTrue(keys.contentToString(), ComarkHighlighterColors.COMPONENT_NAME in keys)
        val attributes = (myFixture.editor as EditorEx).highlighter.createIterator(name).textAttributes
        val expected = EditorColorsManager.getInstance().globalScheme.getAttributes(ComarkHighlighterColors.COMPONENT_NAME)
        assertEquals(expected, attributes)

        assertEquals(ComarkTypes.COMPONENT_MARKER, tokenAt(name - 1).first)
        assertEquals(ComarkTypes.ATTR_NAME, tokenAt(myFixture.editor.document.text.indexOf("type")).first)
        assertEquals(ComarkTypes.ATTR_VALUE, tokenAt(myFixture.editor.document.text.indexOf("\"info\"")).first)
        assertEquals(ComarkTypes.COMPONENT_CLOSE, tokenAt(myFixture.editor.document.text.lastIndexOf("::")).first)
        // Markdown keeps its colors: the header marker and the inline layer inside the paragraph
        val (headerType, headerKeys) = tokenAt(0)
        assertEquals(MarkdownTokenTypes.ATX_HEADER, headerType)
        assertTrue(MarkdownHighlighterColors.HEADER_MARKER in headerKeys)
        assertEquals(MarkdownTokenTypes.EMPH, tokenAt(myFixture.editor.document.text.indexOf("**")).first)
    }

    fun testMdcFileUsesComarkHighlighter() {
        val file = myFixture.configureByText("page.mdc", text)
        assertInstanceOf(file, ComarkFile::class.java)
        assertComarkHighlighted()
    }

    fun testMdFileInNuxtContentProjectUsesComarkHighlighter() {
        myFixture.addFileToProject("site/package.json", """{ "devDependencies": { "@nuxt/content": "^3.0.0" } }""")
        val file = myFixture.addFileToProject("site/content/index.md", text)
        assertInstanceOf(file, ComarkFile::class.java)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertComarkHighlighted()
    }

    fun testPlainMdFileKeepsMarkdownHighlighter() {
        val file = myFixture.configureByText("readme.md", text)
        assertFalse(file is ComarkFile)
        val (type, _) = tokenAt(myFixture.editor.document.text.indexOf("alert"))
        assertFalse("plain Markdown must not see Comark tokens", type is cz.xprees.comark.parser.ComarkElementType)
        val (headerType, headerKeys) = tokenAt(0)
        assertEquals(MarkdownTokenTypes.ATX_HEADER, headerType)
        assertTrue(MarkdownHighlighterColors.HEADER_MARKER in headerKeys)
    }

    fun testTokenMapping() {
        val highlighter = ComarkSyntaxHighlighter()
        val expected = mapOf(
            ComarkTypes.COMPONENT_MARKER to ComarkHighlighterColors.COMPONENT_MARKER,
            ComarkTypes.COMPONENT_NAME to ComarkHighlighterColors.COMPONENT_NAME,
            ComarkTypes.COMPONENT_CLOSE to ComarkHighlighterColors.COMPONENT_CLOSE,
            ComarkTypes.CONTENT_LBRACKET to ComarkHighlighterColors.CONTENT_BRACKETS,
            ComarkTypes.CONTENT_RBRACKET to ComarkHighlighterColors.CONTENT_BRACKETS,
            ComarkTypes.ATTR_LBRACE to ComarkHighlighterColors.ATTRIBUTE_BRACES,
            ComarkTypes.ATTR_RBRACE to ComarkHighlighterColors.ATTRIBUTE_BRACES,
            ComarkTypes.ATTR_NAME to ComarkHighlighterColors.ATTRIBUTE_NAME,
            ComarkTypes.ATTR_EQ to ComarkHighlighterColors.ATTRIBUTE_EQ,
            ComarkTypes.ATTR_VALUE to ComarkHighlighterColors.ATTRIBUTE_VALUE,
            ComarkTypes.ATTR_ID to ComarkHighlighterColors.ATTRIBUTE_ID,
            ComarkTypes.ATTR_CLASS to ComarkHighlighterColors.ATTRIBUTE_CLASS,
            ComarkTypes.PROPS_FENCE to ComarkHighlighterColors.PROPS_FENCE,
            ComarkTypes.SLOT_MARKER to ComarkHighlighterColors.SLOT_MARKER,
            ComarkTypes.SLOT_NAME to ComarkHighlighterColors.SLOT_NAME,
            ComarkTypes.BAD_CHARACTER to ComarkHighlighterColors.BAD_CHARACTER,
        )
        for ((type, key) in expected) {
            assertEquals(type.toString(), listOf(key), highlighter.getTokenHighlights(type).toList())
        }
        // Markdown tokens are delegated to the Markdown highlighter
        val markdown = MarkdownSyntaxHighlighter()
        for (type in listOf(MarkdownTokenTypes.ATX_HEADER, MarkdownTokenTypes.CODE_FENCE_START, MarkdownTokenTypes.TEXT)) {
            assertEquals(markdown.getTokenHighlights(type).toList(), highlighter.getTokenHighlights(type).toList())
        }
        assertTrue(highlighter.getTokenHighlights(ComarkTypes.PROPS_CONTENT).isEmpty())
    }

    fun testHighlightingLexerCoversText() {
        val sample = "---\ntitle: x\n---\n\n# Head {.a}\n\n::card[Hi **you**]{.x #y z=\"1\"} junk\n---\nicon: a\n---\n#title\nText :b[c]{d}\n  :::inner\n  x\n  :::\n::\n"
        val lexer = ComarkHighlightingLexer()
        lexer.start(sample, 0, sample.length, 0)
        var expected = 0
        val types = ArrayList<IElementType>()
        while (lexer.tokenType != null) {
            assertEquals(expected, lexer.tokenStart)
            assertTrue(lexer.tokenEnd > lexer.tokenStart)
            types += lexer.tokenType!!
            expected = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(sample.length, expected)
        assertTrue(ComarkTypes.BAD_CHARACTER in types)
        assertTrue(ComarkTypes.SLOT_NAME in types)
        assertTrue(ComarkTypes.PROPS_FENCE in types)
        // the component's [content] is lexed by the Markdown inline layer
        assertFalse(ComarkTypes.INLINE_CONTENT in types)
        assertTrue(MarkdownTokenTypes.EMPH in types)
    }
}
