package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorNotificationPanel

/** The version banner ([ComarkStudioVersionNotificationProvider]), version lookup and the adoption notification. */
class ComarkStudioNotificationsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            val properties = PropertiesComponent.getInstance(project)
            properties.unsetValue("comark.studio.version.dismissed")
            properties.unsetValue("comark.studio.adoption.never")
        } finally {
            super.tearDown()
        }
    }

    private fun withStyle(preset: ComarkStylePreset, action: () -> Unit) {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            ComarkCodeStyleSettings.getInstance(settings).STYLE_PRESET = preset
            action()
        }
    }

    private fun page(): com.intellij.openapi.vfs.VirtualFile {
        val file = myFixture.addFileToProject("content/page.mdc", "# Page\n").virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        return file
    }

    private fun packageJson(range: String) =
        myFixture.addFileToProject("package.json", """{ "dependencies": { "@nuxt/content": "^3.16.0", "nuxt-studio": "$range" } }""")

    // ---- version lookup -------------------------------------------------------------------------------------------------

    fun testVersionsFromTheNearestPackageAndLockfile() {
        packageJson("^1.8.0")
        myFixture.addFileToProject("pnpm-lock.yaml", "lockfileVersion: '9.0'\npackages:\n  comark@0.7.0:\n    resolution: {}\n  nuxt-studio@1.9.2:\n    resolution: {}\n")
        val info = ComarkStudioVersions.getInstance(project).dependenciesFor(page())!!
        assertEquals("^1.8.0", info.studioRange)
        assertEquals(listOf("1.9.2"), info.studioVersions)
        assertEquals(listOf("0.7.0"), info.comarkVersions)
        assertEquals("pnpm-lock.yaml", info.lockfile)
        assertEquals(listOf("nuxt-studio 1.9.2"), info.mismatches)
    }

    fun testNoNuxtStudioDependency() {
        myFixture.addFileToProject("package.json", """{ "dependencies": { "@nuxt/content": "^3.16.0" } }""")
        assertNull(ComarkStudioVersions.getInstance(project).dependenciesFor(page()))
    }

    // ---- version banner -------------------------------------------------------------------------------------------------

    fun testBannerForAnotherStudioVersion() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        packageJson("^1.8.0")
        val lock = myFixture.addFileToProject("yarn.lock", "\"nuxt-studio@^1.8.0\":\n  version \"1.9.2\"\n").virtualFile
        val file = page()
        assertEquals("nuxt-studio 1.9.2", ComarkStudioVersionNotificationProvider.mismatch(project, file))
        val editor = FileEditorManager.getInstance(project).getSelectedEditor(file)!!
        val panel = ComarkStudioVersionNotificationProvider().collectNotificationData(project, file)!!.apply(editor) as EditorNotificationPanel
        assertEquals(
            "The Nuxt Studio style matches nuxt-studio 1.8.0 (comark 0.7.0); this project uses nuxt-studio 1.9.2. Studio may save some files differently.",
            panel.text,
        )
        // dismissed for this version, shown again for another one
        ComarkStudioVersionNotificationProvider.dismiss(project, "nuxt-studio 1.9.2")
        assertNull(ComarkStudioVersionNotificationProvider.mismatch(project, file))
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(lock, "\"nuxt-studio@^1.8.0\":\n  version \"1.10.0\"\n") }
        assertEquals("nuxt-studio 1.10.0", ComarkStudioVersionNotificationProvider.mismatch(project, file))
    }

    fun testNoBannerForThePinnedVersion() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        packageJson("^1.8.0")
        myFixture.addFileToProject("package-lock.json", """{ "packages": { "node_modules/nuxt-studio": { "version": "1.8.0" } } }""")
        assertNull(ComarkStudioVersionNotificationProvider.mismatch(project, page()))
    }

    fun testNoBannerInTheVsCodeStyle() = withStyle(ComarkStylePreset.VSCODE) {
        packageJson("2.0.0")
        assertNull(ComarkStudioVersionNotificationProvider.mismatch(project, page()))
    }

    fun testBannerInTheCustomStyle() = withStyle(ComarkStylePreset.CUSTOM) {
        packageJson("2.0.0")
        assertEquals("nuxt-studio 2.0.0", ComarkStudioVersionNotificationProvider.mismatch(project, page()))
    }

    // ---- adoption ---------------------------------------------------------------------------------------------------------

    fun testOfferedWhenTheProjectDependsOnNuxtStudio() = withStyle(ComarkStylePreset.VSCODE) {
        assertFalse(ComarkStudioAdoption.shouldOffer(project))
        packageJson("^1.8.0")
        assertTrue(ComarkStudioAdoption.shouldOffer(project))
        val notification = ComarkStudioAdoption.createNotification(project)
        assertEquals(listOf("Use Nuxt Studio style", "Not now", "Don't ask again"), notification.actions.map { it.templateText })
        ComarkStudioAdoption.neverAskAgain(project)
        assertFalse(ComarkStudioAdoption.shouldOffer(project))
    }

    fun testOfferedOnlyOnce() = withStyle(ComarkStylePreset.VSCODE) {
        packageJson("^1.8.0")
        assertTrue(ComarkStudioAdoption.shouldOffer(project))
        // shown once: "Not now" or closing it does not bring it back on the next project open
        ComarkStudioAdoption.markShown(project)
        assertFalse(ComarkStudioAdoption.shouldOffer(project))
    }

    fun testNotOfferedWhenEditorConfigSetsThePreset() = withStyle(ComarkStylePreset.VSCODE) {
        packageJson("^1.8.0")
        myFixture.addFileToProject(".editorconfig", "root = true\n\n[*.md]\nij_comark_style_preset = nuxt_studio\n")
        assertTrue(ComarkStudioAdoption.editorConfigSetsPreset(project))
        assertFalse(ComarkStudioAdoption.shouldOffer(project))
    }

    fun testNotOfferedForDependenciesInNodeModules() = withStyle(ComarkStylePreset.VSCODE) {
        myFixture.addFileToProject("node_modules/some-theme/package.json", """{ "dependencies": { "nuxt-studio": "^1.8.0" } }""")
        assertFalse(ComarkStudioAdoption.shouldOffer(project))
    }

    fun testNotOfferedWhenAStudioStyleIsSelected() = withStyle(ComarkStylePreset.NUXT_STUDIO) {
        packageJson("^1.8.0")
        assertFalse(ComarkStudioAdoption.shouldOffer(project))
    }

    fun testUseNuxtStudioStyleSetsTheProjectCodeStyle() {
        val manager = CodeStyleSettingsManager.getInstance(project)
        val usedProjectSettings = manager.USE_PER_PROJECT_SETTINGS
        val previous = manager.mainProjectCodeStyle?.let(manager::cloneSettings)
        try {
            ComarkStudioAdoption.useNuxtStudioStyle(project)
            val settings = manager.mainProjectCodeStyle!!
            assertTrue(manager.USE_PER_PROJECT_SETTINGS)
            assertEquals(ComarkStylePreset.NUXT_STUDIO, ComarkCodeStyleSettings.getInstance(settings).STYLE_PRESET)
        } finally {
            if (previous != null) manager.setMainProjectCodeStyle(previous)
            manager.USE_PER_PROJECT_SETTINGS = usedProjectSettings
            manager.notifyCodeStyleSettingsChanged()
        }
    }
}
