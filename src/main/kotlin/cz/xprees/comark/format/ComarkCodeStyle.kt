package cz.xprees.comark.format

import cz.xprees.comark.settings.ComarkSettings
import com.intellij.application.options.CodeStyle
import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import cz.xprees.comark.lang.ComarkLanguage

/**
 * Settings | Editor | Code Style | Comark: the indentation the Comark formatter and Enter use.
 *
 * Comark files keep the Markdown file type, so without this they used the Markdown code style's indent (4 by default), while the
 * VS Code extension's formatter (monarch-mdc) and Nuxt projects use 2. The default here is 2.
 *
 * The page offers the indent size only. The formatter always indents with spaces, like monarch-mdc, and has to: Markdown counts
 * a leading tab as four columns, so a nested component indented with a tab would turn into an indented code block. "Use tab
 * character" and the tab size would therefore have no effect, and are not shown.
 */
class ComarkCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = ComarkLanguage

    override fun getConfigurableDisplayName(): String = "Comark"

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = DEFAULT_INDENT
        indentOptions.CONTINUATION_INDENT_SIZE = DEFAULT_INDENT
        indentOptions.TAB_SIZE = DEFAULT_INDENT
        indentOptions.USE_TAB_CHARACTER = false
    }

    /** Asks [customizeSettings] which options to show. */
    override fun getIndentOptionsEditor(): IndentOptionsEditor = IndentOptionsEditor(this)

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.INDENT_SETTINGS) consumer.showStandardOptions(CodeStyleSettingsCustomizable.IndentOption.INDENT_SIZE.name)
    }

    override fun getCodeSample(settingsType: SettingsType): String = SAMPLE

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, configurableDisplayName) {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : TabbedLanguageCodeStylePanel(ComarkLanguage, currentSettings, settings) {
                    override fun initTabs(settings: CodeStyleSettings) {
                        addIndentOptionsTab(settings)
                    }
                }
        }

    companion object {
        const val DEFAULT_INDENT = 2

        private val SAMPLE = """
            ::card{title="Comark"}
            ---
            icon: i-lucide-box
            links:
              - label: Docs
                to: /docs
            ---
            Default slot content

            #footer
              :::badge{color="primary"}
              Nested component
              :::
            ::
        """.trimIndent()
    }
}

/** The indentation step of a Comark file for the formatter and Enter. */
internal object ComarkIndent {
    /**
     * EditorConfig or detected indentation of this file when present (they override language settings), otherwise the Comark code
     * style's indent size. The file-type based lookup alone would give the Markdown code style's, as Comark files are Markdown files.
     */
    fun indentSize(file: PsiFile): Int = forDialect(configuredIndentSize(file))

    private fun configuredIndentSize(file: PsiFile): Int {
        val fileOptions = CodeStyle.getIndentOptions(file)
        if (fileOptions.isOverrideLanguageOptions) return fileOptions.INDENT_SIZE.coerceAtLeast(0)
        val comark = CodeStyle.getSettings(file).getCommonSettings(ComarkLanguage).indentOptions
        return (comark?.INDENT_SIZE ?: fileOptions.INDENT_SIZE).coerceAtLeast(0)
    }

    /**
     * The step actually used: with the comark dialect a nested component four or more columns past its parent would be code, so
     * indent sizes of four and more fall back to 2. Nuxt Content (remark-mdc) accepts any indentation and keeps the configured size.
     */
    fun forDialect(size: Int): Int =
        if (size >= 4 && ComarkSettings.getInstance().dialect == ComarkSettings.Dialect.COMARK) 2 else size
}
