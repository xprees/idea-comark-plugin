package cz.xprees.comark.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import cz.xprees.comark.ComarkIcons
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import javax.swing.Icon

/**
 * Comark (Components in Markdown, formerly MDC) as a dialect of the bundled Markdown language.
 *
 * Being a dialect means every Markdown feature registered for the `Markdown` language (folding, structure view, inspections,
 * completion, ...) also applies to Comark files, and the files themselves stay `MarkdownFile`s with the Markdown file type,
 * so the Markdown preview and editor actions keep working. Comark only swaps in its own parser (see
 * [cz.xprees.comark.parser.ComarkParserDefinition]) that understands block components, props and slots.
 */
object ComarkLanguage : Language(MarkdownLanguage.INSTANCE, "Comark") {
    @Suppress("unused")
    private fun readResolve(): Any = ComarkLanguage

    override fun getDisplayName(): String = "Comark"

    override fun isCaseSensitive(): Boolean = true
}

/**
 * File type of the Comark language. It has no extensions of its own: `.md` and `.mdc` files keep the Markdown file type (so the
 * preview works) and are switched to Comark by [ComarkLanguageSubstitutor]. The platform needs a file type associated with a
 * substituted language to pick its syntax highlighter, which is what this is for.
 */
object ComarkFileType : LanguageFileType(ComarkLanguage) {
    override fun getName(): String = "Comark"

    override fun getDescription(): String = "Comark (Components in Markdown)"

    override fun getDefaultExtension(): String = "mdc"

    override fun getIcon(): Icon = ComarkIcons.FILE

    override fun getDisplayName(): String = "Comark"
}
