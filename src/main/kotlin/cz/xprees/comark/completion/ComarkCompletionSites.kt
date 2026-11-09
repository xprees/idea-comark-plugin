package cz.xprees.comark.completion

import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parents
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.meta.ComponentNaming
import cz.xprees.comark.meta.ComponentUsages
import cz.xprees.comark.meta.InlineComponentSyntax
import cz.xprees.comark.meta.PropsYaml
import cz.xprees.comark.parser.ComarkSyntax
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownElementTypes

/**
 * Where component completion was invoked and what is being typed there. [prefix] is the text typed so far, which starts at
 * [prefixStart]; lookup items replace it.
 */
sealed class CompletionSite(val prefixStart: Int, val prefix: String) {
    /** `::na|` at the start of a line: a block component name. [markers] is the number of colons. */
    class BlockName(prefixStart: Int, prefix: String, val markers: Int) : CompletionSite(prefixStart, prefix)

    /** `text :na|`: an inline component name. */
    class InlineName(prefixStart: Int, prefix: String) : CompletionSite(prefixStart, prefix)

    /** `::name{ty|` or `:name{ty|`: a prop of [componentName] written as an attribute; [existing] are the attributes already there. */
    class Attribute(prefixStart: Int, prefix: String, val componentName: String, val existing: Set<String>) : CompletionSite(prefixStart, prefix)

    /** `#na|` on its own line inside a block component: a slot of [componentName]. */
    class Slot(prefixStart: Int, prefix: String, val componentName: String, val existing: Set<String>) : CompletionSite(prefixStart, prefix)

    /** A top-level key in the YAML props block of [componentName]; [indent] is the line's indentation. */
    class PropsKey(prefixStart: Int, prefix: String, val componentName: String, val existing: Set<String>, val indent: String) :
        CompletionSite(prefixStart, prefix)
}

/**
 * Recognises the places where Comark component completion applies, by looking at the text of the current line and at the
 * (committed, original) PSI around it. Text decides what is being typed, because the text being typed is usually not valid
 * syntax yet; PSI decides whether the place counts at all (not in a code fence, inline code, HTML or front matter; which
 * component a slot or props block belongs to).
 */
object ComarkCompletionSites {
    private val BLOCK_NAME = Regex("""^[ \t]*(:{2,})[ \t]*([A-Za-z$][\w$.-]*)?$""")
    private val SLOT_NAME = Regex("""^[ \t]*#([A-Za-z$][\w$.-]*)?$""")
    private val NAME_REST = Regex("""^[\w$.-]*""")
    private val PROPS_KEY = Regex("""^([ \t]*)([A-Za-z_$][\w$-]*)?$""")

    /** Containers in which a `#name` line is not a slot of the enclosing component. */
    private val NOT_SLOT_LEVEL = TokenSet.create(
        MarkdownElementTypes.LIST_ITEM,
        MarkdownElementTypes.BLOCK_QUOTE,
        MarkdownElementTypes.HTML_BLOCK,
        ComarkTypes.COMPONENT_HEADER,
    )

    /** The completion site at [offset] of the Comark [file] whose current text is [text], or null when none applies. */
    fun detect(file: ComarkFile, text: CharSequence, offset: Int): CompletionSite? {
        if (offset < 0 || offset > text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        val before = text.subSequence(lineStart, offset)
        val contentStart = ComarkSyntax.skipSpaces(text, lineStart, offset)

        val probe = file.findElementAt(if (offset > lineStart) offset - 1 else offset) ?: file.findElementAt(offset)
        // On an empty line the character before the caret is the previous line's break, outside the props content.
        val props = listOf(file.findElementAt(offset), probe).firstOrNull { it?.elementType == ComarkTypes.PROPS_CONTENT }
        if (props != null) {
            val component = props.parent?.parent as? ComarkBlockComponent ?: return null
            val start = props.textRange.startOffset
            return propsKey(component, props.text, offset - start)?.shifted(start)
        }
        // The line's first token tells whether the line is code (fence, indented block), HTML, front matter or props.
        val lineElement = file.findElementAt(contentStart) ?: probe
        if (lineElement != null && ComponentUsages.isInsideNonContent(lineElement)) return null
        if (probe != null && probe != lineElement && ComponentUsages.isInsideNonContent(probe)) return null

        BLOCK_NAME.find(before)?.let { match ->
            val name = match.groups[2]
            val prefixStart = if (name != null) lineStart + name.range.first else offset
            return CompletionSite.BlockName(prefixStart, name?.value.orEmpty(), match.groupValues[1].length)
        }
        SLOT_NAME.find(before)?.let { match ->
            return slot(file, text, offset, lineEnd, contentStart, match.groups[1]?.value.orEmpty())
        }
        blockAttribute(file, text, offset, contentStart, lineEnd)?.let { return it }
        inlineAttribute(text, offset, lineStart, lineEnd)?.let { return it }
        // other Comark tokens (attribute values, the unparsed rest of an opener line, ...) don't hold inline components
        if (probe != null && probe.language == ComarkLanguage && probe.elementType != ComarkTypes.INLINE_CONTENT) return null
        return inlineName(text, offset, lineStart)
    }

    /** A props block key at [offset] of [yaml] (the props block text, injected or not). */
    fun propsKey(component: ComarkBlockComponent, yaml: CharSequence, offset: Int): CompletionSite.PropsKey? {
        val name = component.componentName ?: return null
        if (offset < 0 || offset > yaml.length) return null
        var lineStart = offset
        while (lineStart > 0 && yaml[lineStart - 1] != '\n') lineStart--
        val before = yaml.subSequence(lineStart, offset).toString()
        val match = PROPS_KEY.find(before) ?: return null
        val indent = match.groupValues[1]
        // a line indented deeper than the other keys belongs to a nested mapping or a multi-line string
        if (hasOtherContent(yaml, lineStart) && indent.length != PropsYaml.baseIndent(yaml, offset)) return null
        val prefix = match.groupValues[2]
        val existing = PropsYaml.keys(yaml).filter { offset !in it.start..it.end }.mapTo(HashSet()) { ComponentNaming.looseKey(it.name) }
        return CompletionSite.PropsKey(offset - prefix.length, prefix, name, existing, indent)
    }

    /** True when [yaml] has a non-blank, non-comment line other than the one starting at [lineStart]. */
    private fun hasOtherContent(yaml: CharSequence, lineStart: Int): Boolean {
        var start = 0
        for (line in yaml.lines()) {
            if (start != lineStart && line.isNotBlank() && !line.trimStart().startsWith("#")) return true
            start += line.length + 1
        }
        return false
    }

    private fun CompletionSite.PropsKey.shifted(by: Int) = CompletionSite.PropsKey(prefixStart + by, prefix, componentName, existing, indent)

    private fun slot(file: ComarkFile, text: CharSequence, offset: Int, lineEnd: Int, contentStart: Int, prefix: String): CompletionSite? {
        if (!NAME_REST.matches(text.subSequence(offset, lineEnd))) return null
        val element = file.findElementAt(contentStart) ?: return null
        val component = PsiTreeUtil.getParentOfType(element, ComarkBlockComponent::class.java) ?: return null
        if (element.parents(withSelf = true).takeWhile { it != component }.any { it.elementType in NOT_SLOT_LEVEL }) return null
        val name = component.componentName ?: return null
        val existing = component.slots.filter { it.textRange.startOffset != contentStart }.mapNotNullTo(HashSet()) { it.slotName }
        return CompletionSite.Slot(offset - prefix.length, prefix, name, existing)
    }

    /** Inside the `{...}` of a block component opener line. */
    private fun blockAttribute(file: ComarkFile, text: CharSequence, offset: Int, contentStart: Int, lineEnd: Int): CompletionSite? {
        val opener = ComarkSyntax.parseOpener(text, contentStart, lineEnd) ?: return null
        val attributes = opener.attributes ?: return null
        // the parser must agree that this line opens a component (not, say, a line of a paragraph inside a list)
        if (file.findElementAt(contentStart)?.elementType != ComarkTypes.COMPONENT_MARKER) return null
        return attributeSite(text, offset, attributes, text.subSequence(opener.nameStart, opener.nameEnd).toString())
    }

    /** Inside the `{...}` after an inline component. */
    private fun inlineAttribute(text: CharSequence, offset: Int, lineStart: Int, lineEnd: Int): CompletionSite? {
        for (component in InlineComponentSyntax.findAll(text, lineStart, lineEnd)) {
            val attributes = component.attributes ?: continue
            attributeSite(text, offset, attributes, component.name(text))?.let { return it }
        }
        return null
    }

    private fun attributeSite(text: CharSequence, offset: Int, scan: ComarkSyntax.AttrScan, componentName: String): CompletionSite? {
        val open = scan.tokens.first()
        val close = scan.tokens.lastOrNull()?.takeIf { it.part == ComarkSyntax.AttrPart.RBRACE }
        if (offset <= open.start) return null
        if (close != null && offset > close.start) return null
        if (close == null && offset > scan.end) return null
        // the token the caret is in or right after: a name being typed, or a value / class / id where props don't go
        val token = scan.tokens.firstOrNull { it.start < offset && offset <= it.end && it.part != ComarkSyntax.AttrPart.LBRACE }
        val prefixStart = when (token?.part) {
            null -> offset
            ComarkSyntax.AttrPart.NAME -> token.start
            else -> return null
        }
        val existing = scan.tokens.filter { it.part == ComarkSyntax.AttrPart.NAME && it !== token }
            .mapTo(HashSet()) { ComponentNaming.looseKey(text.subSequence(it.start, it.end).toString().removePrefix(":")) }
        return CompletionSite.Attribute(prefixStart, text.subSequence(prefixStart, offset).toString(), componentName, existing)
    }

    /** `:na|` where an inline component may start (comark's previous-character rule). */
    private fun inlineName(text: CharSequence, offset: Int, lineStart: Int): CompletionSite? {
        var nameStart = offset
        while (nameStart > lineStart && ComarkSyntax.isNamePart(text[nameStart - 1])) nameStart--
        val colon = nameStart - 1
        if (colon < lineStart || text[colon] != ':') return null
        if (!InlineComponentSyntax.isAllowedBefore(text, colon, lineStart)) return null
        if (nameStart < offset && !ComarkSyntax.isNameStart(text[nameStart])) return null
        return CompletionSite.InlineName(nameStart, text.subSequence(nameStart, offset).toString())
    }
}
