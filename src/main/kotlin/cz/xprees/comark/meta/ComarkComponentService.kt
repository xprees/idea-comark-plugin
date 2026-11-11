package cz.xprees.comark.meta

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
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
import java.util.concurrent.CancellationException
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
 * file or directory is created, deleted, moved or renamed; parsed contents are cached per file by modification stamp (for a
 * `.vue` file also by the stamps of the TypeScript files its prop types are imported from), and unsaved edits of `.vue` and
 * those TypeScript files are seen through their documents. The index itself is a [CachedValue] that depends on all of
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

    /**
     * A parsed `.vue` file: its own [stamp], the stamps of the TypeScript files its prop types were read from, and the
     * [typeModuleTracker] count at parse time when it imported types (-1 otherwise), because a created, deleted or moved
     * TypeScript file can change what its imports resolve to.
     */
    private class ParsedSfc(val stamp: Long, val dependencies: Map<VirtualFile, Long>, val modulesStamp: Long, val value: SfcInfo)

    /** Changes when files that may be components or metadata appear, disappear or move. */
    private val structureTracker = SimpleModificationTracker()

    /** Changes when the content of a `.vue` or metadata file, or of a TypeScript file a component imports types from, changes. */
    private val contentTracker = SimpleModificationTracker()

    /** Changes when TypeScript files or directories appear, disappear or move (imports may resolve differently). */
    private val typeModuleTracker = SimpleModificationTracker()

    private val sfcCache = ConcurrentHashMap<VirtualFile, ParsedSfc>()

    /** TypeScript files some cached component reads prop types from: editing one of them changes that component. */
    private val typeDependencies: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()
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
                if (isComponentFile(file.name) || file in typeDependencies) contentTracker.incModificationCount()
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

    /**
     * Props, slots and description parsed from the `.vue` [file], or null when unreadable. Prop types imported from TypeScript
     * files are resolved ([NuxtTypeModules]); the result is cached by the modification stamps of the file and of those imports.
     */
    @RequiresReadLock
    fun sfcInfo(file: VirtualFile): SfcInfo? {
        if (!file.isValid) return null
        val stamp = stampOf(file)
        sfcCache[file]?.takeIf { it.stamp == stamp && isUpToDate(it) }?.let { return it.value }
        val text = loadText(file, MAX_SFC_SIZE) ?: return null
        val modules = NuxtTypeModules(
            component = file,
            rootFallback = { ProjectFileIndex.getInstance(project).getContentRootForFile(it) },
            stamp = ::stampOf,
            load = { loadText(it, MAX_SFC_SIZE) },
        )
        val modulesStamp = typeModuleTracker.modificationCount
        val value = try {
            VueSfcParser.parse(text, modules)
        } catch (e: Exception) {
            // one malformed (or half-typed) component must not take component support down in every Comark file
            if (e is ControlFlowException || e is CancellationException) throw e
            LOG.warn("Comark: cannot parse component ${file.path}", e)
            SfcInfo.EMPTY
        }
        sfcCache[file] = ParsedSfc(stamp, modules.dependencies, if (modules.consulted) modulesStamp else -1, value)
        typeDependencies += modules.dependencies.keys
        return value
    }

    private fun isUpToDate(parsed: ParsedSfc): Boolean {
        if (parsed.modulesStamp >= 0 && parsed.modulesStamp != typeModuleTracker.modificationCount) return false
        return parsed.dependencies.all { (file, stamp) -> file.isValid && stampOf(file) == stamp }
    }

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
        typeDependencies.clear()
        structureTracker.incModificationCount()
        contentTracker.incModificationCount()
        typeModuleTracker.incModificationCount()
        val url = ComarkSettings.getInstance().componentMetadataUrl
        if (url.isNotBlank()) ComarkRemoteMetadata.getInstance().refresh(url)
        DaemonCodeAnalyzer.getInstance(project).restart("Comark components refreshed")
    }

    override fun dispose() {
        sfcCache.clear()
        metaCache.clear()
        typeDependencies.clear()
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

    /**
     * The components being merged by one [buildIndex]: indexed by file and by the [ComponentNaming.lookupKey] of every name, so
     * that matching a metadata record costs a map lookup instead of a scan of all components.
     */
    private class IndexBuilder {
        val components = ArrayList<ComarkComponent>()
        val byFile = HashMap<VirtualFile, Int>()
        private val byKey = HashMap<String, MutableList<Int>>()

        /** Directories relative metadata paths without an app of their own are resolved against, computed once per build. */
        var remoteBases: Collection<VirtualFile>? = null

        fun add(component: ComarkComponent): Int {
            val index = components.size
            components += component
            component.file?.let { byFile[it] = index }
            indexNames(component, index)
            return index
        }

        fun replace(index: Int, component: ComarkComponent) {
            components[index] = component
            component.file?.let { byFile[it] = index }
            indexNames(component, index)
        }

        /** Indices of the components one of whose names has the lookup key [key], in the order they were added. */
        fun withKey(key: String): List<Int> = byKey[key].orEmpty()

        private fun indexNames(component: ComarkComponent, index: Int) {
            for (name in component.allNames) {
                val indices = byKey.getOrPut(ComponentNaming.lookupKey(name)) { ArrayList(1) }
                if (index !in indices) {
                    indices += index
                    indices.sort()
                }
            }
        }
    }

    private fun buildIndex(files: DiscoveredFiles): ComponentIndex {
        val builder = IndexBuilder()
        for (vue in files.vueFiles) {
            ProgressManager.checkCanceled()
            val names = ComponentNaming.componentNames(vue.relativePath)
            if (names.isEmpty()) continue
            val info = sfcInfo(vue.file) ?: SfcInfo.EMPTY
            builder.add(
                ComarkComponent(
                    name = names.first(),
                    aliases = names.drop(1),
                    description = info.description,
                    props = info.props,
                    slots = info.slots,
                    source = ComponentSource.VUE_FILE,
                    file = vue.file,
                    root = vue.root,
                ),
            )
        }
        for (meta in files.metaFiles) {
            val records = cachedParse(meta.file, metaCache, MAX_META_SIZE) { ComponentMetaParser.parse(it) } ?: continue
            for (record in records) {
                ProgressManager.checkCanceled()
                merge(record, ComponentSource.COMPONENT_META, meta.root, builder)
            }
        }
        val settings = ComarkSettings.getInstance()
        if (settings.componentMetadataUrl.isNotBlank()) {
            val records = ComarkRemoteMetadata.getInstance().records(settings.componentMetadataUrl, settings.componentMetadataCacheMinutes)
            if (records.isNotEmpty()) builder.remoteBases = LinkedHashSet<VirtualFile>().apply {
                builder.components.mapNotNullTo(this) { it.root }
                addAll(topLevelContentRoots())
            }
            for (record in records) {
                ProgressManager.checkCanceled()
                merge(record, ComponentSource.REMOTE, null, builder)
            }
        }
        val known = files.vueFiles.mapTo(HashSet()) { it.file }
        sfcCache.keys.retainAll(known)
        metaCache.keys.retainAll(files.metaFiles.mapTo(HashSet()) { it.file })
        typeDependencies.retainAll(sfcCache.values.flatMapTo(HashSet()) { it.dependencies.keys })
        return ComponentIndex(builder.components)
    }

    /** Adds [record] to the [builder], merging it into the component of the same file (or the same name in the same app). */
    private fun merge(record: MetaComponentRecord, source: ComponentSource, root: VirtualFile?, builder: IndexBuilder) {
        val components = builder.components
        val file = resolveFile(record, root, builder.remoteBases.orEmpty())
        val existing = file?.let { builder.byFile[it] }
            ?: builder.withKey(ComponentNaming.lookupKey(record.name)).firstOrNull { sameApp(components[it], root) }
        if (existing != null) {
            builder.replace(existing, merged(components[existing], record, source))
            if (file != null) builder.byFile[file] = existing
            return
        }
        builder.add(
            ComarkComponent(
                name = record.name,
                description = record.description,
                documentation = record.documentation,
                docsUrl = record.docsUrl,
                props = record.props,
                slots = record.slots,
                source = source,
                file = file,
                root = root,
            ),
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

    /**
     * The `.vue` file a metadata record describes: its absolute path, or its path relative to the Nuxt app [root] (for a record
     * without an app, relative to [remoteBases]: the app and content roots).
     */
    private fun resolveFile(record: MetaComponentRecord, root: VirtualFile?, remoteBases: Collection<VirtualFile>): VirtualFile? {
        val bases = if (root != null) listOf(root) else remoteBases
        for (path in listOfNotNull(record.fullPath, record.filePath)) {
            val normalized = path.replace('\\', '/')
            if (normalized.startsWith("/") || WINDOWS_ABSOLUTE_PATH.containsMatchIn(normalized)) {
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
        val stamp = stampOf(file)
        cache[file]?.takeIf { it.stamp == stamp }?.let { return it.value }
        val text = loadText(file, maxSize) ?: return null
        val value = parse(text)
        cache[file] = Parsed(stamp, value)
        return value
    }

    /** The modification stamp of [file]'s document when it is open (unsaved edits count), else of the file. */
    private fun stampOf(file: VirtualFile): Long =
        FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp

    /** The text of [file]: its document when it is open, else its VFS content when not larger than [maxSize]; null if unreadable. */
    private fun loadText(file: VirtualFile, maxSize: Long): CharSequence? {
        if (!file.isValid || file.isDirectory) return null
        FileDocumentManager.getInstance().getCachedDocument(file)?.let { return it.immutableCharSequence }
        if (file.length > maxSize) return null
        return try {
            VfsUtilCore.loadText(file)
        } catch (_: IOException) {
            null
        }
    }

    // ---- Change tracking --------------------------------------------------------------------------------------------

    private fun onFileEvents(events: List<VFileEvent>) {
        var structure = false
        var content = false
        var modules = false
        // VFS events are application wide; only files below this project's content roots matter
        val roots = ProjectRootManager.getInstance(project).contentRoots.map { it.path }
        for (event in events) {
            val root = roots.firstOrNull { FileUtil.isAncestor(it, event.path, false) } ?: continue
            val relativePath = event.path.removePrefix(root)
            when (event) {
                is VFileContentChangeEvent -> if (isComponentFile(event.file.name) || event.file in typeDependencies) content = true
                is VFileCreateEvent -> {
                    if (affectsStructure(event.path, event.childName, event.isDirectory)) structure = true
                    if (affectsModules(relativePath, event.childName, event.isDirectory)) modules = true
                }

                is VFileDeleteEvent, is VFileMoveEvent, is VFileCopyEvent -> {
                    val file = event.file
                    if (file != null && affectsStructure(event.path, file.name, file.isDirectory)) structure = true
                    if (file != null && affectsModules(relativePath, file.name, file.isDirectory)) modules = true
                }

                is VFilePropertyChangeEvent -> if (event.isRename) {
                    val names = listOf(event.oldValue, event.newValue).filterIsInstance<String>()
                    if (event.file.isDirectory || names.any(::isComponentFile)) structure = true
                    if (names.any { affectsModules(relativePath, it, event.file.isDirectory) }) modules = true
                }
            }
            if (structure && content && modules) break
        }
        if (structure) structureTracker.incModificationCount()
        if (modules) typeModuleTracker.incModificationCount()
        if (content || modules) contentTracker.incModificationCount()
    }

    /**
     * Whether creating, deleting or moving the file [name] at [path] can change what a component's type imports resolve to:
     * TypeScript files, project root markers and directories, outside dependencies and build output.
     */
    private fun affectsModules(path: String, name: String, isDirectory: Boolean): Boolean =
        (isDirectory || NuxtTypeModules.isTypeModule(name) || name in NuxtTypeModules.ROOT_MARKERS) &&
            path.split('/').none { isSkippedDirectory(it) }

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
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/")
        private val LOG = logger<ComarkComponentService>()

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
