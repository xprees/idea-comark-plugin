@file:OptIn(org.intellij.markdown.ExperimentalApi::class)

package cz.xprees.comark.preview

import com.intellij.openapi.progress.ProgressManager
import cz.xprees.comark.lang.ComarkFlavour
import cz.xprees.comark.parser.ComarkSyntax
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/**
 * Rewrites the inline Comark syntax of a Markdown snippet into inline HTML before the snippet is rendered for the preview:
 *
 * - inline components `:name`, `:name[content]`, `:name[content]{attrs}`, `:name{attrs}` become
 *   `<span class="comark-inline-component">`, whose content stays Markdown (inline HTML doesn't stop inline parsing);
 * - spans `[text]{attrs}` become `<span class="comark-span">`;
 * - attribute groups that style the preceding element (`**bold**{.red}`, `[link](url){target=_blank}`) or the whole
 *   paragraph / heading (`{...}` ending a line) are dropped, as they only affect the rendered page.
 *
 * Only text that Markdown treats as inline content is rewritten: code spans, inline HTML, autolinks, link destinations and every
 * non-paragraph block (code, HTML blocks, link definitions, front matter) are left alone, which is decided from an
 * intellij-markdown parse of the snippet. The rules mirror comark's `comark_inline_component`, `comark_inline_span` and
 * `comark_inline_props` (components.ts, attributes.ts). This is preview-only: the editor's inline parsing is untouched.
 */
internal class InlineComarkRewriter(private val text: String) {
    /** The rewritten snippet and the mapping from its offsets to the offsets of the original snippet. */
    class Result(val text: String, val mapping: OffsetMapping)

    private val out = StringBuilder(text.length + 64)
    private val mapping = OffsetMapping()

    /** Characters that may be rewritten: inside an inline content holder and outside code, HTML and link targets. */
    private val inline = BooleanArray(text.length)

    fun rewrite(): Result {
        if (!mayContainSyntax()) return Result(text, OffsetMapping().apply { copy(0, text.length) })
        markInlineContent(MarkdownParser(ComarkFlavour.INSTANCE, true, CancellationToken.NonCancellable).buildMarkdownTreeFromString(text), inHolder = false)
        val closers = ArrayDeque<Closer>()
        var copiedFrom = 0
        var i = 0

        fun flush(until: Int) {
            if (until > copiedFrom) {
                out.append(text, copiedFrom, until)
                mapping.copy(copiedFrom, until - copiedFrom)
            }
        }

        fun emit(html: String, anchor: Int) {
            out.append(html)
            mapping.generated(anchor, html.length)
        }

        while (i < text.length) {
            ProgressManager.checkCanceled()
            val closer = closers.lastOrNull()
            if (closer != null && i == closer.at) {
                flush(i)
                emit(closer.html, i)
                closers.removeLast()
                i = closer.resumeAt
                copiedFrom = i
                continue
            }
            if (!inline[i]) {
                i++
                continue
            }
            val limit = closer?.at ?: lineEnd(i)
            val match = when (text[i]) {
                ':' -> inlineComponent(i, limit)
                '[' -> span(i, limit)
                '{' -> droppedAttributes(i, limit)
                else -> null
            }
            if (match == null) {
                i++
                continue
            }
            flush(i)
            emit(match.openHtml, i)
            if (match.closer != null) closers.addLast(match.closer)
            i = match.continueAt
            copiedFrom = i
        }
        flush(text.length)
        // a closer left open (its content ran past the snippet) still needs its tag
        while (closers.isNotEmpty()) emit(closers.removeLast().html, text.length)
        return Result(out.toString(), mapping)
    }

    private class Closer(val at: Int, val resumeAt: Int, val html: String)

    /** [openHtml] replaces the text up to [continueAt]; [closer] closes the element later. */
    private class Match(val openHtml: String, val continueAt: Int, val closer: Closer? = null)

    private fun mayContainSyntax(): Boolean = text.indexOf(':') >= 0 || text.indexOf('{') >= 0

    private fun lineEnd(from: Int): Int {
        val end = text.indexOf('\n', from)
        return if (end < 0) text.length else end
    }

    private fun allInline(from: Int, to: Int): Boolean {
        for (k in from until to) if (!inline[k] && text[k] != '\n') return false
        return true
    }

    // ---- Syntax ---------------------------------------------------------------------------------------------------

    private fun inlineComponent(start: Int, limit: Int): Match? {
        if (start > 0 && text[start - 1] !in ALLOWED_BEFORE_COMPONENT) return null
        val nameLength = ComarkSyntax.inlineNameLength(text, start + 1, limit)
        if (nameLength == 0) return null
        val nameEnd = start + 1 + nameLength
        // `:smile:` is an emoji short code, not a component
        if (nameEnd < limit && text[nameEnd] == ':') return null
        val name = text.substring(start + 1, nameEnd)
        var contentClose = -1
        var after = nameEnd
        if (after < limit && text[after] == '[') {
            contentClose = ComarkSyntax.findClosingBracket(text, after, limit)
            if (contentClose >= 0) after = contentClose + 1
        }
        val attributes = attributesAt(after, limit)
        val end = attributes?.end ?: after
        if (!allInline(start, end)) return null
        val title = escape(":$name" + (attributes?.let { text.substring(after, it.end) } ?: ""))
        val open = """<span class="comark-inline-component" data-component="${escape(name)}" title="$title">"""
        return if (contentClose >= 0) {
            Match(open, nameEnd + 1, Closer(contentClose, end, "</span>"))
        } else {
            Match("$open${escape(name)}</span>", end)
        }
    }

    private fun span(start: Int, limit: Int): Match? {
        val close = ComarkSyntax.findClosingBracket(text, start, limit)
        if (close < 0) return null
        val attributes = attributesAt(close + 1, limit) ?: return null
        if (!allInline(start, attributes.end)) return null
        val title = escape(text.substring(close + 1, attributes.end))
        return Match("""<span class="comark-span" title="$title">""", start + 1, Closer(close, attributes.end, "</span>"))
    }

    /** `{attrs}` right after an emphasis / code / link closer, or ending the line: removed. */
    private fun droppedAttributes(start: Int, limit: Int): Match? {
        if (start > 0 && text[start - 1] == '$') return null
        val attributes = attributesAt(start, limit) ?: return null
        val afterCloser = start > 0 && text[start - 1] in CLOSERS
        val endsLine = ComarkSyntax.isBlank(text, attributes.end, lineEnd(attributes.end))
        if (!afterCloser && !endsLine) return null
        if (!allInline(start, attributes.end)) return null
        return Match("", attributes.end)
    }

    private fun attributesAt(index: Int, limit: Int): ComarkSyntax.AttrScan? {
        if (index >= limit || text[index] != '{') return null
        val scan = ComarkSyntax.scanAttributes(text, index, limit) ?: return null
        return scan.takeIf { it.closed }
    }

    // ---- Where inline syntax may be ---------------------------------------------------------------------------------

    private fun markInlineContent(node: ASTNode, inHolder: Boolean) {
        val type = node.type
        val holder = inHolder || type in HOLDERS
        if (type in OPAQUE) return
        if (holder && node.children.isEmpty()) {
            for (k in node.startOffset until minOf(node.endOffset, inline.size)) inline[k] = true
            return
        }
        for (child in node.children) markInlineContent(child, holder)
    }

    companion object {
        /** comark's `ALLOWED_PREV_CHARS` for inline components. */
        private val ALLOWED_BEFORE_COMPONENT = setOf(' ', '\t', '\n', '*', '_', '[', ']', '}', ')', '>')

        /** Characters ending an inline element that a following `{attrs}` group applies to. */
        private val CLOSERS = setOf('*', '_', '`', ')', '~', ']')

        private val HOLDERS: Set<IElementType> = setOf(
            MarkdownElementTypes.PARAGRAPH,
            MarkdownTokenTypes.ATX_CONTENT,
            MarkdownTokenTypes.SETEXT_CONTENT,
            GFMTokenTypes.CELL,
        )

        private val OPAQUE: Set<IElementType> = setOf(
            MarkdownElementTypes.CODE_SPAN,
            MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.HTML_BLOCK,
            MarkdownElementTypes.AUTOLINK,
            MarkdownElementTypes.LINK_DESTINATION,
            MarkdownElementTypes.LINK_TITLE,
            MarkdownElementTypes.LINK_DEFINITION,
            MarkdownTokenTypes.HTML_TAG,
            GFMTokenTypes.GFM_AUTOLINK,
            GFMElementTypes.INLINE_MATH,
            GFMElementTypes.BLOCK_MATH,
        )

        fun escape(value: String): String = buildString(value.length) {
            for (c in value) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }
    }
}
