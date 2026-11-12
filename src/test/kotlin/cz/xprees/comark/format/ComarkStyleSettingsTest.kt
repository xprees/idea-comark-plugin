package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.util.JDOMUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.comark.lang.ComarkLanguage
import org.jdom.Element

/** *Code Style › Comark › Style*: [ComarkCodeStyleSettings], its presets, storage, EditorConfig, and [ComarkStyle]. */
class ComarkStyleSettingsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        enableEditorConfig(true)
    }

    override fun tearDown() {
        try {
            enableEditorConfig(false)
        } finally {
            super.tearDown()
        }
    }

    /** The EditorConfig plugin applies `.editorconfig` files in tests only when told to (its own tests do the same). */
    private fun enableEditorConfig(enabled: Boolean) {
        val utils = Class.forName("org.editorconfig.Utils")
        utils.getMethod("setEnabledInTests", Boolean::class.javaPrimitiveType).invoke(null, enabled)
        utils.getMethod("setFullIntellijSettingsSupportEnabledInTest", Boolean::class.javaPrimitiveType).invoke(null, enabled)
        val handler = Class.forName("org.editorconfig.configmanagement.extended.EditorConfigCodeStyleSettingsModifier\$Handler")
        handler.getMethod("setEnabledInTests", Boolean::class.javaPrimitiveType).invoke(handler.getField("INSTANCE").get(null), enabled)
    }

    private fun withSettings(change: (ComarkCodeStyleSettings) -> Unit, action: () -> Unit) {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            change(ComarkCodeStyleSettings.getInstance(settings))
            action()
        }
    }

    fun testDefaultIsTheVsCodeStyle() {
        myFixture.configureByText("page.mdc", "# x\n")
        val style = ComarkStyle.of(myFixture.file)
        assertEquals(ComarkStylePreset.VSCODE, style.preset)
        assertFalse(style.usesStudio)
    }

    fun testPresetsWriteTheirValues() {
        val settings = ComarkCodeStyleSettings(CodeStyle.createTestSettings())
        settings.BULLET_MARKER = BulletMarker.PLUS
        settings.SORT_PROPS = true
        settings.applyPreset(ComarkStylePreset.CUSTOM)
        // Custom keeps the options
        assertEquals(BulletMarker.PLUS, settings.BULLET_MARKER)
        settings.applyPreset(ComarkStylePreset.NUXT_STUDIO)
        assertEquals(ComarkStylePreset.NUXT_STUDIO, settings.STYLE_PRESET)
        assertEquals(StudioStyleOptions.STUDIO, settings.toOptions(2))
    }

    fun testNuxtStudioIgnoresTheOptionsAndCustomUsesThem() {
        myFixture.configureByText("page.mdc", "# x\n")
        withSettings({ it.STYLE_PRESET = ComarkStylePreset.NUXT_STUDIO; it.BULLET_MARKER = BulletMarker.ASTERISK }) {
            assertEquals(StudioStyleOptions.STUDIO, ComarkStyle.of(myFixture.file).options)
        }
        withSettings({ it.STYLE_PRESET = ComarkStylePreset.CUSTOM; it.BULLET_MARKER = BulletMarker.ASTERISK }) {
            val style = ComarkStyle.of(myFixture.file)
            assertTrue(style.usesStudio)
            assertEquals('*', style.options.bulletMarker)
        }
    }

    fun testIndentSizePerStyle() {
        myFixture.configureByText("page.mdc", "# x\n")
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCommonSettings(ComarkLanguage).indentOptions!!.INDENT_SIZE = 3
            val custom = ComarkCodeStyleSettings.getInstance(settings)
            assertEquals(3, ComarkIndent.indentSize(myFixture.file))
            custom.STYLE_PRESET = ComarkStylePreset.NUXT_STUDIO
            // Studio always nests by two spaces, and so do Enter and Auto-Indent Lines
            assertEquals(2, ComarkIndent.indentSize(myFixture.file))
            custom.STYLE_PRESET = ComarkStylePreset.CUSTOM
            assertEquals(3, ComarkIndent.indentSize(myFixture.file))
            assertEquals(3, ComarkStyle.of(myFixture.file).options.indentSize)
        }
    }

    fun testStoredInTheSchemeXml() {
        val settings = CodeStyle.createTestSettings()
        val custom = ComarkCodeStyleSettings.getInstance(settings)
        custom.applyPreset(ComarkStylePreset.CUSTOM)
        custom.FENCE_MARKER = FenceMarker.TILDE
        custom.PROPS_INLINE_MAX = 1
        val element = Element("code_scheme")
        settings.writeExternal(element)
        val xml = JDOMUtil.writeElement(element)
        assertTrue(xml, xml.contains("<ComarkCodeStyleSettings>"))
        assertTrue(xml, xml.contains("<option name=\"STYLE_PRESET\" value=\"CUSTOM\" />"))
        assertTrue(xml, xml.contains("<option name=\"FENCE_MARKER\" value=\"TILDE\" />"))

        val loaded = CodeStyle.createTestSettings()
        loaded.readExternal(JDOMUtil.load(xml))
        val read = ComarkCodeStyleSettings.getInstance(loaded)
        assertEquals(ComarkStylePreset.CUSTOM, read.STYLE_PRESET)
        assertEquals(FenceMarker.TILDE, read.FENCE_MARKER)
        assertEquals(1, read.PROPS_INLINE_MAX)
        // defaults are not written
        val defaults = Element("code_scheme").also { CodeStyle.createTestSettings().writeExternal(it) }
        assertFalse(JDOMUtil.writeElement(defaults).contains("ComarkCodeStyleSettings"))
    }

    fun testExposedAsEditorConfigProperties() {
        val settings = CodeStyle.createTestSettings()
        val mapper = LanguageCodeStyleSettingsProvider.forLanguage(ComarkLanguage)!!.getPropertyMapper(settings)
        assertEquals("comark", mapper.languageDomainId)
        val names = mapper.enumProperties()
        for (name in listOf("style_preset", "bullet_marker", "ordered_delimiter", "emphasis_marker", "strong_marker", "fence_marker",
            "max_blank_lines", "props_inline_max", "sort_props", "front_matter", "indent_size")) {
            assertTrue("$name in $names", name in names)
        }
        val accessor = mapper.getAccessor("style_preset")!!
        assertTrue(accessor.setFromString("nuxt_studio"))
        assertEquals(ComarkStylePreset.NUXT_STUDIO, ComarkCodeStyleSettings.getInstance(settings).STYLE_PRESET)
        assertEquals("nuxt_studio", accessor.asString)
    }

    /**
     * The settings EditorConfig gives [file]. Tests run with temporary code style settings, which `CodeStyle.getSettings(file)`
     * returns as they are, so the EditorConfig modifier is applied here the way the platform applies it to a file's settings.
     */
    private fun editorConfigSettings(file: PsiFile): CodeStyleSettings {
        val settings = TransientCodeStyleSettings(file, CodeStyle.getSettings(project))
        val modifier = CodeStyleSettingsModifier.EP_NAME.extensionList.single { it.javaClass.name.contains("editorconfig", ignoreCase = true) }
        assertTrue(modifier.modifySettingsAndUiCustomization(settings, file))
        return settings
    }

    fun testReadFromEditorConfig() {
        myFixture.addFileToProject(".editorconfig", "root = true\n\n[*.mdc]\nij_comark_style_preset = custom\nij_comark_bullet_marker = asterisk\n")
        myFixture.configureByText("page.mdc", "- a\n- b\n")
        val style = ComarkStyle.of(editorConfigSettings(myFixture.file)) { 2 }
        assertEquals(ComarkStylePreset.CUSTOM, style.preset)
        assertEquals('*', style.options.bulletMarker)
    }

    fun testNuxtStudioFromEditorConfig() {
        myFixture.addFileToProject(".editorconfig", "root = true\n\n[*]\nij_comark_style_preset = nuxt_studio\n")
        myFixture.configureByText("page.mdc", "* a\n* b\n")
        assertEquals(ComarkStylePreset.NUXT_STUDIO, ComarkStyle.of(editorConfigSettings(myFixture.file)) { 2 }.preset)
    }
}
