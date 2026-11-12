package cz.xprees.comark.format

import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.lang.ComarkFileType
import cz.xprees.comark.lang.ComarkLanguage
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * The *Style* tab of *Settings › Editor › Code Style › Comark*: the style preset and the options of the custom style
 * ([ComarkCodeStyleSettings]), with a preview that Reformat Code formats in the selected style.
 *
 * Picking a preset writes its option values into the controls; changing an option switches the preset to *Custom*. With the
 * VS Code preset the options are disabled, as the VS Code extension's formatter has none of them.
 */
internal class ComarkStylePanel(currentSettings: CodeStyleSettings?, settings: CodeStyleSettings) :
    CodeStyleAbstractPanel(ComarkLanguage, currentSettings, settings) {

    internal val preset = combo(ComarkStylePreset.entries) { it.displayName }
    internal val bullet = combo(BulletMarker.entries) { "${it.char} item" }
    private val ordered = combo(OrderedDelimiter.entries) { "1${it.char} item" }
    private val emphasis = combo(EmphasisMarker.entries) { "${it.char}text${it.char}" }
    private val strong = combo(EmphasisMarker.entries) { "${it.char}${it.char}text${it.char}${it.char}" }
    private val fence = combo(FenceMarker.entries) { it.char.toString().repeat(3) }
    private val blankLines = JSpinner(SpinnerNumberModel(1, 1, ComarkCodeStyleSettings.MAX_BLANK_LINES_LIMIT, 1))
    private val propsInline = JSpinner(SpinnerNumberModel(3, 0, ComarkCodeStyleSettings.PROPS_INLINE_MAX_LIMIT, 1))
    private val sortProps = JBCheckBox(ComarkBundle.message("codestyle.style.sort.props"))
    private val frontMatter = combo(FrontMatterMode.entries) {
        ComarkBundle.message(
            when (it) {
                FrontMatterMode.STUDIO -> "codestyle.style.front.matter.studio"
                FrontMatterMode.REDUMP -> "codestyle.style.front.matter.redump"
                FrontMatterMode.KEEP -> "codestyle.style.front.matter.keep"
            },
        )
    }
    internal val options: List<JComponent> = listOf(bullet, ordered, emphasis, strong, fence, blankLines, propsInline, sortProps, frontMatter)

    /** True while the controls are being filled, so that the change listeners don't switch the preset to Custom. */
    private var updating = false

    private val root = JPanel(BorderLayout())

    init {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(ComarkBundle.message("codestyle.style.preset"), preset)
            .addComponentToRightColumn(comment(ComarkBundle.message("codestyle.style.preset.comment")))
            .addSeparator()
            .addLabeledComponent(ComarkBundle.message("codestyle.style.bullet"), bullet)
            .addLabeledComponent(ComarkBundle.message("codestyle.style.ordered"), ordered)
            .addLabeledComponent(ComarkBundle.message("codestyle.style.emphasis"), emphasis)
            .addLabeledComponent(ComarkBundle.message("codestyle.style.strong"), strong)
            .addLabeledComponent(ComarkBundle.message("codestyle.style.fence"), fence)
            .addLabeledComponent(ComarkBundle.message("codestyle.style.blank.lines"), blankLines)
            .addLabeledComponent(ComarkBundle.message("codestyle.style.props.inline"), propsInline)
            .addComponentToRightColumn(comment(ComarkBundle.message("codestyle.style.props.inline.comment")))
            .addComponentToRightColumn(sortProps)
            .addLabeledComponent(ComarkBundle.message("codestyle.style.front.matter"), frontMatter)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        form.border = JBUI.Borders.empty(8)
        val preview = JPanel(BorderLayout())
        installPreviewPanel(preview)
        root.add(form, BorderLayout.WEST)
        root.add(preview, BorderLayout.CENTER)

        preset.addActionListener {
            if (updating) return@addActionListener
            val selected = preset.item ?: return@addActionListener
            if (selected != ComarkStylePreset.CUSTOM) {
                // a preset fills in its values
                val values = ComarkCodeStyleSettings(settings).apply { applyPreset(selected) }
                fill(values)
            }
            updateEnabled()
            somethingChanged()
        }
        val toCustom = {
            if (!updating) {
                updating = true
                preset.item = ComarkStylePreset.CUSTOM
                updating = false
                updateEnabled()
                somethingChanged()
            }
        }
        for (control in listOf(bullet, ordered, emphasis, strong, fence, frontMatter)) control.addActionListener { toCustom() }
        blankLines.addChangeListener { toCustom() }
        propsInline.addChangeListener { toCustom() }
        sortProps.addActionListener { toCustom() }
        updateEnabled()
    }

    private fun <T> combo(values: List<T>, text: (T) -> String): ComboBox<T> =
        ComboBox(CollectionComboBoxModel(values)).apply { renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = value?.let(text) ?: "" } }

    private fun comment(text: String) = JBLabel(text).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }

    private fun updateEnabled() {
        val enabled = preset.item != ComarkStylePreset.VSCODE
        for (option in options) option.isEnabled = enabled
    }

    /** Puts [values]' options (not the preset) into the controls. */
    private fun fill(values: ComarkCodeStyleSettings) {
        val wasUpdating = updating
        updating = true
        try {
            bullet.item = values.BULLET_MARKER
            ordered.item = values.ORDERED_DELIMITER
            emphasis.item = values.EMPHASIS_MARKER
            strong.item = values.STRONG_MARKER
            fence.item = values.FENCE_MARKER
            blankLines.value = values.MAX_BLANK_LINES.coerceIn(1, ComarkCodeStyleSettings.MAX_BLANK_LINES_LIMIT)
            propsInline.value = values.PROPS_INLINE_MAX.coerceIn(0, ComarkCodeStyleSettings.PROPS_INLINE_MAX_LIMIT)
            sortProps.isSelected = values.SORT_PROPS
            frontMatter.item = values.FRONT_MATTER
        } finally {
            updating = wasUpdating
        }
    }

    private fun write(target: ComarkCodeStyleSettings) {
        target.STYLE_PRESET = preset.item ?: ComarkStylePreset.VSCODE
        target.BULLET_MARKER = bullet.item ?: BulletMarker.DASH
        target.ORDERED_DELIMITER = ordered.item ?: OrderedDelimiter.DOT
        target.EMPHASIS_MARKER = emphasis.item ?: EmphasisMarker.ASTERISK
        target.STRONG_MARKER = strong.item ?: EmphasisMarker.ASTERISK
        target.FENCE_MARKER = fence.item ?: FenceMarker.BACKTICK
        target.MAX_BLANK_LINES = blankLines.value as Int
        target.PROPS_INLINE_MAX = propsInline.value as Int
        target.SORT_PROPS = sortProps.isSelected
        target.FRONT_MATTER = frontMatter.item ?: FrontMatterMode.STUDIO
    }

    override fun apply(settings: CodeStyleSettings) {
        write(ComarkCodeStyleSettings.getInstance(settings))
        // Nuxt Studio never wraps lines
        if (preset.item == ComarkStylePreset.NUXT_STUDIO) settings.getCommonSettings(ComarkLanguage).WRAP_ON_TYPING = NO_WRAP
    }

    override fun isModified(settings: CodeStyleSettings): Boolean {
        val current = ComarkCodeStyleSettings.getInstance(settings)
        val edited = ComarkCodeStyleSettings(settings).also(::write)
        return edited.STYLE_PRESET != current.STYLE_PRESET || edited.BULLET_MARKER != current.BULLET_MARKER ||
            edited.ORDERED_DELIMITER != current.ORDERED_DELIMITER || edited.EMPHASIS_MARKER != current.EMPHASIS_MARKER ||
            edited.STRONG_MARKER != current.STRONG_MARKER || edited.FENCE_MARKER != current.FENCE_MARKER ||
            edited.MAX_BLANK_LINES != current.MAX_BLANK_LINES || edited.PROPS_INLINE_MAX != current.PROPS_INLINE_MAX ||
            edited.SORT_PROPS != current.SORT_PROPS || edited.FRONT_MATTER != current.FRONT_MATTER ||
            preset.item == ComarkStylePreset.NUXT_STUDIO && settings.getCommonSettings(ComarkLanguage).WRAP_ON_TYPING != NO_WRAP
    }

    override fun resetImpl(settings: CodeStyleSettings) {
        val values = ComarkCodeStyleSettings.getInstance(settings)
        updating = true
        try {
            preset.item = values.STYLE_PRESET
            fill(values)
        } finally {
            updating = false
        }
        updateEnabled()
    }

    override fun getPanel(): JComponent = root

    override fun getTabTitle(): String = ComarkBundle.message("codestyle.style.tab")

    override fun getRightMargin(): Int = -1

    override fun getFileType(): FileType = ComarkFileType

    override fun getPreviewText(): String = PREVIEW

    override fun createHighlighter(scheme: EditorColorsScheme): EditorHighlighter =
        EditorHighlighterFactory.getInstance().createEditorHighlighter(ComarkFileType, scheme, null)

    /** A Comark file, so the preview is formatted by the Comark formatter. */
    companion object {
        private val NO_WRAP = CommonCodeStyleSettings.WrapOnTyping.NO_WRAP.intValue

        private val PREVIEW = """
            ---
            title: Getting started
            navigation:   true
            ---
            # Getting started
            Install the module, then:
            * add it to `nuxt.config.ts`
            * restart the dev server
            1) Write *some* content
            2) Save it with __strong__ words
            ::card{title="Comark" icon="i-lucide-box" to="/docs"}
            Default slot content
            :::badge{color="primary" variant="soft" size="md" label="New"}
            Nested
            :::
            ```ts
            export default defineNuxtConfig({ modules: ['@nuxt/content'] })
            ```
            ::
        """.trimIndent() + "\n"
    }
}
