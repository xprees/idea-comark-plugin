package cz.xprees.comark.meta

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parents
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.parser.ComarkSyntax
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkAttribute
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkComponentHeader
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.psi.ComarkPropsContent
import cz.xprees.comark.psi.ComarkSlot
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

/**
 * Comark's inline component syntax, `:name`, `:name[content]` and `:name[content]{attrs}`, on plain text.
 *
 * Inline components live inside Markdown paragraphs, which the Markdown plugin parses into its own inline PSI that knows
 * nothing about them, so they are recognised textually, following comark's `comark_inline_component` rule: the colon must start
 * the text or follow one of [ALLOWED_PREVIOUS] (so `https://`, `10:30` and `Note:` are not components), and the name must start
 * with a letter or `$`.
 */
object InlineComponentSyntax {
    /** comark's `ALLOWED_PREV_CHARS`. */
    const val ALLOWED_PREVIOUS = " \t\n*_[]})>"

    /** Offsets of an inline component: name, optional `[content]` brackets (-1 when absent) and attribute group. */
    class InlineComponent(
        val colon: Int,
        val nameStart: Int,
        val nameEnd: Int,
        val contentOpen: Int,
        val contentClose: Int,
        val attributes: ComarkSyntax.AttrScan?,
    ) {
        fun name(text: CharSequence): String = text.subSequence(nameStart, nameEnd).toString()
    }

    /** True when a colon at [colon] may start an inline component (start of [regionStart] or an allowed previous character). */
    fun isAllowedBefore(text: CharSequence, colon: Int, regionStart: Int = 0): Boolean =
        colon <= regionStart || text[colon - 1] in ALLOWED_PREVIOUS

    /** The inline component whose colon is at [colon], or null. [regionStart] is where the surrounding inline text starts. */
    fun parseAt(text: CharSequence, colon: Int, regionStart: Int, end: Int): InlineComponent? {
        if (colon >= end || text[colon] != ':' || !isAllowedBefore(text, colon, regionStart)) return null
        val nameStart = colon + 1
        val length = ComarkSyntax.inlineNameLength(text, nameStart, end)
        if (length == 0) return null
        val nameEnd = nameStart + length
        var i = nameEnd
        var contentOpen = -1
        var contentClose = -1
        if (i < end && text[i] == '[') {
            val close = ComarkSyntax.findClosingBracket(text, i, end)
            if (close >= 0) {
                contentOpen = i
                contentClose = close
                i = close + 1
            }
        }
        val attributes = if (i < end && text[i] == '{') ComarkSyntax.scanAttributes(text, i, end) else null
        return InlineComponent(colon, nameStart, nameEnd, contentOpen, contentClose, attributes)
    }

    /** All inline components in `[from, to)`, including ones nested in another component's `[content]`. */
    fun findAll(text: CharSequence, from: Int, to: Int): List<InlineComponent> {
        val result = ArrayList<InlineComponent>()
        var i = from
        var skipFrom = -1
        var skipTo = -1
        while (i < to) {
            if (i == skipFrom) {
                i = skipTo
                continue
            }
            if (text[i] == ':') {
                val component = parseAt(text, i, from, to)
                if (component != null) {
                    result += component
                    component.attributes?.let {
                        skipFrom = it.tokens.first().start
                        skipTo = it.end
                    }
                    i = component.nameEnd
                    continue
                }
            }
            i++
        }
        return result
    }
}

/** Top-level keys of the YAML in a component's props block, on plain text (it may or may not be injected as YAML). */
object PropsYaml {
    private val KEY_LINE = Regex("""^([ \t]*)("[^"]*"|'[^']*'|[^\s:#'"\-][^:#]*?)[ \t]*:(?:[ \t]|$)""")

    /** A top-level `key:` with the key's offsets in the text. */
    class Key(val name: String, val start: Int, val end: Int)

    /** Indentation of the YAML's top level: the smallest indentation of a non-blank, non-comment line (0 when there are none). */
    fun baseIndent(yaml: CharSequence, exceptLineAt: Int = -1): Int {
        var min = Int.MAX_VALUE
        forEachLine(yaml) { start, end ->
            if (exceptLineAt in start..end) return@forEachLine
            var i = start
            while (i < end && (yaml[i] == ' ' || yaml[i] == '\t')) i++
            if (i < end && yaml[i] != '#') min = minOf(min, i - start)
        }
        return if (min == Int.MAX_VALUE) 0 else min
    }

    /** Every top-level key. */
    fun keys(yaml: CharSequence): List<Key> {
        val base = baseIndent(yaml)
        val keys = ArrayList<Key>()
        forEachLine(yaml) { start, end -> keyOnLine(yaml, start, end, base)?.let(keys::add) }
        return keys
    }

    /** The top-level key at [offset] (the offset may also be right after the key). */
    fun keyAt(yaml: CharSequence, offset: Int): Key? {
        if (offset < 0 || offset > yaml.length) return null
        var lineStart = offset
        while (lineStart > 0 && yaml[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < yaml.length && yaml[lineEnd] != '\n') lineEnd++
        val key = keyOnLine(yaml, lineStart, lineEnd, baseIndent(yaml)) ?: return null
        return key.takeIf { offset in it.start..it.end }
    }

    private fun keyOnLine(yaml: CharSequence, start: Int, end: Int, base: Int): Key? {
        val match = KEY_LINE.find(yaml.subSequence(start, end)) ?: return null
        if (match.groupValues[1].length != base) return null
        val group = match.groups[2]!!
        var keyStart = start + group.range.first
        var keyEnd = start + group.range.last + 1
        var name = group.value
        if (name.length >= 2 && (name[0] == '"' || name[0] == '\'')) {
            name = name.substring(1, name.length - 1)
            keyStart++
            keyEnd--
        }
        return Key(name, keyStart, keyEnd)
    }

    private inline fun forEachLine(text: CharSequence, action: (Int, Int) -> Unit) {
        var start = 0
        while (start <= text.length) {
            var end = start
            while (end < text.length && text[end] != '\n') end++
            action(start, end)
            if (end >= text.length) break
            start = end + 1
        }
    }
}

/** A reference to a component, or to one of its props or slots, in a Comark document. [range] is in the searched file. */
sealed class ComponentUsage(val componentName: String, val range: TextRange) {
    /** `::name` or `:name`. */
    class Name(componentName: String, range: TextRange) : ComponentUsage(componentName, range)

    /** `{prop="..."}` on a block or inline component, or `prop:` in a props block. */
    class Prop(componentName: String, val propName: String, range: TextRange) : ComponentUsage(componentName, range)

    /** `#slot` inside a block component. */
    class Slot(componentName: String, val slotName: String, range: TextRange) : ComponentUsage(componentName, range)
}

/**
 * Finds what component (or prop, or slot) the text at an offset of a Comark document refers to, for navigation, documentation
 * and highlighting. Block components, their attributes and slots come from Comark PSI; inline components from the text of the
 * Markdown around them ([InlineComponentSyntax]); props-block keys from the YAML text, injected or not.
 */
object ComponentUsages {
    /** Markdown and Comark elements whose text never contains component references. */
    private val NO_COMPONENTS = TokenSet.create(
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.INLINE_MATH,
        MarkdownElementTypes.BLOCK_MATH,
        MarkdownElementTypes.HTML_BLOCK,
        MarkdownElementTypes.FRONT_MATTER_HEADER,
        MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.AUTOLINK,
        MarkdownTokenTypes.HTML_TAG,
        MarkdownElementTypes.LINK_COMMENT,
        ComarkTypes.PROPS_BLOCK,
    )

    /** The usage at [offset] of [file] (a Comark file, or YAML injected into a props block), also trying the offset before. */
    fun find(file: PsiFile, offset: Int): ComponentUsage? {
        propsHost(file)?.let { host ->
            val component = host.parent?.parent as? ComarkBlockComponent ?: return null
            return propsKey(component, file.viewProvider.contents, offset, 0)
        }
        if (file !is ComarkFile) return null
        return atOffset(file, offset) ?: if (offset > 0) atOffset(file, offset - 1) else null
    }

    /** The Markdown file a usage in [file] belongs to (the host file for injected YAML), for picking the right app's component. */
    fun contextFile(file: PsiFile): VirtualFile? {
        val topLevel = InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file) ?: file
        return topLevel.originalFile.virtualFile ?: topLevel.virtualFile
    }

    /** The props block content [file] is injected into, when it is YAML injected into a Comark props block. */
    fun propsHost(file: PsiFile): ComarkPropsContent? =
        InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) as? ComarkPropsContent

    /** True when [element] is inside code, HTML, front matter, a link destination or a props block. */
    fun isInsideNonContent(element: PsiElement): Boolean = element.parents(withSelf = true).any { it.elementType in NO_COMPONENTS }

    private fun atOffset(file: ComarkFile, offset: Int): ComponentUsage? {
        val element = file.findElementAt(offset) ?: return null
        when (element.elementType) {
            ComarkTypes.COMPONENT_NAME -> return ComponentUsage.Name(element.text, element.textRange)
            ComarkTypes.ATTR_NAME -> return attributeUsage(element)
            ComarkTypes.SLOT_NAME -> {
                val slot = element.parent as? ComarkSlot ?: return null
                val component = slot.component?.componentName ?: return null
                return ComponentUsage.Slot(component, element.text, element.textRange)
            }

            ComarkTypes.PROPS_CONTENT -> {
                val component = element.parent?.parent as? ComarkBlockComponent ?: return null
                return propsKey(component, element.text, offset - element.textRange.startOffset, element.textRange.startOffset)
            }
        }
        if (element.language == ComarkLanguage && element.elementType != ComarkTypes.INLINE_CONTENT) return null
        if (isInsideNonContent(element)) return null
        return inlineUsage(file.viewProvider.contents, offset)
    }

    private fun attributeUsage(name: PsiElement): ComponentUsage? {
        val attribute = name.parent as? ComarkAttribute ?: return null
        val header = attribute.parent?.parent as? ComarkComponentHeader ?: return null
        val component = (header.parent as? ComarkBlockComponent)?.componentName ?: return null
        val propName = attribute.attributeName?.takeIf { it.isNotEmpty() } ?: return null
        val range = name.textRange
        return ComponentUsage.Prop(component, propName, if (attribute.isBinding) TextRange(range.startOffset + 1, range.endOffset) else range)
    }

    private fun propsKey(component: ComarkBlockComponent, yaml: CharSequence, offset: Int, shift: Int): ComponentUsage? {
        val name = component.componentName ?: return null
        val key = PropsYaml.keyAt(yaml, offset) ?: return null
        return ComponentUsage.Prop(name, key.name, TextRange(shift + key.start, shift + key.end))
    }

    /** An inline component name or attribute at [offset], from the text of its line. */
    fun inlineUsage(text: CharSequence, offset: Int): ComponentUsage? {
        if (offset < 0 || offset >= text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        for (component in InlineComponentSyntax.findAll(text, lineStart, lineEnd)) {
            val name = component.name(text)
            if (offset in component.nameStart until component.nameEnd) {
                return ComponentUsage.Name(name, TextRange(component.nameStart, component.nameEnd))
            }
            val attributes = component.attributes ?: continue
            val token = attributes.tokens.firstOrNull { offset >= it.start && offset < it.end } ?: continue
            if (token.part != ComarkSyntax.AttrPart.NAME) return null
            val start = if (text[token.start] == ':') token.start + 1 else token.start
            if (start >= token.end) return null
            return ComponentUsage.Prop(name, text.subSequence(start, token.end).toString(), TextRange(start, token.end))
        }
        return null
    }
}
