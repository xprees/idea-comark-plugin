package cz.xprees.comark.editor.yaml

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import cz.xprees.comark.psi.ComarkPropsBlock
import cz.xprees.comark.psi.ComarkPropsContent
import org.jetbrains.yaml.YAMLLanguage

/**
 * Injects YAML into component props blocks (the text between the `---` fences right after an opener), which gives them YAML
 * highlighting, error checking and editing support.
 *
 * Props of a nested component are indented along with the component; comark strips that indentation before parsing the YAML, so the
 * injection leaves it out too: each line becomes its own injected range starting after the fence's indentation. The document front
 * matter and ```` ```yaml [props] ```` fences are injected by the Markdown plugin itself.
 *
 * Registered only when the YAML plugin is installed (`comark-yaml.xml`).
 */
class ComarkPropsYamlInjector : MultiHostInjector {
    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? ComarkPropsContent ?: return
        if (!host.isValidHost || host.textLength == 0) return
        val ranges = lineRanges(host.text, fenceIndent(host))
        if (ranges.isEmpty()) return
        registrar.startInjecting(YAMLLanguage.INSTANCE)
        for (range in ranges) registrar.addPlace(null, null, host, range)
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(ComarkPropsContent::class.java)

    internal companion object {
        /** Number of whitespace characters before the opening `---` on its line. */
        fun fenceIndent(host: ComarkPropsContent): Int {
            val fence = (host.parent as? ComarkPropsBlock)?.firstChild ?: return 0
            val text = host.containingFile.viewProvider.contents
            var i = fence.textRange.startOffset
            var indent = 0
            while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) {
                i--
                indent++
            }
            return indent
        }

        /**
         * Ranges of [text] (the props YAML) to inject: everything when [indent] is 0, otherwise one range per line, each starting after
         * up to [indent] leading whitespace characters and including the line break.
         */
        fun lineRanges(text: CharSequence, indent: Int): List<TextRange> {
            if (text.isEmpty()) return emptyList()
            if (indent <= 0) return listOf(TextRange(0, text.length))
            val ranges = ArrayList<TextRange>()
            var start = 0
            while (start < text.length) {
                var end = start
                while (end < text.length && text[end] != '\n') end++
                val next = if (end < text.length) end + 1 else end
                var contentStart = start
                while (contentStart < end && contentStart - start < indent && (text[contentStart] == ' ' || text[contentStart] == '\t')) contentStart++
                if (next > contentStart) ranges += TextRange(contentStart, next)
                start = next
            }
            return ranges
        }
    }
}
