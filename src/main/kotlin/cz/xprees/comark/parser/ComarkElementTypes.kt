package cz.xprees.comark.parser

import com.intellij.psi.tree.IElementType
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.psi.ComarkAttributeValueElementType
import cz.xprees.comark.psi.ComarkComponentNameElementType
import cz.xprees.comark.psi.ComarkPropsContentElementType
import org.intellij.markdown.MarkdownElementType
import org.intellij.plugins.markdown.lang.MarkdownElementType as PlatformMarkdownElementType
import org.intellij.markdown.IElementType as MarkdownIElementType

/** A platform element type of the Comark language. */
open class ComarkElementType(debugName: String) : IElementType(debugName, ComarkLanguage) {
    override fun toString(): String = "Comark:" + super.toString()
}

/**
 * Comark node types. Each one exists twice: as an intellij-markdown type (the parser builds an intellij-markdown AST so Comark
 * structure and the Markdown content between it live in one tree) and as the platform type the PSI uses. [platformType] maps
 * the former to the latter and falls back to the Markdown plugin's own mapping for regular Markdown nodes.
 */
object ComarkTypes {
    private val toPlatform = HashMap<MarkdownIElementType, IElementType>()
    private val toMarkdown = HashMap<IElementType, MarkdownIElementType>()

    private fun composite(name: String, platform: IElementType = ComarkElementType(name)): Pair<MarkdownIElementType, IElementType> =
        register(MarkdownElementType(name, false), platform)

    private fun token(name: String, platform: IElementType = ComarkElementType(name)): Pair<MarkdownIElementType, IElementType> =
        register(MarkdownElementType(name, true), platform)

    private fun register(markdown: MarkdownIElementType, platform: IElementType): Pair<MarkdownIElementType, IElementType> {
        toPlatform[markdown] = platform
        toMarkdown[platform] = markdown
        return markdown to platform
    }

    // ---- composites ---------------------------------------------------------------------------------------------
    /** `::name ... ::` including the closing marker. */
    val BLOCK_COMPONENT_PAIR = composite("BLOCK_COMPONENT")

    /** The opener line content `::name[content]{attrs}`. */
    val COMPONENT_HEADER_PAIR = composite("COMPONENT_HEADER")

    /** `---` YAML props block at the top of a component. */
    val PROPS_BLOCK_PAIR = composite("PROPS_BLOCK")

    /** `#name` slot with its content. */
    val SLOT_PAIR = composite("SLOT")

    /** `{...}` attribute group. */
    val ATTRIBUTES_PAIR = composite("ATTRIBUTES")

    /** One `key="value"` / `bool` attribute. */
    val ATTRIBUTE_PAIR = composite("ATTRIBUTE")

    // ---- tokens -------------------------------------------------------------------------------------------------
    val COMPONENT_MARKER_PAIR = token("COMPONENT_MARKER")
    val COMPONENT_NAME_PAIR = token("COMPONENT_NAME", ComarkComponentNameElementType())
    val COMPONENT_CLOSE_PAIR = token("COMPONENT_CLOSE")
    val CONTENT_LBRACKET_PAIR = token("CONTENT_LBRACKET")
    val CONTENT_RBRACKET_PAIR = token("CONTENT_RBRACKET")
    val INLINE_CONTENT_PAIR = token("INLINE_CONTENT")
    val ATTR_LBRACE_PAIR = token("ATTR_LBRACE")
    val ATTR_RBRACE_PAIR = token("ATTR_RBRACE")
    val ATTR_NAME_PAIR = token("ATTR_NAME")
    val ATTR_EQ_PAIR = token("ATTR_EQ")
    val ATTR_VALUE_PAIR = token("ATTR_VALUE", ComarkAttributeValueElementType())
    val ATTR_ID_PAIR = token("ATTR_ID")
    val ATTR_CLASS_PAIR = token("ATTR_CLASS")
    val PROPS_FENCE_PAIR = token("PROPS_FENCE")
    val PROPS_CONTENT_PAIR = token("PROPS_CONTENT", ComarkPropsContentElementType())
    val SLOT_MARKER_PAIR = token("SLOT_MARKER")
    val SLOT_NAME_PAIR = token("SLOT_NAME")
    val BAD_CHARACTER_PAIR = token("BAD_CHARACTER")

    // Platform types (what PSI and the highlighter see)
    @JvmField val BLOCK_COMPONENT = BLOCK_COMPONENT_PAIR.second
    @JvmField val COMPONENT_HEADER = COMPONENT_HEADER_PAIR.second
    @JvmField val PROPS_BLOCK = PROPS_BLOCK_PAIR.second
    @JvmField val SLOT = SLOT_PAIR.second
    @JvmField val ATTRIBUTES = ATTRIBUTES_PAIR.second
    @JvmField val ATTRIBUTE = ATTRIBUTE_PAIR.second
    @JvmField val COMPONENT_MARKER = COMPONENT_MARKER_PAIR.second
    @JvmField val COMPONENT_NAME = COMPONENT_NAME_PAIR.second
    @JvmField val COMPONENT_CLOSE = COMPONENT_CLOSE_PAIR.second
    @JvmField val CONTENT_LBRACKET = CONTENT_LBRACKET_PAIR.second
    @JvmField val CONTENT_RBRACKET = CONTENT_RBRACKET_PAIR.second
    @JvmField val INLINE_CONTENT = INLINE_CONTENT_PAIR.second
    @JvmField val ATTR_LBRACE = ATTR_LBRACE_PAIR.second
    @JvmField val ATTR_RBRACE = ATTR_RBRACE_PAIR.second
    @JvmField val ATTR_NAME = ATTR_NAME_PAIR.second
    @JvmField val ATTR_EQ = ATTR_EQ_PAIR.second
    @JvmField val ATTR_VALUE = ATTR_VALUE_PAIR.second
    @JvmField val ATTR_ID = ATTR_ID_PAIR.second
    @JvmField val ATTR_CLASS = ATTR_CLASS_PAIR.second
    @JvmField val PROPS_FENCE = PROPS_FENCE_PAIR.second
    @JvmField val PROPS_CONTENT = PROPS_CONTENT_PAIR.second
    @JvmField val SLOT_MARKER = SLOT_MARKER_PAIR.second
    @JvmField val SLOT_NAME = SLOT_NAME_PAIR.second
    @JvmField val BAD_CHARACTER = BAD_CHARACTER_PAIR.second

    /** Platform type for an intellij-markdown type: Comark's own, or the Markdown plugin's mapping. */
    @JvmStatic
    fun platformType(type: MarkdownIElementType): IElementType = toPlatform[type] ?: PlatformMarkdownElementType.platformType(type)

    @JvmStatic
    fun isComarkType(type: MarkdownIElementType): Boolean = type in toPlatform
}
