package cz.xprees.comark.highlighting

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
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
 * computes header anchors from the whole heading text, so links to these anchors would look unresolved.
 */
object ComarkAnchors {
    fun anchors(file: ComarkFile): Set<String> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(compute(file), file)
    }

    private fun compute(file: ComarkFile): Set<String> {
        val result = HashSet<String>()
        for (element in SyntaxTraverser.psiTraverser(file)) {
            ProgressManager.checkCanceled()
            when {
                element.elementType == ComarkTypes.ATTR_ID -> idOf(element.text)?.let(result::add)
                ComarkInlineSyntax.isInlineHolder(element) -> collectInline(element, result)
            }
        }
        return result
    }

    private fun collectInline(holder: PsiElement, into: MutableSet<String>) {
        val text = holder.text
        val scan = ComarkInlineSyntax.scan(holder)
        for (token in scan.tokens) {
            if (token.kind == ComarkInlineScanner.Kind.ATTR_ID) idOf(text.substring(token.start, token.end))?.let(into::add)
        }
        val heading = holder.elementType == MarkdownTokenTypes.ATX_CONTENT || holder.elementType == MarkdownTokenTypes.SETEXT_CONTENT
        if (!heading) return
        val trailing = scan.elements.lastOrNull() as? ComarkInlineScanner.Attributes ?: return
        if (trailing.target == ComarkInlineScanner.AttributeTarget.BLOCK) slug(text.substring(0, trailing.start)).takeIf { it.isNotEmpty() }?.let(into::add)
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
     * True when the link destination [destination] (`#anchor` or `path#anchor`, resolved against [context]'s file) points at an
     * anchor that a Comark document defines.
     */
    fun isComarkAnchor(context: PsiElement, destination: String): Boolean {
        val hash = destination.indexOf('#')
        if (hash < 0 || hash == destination.length - 1) return false
        val anchor = destination.substring(hash + 1)
        val path = destination.substring(0, hash)
        val contextFile = context.containingFile?.originalFile ?: return false
        val target = if (path.isEmpty()) {
            contextFile
        } else {
            val directory = contextFile.virtualFile?.parent ?: return false
            val file = directory.findFileByRelativePath(path) ?: return false
            PsiManager.getInstance(context.project).findFile(file)
        }
        return target is ComarkFile && anchor in anchors(target)
    }
}
