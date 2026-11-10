package cz.xprees.comark.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import org.intellij.plugins.markdown.highlighting.MarkdownHighlighterColors

/**
 * Text attributes of Comark syntax.
 *
 * Every key falls back to a [DefaultLanguageHighlighterColors] key (or a Markdown one), so Comark looks right in every bundled
 * and third-party color scheme without shipping scheme XML. The fallbacks were picked from keys that the Light, Dark, Darcula
 * and Classic Light schemes all define: markers use the keyword color like Markdown's own markers (headers, list bullets,
 * emphasis), component names the function-declaration color, attribute names / values the field / string colors.
 */
object ComarkHighlighterColors {
    /** `::` / `:::` of a block component opener and the `:` of an inline component. */
    @JvmField
    val COMPONENT_MARKER: TextAttributesKey = createTextAttributesKey("COMARK_COMPONENT_MARKER", DefaultLanguageHighlighterColors.KEYWORD)

    /** Block component name (`alert` in `::alert`). */
    @JvmField
    val COMPONENT_NAME: TextAttributesKey = createTextAttributesKey("COMARK_COMPONENT_NAME", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)

    /** The `::` closing a block component. */
    @JvmField
    val COMPONENT_CLOSE: TextAttributesKey = createTextAttributesKey("COMARK_COMPONENT_CLOSE", COMPONENT_MARKER)

    /** Inline component name (`icon` in `:icon{name="x"}`). */
    @JvmField
    val INLINE_COMPONENT: TextAttributesKey = createTextAttributesKey("COMARK_INLINE_COMPONENT", COMPONENT_NAME)

    /** `[` and `]` around component content (`::alert[content]`, `:badge[content]`) and spans. */
    @JvmField
    val CONTENT_BRACKETS: TextAttributesKey = createTextAttributesKey("COMARK_CONTENT_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)

    /** Text of a span (`[text]{.class}`): plain text by default, a span renders as a `<span>`. */
    @JvmField
    val SPAN: TextAttributesKey = createTextAttributesKey("COMARK_SPAN", HighlighterColors.TEXT)

    /** `{` and `}` of an attribute group. */
    @JvmField
    val ATTRIBUTE_BRACES: TextAttributesKey = createTextAttributesKey("COMARK_ATTRIBUTE_BRACES", DefaultLanguageHighlighterColors.BRACES)

    @JvmField
    val ATTRIBUTE_NAME: TextAttributesKey = createTextAttributesKey("COMARK_ATTRIBUTE_NAME", DefaultLanguageHighlighterColors.INSTANCE_FIELD)

    /** `=` between an attribute name and its value. */
    @JvmField
    val ATTRIBUTE_EQ: TextAttributesKey = createTextAttributesKey("COMARK_ATTRIBUTE_EQ", DefaultLanguageHighlighterColors.OPERATION_SIGN)

    @JvmField
    val ATTRIBUTE_VALUE: TextAttributesKey = createTextAttributesKey("COMARK_ATTRIBUTE_VALUE", DefaultLanguageHighlighterColors.STRING)

    /** `#id` shorthand. */
    @JvmField
    val ATTRIBUTE_ID: TextAttributesKey = createTextAttributesKey("COMARK_ATTRIBUTE_ID", DefaultLanguageHighlighterColors.METADATA)

    /** `.class` shorthand. */
    @JvmField
    val ATTRIBUTE_CLASS: TextAttributesKey = createTextAttributesKey("COMARK_ATTRIBUTE_CLASS", DefaultLanguageHighlighterColors.STATIC_FIELD)

    /** `#` starting a slot line. */
    @JvmField
    val SLOT_MARKER: TextAttributesKey = createTextAttributesKey("COMARK_SLOT_MARKER", DefaultLanguageHighlighterColors.KEYWORD)

    /** Slot name (`header` in `#header`). */
    @JvmField
    val SLOT_NAME: TextAttributesKey = createTextAttributesKey("COMARK_SLOT_NAME", DefaultLanguageHighlighterColors.CONSTANT)

    /** `---` fences of a component's YAML props block, styled like front matter delimiters. */
    @JvmField
    val PROPS_FENCE: TextAttributesKey = createTextAttributesKey("COMARK_PROPS_FENCE", MarkdownHighlighterColors.FRONT_MATTER_HEADER_DELIMITER)

    /** `{{ expression }}` data binding. */
    @JvmField
    val BINDING: TextAttributesKey = createTextAttributesKey("COMARK_BINDING", DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR)

    /** `{{` and `}}` of a data binding. */
    @JvmField
    val BINDING_BRACES: TextAttributesKey = createTextAttributesKey("COMARK_BINDING_BRACES", DefaultLanguageHighlighterColors.KEYWORD)

    /**
     * Text after a component opener's attributes, which Comark ignores. Shown greyed out like unused code: the
     * "Ignored text after component opener" inspection reports it and offers the fixes, so a second, error-like style would only
     * repeat that (it used to be shown with the bad character style as well as the inspection's warning).
     */
    @JvmField
    val BAD_CHARACTER: TextAttributesKey = createTextAttributesKey("COMARK_BAD_CHARACTER", CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES)
}
