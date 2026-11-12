package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings

/**
 * The Comark part of a code style scheme (*Settings › Editor › Code Style › Comark › Style*): which style Reformat Code writes,
 * and the options of the [ComarkStylePreset.CUSTOM] style.
 *
 * Stored in the scheme XML (and so in `.idea/codeStyles` for project schemes) under `ComarkCodeStyleSettings`, and exposed to
 * EditorConfig as `ij_comark_<field>` (lower case), e.g. `ij_comark_style_preset = nuxt_studio`; enum values are written in lower
 * case. The platform finds the fields by reflection: they must stay public, mutable and named in upper case.
 *
 * The options only apply to the custom style: [ComarkStylePreset.NUXT_STUDIO] always means exactly what Nuxt Studio writes, and
 * [ComarkStylePreset.VSCODE] is the VS Code extension's formatter, which has none of these options ([ComarkStyle.of] resolves the
 * effective style of a file). Picking a preset in the settings page writes the preset's values into the options, so switching to
 * *Custom* starts from them.
 */
class ComarkCodeStyleSettings(container: CodeStyleSettings) : CustomCodeStyleSettings(TAG, container) {
    /** Which style Reformat Code writes. */
    @JvmField
    var STYLE_PRESET: ComarkStylePreset = ComarkStylePreset.VSCODE

    /** Bullet list marker. */
    @JvmField
    var BULLET_MARKER: BulletMarker = BulletMarker.DASH

    /** Ordered list delimiter (`1.` or `1)`). */
    @JvmField
    var ORDERED_DELIMITER: OrderedDelimiter = OrderedDelimiter.DOT

    /** Emphasis delimiter (`*text*` or `_text_`). */
    @JvmField
    var EMPHASIS_MARKER: EmphasisMarker = EmphasisMarker.ASTERISK

    /** Strong emphasis delimiter (`**text**` or `__text__`). */
    @JvmField
    var STRONG_MARKER: EmphasisMarker = EmphasisMarker.ASTERISK

    /** Code fence character. */
    @JvmField
    var FENCE_MARKER: FenceMarker = FenceMarker.BACKTICK

    /** Blank lines kept between top-level blocks: the source's count, at least one and at most this many. */
    @JvmField
    var MAX_BLANK_LINES: Int = 1

    /** A block component with more props than this writes them as a YAML block below its opener, otherwise inline `{...}`. */
    @JvmField
    var PROPS_INLINE_MAX: Int = STUDIO_PROPS_INLINE_MAX

    /**
     * Order component props as Nuxt Studio's database path does (by how often each name occurs on the same tag in the document);
     * off keeps the order of the source.
     */
    @JvmField
    var SORT_PROPS: Boolean = true

    /** What happens to the front matter. */
    @JvmField
    var FRONT_MATTER: FrontMatterMode = FrontMatterMode.STUDIO

    /** Writes the option values of [preset] (and selects it). [ComarkStylePreset.CUSTOM] only selects it, keeping the options. */
    fun applyPreset(preset: ComarkStylePreset) {
        STYLE_PRESET = preset
        if (preset == ComarkStylePreset.CUSTOM) return
        // VS Code: the options don't apply; they hold Nuxt Studio's values, a sensible start for a custom style
        BULLET_MARKER = BulletMarker.DASH
        ORDERED_DELIMITER = OrderedDelimiter.DOT
        EMPHASIS_MARKER = EmphasisMarker.ASTERISK
        STRONG_MARKER = EmphasisMarker.ASTERISK
        FENCE_MARKER = FenceMarker.BACKTICK
        MAX_BLANK_LINES = 1
        PROPS_INLINE_MAX = STUDIO_PROPS_INLINE_MAX
        SORT_PROPS = true
        FRONT_MATTER = FrontMatterMode.STUDIO
    }

    /** The options as the formatter takes them, with [indentSize] as the component nesting step. */
    fun toOptions(indentSize: Int): StudioStyleOptions = StudioStyleOptions(
        indentSize = indentSize,
        bulletMarker = BULLET_MARKER.char,
        orderedDelimiter = ORDERED_DELIMITER.char,
        emphasisMarker = EMPHASIS_MARKER.char,
        strongMarker = STRONG_MARKER.char,
        fenceMarker = FENCE_MARKER.char,
        maxBlankLines = MAX_BLANK_LINES.coerceIn(1, MAX_BLANK_LINES_LIMIT),
        propsInlineMax = PROPS_INLINE_MAX.coerceIn(0, PROPS_INLINE_MAX_LIMIT),
        sortProps = SORT_PROPS,
        frontMatter = FRONT_MATTER,
    )

    companion object {
        const val TAG = "ComarkCodeStyleSettings"

        /** comark's `maxInlineAttributes`: Studio writes up to three props inline. */
        const val STUDIO_PROPS_INLINE_MAX = 3
        const val PROPS_INLINE_MAX_LIMIT = 20
        const val MAX_BLANK_LINES_LIMIT = 10

        fun getInstance(settings: CodeStyleSettings): ComarkCodeStyleSettings = settings.getCustomSettings(ComarkCodeStyleSettings::class.java)
    }
}

/** The styles Reformat Code can write for Comark files. */
enum class ComarkStylePreset(val displayName: String) {
    /** The port of the VS Code extension's formatter (monarch-mdc): indentation and trailing whitespace only. */
    VSCODE("VS Code (monarch-mdc)"),

    /** What Nuxt Studio writes when it saves the document, except for content Studio would damage, which is kept. */
    NUXT_STUDIO("Nuxt Studio"),

    /** Nuxt Studio's style with the options of the Style tab. */
    CUSTOM("Custom"),
}

enum class BulletMarker(val char: Char) { DASH('-'), ASTERISK('*'), PLUS('+') }

enum class OrderedDelimiter(val char: Char) { DOT('.'), PARENTHESIS(')') }

enum class EmphasisMarker(val char: Char) { ASTERISK('*'), UNDERSCORE('_') }

enum class FenceMarker(val char: Char) { BACKTICK('`'), TILDE('~') }

enum class FrontMatterMode {
    /**
     * Nuxt Studio's front matter data rules for the file's collection: a missing title and description added for page
     * collections, keys in Nuxt Content's column order, `navigation: true`, nulls and empty lists dropped, js-yaml's dump.
     */
    STUDIO,

    /** The data kept as it is and only re-dumped by js-yaml, as Studio's code editor does. */
    REDUMP,

    /** The front matter is left exactly as written. */
    KEEP,
}

/**
 * The effective style of a Comark file: the preset of its code style (EditorConfig included) and, for the Studio based styles,
 * the options the formatter applies.
 */
data class ComarkStyle(val preset: ComarkStylePreset, val options: StudioStyleOptions) {
    /** True when Reformat Code writes the Studio based style ([StudioStyleAdapter]) instead of the VS Code one. */
    val usesStudio: Boolean get() = preset != ComarkStylePreset.VSCODE

    companion object {
        val VSCODE = ComarkStyle(ComarkStylePreset.VSCODE, StudioStyleOptions.STUDIO)

        /** Studio's nesting step: two spaces per component level. */
        const val STUDIO_INDENT = 2

        fun of(file: PsiFile): ComarkStyle = of(CodeStyle.getSettings(file)) { ComarkIndent.configuredIndentSize(file) }

        /** The style [settings] select; [indentSize] gives the configured indent size, used by the custom style. */
        fun of(settings: CodeStyleSettings, indentSize: () -> Int): ComarkStyle {
            val custom = ComarkCodeStyleSettings.getInstance(settings)
            return when (custom.STYLE_PRESET) {
                ComarkStylePreset.VSCODE -> VSCODE
                ComarkStylePreset.NUXT_STUDIO -> ComarkStyle(ComarkStylePreset.NUXT_STUDIO, StudioStyleOptions.STUDIO)
                ComarkStylePreset.CUSTOM -> ComarkStyle(
                    ComarkStylePreset.CUSTOM,
                    custom.toOptions(ComarkIndent.forDialect(indentSize()).takeIf { it > 0 } ?: STUDIO_INDENT),
                )
            }
        }
    }
}
