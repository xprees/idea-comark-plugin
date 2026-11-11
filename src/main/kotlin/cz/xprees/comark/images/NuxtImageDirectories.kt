package cz.xprees.comark.images

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import cz.xprees.comark.lang.NuxtPaths
import cz.xprees.comark.settings.ComarkSettings
import java.util.concurrent.ConcurrentHashMap

/** Where an [ImageDir] comes from. */
enum class ImageDirKind {
    /** Listed in [ComarkSettings.imageDirectories]. */
    OVERRIDE,

    /** A layer's Nuxt Image directory (`image.dir`). */
    IMAGE,

    /** A layer's public directory (`dir.public`). */
    PUBLIC,
}

/**
 * A directory image paths resolve against.
 *
 * @property path the absolute, `/`-separated path; the directory may not exist (yet).
 * @property layer the path of the Nuxt layer it belongs to.
 * @property label the path shown to the user: relative to the Nuxt root (`public`, `app/assets/images`, `layers/base/public`), or
 * the settings entry as written for [ImageDirKind.OVERRIDE].
 */
data class ImageDir(val path: String, val kind: ImageDirKind, val layer: String, val label: String, val exists: Boolean)

/**
 * The image directories of one Nuxt project, in priority order (the first directory holding a path wins), and how its URLs are
 * prefixed.
 *
 * @property nuxtRoot the path of the Nuxt project (the root layer).
 * @property nuxtImage some layer uses Nuxt Image.
 * @property isNuxt the root is a Nuxt project (it has a `nuxt.config.*`, or its `package.json` depends on Nuxt or Nuxt Content).
 */
data class ImageRoots(
    val nuxtRoot: String,
    val dirs: List<ImageDir>,
    val baseURL: String?,
    val cdnURL: String?,
    val aliases: Map<String, String>,
    val nuxtImage: Boolean,
    val isNuxt: Boolean,
)

/** An image path resolved to a file: [file] was found in [dir]. */
data class ResolvedImage(val file: VirtualFile, val dir: ImageDir, val src: ImageSrc)

/** An image a project serves: [file] under the public path [path] (`/threats/x.webp`), found in [dir]. */
data class ImageEntry(val path: String, val file: VirtualFile, val dir: ImageDir)

/**
 * Resolves image paths (`/threats/x.webp`) of Comark documents to files, the way the Nuxt site serves them.
 *
 * The Nuxt root of a document is the nearest directory above it holding a `nuxt.config.*`, moved up to an app that consumes it as a
 * layer (`<app>/layers/<it>` or a local `extends` entry); without a configuration, the nearest directory with a `package.json`.
 * Its layers are the root itself, the directories in `<root>/layers/` with a `nuxt.config.*` (by name), then local `extends` entries
 * (recursively). Directories are tried in this order:
 * 1. when [ComarkSettings.imageDirectories] lists any, only those (project-relative or absolute; missing ones are skipped);
 * 2. otherwise, for each layer using Nuxt Image (`@nuxt/image` in a `package.json` or `modules`), its image directory
 * `resolve(srcDir, image.dir || dir.public || 'public')`, `srcDir` being the configured one, else `app/` when it exists, else the
 * layer;
 * 3. then each layer's public directory `resolve(layer, dir.public || 'public')`.
 *
 * Paths may carry the CDN URL ([ComarkSettings.imageCdnUrl], else `app.cdnURL`), `app.baseURL` and `/_ipx/<modifiers>` prefixes;
 * `image.alias` paths are remote and never resolve. The configuration is read as text (see [NuxtImageConfig]) and cached until a
 * `nuxt.config.*` or `package.json` changes, the settings change, or a candidate directory or layer is created, deleted or moved;
 * [tracker] counts those changes for caches built on top.
 */
@Service(Service.Level.PROJECT)
class NuxtImageDirectories(private val project: Project) : Disposable {
    private class Cached(val dependencies: List<Pair<VirtualFile, Long>>, val roots: ImageRoots) {
        fun isValid() = dependencies.all { (file, stamp) -> file.isValid && file.modificationStamp == stamp }
    }

    private class Listing(val roots: ImageRoots, val tracker: Long, val structure: Long, val entries: List<ImageEntry>)

    private class Layer(val dir: VirtualFile, val config: NuxtConfigInfo, val manifest: CharSequence?, val depth: Int)

    /** Counts invalidations: configuration, settings or directory layout changes. */
    val tracker = SimpleModificationTracker()

    /** Per directory: the path of its Nuxt root ("" for none). */
    private val rootOf = ConcurrentHashMap<String, String>()
    private val cache = ConcurrentHashMap<String, Cached>()
    private val listings = ConcurrentHashMap<String, Listing>()

    /** Paths whose creation, deletion or move changes some cached result: candidate directories (existing or not) and layers. */
    private val watched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var settingsKey: String? = null

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any(::affects)) invalidate()
            }
        })
    }

    /** The image directories for the Comark document [file], or null when images are disabled or [file] is in no project. */
    fun forFile(file: VirtualFile): ImageRoots? {
        val settings = ComarkSettings.getInstance()
        checkSettings(settings)
        if (!settings.imagesEnabled) return null
        val directory = (if (file.isDirectory) file else file.parent) ?: return null
        if (!directory.isInLocalFileSystem && directory.fileSystem.protocol != "temp") return null
        val root = rootFor(directory) ?: return null
        cache[root.path]?.takeIf { it.isValid() }?.let { return it.roots }
        val dependencies = ArrayList<Pair<VirtualFile, Long>>()
        val result = compute(root, settings, dependencies)
        cache[root.path] = Cached(dependencies, result)
        return result
    }

    /** The file the image path [raw] (as written in [context]) stands for, or null when it is not a local image path or is missing. */
    fun resolve(context: VirtualFile, raw: String): ResolvedImage? {
        val roots = forFile(context) ?: return null
        val src = ImagePaths.normalize(raw, roots.baseURL, roots.cdnURL, roots.aliases) ?: return null
        if (src.remoteUrl != null) return null
        val relative = src.path.removePrefix("/")
        if (relative.split('/').any { it == ".." || it == "." }) return null
        val fileSystem = context.fileSystem
        for (dir in roots.dirs) {
            val directory = fileSystem.findFileByPath(dir.path)?.takeIf { it.isDirectory } ?: continue
            val file = directory.findFileByRelativePath(relative)?.takeIf { it.isValid && !it.isDirectory } ?: continue
            return ResolvedImage(file, dir, src)
        }
        return null
    }

    /** The public path (`/threats/x.webp`) [image] is served under for documents like [context], or null when no directory holds it. */
    fun pathFor(image: VirtualFile, context: VirtualFile): String? {
        val roots = forFile(context) ?: return null
        val path = image.path
        val dir = roots.dirs.firstOrNull { path != it.path && NuxtPaths.isUnder(path, it.path) } ?: return null
        return path.substring(dir.path.trimEnd('/').length)
    }

    /**
     * Every image the documents like [context] can use, in directory priority order; a path in several directories is listed once,
     * from the directory that wins.
     */
    fun listImages(context: VirtualFile): List<ImageEntry> {
        val roots = forFile(context) ?: return emptyList()
        val count = tracker.modificationCount
        val structure = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount
        listings[roots.nuxtRoot]?.takeIf { it.tracker == count && it.structure == structure && it.roots == roots }?.let { return it.entries }
        val entries = LinkedHashMap<String, ImageEntry>()
        val fileSystem = context.fileSystem
        for (dir in roots.dirs) {
            val directory = fileSystem.findFileByPath(dir.path)?.takeIf { it.isDirectory } ?: continue
            VfsUtilCore.visitChildrenRecursively(directory, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    ProgressManager.checkCanceled()
                    if (file.isDirectory) return file == directory || !isSkipped(file.name)
                    if (ImagePaths.isImageFile(file)) {
                        val relative = VfsUtilCore.getRelativePath(file, directory) ?: return true
                        entries.putIfAbsent("/$relative", ImageEntry("/$relative", file, dir))
                    }
                    return true
                }
            })
        }
        val result = entries.values.toList()
        listings[roots.nuxtRoot] = Listing(roots, count, structure, result)
        return result
    }

    /** True when [context] belongs to a Nuxt project (and images are enabled). */
    fun isNuxt(context: VirtualFile): Boolean = forFile(context)?.isNuxt == true

    /**
     * The image directories of [context]'s project (default: the project directory, or the first Nuxt project directly below it),
     * as labels joined with ` > ` in priority order; empty when there are none.
     */
    fun detectedLabel(context: VirtualFile? = null): String {
        val start = context ?: project.guessProjectDir()?.let { base ->
            if (isNuxtDirectory(base)) base else base.children.filter { it.isDirectory && !isSkipped(it.name) }.sortedBy { it.name }
                .firstOrNull(::isNuxtDirectory) ?: base
        } ?: return ""
        return forFile(start)?.dirs?.joinToString(" > ") { it.label }.orEmpty()
    }

    /** Drops every cached result and restarts highlighting. */
    fun invalidate() {
        clear()
        tracker.incModificationCount()
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("Comark image directories changed")
    }

    override fun dispose() = clear()

    private fun clear() {
        rootOf.clear()
        cache.clear()
        listings.clear()
        watched.clear()
    }

    private fun checkSettings(settings: ComarkSettings) {
        val key = "${settings.imagesEnabled}\u0000${settings.imageDirectories}\u0000${settings.imageCdnUrl}"
        if (settingsKey == key) return
        val first = settingsKey == null
        settingsKey = key
        if (!first) {
            clear()
            tracker.incModificationCount()
        }
    }

    // ---- Root and layer discovery ---------------------------------------------------------------------------------

    private fun rootFor(directory: VirtualFile): VirtualFile? {
        rootOf[directory.path]?.let { path -> return if (path.isEmpty()) null else directory.fileSystem.findFileByPath(path) }
        val base = project.guessProjectDir()
        val bounded = base != null && VfsUtilCore.isAncestor(base, directory, false)
        var root: VirtualFile? = null
        var manifest: VirtualFile? = null
        var current: VirtualFile? = directory
        while (current != null) {
            val candidate: VirtualFile = current
            if (nuxtConfig(candidate) != null) {
                val inner = root
                // an outer app takes over when it consumes the inner project as a layer
                if (inner == null || consumes(candidate, inner)) root = candidate
            }
            if (manifest == null && candidate.findChild(MANIFEST) != null) manifest = candidate
            if (bounded && candidate == base) break
            current = candidate.parent
        }
        val result = root ?: manifest ?: base?.takeIf { bounded }
        rootOf[directory.path] = result?.path.orEmpty()
        return result
    }

    private fun consumes(app: VirtualFile, layer: VirtualFile): Boolean {
        if (layer.parent?.name == LAYERS && layer.parent?.parent == app) return true
        val config = nuxtConfig(app)?.let(::load)?.let { NuxtImageConfig.parse(it.toString()) } ?: return false
        return config.extends.any { extendsPath(app, config, it) == layer.path }
    }

    private fun compute(root: VirtualFile, settings: ComarkSettings, dependencies: MutableList<Pair<VirtualFile, Long>>): ImageRoots {
        fun read(file: VirtualFile?): CharSequence? {
            file ?: return null
            dependencies += file to file.modificationStamp
            return load(file)
        }

        fun layer(dir: VirtualFile, depth: Int) =
            Layer(dir, read(nuxtConfig(dir))?.let { NuxtImageConfig.parse(it.toString()) } ?: NuxtConfigInfo(), read(dir.findChild(MANIFEST)), depth)

        val rootLayer = layer(root, 0)
        val isNuxt = nuxtConfig(root) != null || rootLayer.manifest?.let(NuxtImageConfig::manifestIsNuxt) == true
        val layers = LinkedHashMap<String, Layer>()
        layers[root.path] = rootLayer
        root.findChild(LAYERS)?.takeIf { it.isDirectory }?.children.orEmpty()
            .filter { it.isDirectory && nuxtConfig(it) != null }.sortedBy { it.name }
            .forEach { layers.putIfAbsent(it.path, layer(it, 1)) }
        var index = 0
        while (index < layers.size) {
            val current = layers.values.elementAt(index++)
            if (current.depth >= MAX_EXTENDS_DEPTH) continue
            for (entry in current.config.extends) {
                val path = extendsPath(current.dir, current.config, entry)
                if (path in layers || isGenerated(path)) continue
                val dir = root.fileSystem.findFileByPath(path)?.takeIf { it.isDirectory } ?: continue
                layers[path] = layer(dir, current.depth + 1)
            }
        }
        watched += root.path + "/" + LAYERS
        layers.keys.forEach(watched::add)

        val rootImage = rootLayer.config.hasImageModule || rootLayer.manifest?.let(NuxtImageConfig::manifestHasNuxtImage) == true
        val nuxtImage = layers.values.map { layer ->
            rootImage || layer.config.hasImageModule || layer.manifest?.let(NuxtImageConfig::manifestHasNuxtImage) == true
        }
        val dirs = ArrayList<ImageDir>()
        val overrides = settings.imageDirectoryList()
        if (overrides.isNotEmpty()) {
            val base = project.guessProjectDir()
            for (entry in overrides) {
                val path = FileUtil.toSystemIndependentName(entry)
                val dir = (if (FileUtil.isAbsolute(path)) (root.fileSystem.findFileByPath(path) ?: LocalFileSystem.getInstance().findFileByPath(path))
                else base?.findFileByRelativePath(path))?.takeIf { it.isDirectory }
                if (dir == null) {
                    base?.let { watched += NuxtPaths.resolve(it.path, path) }
                    continue
                }
                dirs += ImageDir(dir.path, ImageDirKind.OVERRIDE, root.path, entry, true)
            }
        } else if (isNuxt) {
            for ((layer, image) in layers.values.zip(nuxtImage)) {
                if (!image) continue
                val srcDir = NuxtPaths.sourceRoot(layer.dir, layer.config.srcDir)
                val configured = layer.config.imageDir ?: rootLayer.config.imageDir.takeIf { layer !== rootLayer }
                dirs += dir(root, layer, NuxtPaths.resolve(srcDir, configured ?: layer.config.publicDir ?: PUBLIC), ImageDirKind.IMAGE)
            }
            for (layer in layers.values) {
                dirs += dir(root, layer, NuxtPaths.resolve(layer.dir.path, layer.config.publicDir ?: PUBLIC), ImageDirKind.PUBLIC)
            }
        }
        val distinct = dirs.distinctBy { it.path }
        distinct.forEach { watched += it.path }
        val ordered = layers.values.toList()
        val cdnURL = settings.imageCdnUrl.ifBlank { null } ?: ordered.firstNotNullOfOrNull { it.config.cdnURL }
        val aliases = LinkedHashMap<String, String>()
        ordered.reversed().forEach { aliases.putAll(it.config.imageAliases) }
        return ImageRoots(
            nuxtRoot = root.path,
            dirs = distinct,
            baseURL = ordered.firstNotNullOfOrNull { it.config.baseURL },
            cdnURL = cdnURL,
            aliases = aliases,
            nuxtImage = nuxtImage.any { it },
            isNuxt = isNuxt,
        )
    }

    private fun dir(root: VirtualFile, layer: Layer, path: String, kind: ImageDirKind): ImageDir {
        val label = FileUtil.getRelativePath(root.path, path, '/')?.takeIf { it != "." } ?: path.substringAfterLast('/')
        val exists = root.fileSystem.findFileByPath(path)?.isDirectory == true
        return ImageDir(path, kind, layer.dir.path, label, exists)
    }

    /** The directory an `extends` [entry] of the layer [dir] names: `~~/x` and `@@/x` against the layer, `~/x` and `@/x` its srcDir. */
    private fun extendsPath(dir: VirtualFile, config: NuxtConfigInfo, entry: String): String = when {
        entry.startsWith("~~/") || entry.startsWith("@@/") -> NuxtPaths.resolve(dir.path, entry.substring(3))
        entry.startsWith("~/") || entry.startsWith("@/") -> NuxtPaths.resolve(NuxtPaths.sourceRoot(dir, config.srcDir), entry.substring(2))
        else -> NuxtPaths.resolve(dir.path, entry)
    }

    private fun isNuxtDirectory(dir: VirtualFile): Boolean =
        nuxtConfig(dir) != null || dir.findChild(MANIFEST)?.let(::load)?.let(NuxtImageConfig::manifestIsNuxt) == true

    // ---- Invalidation -----------------------------------------------------------------------------------------------

    private fun affects(event: VFileEvent): Boolean {
        val path = event.path
        if (isGenerated(path)) return false
        val name = path.substringAfterLast('/')
        if (name == MANIFEST || NuxtPaths.NUXT_CONFIG.matches(name)) return true
        val paths = when (event) {
            is VFileCreateEvent, is VFileDeleteEvent -> listOf(path)
            is VFileMoveEvent -> listOf(path, event.oldPath)
            is VFileCopyEvent -> listOf(event.newParent.path + "/" + event.newChildName)
            is VFilePropertyChangeEvent -> if (event.isRename) listOf(path, event.file.parent?.path + "/" + event.oldValue) else return false
            else -> return false
        }
        return paths.any { changed ->
            val changedName = changed.substringAfterLast('/')
            changedName == NuxtPaths.NUXT_4_SOURCE_DIRECTORY || changed.substringBeforeLast('/').endsWith("/$LAYERS") ||
                    watched.any { NuxtPaths.isUnder(it, changed) }
        }
    }

    companion object {
        private const val MANIFEST = "package.json"
        private const val LAYERS = "layers"
        private const val PUBLIC = "public"
        private const val NODE_MODULES = "node_modules"
        private const val NUXT_BUILD = ".nuxt"
        private const val MAX_EXTENDS_DEPTH = 5
        private val CONFIG_ORDER = listOf("ts", "js", "mjs", "mts", "cjs", "cts")

        fun getInstance(project: Project): NuxtImageDirectories = project.service()

        private fun isSkipped(name: String) = name == NODE_MODULES || name.startsWith(".")

        /** Paths inside `node_modules` or the `.nuxt` build directory, which never hold project layers or sources. */
        private fun isGenerated(path: String) = path.split('/').any { it == NODE_MODULES || it == NUXT_BUILD }

        /** The `nuxt.config.*` of [dir] (`.ts` preferred), or null. */
        private fun nuxtConfig(dir: VirtualFile): VirtualFile? =
            dir.children.filter { !it.isDirectory && NuxtPaths.NUXT_CONFIG.matches(it.name) }.minByOrNull { CONFIG_ORDER.indexOf(it.extension) }

        private fun load(file: VirtualFile): CharSequence? = try {
            VfsUtilCore.loadText(file)
        } catch (_: Exception) {
            null
        }
    }
}
