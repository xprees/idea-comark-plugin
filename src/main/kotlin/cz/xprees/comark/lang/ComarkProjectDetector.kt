package cz.xprees.comark.lang

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.FileContentUtilCore
import org.intellij.plugins.markdown.lang.hasMarkdownType
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether a Markdown file is Comark content: true when any `package.json` from the file's directory up to the file system
 * root depends on Nuxt Content, Nuxt MDC, Comark or a Comark based theme. Results are cached per `package.json` (keyed by its
 * modification stamp) and per directory, and dropped when a `package.json` changes, which also re-parses the open Markdown files.
 */
@Service(Service.Level.PROJECT)
class ComarkProjectDetector(private val project: Project) : Disposable {
    private class Manifest(val stamp: Long, val usesComark: Boolean)

    private val manifests = ConcurrentHashMap<String, Manifest>()
    private val directories = ConcurrentHashMap<String, Boolean>()

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith("/$MANIFEST") || it.path == MANIFEST }) invalidate()
            }
        })
    }

    fun isComarkContext(file: VirtualFile): Boolean {
        val directory = (if (file.isDirectory) file else file.parent) ?: return false
        if (!directory.isInLocalFileSystem && directory.fileSystem.protocol != "temp") return false
        return isComarkDirectory(directory)
    }

    private fun isComarkDirectory(directory: VirtualFile): Boolean {
        directories[directory.path]?.let { return it }
        val manifest = directory.findChild(MANIFEST)
        val result = (manifest != null && manifestUsesComark(manifest)) || (directory.parent?.let(::isComarkDirectory) ?: false)
        directories[directory.path] = result
        return result
    }

    private fun manifestUsesComark(file: VirtualFile): Boolean {
        val stamp = file.modificationStamp
        manifests[file.path]?.takeIf { it.stamp == stamp }?.let { return it.usesComark }
        val uses = try {
            val text = VfsUtilCore.loadText(file)
            DEPENDENCY.containsMatchIn(text)
        } catch (_: Exception) {
            false
        }
        manifests[file.path] = Manifest(stamp, uses)
        return uses
    }

    /** Drops every cached decision and re-parses open Markdown files, whose language may have changed. */
    fun invalidate() {
        manifests.clear()
        directories.clear()
        reparseOpenMarkdownFiles(project)
    }

    override fun dispose() {
        manifests.clear()
        directories.clear()
    }

    companion object {
        private const val MANIFEST = "package.json"

        /** `"@nuxt/content"`, `"@nuxtjs/mdc"`, `"comark"`, `"@comark/..."`, `"docus"`, `"@nuxt/ui-pro"` as dependency keys. */
        private val DEPENDENCY = Regex(""""(?:@nuxt/content|@nuxtjs/mdc|comark|@comark/[\w.-]+|docus|@nuxt/ui-pro|@nuxtlabs/[\w.-]*mdc[\w.-]*)"\s*:""")

        fun getInstance(project: Project): ComarkProjectDetector = project.service()

        /**
         * Re-parses the open Markdown files, whose language may have changed, and refreshes the icons shown for Markdown files (Comark
         * files get the Comark icon) in the Project view and the editor tabs.
         */
        fun reparseOpenMarkdownFiles(project: Project) {
            if (project.isDisposed) return
            ApplicationManager.getApplication().invokeLater({
                val files = FileEditorManager.getInstance(project).openFiles.filter { it.hasMarkdownType() }
                if (files.isNotEmpty()) FileContentUtilCore.reparseFiles(files)
                val editors = FileEditorManagerEx.getInstanceEx(project)
                files.forEach(editors::updateFilePresentation)
                ProjectView.getInstance(project).refresh()
            }, project.disposed)
        }
    }
}
