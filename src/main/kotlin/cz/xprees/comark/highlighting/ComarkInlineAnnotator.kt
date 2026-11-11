package cz.xprees.comark.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import cz.xprees.comark.parser.ComarkInlineScanner
import cz.xprees.comark.parser.ComarkInlineScanner.Kind
import cz.xprees.comark.psi.ComarkFile

/**
 * Highlights inline Comark syntax (inline components, spans, attribute groups, bindings) inside paragraphs, headings, table cells
 * and component `[content]`.
 *
 * The lexer can't do this: inline content is lexed by the Markdown inline lexer, which knows nothing about Comark. The elements
 * holding it are Markdown-language PSI, so this annotator is registered for the Markdown language (Comark being its dialect, it
 * also sees Comark's own `[content]` leaves) and does nothing outside Comark files.
 */
class ComarkInlineAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (!ComarkInlineSyntax.isInlineHolder(element)) return
        if (holder.currentAnnotationSession.file !is ComarkFile) return
        val base = element.textRange.startOffset
        for (token in ComarkInlineSyntax.scan(element).tokens) {
            val key = keyFor(token.kind) ?: continue
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(base + token.start, base + token.end))
                .textAttributes(key)
                .create()
        }
    }

    companion object {
        /** The key an inline token is highlighted with (null: left to Markdown). */
        fun keyFor(kind: ComarkInlineScanner.Kind): TextAttributesKey? = when (kind) {
            Kind.COMPONENT_MARKER -> ComarkHighlighterColors.COMPONENT_MARKER
            Kind.COMPONENT_NAME -> ComarkHighlighterColors.INLINE_COMPONENT
            Kind.CONTENT_OPEN, Kind.CONTENT_CLOSE, Kind.SPAN_OPEN, Kind.SPAN_CLOSE -> ComarkHighlighterColors.CONTENT_BRACKETS
            Kind.COMPONENT_CONTENT -> null
            Kind.SPAN_CONTENT -> ComarkHighlighterColors.SPAN
            Kind.ATTR_LBRACE, Kind.ATTR_RBRACE -> ComarkHighlighterColors.ATTRIBUTE_BRACES
            Kind.ATTR_CLASS -> ComarkHighlighterColors.ATTRIBUTE_CLASS
            Kind.ATTR_ID -> ComarkHighlighterColors.ATTRIBUTE_ID
            Kind.ATTR_NAME -> ComarkHighlighterColors.ATTRIBUTE_NAME
            Kind.ATTR_EQ -> ComarkHighlighterColors.ATTRIBUTE_EQ
            Kind.ATTR_VALUE -> ComarkHighlighterColors.ATTRIBUTE_VALUE
            Kind.BINDING_OPEN, Kind.BINDING_CLOSE -> ComarkHighlighterColors.BINDING_BRACES
            Kind.BINDING_EXPRESSION -> ComarkHighlighterColors.BINDING
        }
    }
}
