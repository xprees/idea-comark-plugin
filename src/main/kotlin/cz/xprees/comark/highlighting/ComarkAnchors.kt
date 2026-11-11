package cz.xprees.comark.highlighting

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
import cz.xprees.comark.navigation.ComarkContentRoutes
import cz.xprees.comark.parser.ComarkInlineScanner
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import java.util.Locale

/**
 * Link anchors a Comark document defines beyond the Markdown plugin's header anchors.
 *
 * Comark gives an element the id written in its attributes (`# Docs {#docs}`, `[text]{#ref}`, `::alert{#note}`), and a heading
 * with trailing attributes gets its automatic id from the text before them (`# Intro {.big}` is `#intro`). The Markdown plugin
 * computes header anchors from the whole heading text, so links to these anchors would look unresolved. Every heading also gets the
 * id comark and Nuxt Content generate for it: its text content (inline components and spans reduced to their content), slugified
 * with comark's rules (`## Install :badge[New]` is `#install-new`, `## 1. Setup` is `#_1-setup`).
 */
object ComarkAnchors {
    fun anchors(file: ComarkFile): Set<String> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(compute(file), file)
    }

    private fun compute(file: ComarkFile): Set<String> {
        val result = HashSet<String>()
        val headingSlugs = HashMap<String, Int>()
        for (element in SyntaxTraverser.psiTraverser(file)) {
            ProgressManager.checkCanceled()
            when {
                element.elementType == ComarkTypes.ATTR_ID -> idOf(element.text)?.let(result::add)
                ComarkInlineSyntax.isInlineHolder(element) -> collectInline(element, result, headingSlugs)
            }
        }
        return result
    }

    private fun collectInline(holder: PsiElement, into: MutableSet<String>, headingSlugs: MutableMap<String, Int>) {
        val text = holder.text
        val scan = ComarkInlineSyntax.scan(holder)
        for (token in scan.tokens) {
            if (token.kind == ComarkInlineScanner.Kind.ATTR_ID) idOf(text.substring(token.start, token.end))?.let(into::add)
        }
        val heading = holder.elementType == MarkdownTokenTypes.ATX_CONTENT || holder.elementType == MarkdownTokenTypes.SETEXT_CONTENT
        if (!heading) return
        val trailing = scan.elements.lastOrNull() as? ComarkInlineScanner.Attributes
        if (trailing?.target == ComarkInlineScanner.AttributeTarget.BLOCK) slug(text.substring(0, trailing.start)).takeIf { it.isNotEmpty() }?.let(into::add)
        // The id comark and Nuxt Content give the heading: its text content, slugified, with a counter for repeated slugs
        val comarkSlug = comarkSlug(textContent(text, scan))
        if (comarkSlug.isNotEmpty()) {
            val count = headingSlugs.getOrDefault(comarkSlug, 0)
            headingSlugs[comarkSlug] = count + 1
            into += if (count == 0) comarkSlug else "$comarkSlug-$count"
        }
    }

    /**
     * Approximate text content of inline Markdown [text] as comark renders it: inline components and spans reduced to their
     * content, attribute groups and bindings dropped, link destinations dropped.
     */
    private fun textContent(text: String, scan: ComarkInlineScanner.Result): String {
        val removed = BooleanArray(text.length)
        fun remove(from: Int, to: Int) {
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(text.length)) removed[i] = true
        }
        for (element in scan.elements) {
            when (element) {
                is ComarkInlineScanner.Attributes, is ComarkInlineScanner.Binding -> remove(element.start, element.end)
                is ComarkInlineScanner.InlineComponent -> if (element.hasContent) {
                    remove(element.start, element.contentStart)
                    remove(element.contentEnd, element.end)
                } else {
                    remove(element.start, element.end)
                }

                is ComarkInlineScanner.Span -> {
                    remove(element.start, element.contentStart)
                    remove(element.contentEnd, element.end)
                }
            }
        }
        val kept = StringBuilder(text.length)
        for (i in text.indices) if (!removed[i]) kept.append(text[i])
        return LINK_DESTINATION.replace(kept, "]")
    }

    private val LINK_DESTINATION = Regex("""]\([^)]*\)""")
    private val NOT_ID_CHARACTERS = Regex("""[^\p{L}\p{M}\p{Nd}\p{Nl}_-]+""")
    private val HYPHEN_RUNS = Regex("-{2,}")
    private val LEADING_MARKS_AND_HYPHENS = Regex("""^(?:\p{M}+|-+)+|-+$""")

    /** comark's `slugify` (internal/heading-id.ts): the id of a heading with text content [text]. */
    fun comarkSlug(text: String): String {
        var slug = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT).trim()
            .replace(Regex("\\s+"), "-")
            .replace(NOT_ID_CHARACTERS, "")
            .replace(HYPHEN_RUNS, "-")
            .replace(LEADING_MARKS_AND_HYPHENS, "")
        if (slug.isNotEmpty() && slug[0] in '0'..'9') slug = "_$slug"
        return slug
    }

    private fun idOf(token: String): String? = token.removePrefix("#").takeIf { it.isNotEmpty() }

    /** Header anchor of [text] the way the Markdown plugin builds it: lower case, letters, digits, `-` and `_`, blanks as `-`. */
    fun slug(text: String): String {
        val builder = StringBuilder()
        var blank = false
        for (c in text.trim().lowercase(Locale.ROOT)) {
            when {
                c.isWhitespace() -> blank = true
                c.isLetterOrDigit() || c == '-' || c == '_' -> {
                    if (blank && builder.isNotEmpty()) builder.append('-')
                    blank = false
                    builder.append(c)
                }
            }
        }
        return builder.toString()
    }

    /**
     * True when the link destination [destination] (`#anchor`, `path#anchor` resolved against [context]'s file, or a Nuxt Content
     * route `/page#anchor`, see [ComarkContentRoutes]) points at an anchor that a Comark document defines.
     */
    fun isComarkAnchor(context: PsiElement, destination: String): Boolean {
        val hash = destination.indexOf('#')
        if (hash < 0 || hash == destination.length - 1) return false
        val anchor = destination.substring(hash + 1)
        val path = destination.substring(0, hash)
        val contextFile = context.containingFile?.originalFile ?: return false
        val route = ComarkContentRoutes.parseRouteLink(destination)
        val target = if (path.isEmpty()) {
            contextFile
        } else if (route != null) {
            ComarkContentRoutes.resolve(contextFile, route.path)?.let { PsiManager.getInstance(context.project).findFile(it) }
        } else {
            val directory = contextFile.virtualFile?.parent ?: return false
            val file = directory.findFileByRelativePath(path) ?: return false
            PsiManager.getInstance(context.project).findFile(file)
        }
        return target is ComarkFile && anchor in anchors(target)
    }
}
