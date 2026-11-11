package cz.xprees.comark.highlighting

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.highlighting.MarkdownHighlighterColors

/**
 * Drops the Markdown plugin's link coloring of spans and component content.
 *
 * The Markdown parser reads `[text]` as a reference link (and `:name[content][more]` as a full reference link), and the Markdown
 * annotator colors it as a link label. In Comark a bare `[text]` is a span and `[content]` after `:name` is the component's
 * content, so that coloring would be misleading; Comark's own span / content highlighting takes its place.
 */
class ComarkSpanHighlightInfoFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        val key = highlightInfo.forcedTextAttributesKey ?: return true
        if (key != MarkdownHighlighterColors.LINK_LABEL && key != MarkdownHighlighterColors.REFERENCE_LINK && key != MarkdownHighlighterColors.LINK_TEXT) {
            return true
        }
        if (file !is ComarkFile) return true
        return !ComarkInlineSyntax.isCoveredBySpans(file, TextRange(highlightInfo.startOffset, highlightInfo.endOffset))
    }
}
