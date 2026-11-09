package cz.xprees.comark.lang

import org.intellij.plugins.markdown.lang.parser.MarkdownDefaultFlavour

/**
 * Markdown flavour of Comark files. Block structure is parsed by [cz.xprees.comark.parser.ComarkTreeBuilder]; this flavour parses
 * the Markdown between it (and paragraph content on demand), the same way the Markdown plugin does for plain Markdown.
 */
class ComarkFlavour : MarkdownDefaultFlavour() {
    companion object {
        @JvmField
        val INSTANCE = ComarkFlavour()
    }
}
