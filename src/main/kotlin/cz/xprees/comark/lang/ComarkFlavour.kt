package cz.xprees.comark.lang

import org.intellij.plugins.markdown.lang.parser.MarkdownDefaultFlavour

/**
 * Markdown flavour of Comark files. Block structure is parsed by [cz.xprees.comark.parser.ComarkTreeBuilder]; this flavour parses
 * the Markdown between it (and paragraph content on demand), the same way the Markdown plugin does for plain Markdown.
 *
 * The Markdown preview doesn't consult a file's flavour (it renders with the Markdown plugin's default flavour), so components are
 * rendered for the preview by [cz.xprees.comark.preview.ComarkPreviewPanelProvider], not through this flavour's HTML providers.
 * Keeping the flavour's parsing identical to the default one is deliberate: the editor parses paragraph content lazily with it.
 */
class ComarkFlavour : MarkdownDefaultFlavour() {
    companion object {
        @JvmField
        val INSTANCE = ComarkFlavour()
    }
}
