package cz.xprees.comark.meta

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import cz.xprees.comark.settings.ComarkSettings
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers the components Comark documents can use and keeps them in a [ComponentIndex].
 *
 * Three sources, merged with later ones winning on conflicts (the file of a component is always kept for navigation):
 * 1. `.vue` files below Nuxt `components/` directories, named by Nuxt's rules ([ComponentNaming]) and parsed by [VueSfcParser];
 * 2. `.nuxt/component-meta.mjs` / `.json` written by nuxt-component-meta anywhere in the project ([ComponentMetaParser]);
 * 3. the remote URL from the settings ([ComarkRemoteMetadata]), fetched in the background.
 *
 * Files are found by walking the project's content roots (skipping `node_modules`, build output, hidden and excluded
 * directories) instead of using indexes, so components are available in dumb mode too. The walk is cached until a relevant
 * file or directory is created, deleted, moved or renamed; parsed contents are cached per file by modification stamp, and
 * unsaved edits of `.vue` files are seen through their documents. The index itself is a [CachedValue] that depends on all of
 * that plus the remote metadata and the URL setting, so typing in a Markdown file never re-scans anything.
 */
@Service(Service.Level.PROJECT)
class ComarkComponentService(private val project: Project) : Disposable {
    /** A `.vue` file below a `components/` directory: [relativePath] is relative to that directory, [root] is the app directory. */
    private class VueFile(val file: VirtualFile, val relativePath: String, val root: VirtualFile)

    /** A nuxt-component-meta output file and the Nuxt app directory it describes (the parent of `.nuxt`). */
    private class MetaFile(val file: VirtualFile, val root: VirtualFile)

    private class DiscoveredFiles(val vueFiles: List<VueFile>, val metaFiles: List<MetaFile>)

    private class Parsed<T>(val stamp: Long, val value: T)

    /** Changes when files that may be components or metadata appear, disappear or move. */
    private val structureTracker = SimpleModificationTracker()

    /** Changes when the content of a `.vue` or metadata file changes (on disk or in an editor). */
    private val contentTracker = SimpleModificationTracker()

    private val sfcCache = ConcurrentHashMap<VirtualFile, Parsed<SfcInfo>>()
    private val metaCache = ConcurrentHashMap<VirtualFile, Parsed<List<MetaComponentRecord>?>>()

    private val filesCache: CachedValue<DiscoveredFiles>
    private val indexCache: CachedValue<ComponentIndex>

    init {
        val manager = CachedValuesManager.getManager(project)
        filesCache = manager.createCachedValue {
            CachedValueProvider.Result.create(discoverFiles(), structureTracker, ProjectRootManager.getInstance(project))
        }
        indexCache = manager.createCachedValue {
            CachedValueProvider.Result.create(
                buildIndex(filesCache.value),
                structureTracker,
                contentTracker,
                ProjectRootManager.getInstance(project),
                ComarkRemoteMetadata.getInstance().modificationTracker,
                MetadataUrlTracker,
            )
        }
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onFileEvents(events)
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (isComponentFile(file.name)) contentTracker.incModificationCount()
            }
        }, this)
    }

    /**
     * All known components. Cheap when nothing changed; otherwise recomputed in the calling thread, which completion,
     * navigation, documentation and inspections all call from a background read action. Also schedules a refresh of remote
     * metadata when it has expired, without waiting for it.
     */
    @RequiresReadLock
    fun index(): ComponentIndex {
        val settings = ComarkSettings.getInstance()
        val url = settings.componentMetadataUrl
        if (url.isNotBlank()) ComarkRemoteMetadata.getInstance().records(url, settings.componentMetadataCacheMinutes)
        return indexCache.value
    }

    /** Props, slots and description parsed from the `.vue` [file] (cached by modification stamp), or null when unreadable. */
    @RequiresReadLock
    fun sfcInfo(file: VirtualFile): SfcInfo? = cachedParse(file, sfcCache, MAX_SFC_SIZE) { VueSfcParser.parse(it) }

    /**
     * Path of the `.vue` [file] relative to the `components/` directory it is a component of (`content/prose/Alert.vue`), or null
     * when it is not below one. Computed from the file's current location, so it also works for a file that was just renamed.
     */
    fun componentPath(file: VirtualFile): String? {
        val root = topLevelContentRoots().firstOrNull { VfsUtilCore.isAncestor(it, file, true) } ?: return null
        return componentFile(file, root)?.relativePath
    }

    /** Forgets everything and rediscovers components (and re-fetches remote metadata) on the next request. */
    fun refresh() {
        sfcCache.clear()
        metaCache.clear()
        structureTracker.incModificationCount()
        contentTracker.incModificationCount()
        val url = ComarkSettings.getInstance().componentMetadataUrl
        if (url.isNotBlank()) ComarkRemoteMetadata.getInstance().refresh(url)
        DaemonCodeAnalyzer.getInstance(project).restart("Comark components refreshed")
    }

    override fun dispose() {
        sfcCache.clear()
        metaCache.clear()
    }

    // ---- Discovery --------------------------------------------------------------------------------------------------

    private fun discoverFiles(): DiscoveredFiles {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val vueFiles = ArrayList<VueFile>()
        val metaFiles = ArrayList<MetaFile>()
        for (root in topLevelContentRoots()) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any>(NO_FOLLOW_SYMLINKS) {
                override fun visitFileEx(file: VirtualFile): Result {
                    ProgressManager.checkCanceled()
                    if (file.isDirectory) {
                        if (file == root) return CONTINUE
                        val name = file.name
                        if (name == NUXT_BUILD_DIRECTORY) {
                            for (metaName in META_FILE_NAMES) {
                                file.findChild(metaName)?.takeIf { !it.isDirectory }?.let { meta -> file.parent?.let { metaFiles += MetaFile(meta, it) } }
                            }
                            return SKIP_CHILDREN
                        }
                        if (isSkippedDirectory(name) || fileIndex.isExcluded(file)) return SKIP_CHILDREN
                        return CONTINUE
                    }
                    if (file.extension.equals(VUE_EXTENSION, ignoreCase = true)) componentFile(file, root)?.let(vueFiles::add)
                    return CONTINUE
                }
            })
        }
        return DiscoveredFiles(vueFiles, metaFiles)
    }

    private fun topLevelContentRoots(): List<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentRoots.filter { it.isValid && it.isDirectory }
        return roots.filter { root -> roots.none { other -> other != root && VfsUtilCore.isAncestor(other, root, true) } }
    }

    /** [file] as a component when it is below a `components/` directory (the outermost one, as Nuxt scans from there). */
    private fun componentFile(file: VirtualFile, walkRoot: VirtualFile): VueFile? {
        var componentsDirectory: VirtualFile? = null
        var directory = file.parent
        while (directory != null) {
            if (directory.name == COMPONENTS_DIRECTORY) componentsDirectory = directory
            if (directory == walkRoot) break
            directory = directory.parent
        }
        val components = componentsDirectory ?: return null
        val relativePath = VfsUtilCore.getRelativePath(file, components) ?: return null
        return VueFile(file, relativePath, appRoot(components))
    }

    /**
     * The Nuxt app directory owning a `components/` directory: its parent, or the parent of a source directory (Nuxt 4's `app/`,
     * a custom `srcDir` such as `src/`) when that one holds the app's `nuxt.config` or `package.json`.
     */
    private fun appRoot(componentsDirectory: VirtualFile): VirtualFile {
        val parent = componentsDirectory.parent ?: return componentsDirectory
        val grandParent = parent.parent ?: return parent
        return if (APP_ROOT_MARKERS.any { grandParent.findChild(it) != null }) grandParent else parent
    }

    // ---- Index ------------------------------------------------------------------------------------------------------

    private fun buildIndex(files: DiscoveredFiles): ComponentIndex {
        val components = ArrayList<ComarkComponent>()
        val byFile = HashMap<VirtualFile, Int>()
        for (vue in files.vueFiles) {
            ProgressManager.checkCanceled()
            val names = ComponentNaming.componentNames(vue.relativePath)
            if (names.isEmpty()) continue
            val info = sfcInfo(vue.file) ?: SfcInfo.EMPTY
            byFile[vue.file] = components.size
            components += ComarkComponent(
                name = names.first(),
                aliases = names.drop(1),
                description = info.description,
                props = info.props,
                slots = info.slots,
                source = ComponentSource.VUE_FILE,
                file = vue.file,
                root = vue.root,
            )
        }
        for (meta in files.metaFiles) {
            val records = cachedParse(meta.file, metaCache, MAX_META_SIZE) { ComponentMetaParser.parse(it) } ?: continue
            for (record in records) {
                ProgressManager.checkCanceled()
                merge(record, ComponentSource.COMPONENT_META, meta.root, components, byFile)
            }
        }
        val settings = ComarkSettings.getInstance()
        if (settings.componentMetadataUrl.isNotBlank()) {
            for (record in ComarkRemoteMetadata.getInstance().records(settings.componentMetadataUrl, settings.componentMetadataCacheMinutes)) {
                ProgressManager.checkCanceled()
                merge(record, ComponentSource.REMOTE, null, components, byFile)
            }
        }
        val known = files.vueFiles.mapTo(HashSet()) { it.file }
        sfcCache.keys.retainAll(known)
        metaCache.keys.retainAll(files.metaFiles.mapTo(HashSet()) { it.file })
        return ComponentIndex(components)
    }

    /** Adds [record] to [components], merging it into the component of the same file (or the same name in the same app). */
    private fun merge(
        record: MetaComponentRecord,
        source: ComponentSource,
        root: VirtualFile?,
        components: MutableList<ComarkComponent>,
        byFile: MutableMap<VirtualFile, Int>,
    ) {
        val file = resolveFile(record, root, components)
        val existing = file?.let { byFile[it] } ?: components.indices.firstOrNull { i ->
            val candidate = components[i]
            sameApp(candidate, root) && candidate.allNames.any { ComponentNaming.lookupKey(it) == ComponentNaming.lookupKey(record.name) }
        }
        if (existing != null) {
            components[existing] = merged(components[existing], record, source)
            if (file != null) byFile[file] = existing
            return
        }
        if (file != null) byFile[file] = components.size
        components += ComarkComponent(
            name = record.name,
            description = record.description,
            documentation = record.documentation,
            docsUrl = record.docsUrl,
            props = record.props,
            slots = record.slots,
            source = source,
            file = file,
            root = root,
        )
    }

    private fun sameApp(component: ComarkComponent, root: VirtualFile?): Boolean =
        root == null || component.root == null || component.root == root || component.file?.let { VfsUtilCore.isAncestor(root, it, true) } == true

    /** Metadata from [record] on top of [base]; [base] keeps its file, and its descriptions fill gaps in the metadata. */
    private fun merged(base: ComarkComponent, record: MetaComponentRecord, source: ComponentSource): ComarkComponent {
        val props = if (record.props.isEmpty()) {
            base.props
        } else {
            record.props.map { prop -> base.findProp(prop.name)?.let { prop.withDescription(it.description, it.defaultValue) } ?: prop }
        }
        val slots = if (record.slots.isEmpty()) {
            base.slots
        } else {
            record.slots.map { slot -> if (slot.description != null) slot else base.findSlot(slot.name)?.let { ComarkSlotInfo(slot.name, it.description, slot.type) } ?: slot }
        }
        return ComarkComponent(
            name = record.name,
            aliases = base.allNames.filter { it != record.name }.distinct(),
            description = record.description ?: base.description,
            documentation = record.documentation ?: base.documentation,
            docsUrl = record.docsUrl ?: base.docsUrl,
            props = props,
            slots = slots,
            source = source,
            file = base.file,
            root = base.root,
        )
    }

    /** The `.vue` file a metadata record describes: its absolute path, or its path relative to the Nuxt app / content roots. */
    private fun resolveFile(record: MetaComponentRecord, root: VirtualFile?, components: List<ComarkComponent>): VirtualFile? {
        val bases = LinkedHashSet<VirtualFile>()
        root?.let(bases::add)
        if (root == null) {
            components.mapNotNullTo(bases) { it.root }
            bases += topLevelContentRoots()
        }
        for (path in listOfNotNull(record.fullPath, record.filePath)) {
            val normalized = path.replace('\\', '/')
            if (normalized.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(normalized)) {
                (root?.fileSystem?.findFileByPath(normalized) ?: LocalFileSystem.getInstance().findFileByPath(normalized))
                    ?.takeIf { !it.isDirectory }?.let { return it }
            } else {
                for (base in bases) base.findFileByRelativePath(normalized)?.takeIf { !it.isDirectory }?.let { return it }
            }
        }
        return null
    }

    // ---- Cached file contents ---------------------------------------------------------------------------------------

    private fun <T> cachedParse(file: VirtualFile, cache: MutableMap<VirtualFile, Parsed<T>>, maxSize: Long, parse: (CharSequence) -> T): T? {
        if (!file.isValid) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        cache[file]?.takeIf { it.stamp == stamp }?.let { return it.value }
        val text: CharSequence = document?.immutableCharSequence ?: run {
            if (file.length > maxSize) return null
            try {
                VfsUtilCore.loadText(file)
            } catch (_: IOException) {
                return null
            }
        }
        val value = parse(text)
        cache[file] = Parsed(stamp, value)
        return value
    }

    // ---- Change tracking --------------------------------------------------------------------------------------------

    private fun onFileEvents(events: List<VFileEvent>) {
        var structure = false
        var content = false
        // VFS events are application wide; only files below this project's content roots matter
        val roots = ProjectRootManager.getInstance(project).contentRoots.map { it.path }
        for (event in events) {
            if (roots.none { FileUtil.isAncestor(it, event.path, false) }) continue
            when (event) {
                is VFileContentChangeEvent -> if (isComponentFile(event.file.name)) content = true
                is VFileCreateEvent -> if (affectsStructure(event.path, event.childName, event.isDirectory)) structure = true
                is VFileDeleteEvent, is VFileMoveEvent, is VFileCopyEvent -> {
                    val file = event.file
                    if (file != null && affectsStructure(event.path, file.name, file.isDirectory)) structure = true
                }

                is VFilePropertyChangeEvent -> if (event.isRename) {
                    val names = listOf(event.oldValue, event.newValue).filterIsInstance<String>()
                    if (event.file.isDirectory || names.any(::isComponentFile)) structure = true
                }
            }
            if (structure && content) break
        }
        if (structure) structureTracker.incModificationCount()
        if (content) contentTracker.incModificationCount()
    }

    private fun affectsStructure(path: String, name: String, isDirectory: Boolean): Boolean =
        isComponentFile(name) || isDirectory && (name == NUXT_BUILD_DIRECTORY || path.split('/').none { isSkippedDirectory(it) })

    private object MetadataUrlTracker : ModificationTracker {
        /** Changes whenever the metadata URL setting does (a different URL means different remote components). */
        override fun getModificationCount(): Long = ComarkSettings.getInstance().componentMetadataUrl.hashCode().toLong()
    }

    companion object {
        private const val VUE_EXTENSION = "vue"
        private const val COMPONENTS_DIRECTORY = "components"
        private const val NUXT_BUILD_DIRECTORY = ".nuxt"
        private const val MAX_SFC_SIZE = 4L * 1024 * 1024
        private const val MAX_META_SIZE = 128L * 1024 * 1024

        private val APP_ROOT_MARKERS = listOf("nuxt.config.ts", "nuxt.config.js", "nuxt.config.mjs", "nuxt.config.mts", "package.json")

        /** Files nuxt-component-meta writes into `.nuxt/`. */
        val META_FILE_NAMES = listOf("component-meta.mjs", "component-meta.json", "component-meta.js")

        /** Dependencies and build output never contain the project's own components. */
        private val SKIPPED_DIRECTORIES = setOf("node_modules", "dist", "coverage", "bower_components")

        private fun isSkippedDirectory(name: String) = name in SKIPPED_DIRECTORIES || (name.length > 1 && name.startsWith('.'))

        private fun isComponentFile(name: String) = name.endsWith(".$VUE_EXTENSION", ignoreCase = true) || name in META_FILE_NAMES

        fun getInstance(project: Project): ComarkComponentService = project.service()
    }
}
