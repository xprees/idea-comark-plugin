package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.psi.ComarkFile
import javax.swing.JSpinner

/** The Style tab ([ComarkStylePanel]) and the settings preview. */
class ComarkStylePanelTest : BasePlatformTestCase() {
    fun testPresetAndOptions() {
        val settings = CodeStyle.createTestSettings()
        val panel = ComarkStylePanel(null, settings)
        try {
            panel.reset(settings)
            assertEquals(ComarkStylePreset.VSCODE, panel.preset.item)
            // the VS Code style has none of the options
            assertTrue(panel.options.none { it.isEnabled })
            panel.preset.item = ComarkStylePreset.NUXT_STUDIO
            assertTrue(panel.options.all { it.isEnabled })
            assertTrue(panel.isModified(settings))
            // changing an option switches to Custom
            panel.bullet.item = BulletMarker.PLUS
            assertEquals(ComarkStylePreset.CUSTOM, panel.preset.item)
            panel.apply(settings)
            val custom = ComarkCodeStyleSettings.getInstance(settings)
            assertEquals(ComarkStylePreset.CUSTOM, custom.STYLE_PRESET)
            assertEquals(BulletMarker.PLUS, custom.BULLET_MARKER)
            assertFalse(panel.isModified(settings))
            // picking a preset writes its values
            panel.preset.item = ComarkStylePreset.NUXT_STUDIO
            assertEquals(BulletMarker.DASH, panel.bullet.item)
            assertEquals(ComarkStylePreset.NUXT_STUDIO, panel.preset.item)
            panel.apply(settings)
            assertEquals(CommonCodeStyleSettings.WrapOnTyping.NO_WRAP.intValue, settings.getCommonSettings(ComarkLanguage).WRAP_ON_TYPING)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testComponentPropsChoice() {
        val settings = CodeStyle.createTestSettings()
        val panel = ComarkStylePanel(null, settings)
        try {
            panel.reset(settings)
            panel.preset.item = ComarkStylePreset.NUXT_STUDIO
            assertEquals(PropsLayout.UP_TO, panel.propsLayout.item)
            assertTrue(panel.propsInline.isEnabled)
            // the count is only for "inline up to"; picking another layout switches to Custom
            panel.propsLayout.item = PropsLayout.BLOCK
            assertFalse(panel.propsInline.isEnabled)
            assertEquals(ComarkStylePreset.CUSTOM, panel.preset.item)
            panel.apply(settings)
            assertEquals(PropsLayout.BLOCK, ComarkCodeStyleSettings.getInstance(settings).PROPS_LAYOUT)
            assertFalse(panel.isModified(settings))
            panel.propsLayout.item = PropsLayout.UP_TO
            assertTrue(panel.propsInline.isEnabled)
            assertTrue(panel.isModified(settings))
            // the VS Code style has none of the options
            panel.preset.item = ComarkStylePreset.VSCODE
            assertFalse(panel.propsLayout.isEnabled)
            assertFalse(panel.propsInline.isEnabled)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testTypedNumbersAreAppliedWithoutEnter() {
        val settings = CodeStyle.createTestSettings()
        ComarkCodeStyleSettings.getInstance(settings).STYLE_PRESET = ComarkStylePreset.CUSTOM
        val panel = ComarkStylePanel(null, settings)
        try {
            panel.reset(settings)
            // typed into the text fields, with no Enter or focus change before OK
            (panel.propsInline.editor as JSpinner.DefaultEditor).textField.text = "0"
            (panel.blankLines.editor as JSpinner.DefaultEditor).textField.text = "2"
            assertTrue(panel.isModified(settings))
            panel.apply(settings)
            val custom = ComarkCodeStyleSettings.getInstance(settings)
            assertEquals(0, custom.PROPS_INLINE_MAX)
            assertEquals(2, custom.MAX_BLANK_LINES)
            // a number out of range is not taken; the last valid one is kept
            (panel.propsInline.editor as JSpinner.DefaultEditor).textField.text = "99"
            panel.apply(settings)
            assertEquals(0, custom.PROPS_INLINE_MAX)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testPreviewIsAComarkFileFormattedInTheSelectedStyle() {
        val file = ComarkCodeStyleSettingsProvider.createPreviewFile(project, "# A\n* a\n* b\n")
        assertInstanceOf(file, ComarkFile::class.java)
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            ComarkCodeStyleSettings.getInstance(settings).STYLE_PRESET = ComarkStylePreset.NUXT_STUDIO
            WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
            // a page with Studio's defaults: the title comes from the heading
            assertEquals("---\ntitle: A\n---\n\n# A\n\n- a\n- b\n", file.text)
        }
    }

    fun testEnterIndentsByTwoInTheNuxtStudioStyle() {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCommonSettings(ComarkLanguage).indentOptions!!.INDENT_SIZE = 4
            ComarkCodeStyleSettings.getInstance(settings).STYLE_PRESET = ComarkStylePreset.NUXT_STUDIO
            myFixture.configureByText("page.mdc", "::outer\n:::inner<caret>\n:::\n::\n")
            myFixture.type("\n")
            myFixture.checkResult("::outer\n:::inner\n  <caret>\n:::\n::\n")
        }
    }
}
