package cz.xprees.comark.editor

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkComponentHeader
import org.intellij.plugins.markdown.lang.MarkdownTokenTypeSets

/**
 * The opener (`::name`) / closer (`::`) pair of a block component, as the pair matching features see it.
 *
 * Matching is done on the PSI rather than on lexer tokens: whether a `::` line closes a component depends on nesting, fences and the
 * colon count of the opener, which the parser has already worked out, and the editor highlighter of a Markdown-type file does not
 * necessarily produce Comark tokens at all.
 */
internal object ComarkComponentPairs {
    /**
     * The component whose opener marker / name or closing marker [element] is, or which the caret touches from the right: the
     * element at the caret right after `::` or `::name` is the line break or whitespace that follows, so a blank leaf directly after
     * a name or closing marker counts too (VS Code highlights the pair while the caret is anywhere from the first colon to the end
     * of the name).
     */
    fun componentForMarker(element: PsiElement?): ComarkBlockComponent? {
        var marker = element ?: return null
        if (marker.isBlankLeaf()) {
            marker = PsiTreeUtil.prevLeaf(marker) ?: return null
            if (marker.elementType != ComarkTypes.COMPONENT_NAME && marker.elementType != ComarkTypes.COMPONENT_CLOSE) return null
        }
        return when (marker.elementType) {
            ComarkTypes.COMPONENT_MARKER, ComarkTypes.COMPONENT_NAME -> (marker.parent as? ComarkComponentHeader)?.parent as? ComarkBlockComponent
            ComarkTypes.COMPONENT_CLOSE -> marker.parent as? ComarkBlockComponent
            else -> null
        }
    }

    /** `::name` of the opener line: from the first colon to the end of the name. */
    fun openerRange(component: ComarkBlockComponent): TextRange? {
        val header = component.header ?: return null
        val marker = header.markerElement ?: return null
        val name = header.nameElement ?: return null
        return TextRange(marker.textRange.startOffset, name.textRange.endOffset)
    }

    /** The opener and the closing marker, or null for a component that is never closed (there is nothing to pair it with). */
    fun markerRanges(component: ComarkBlockComponent): List<TextRange>? {
        val close = component.closeMarker ?: return null
        val opener = openerRange(component) ?: return null
        return listOf(opener, close.textRange)
    }

    /** Whole block: from the opener to the end of the closing marker (or the last non-blank character of an unclosed component). */
    fun blockRange(component: ComarkBlockComponent): TextRange {
        val start = component.textRange.startOffset
        val end = component.closeMarker?.textRange?.endOffset ?: trimmedEnd(component)
        return TextRange(start, maxOf(start, end))
    }

    /** End offset of [element] without its trailing whitespace. */
    fun trimmedEnd(element: PsiElement): Int {
        val range = element.textRange
        val text = element.containingFile.viewProvider.contents
        var end = minOf(range.endOffset, text.length)
        while (end > range.startOffset && text[end - 1].isWhitespace()) end--
        return end
    }
}

/** Whitespace or a line break, which in a Comark file are Markdown's `WHITE_SPACE` / `EOL` tokens. */
internal fun PsiElement.isBlankLeaf(): Boolean =
    this is PsiWhiteSpace || (firstChild == null && elementType in MarkdownTokenTypeSets.WHITE_SPACES)
