package cz.xprees.comark.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.ComarkIcons
import java.util.function.Supplier
import javax.swing.Icon

/**
 * Settings | Editor | Color Scheme | Comark. The demo is highlighted by the Comark lexer (block syntax, Markdown) plus tags for
 * everything the annotator adds (inline components, spans, inline attributes, bindings); block syntax is tagged as well, so
 * clicking any Comark construct in the preview selects its key.
 */
class ComarkColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = ComarkBundle.message("highlighting.color.settings.name")

    override fun getIcon(): Icon = ComarkIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = ComarkSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = DEMO

    companion object {
        private val TAGS: Map<String, TextAttributesKey> = mapOf(
            "marker" to ComarkHighlighterColors.COMPONENT_MARKER,
            "name" to ComarkHighlighterColors.COMPONENT_NAME,
            "close" to ComarkHighlighterColors.COMPONENT_CLOSE,
            "inline" to ComarkHighlighterColors.INLINE_COMPONENT,
            "bracket" to ComarkHighlighterColors.CONTENT_BRACKETS,
            "span" to ComarkHighlighterColors.SPAN,
            "brace" to ComarkHighlighterColors.ATTRIBUTE_BRACES,
            "attr" to ComarkHighlighterColors.ATTRIBUTE_NAME,
            "eq" to ComarkHighlighterColors.ATTRIBUTE_EQ,
            "value" to ComarkHighlighterColors.ATTRIBUTE_VALUE,
            "id" to ComarkHighlighterColors.ATTRIBUTE_ID,
            "class" to ComarkHighlighterColors.ATTRIBUTE_CLASS,
            "slot_marker" to ComarkHighlighterColors.SLOT_MARKER,
            "slot" to ComarkHighlighterColors.SLOT_NAME,
            "fence" to ComarkHighlighterColors.PROPS_FENCE,
            "binding_brace" to ComarkHighlighterColors.BINDING_BRACES,
            "binding" to ComarkHighlighterColors.BINDING,
            "bad" to ComarkHighlighterColors.BAD_CHARACTER,
        )

        private val DESCRIPTORS: Array<AttributesDescriptor> = arrayOf(
            descriptor("highlighting.color.component.marker", ComarkHighlighterColors.COMPONENT_MARKER),
            descriptor("highlighting.color.component.name", ComarkHighlighterColors.COMPONENT_NAME),
            descriptor("highlighting.color.component.close", ComarkHighlighterColors.COMPONENT_CLOSE),
            descriptor("highlighting.color.inline.component", ComarkHighlighterColors.INLINE_COMPONENT),
            descriptor("highlighting.color.content.brackets", ComarkHighlighterColors.CONTENT_BRACKETS),
            descriptor("highlighting.color.span", ComarkHighlighterColors.SPAN),
            descriptor("highlighting.color.attribute.braces", ComarkHighlighterColors.ATTRIBUTE_BRACES),
            descriptor("highlighting.color.attribute.name", ComarkHighlighterColors.ATTRIBUTE_NAME),
            descriptor("highlighting.color.attribute.eq", ComarkHighlighterColors.ATTRIBUTE_EQ),
            descriptor("highlighting.color.attribute.value", ComarkHighlighterColors.ATTRIBUTE_VALUE),
            descriptor("highlighting.color.attribute.id", ComarkHighlighterColors.ATTRIBUTE_ID),
            descriptor("highlighting.color.attribute.class", ComarkHighlighterColors.ATTRIBUTE_CLASS),
            descriptor("highlighting.color.slot.marker", ComarkHighlighterColors.SLOT_MARKER),
            descriptor("highlighting.color.slot.name", ComarkHighlighterColors.SLOT_NAME),
            descriptor("highlighting.color.props.fence", ComarkHighlighterColors.PROPS_FENCE),
            descriptor("highlighting.color.binding.braces", ComarkHighlighterColors.BINDING_BRACES),
            descriptor("highlighting.color.binding", ComarkHighlighterColors.BINDING),
            descriptor("highlighting.color.bad.character", ComarkHighlighterColors.BAD_CHARACTER),
        )

        private fun descriptor(key: String, attributes: TextAttributesKey) =
            AttributesDescriptor(Supplier { ComarkBundle.message(key) }, attributes)

        /** Every tag used here must be in [TAGS]; tests check that the demo covers every key. */
        private val DEMO = """
            ---
            title: Comark color scheme demo
            ---

            # Getting started <brace>{</brace><class>.intro</class><brace>}</brace>

            <marker>::</marker><name>card</name><brace>{</brace><attr>variant</attr><eq>=</eq><value>"outline"</value> <class>.shadow</class> <id>#welcome</id><brace>}</brace>
            <fence>---</fence>
            icon: i-lucide-rocket
            <fence>---</fence>
            Hello <bracket>[</bracket><span>World</span><bracket>]</bracket><brace>{</brace><attr>style</attr><eq>=</eq><value>"color: tomato"</value><brace>}</brace>, press <marker>:</marker><inline>kbd</inline><brace>{</brace><attr>value</attr><eq>=</eq><value>"meta"</value><brace>}</brace> and **save**<brace>{</brace><class>.bold</class><brace>}</brace>.

            Signed in as <binding_brace>{{</binding_brace> <binding>user.name || 'guest'</binding> <binding_brace>}}</binding_brace>, <marker>:</marker><inline>badge</inline><bracket>[</bracket>New<bracket>]</bracket>

            <slot_marker>#</slot_marker><slot>footer</slot>
              <marker>:::</marker><name>alert</name><bracket>[</bracket>Short **content**<bracket>]</bracket><brace>{</brace><attr>type</attr><eq>=</eq><value>"info"</value><brace>}</brace> <bad>ignored text</bad>
              <close>:::</close>
            <close>::</close>
        """.trimIndent()
    }
}
