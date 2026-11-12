package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.psi.ComarkFile

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
