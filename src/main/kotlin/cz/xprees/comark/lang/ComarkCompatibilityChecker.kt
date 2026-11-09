package cz.xprees.comark.lang

import com.intellij.lang.Language
import org.intellij.plugins.markdown.lang.MarkdownCompatibilityChecker

/** Tells the Markdown plugin that Comark files support its Markdown editing features (tables, links, ...). */
class ComarkCompatibilityChecker : MarkdownCompatibilityChecker {
    override fun isSupportedLanguage(language: Language): Boolean = language == ComarkLanguage
}
