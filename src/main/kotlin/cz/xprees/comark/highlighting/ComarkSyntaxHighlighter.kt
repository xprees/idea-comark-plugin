package cz.xprees.comark.highlighting

import com.intellij.lexer.LayeredLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import cz.xprees.comark.parser.ComarkLexer
import cz.xprees.comark.parser.ComarkTypes
import org.intellij.plugins.markdown.highlighting.MarkdownSyntaxHighlighter
import org.intellij.plugins.markdown.lang.MarkdownTokenTypeSets
import org.intellij.plugins.markdown.lang.lexer.MarkdownMergingLexer

/**
 * Lexer for editor highlighting: the Comark top level lexer, with the Markdown inline lexer run over every token that holds
 * inline content (paragraphs, headings, a component's `[content]`), exactly like the Markdown plugin's
 * `MarkdownHighlightingLexer` layers it over its own top level lexer.
 */
class ComarkHighlightingLexer : LayeredLexer(ComarkLexer()) {
    init {
        registerSelfStoppingLayer(MarkdownMergingLexer(), INLINE_HOLDERS, IElementType.EMPTY_ARRAY)
    }

    private companion object {
        val INLINE_HOLDERS: Array<IElementType> = MarkdownTokenTypeSets.INLINE_HOLDING_ELEMENT_TYPES.types + ComarkTypes.INLINE_CONTENT
    }
}

/**
 * Syntax highlighter of Comark files. Comark tokens get [ComarkHighlighterColors]; every other token is a Markdown token and is
 * colored by the Markdown plugin's highlighter, so Markdown keeps its familiar colors inside Comark documents.
 */
class ComarkSyntaxHighlighter : SyntaxHighlighterBase() {
    private val markdown = MarkdownSyntaxHighlighter()

    override fun getHighlightingLexer(): Lexer = ComarkHighlightingLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        val key = COMARK_KEYS[tokenType] ?: return markdown.getTokenHighlights(tokenType)
        return pack(key)
    }

    companion object {
        private val COMARK_KEYS: Map<IElementType, TextAttributesKey> = mapOf(
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
    }
}

/**
 * Registered for the Comark language. The editor of a Markdown file whose language is substituted with Comark asks the
 * substituted language's file type ([cz.xprees.comark.lang.ComarkFileType]) for its highlighter, which ends up here.
 */
class ComarkSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = ComarkSyntaxHighlighter()
}
