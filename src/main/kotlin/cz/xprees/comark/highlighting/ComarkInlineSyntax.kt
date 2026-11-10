package cz.xprees.comark.highlighting

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
import cz.xprees.comark.parser.ComarkInlineScanner
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

/**
 * Connects [ComarkInlineScanner] to the PSI of Comark files.
 *
 * Inline Comark syntax is not part of the Comark PSI: paragraphs, headings and table cells are regular Markdown elements (the
 * Markdown plugin parses their inline content on demand), and for it `:badge[New]{color="blue"}` is just text and a reference
 * link. This object finds the elements that hold inline content, scans their text once and caches the result on the element,
 * so the annotator, the inspection suppressor, the highlight filter and the spellchecker all agree on what is Comark syntax.
 */
object ComarkInlineSyntax {
    /** Elements whose text is one inline content. */
    private val HOLDERS = TokenSet.create(
        MarkdownElementTypes.PARAGRAPH,
        MarkdownTokenTypes.ATX_CONTENT,
        MarkdownTokenTypes.SETEXT_CONTENT,
        MarkdownElementTypes.TABLE_CELL,
        ComarkTypes.INLINE_CONTENT,
    )

    /**
     * Markdown constructs the scanner must skip as a whole. Bare URLs (`GFM_AUTOLINK`) are left to the scanner: the Markdown
     * parser sometimes stretches them over following text.
     */
    private val OPAQUE = TokenSet.create(
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.AUTOLINK,
        MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.INLINE_MATH,
        MarkdownTokenTypes.HTML_TAG,
        MarkdownTokenTypes.EMAIL_AUTOLINK,
    )

    fun isInlineHolder(element: PsiElement): Boolean = element.elementType in HOLDERS

    /** The inline holder containing [element] (or [element] itself), when it lives in a Comark file. */
    fun holderOf(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            if (isInlineHolder(current)) return current.takeIf { it.containingFile is ComarkFile }
            current = current.parent
        }
        return null
    }

    /** Inline syntax of [holder], offsets relative to the holder's start. Cached until the file changes. */
    fun scan(holder: PsiElement): ComarkInlineScanner.Result = CachedValuesManager.getCachedValue(holder) {
        CachedValueProvider.Result.create(compute(holder), holder)
    }

    private fun compute(holder: PsiElement): ComarkInlineScanner.Result {
        val base = holder.textRange.startOffset
        val opaque = ArrayList<IntRange>()
        if (holder.firstChild != null) {
            for (element in SyntaxTraverser.psiTraverser(holder).expand { it.elementType !in OPAQUE }) {
                ProgressManager.checkCanceled()
                if (element !== holder && element.elementType in OPAQUE) {
                    val range = element.textRange
                    if (!range.isEmpty) opaque += (range.startOffset - base) until (range.endOffset - base)
                }
            }
        }
        return ComarkInlineScanner.scan(holder.text, opaque = opaque)
    }

    /** Absolute text ranges of every span and component `[content]` (brackets included) in [holder]. */
    fun bracketRanges(holder: PsiElement): List<TextRange> {
        val base = holder.textRange.startOffset
        return scan(holder).elements.mapNotNull { element ->
            when (element) {
                is ComarkInlineScanner.Span -> TextRange(base + element.start, base + element.contentEnd + 1)
                is ComarkInlineScanner.InlineComponent ->
                    if (element.hasContent) TextRange(base + element.contentStart - 1, base + element.contentEnd + 1) else null

                else -> null
            }
        }
    }

    /**
     * True when [range] (absolute) lies entirely within spans or component contents of the inline content at its start, which
     * is how `[text]` that the Markdown parser takes for a reference link is told apart from real links.
     */
    fun isCoveredBySpans(file: PsiFile, range: TextRange): Boolean {
        if (file !is ComarkFile || range.isEmpty) return false
        val element = file.findElementAt(range.startOffset) ?: return false
        val holder = holderOf(element) ?: return false
        if (!holder.textRange.contains(range)) return false
        var covered = range.startOffset
        for (bracket in bracketRanges(holder).sortedBy { it.startOffset }) {
            if (bracket.startOffset > covered) break
            if (bracket.endOffset > covered) covered = bracket.endOffset
            if (covered >= range.endOffset) return true
        }
        return false
    }
}
