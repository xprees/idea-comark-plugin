package cz.xprees.comark.lang

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/** Loading and unloading the plugin at runtime switches open Markdown files between Comark and Markdown without a restart. */
class ComarkDynamicPluginTest : BasePlatformTestCase() {
    fun testUnloadAndLoadReparseOpenFiles() {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("cz.xprees.comark"))
        assertNotNull("the plugin under test is loaded", descriptor)
        val file = myFixture.addFileToProject("page.mdc", "::alert\nHello\n::\n").virtualFile
        myFixture.openFileInEditor(file)
        fun language() = PsiManager.getInstance(project).findFile(file)!!.language
        assertSame(ComarkLanguage, language())

        val listener = ComarkDynamicPluginListener()
        try {
            listener.beforePluginUnload(descriptor!!, isUpdate = false)
            assertSame(MarkdownLanguage.INSTANCE, language())
            assertFalse(ComarkLanguageSubstitutor.isComarkFile(file, project))
        } finally {
            listener.pluginLoaded(descriptor!!)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        assertSame(ComarkLanguage, language())
    }
}
