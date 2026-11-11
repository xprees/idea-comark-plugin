package cz.xprees.comark.lang

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.FileContentUtilCore
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.hasMarkdownType
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether a Markdown file is Comark content: true when it lies in a content directory of a package whose `package.json` (in
 * any directory from the file's up to the file system root) depends on Nuxt Content, Nuxt MDC, Comark or a Comark based theme.
 * Content directories are every directory named `content` below the package directory (Nuxt Content's default, layers and nested
 * apps included; not inside `node_modules`), the `cwd` of collection sources in `content.config.*` (Nuxt Content 3) and the `base` of
 * `fs` sources in `nuxt.config.*` (Nuxt Content 2). Other Markdown files of such a package (`README.md`, `CHANGELOG.md`) stay plain
 * Markdown. Results are cached per manifest and configuration file (keyed by modification stamp) and per directory, and dropped when
 * one of these files changes, which also re-parses the open Markdown files. A configuration set in
 * [ComarkSettings.contentConfigPath] replaces the discovered ones, and its content directories are Comark with or without a
 * `package.json`.
 */
@Service(Service.Level.PROJECT)
class ComarkProjectDetector(private val project: Project) : Disposable {
    private class Manifest(val stamp: Long, val usesComark: Boolean)
    private class SourceRoots(val stamp: Long, val roots: List<String>)

    private class Override(val setting: String, val file: String?, val stamp: Long, val roots: List<String>)

    private val manifests = ConcurrentHashMap<String, Manifest>()

    @Volatile
    private var override: Override? = null
    private val sourceRoots = ConcurrentHashMap<String, SourceRoots>()

    /** Per directory: the paths of the directories above it (itself included) whose `package.json` depends on Comark. */
    private val packages = ConcurrentHashMap<String, List<String>>()

    /** Per directory: whether its Markdown files are Comark content. */
    private val decisions = ConcurrentHashMap<String, Boolean>()

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val overrideFile = override?.file
                if (events.any { isWatched(it.path.substringAfterLast('/')) || it.path == overrideFile }) invalidate()
            }
        })
    }

    fun isComarkContext(file: VirtualFile): Boolean {
        val directory = (if (file.isDirectory) file else file.parent) ?: return false
        if (!directory.isInLocalFileSystem && directory.fileSystem.protocol != "temp") return false
        val path = directory.path
        decisions[path]?.let { return it }
        val overrideRoots = overrideRoots()
        val result = overrideRoots?.any { isUnder(path, it) } == true || comarkPackages(directory).any { root ->
            isInContentDirectory(path, root) || overrideRoots == null && configuredRoots(directory, root).any { isUnder(path, it) }
        }
        decisions[path] = result
        return result
    }

    private fun comarkPackages(directory: VirtualFile): List<String> {
        packages[directory.path]?.let { return it }
        val above = directory.parent?.let(::comarkPackages).orEmpty()
        val manifest = directory.findChild(MANIFEST)
        val result = if (manifest != null && manifestUsesComark(manifest)) above + directory.path else above
        packages[directory.path] = result
        return result
    }

    /** True when [path] has a directory named `content` below the package directory [root], outside `node_modules`. */
    private fun isInContentDirectory(path: String, root: String): Boolean {
        if (!isUnder(path, root) || path.length == root.length) return false
        val segments = path.substring(root.length).split('/').filter(String::isNotEmpty)
        val content = segments.indexOf(CONTENT_DIRECTORY)
        return content >= 0 && NODE_MODULES !in segments.subList(0, content)
    }

    /** Content directories that the package's Nuxt Content configuration names (absolute paths). */
    private fun configuredRoots(file: VirtualFile, root: String): List<String> {
        val directory = file.fileSystem.findFileByPath(root)?.takeIf { it.isDirectory } ?: return emptyList()
        val configs = directory.children.filter { !it.isDirectory && CONFIG.matches(it.name) }
        val stamp = configs.fold(configs.size.toLong()) { acc, it -> acc * 31 + it.modificationStamp + it.name.hashCode() }
        sourceRoots[root]?.takeIf { it.stamp == stamp }?.let { return it.roots }
        val roots = configs.flatMap { config ->
            val text = try {
                VfsUtilCore.loadText(config)
            } catch (_: Exception) {
                ""
            }
            sourceDirectories(text, config.name.startsWith("nuxt."))
        }.map { resolve(root, it) }.distinct()
        sourceRoots[root] = SourceRoots(stamp, roots)
        return roots
    }

    /**
     * Content directories of the configuration set in [ComarkSettings.contentConfigPath]: the `content` directory next to it and the
     * source directories it names. Null when no configuration is set; empty when the file doesn't exist.
     */
    private fun overrideRoots(): List<String>? {
        val setting = ComarkSettings.getInstance().contentConfigPath
        if (setting.isEmpty()) return null
        val file = (if (FileUtil.isAbsolute(setting)) LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(setting))
        else project.guessProjectDir()?.findFileByRelativePath(FileUtil.toSystemIndependentName(setting)))?.takeIf { !it.isDirectory }
        val stamp = file?.modificationStamp ?: -1
        override?.takeIf { it.setting == setting && it.file == file?.path && it.stamp == stamp }?.let { return it.roots }
        val roots = if (file == null) {
            emptyList()
        } else {
            val root = file.parent.path
            val text = try {
                VfsUtilCore.loadText(file)
            } catch (_: Exception) {
                ""
            }
            (listOf(CONTENT_DIRECTORY) + sourceDirectories(text, file.name.startsWith("nuxt."))).map { resolve(root, it) }.distinct()
        }
        override = Override(setting, file?.path, stamp, roots)
        return roots
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
        clear()
        reparseOpenMarkdownFiles(project)
    }

    override fun dispose() = clear()

    private fun clear() {
        manifests.clear()
        sourceRoots.clear()
        packages.clear()
        decisions.clear()
        override = null
    }

    companion object {
        private const val MANIFEST = "package.json"
        private const val CONTENT_DIRECTORY = "content"
        private const val NODE_MODULES = "node_modules"

        /** `content.config.ts` (Nuxt Content 3) and `nuxt.config.ts` (Nuxt Content 2 sources), in every script flavour. */
        private val CONFIG = Regex("""(?:content|nuxt)\.config\.(?:ts|js|mjs|mts|cjs|cts)""")

        private fun isWatched(name: String) = name == MANIFEST || CONFIG.matches(name)

        /** The last string literal of a `cwd:` / `base:` value: `'docs'`, `resolve(__dirname, 'docs')`, `new URL('./docs', …)`. */
        private val CWD = Regex("""\bcwd\s*:\s*((?:new\s+)?[\w.$]+\s*\([^)]*\)|[^,}\n]*)""")
        private val FS_SOURCE = Regex("""\{[^{}]*\bdriver\s*:\s*['"`]fs['"`][^{}]*}""")
        private val BASE = Regex("""\bbase\s*:\s*((?:new\s+)?[\w.$]+\s*\([^)]*\)|[^,}\n]*)""")
        private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:[\\/]""")
        private val STRING = Regex("""['"`]([^'"`]+)['"`]""")

        /**
         * Directories named by a Nuxt Content configuration [text]: the `cwd` of collection sources (`content.config.*`), or, in a Nuxt
         * configuration ([nuxtConfig]), the `base` of Nuxt Content 2 `fs` sources. Relative to the configuration's directory.
         */
        internal fun sourceDirectories(text: String, nuxtConfig: Boolean): List<String> {
            val values = if (nuxtConfig) {
                FS_SOURCE.findAll(text).mapNotNull { BASE.find(it.value)?.groupValues?.get(1) }
            } else {
                CWD.findAll(text).map { it.groupValues[1] }
            }
            return values.mapNotNull { value -> STRING.findAll(value).lastOrNull()?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } }
                .filter { !it.contains("://") || it.startsWith("file://") }.map { it.removePrefix("file://") }.toList()
        }

        private fun resolve(root: String, path: String): String {
            val absolute = if (path.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(path)) path.replace('\\', '/') else "$root/$path"
            val segments = ArrayList<String>()
            for (segment in absolute.split('/')) {
                when (segment) {
                    "", "." -> {}
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                    else -> segments += segment
                }
            }
            return segments.joinToString("/", prefix = if (absolute.startsWith("/")) "/" else "")
        }

        private fun isUnder(path: String, root: String) = path == root || path.startsWith(if (root.endsWith('/')) root else "$root/")

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
