package cz.xprees.comark.lang

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiManager
import com.intellij.util.FileContentUtilCore
import org.intellij.plugins.markdown.lang.hasMarkdownType

/**
 * Lets the plugin be installed, updated and uninstalled without an IDE restart.
 *
 * Markdown files only become Comark when their PSI is created, so after the plugin is loaded the open Markdown files are re-parsed
 * (and their icons refreshed) to pick up the Comark language. Before it is unloaded, the substitutor stops answering Comark and the
 * open files are re-parsed as plain Markdown right away, so no Comark PSI (whose classes belong to this plugin) outlives it and keeps
 * its class loader from being released.
 */
class ComarkDynamicPluginListener : DynamicPluginListener {
    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (!isComark(pluginDescriptor)) return
        unloading = false
        for (project in ProjectManager.getInstance().openProjects) ComarkProjectDetector.reparseOpenMarkdownFiles(project)
    }

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (!isComark(pluginDescriptor)) return
        unloading = true
        val application = ApplicationManager.getApplication()
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val files = FileEditorManager.getInstance(project).openFiles.filter {
                it.hasMarkdownType() || it.extension.equals(ComarkLanguageSubstitutor.MDC_EXTENSION, ignoreCase = true)
            }
            val reparse = {
                if (files.isNotEmpty()) FileContentUtilCore.reparseFiles(files)
                PsiManager.getInstance(project).dropPsiCaches()
            }
            if (application.isDispatchThread) application.runWriteAction(reparse) else application.invokeAndWait { application.runWriteAction(reparse) }
        }
    }

    companion object {
        private const val PLUGIN_ID = "cz.xprees.comark"

        /** True from the moment the plugin starts unloading: no file is Comark anymore. */
        @Volatile
        var unloading = false
            internal set

        private fun isComark(descriptor: IdeaPluginDescriptor) = descriptor.pluginId.idString == PLUGIN_ID
    }
}
