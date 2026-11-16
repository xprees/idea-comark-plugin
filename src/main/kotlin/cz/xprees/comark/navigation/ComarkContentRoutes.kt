package cz.xprees.comark.navigation

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import cz.xprees.comark.format.ComarkCollections
import cz.xprees.comark.format.StudioCollection
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.lang.ComarkProjectDetector
import cz.xprees.comark.lang.NuxtPaths
import cz.xprees.comark.psi.ComarkFile
import java.util.Locale

/**
 * Page routes of a Nuxt Content package, following Nuxt Content's default routing (`generatePath` in its `path-meta` utilities).
 *
 * The pages are the Markdown files under the package's `content/` directory, the one next to the nearest `package.json` above the
 * linking file. Every path segment becomes a route segment without its numeric ordering prefix (`1.guide` is `guide`,
 * `02.install.md` is `install`), its extension and a `.draft` suffix, lower-cased with blanks as `-`; `index` files stand for their
 * directory (`content/index.md` is `/`, `content/1.guide/1.index.md` is `/guide`). Data files (`.yml`, `.json`, `.csv`) are no
 * pages, and neither are hidden (`.`) or partial (`_`) files and directories.
 *
 * When the package has a Nuxt Content v3 configuration (`content.config.*`, see [ComarkCollections]) with collections, the routes
 * are those of its page collections instead, as Nuxt Content 3.16 builds them (`defineLocalSource`, `describeId`,
 * `generatePath`): for each source, the files below its `cwd` that match `include` and not `exclude`, each routed as the source's
 * `prefix` (by default the static part of `include`, `/threats/en` for `threats/en/**/*.md`) followed by its path below that
 * static part, every segment refined as above. Data collections have no routes. A `path` in a page's front matter replaces the
 * generated route in both cases.
 */
object ComarkContentRoutes {
    /** A route link in a link destination: [path] is the route without a trailing `/`, [range] its range in the destination text. */
    data class RouteLink(val path: String, val range: TextRange)

    private val PAGE_EXTENSIONS = setOf("md", "mdc", "markdown")
    private val ORDERING_PREFIX = Regex("""^\d+\.""")
    private val SEMVER = Regex("""^\d+(?:\.\d+)?(?:\.\d+)?$""")
    private val BLANKS = Regex("""\s+""")

    /**
     * The route of the link destination [text] (as written, without `<…>`), relative to [valueRange] inside it: an absolute path
     * (`/guide/install`, optionally followed by `#anchor` or `?query`) whose last segment has no file extension. Null for relative
     * links, URLs, protocol-relative links (`//host`) and file links (`/images/logo.png`).
     */
    fun parseRouteLink(text: String, valueRange: TextRange = TextRange(0, text.length)): RouteLink? {
        if (valueRange.isEmpty || valueRange.endOffset > text.length) return null
        val value = valueRange.substring(text)
        val end = value.indexOfFirst { it == '#' || it == '?' }.let { if (it < 0) value.length else it }
        val path = value.substring(0, end)
        if (!path.startsWith("/") || path.startsWith("//")) return null
        if ('.' in path.substringAfterLast('/') || path.any { it.isWhitespace() || it == '\\' }) return null
        val normalized = if (path.length > 1) path.trimEnd('/') else path
        return RouteLink(normalized, TextRange.from(valueRange.startOffset, end))
    }

    /** The `content/` directory of the Nuxt Content package [file] belongs to, or null outside such packages. */
    fun contentDirectory(file: PsiFile): VirtualFile? {
        if (file !is ComarkFile) return null
        val virtualFile = file.originalFile.virtualFile ?: return null
        if (!ComarkProjectDetector.getInstance(file.project).isComarkContext(virtualFile)) return null
        var directory = virtualFile.parent
        while (directory != null) {
            if (directory.findChild(MANIFEST)?.isDirectory == false) {
                directory.findChild(CONTENT)?.takeIf { it.isDirectory }?.let { return it }
            }
            directory = directory.parent
        }
        return null
    }

    /**
     * Routes of the pages of the package whose content directory is [contentDirectory], mapped to their files; cached until files
     * are added, removed or renamed, a Comark file changes (its front matter may set `path`) or the configuration changes.
     */
    fun routes(project: Project, contentDirectory: VirtualFile): Map<String, VirtualFile> {
        val directory = PsiManager.getInstance(project).findDirectory(contentDirectory) ?: return emptyMap()
        return CachedValuesManager.getCachedValue(directory) {
            val configuration = contentDirectory.parent?.let { ComarkCollections.getInstance(project).configurationAt(it) }
            val dependencies = mutableListOf<Any>(
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                PsiModificationTracker.getInstance(project).forLanguage(ComarkLanguage),
            )
            configuration?.first?.let { dependencies += it }
            val collections = configuration?.second?.takeIf { it.isNotEmpty() }
            val routes = if (configuration != null && collections != null) {
                collectionRoutes(configuration.first, collections)
            } else {
                collect(contentDirectory)
            }
            CachedValueProvider.Result.create(withFrontMatterPaths(routes), *dependencies.toTypedArray())
        }
    }

    /** The page file the route [path] (as returned by [parseRouteLink]) of a link in [file] stands for. */
    fun resolve(file: PsiFile, path: String): VirtualFile? {
        val content = contentDirectory(file) ?: return null
        return routes(file.project, content)[path]?.takeIf { it.isValid }
    }

    /** The route segment of a file or directory named [name]; empty for index pages, null for names that are no pages. */
    fun routeSegment(name: String, isDirectory: Boolean): String? {
        if (name.startsWith(".") || name.startsWith("_")) return null
        val base = if (isDirectory) {
            name
        } else {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension !in PAGE_EXTENSIONS) return null
            name.substring(0, name.length - extension.length - 1)
        }
        val refined = if (SEMVER.matches(base)) {
            base
        } else {
            val withoutPrefix = base.replace(ORDERING_PREFIX, "").removeSuffix(".draft")
            if (!isDirectory && withoutPrefix == "index") "" else withoutPrefix
        }
        return refined.trim().replace(BLANKS, "-").lowercase(Locale.ROOT)
    }

    private fun collect(contentDirectory: VirtualFile): Map<String, VirtualFile> {
        val routes = LinkedHashMap<String, VirtualFile>()
        fun visit(directory: VirtualFile, route: String) {
            ProgressManager.checkCanceled()
            val children = directory.children.sortedWith(compareBy({ it.isDirectory }, { it.name }))
            for (child in children) {
                val segment = routeSegment(child.name, child.isDirectory) ?: continue
                val childRoute = if (segment.isEmpty()) route else "$route/$segment"
                if (child.isDirectory) visit(child, childRoute) else routes.putIfAbsent(childRoute.ifEmpty { "/" }, child)
            }
        }
        visit(contentDirectory, "")
        return routes
    }

    /** Routes of the page collections of the configuration [config], as Nuxt Content generates them. */
    private fun collectionRoutes(config: VirtualFile, collections: List<ComarkCollections.Collection>): Map<String, VirtualFile> {
        val root = config.parent?.path ?: return emptyMap()
        val routes = LinkedHashMap<String, VirtualFile>()
        for (collection in collections) {
            if (collection.type != StudioCollection.Type.PAGE) continue
            for (source in collection.sources) {
                val cwd = config.fileSystem.findFileByPath(NuxtPaths.resolve(root, source.cwd))?.takeIf { it.isDirectory } ?: continue
                val fixed = source.fixed
                fun visit(directory: VirtualFile, relative: String) {
                    ProgressManager.checkCanceled()
                    for (child in directory.children.sortedWith(compareBy({ it.isDirectory }, { it.name }))) {
                        val path = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                        if (child.isDirectory) {
                            if (child.name != NODE_MODULES && child.name != ".git") visit(child, path)
                            continue
                        }
                        if (child.extension?.lowercase(Locale.ROOT) !in PAGE_EXTENSIONS) continue
                        if (!path.startsWith(fixed) || !ComarkCollections.matches(root, source, child.path)) continue
                        routes.putIfAbsent(collectionRoute(source.routePrefix, path.substring(fixed.length)), child)
                    }
                }
                visit(cwd, "")
            }
        }
        return routes
    }

    /** Nuxt Content's `generatePath` of [prefix] + [key] (the file's path below the source's static part, with its extension). */
    internal fun collectionRoute(prefix: String, key: String): String {
        val stem = key.substringBeforeLast('.', key).takeIf { '/' !in key.substringAfterLast('.', "") } ?: key
        val segments = (prefix.split('/') + stem.split('/')).filter { it.isNotEmpty() }.map(::refineUrlPart).filter { it.isNotEmpty() }
        return "/" + segments.joinToString("/")
    }

    /** Nuxt Content's `refineUrlPart` and `slugify` (lower case): no ordering prefix, `index` and `.draft` dropped. */
    private fun refineUrlPart(part: String): String {
        val refined = if (SEMVER.matches(part)) part else part.replace(ORDERING_PREFIX, "").let { if (it == "index" || it == "index.draft") "" else it.removeSuffix(".draft") }
        return refined.trim().replace(BLANKS, "-").lowercase(Locale.ROOT)
    }

    /** [routes] with each page whose front matter sets `path` moved to that route (as Nuxt Content lets front matter override it). */
    private fun withFrontMatterPaths(routes: Map<String, VirtualFile>): Map<String, VirtualFile> {
        val overrides = routes.values.mapNotNull { file -> frontMatterPath(file)?.let { it to file } }
        if (overrides.isEmpty()) return routes
        val moved = overrides.map { it.second }.toSet()
        val result = LinkedHashMap<String, VirtualFile>()
        for ((path, file) in overrides) result.putIfAbsent(path, file)
        for ((route, file) in routes) if (file !in moved) result.putIfAbsent(route, file)
        return result
    }

    private val FRONT_MATTER_PATH = Regex("""^path\s*:\s*(['"]?)(/[^'"#\s]*)\1\s*(?:#.*)?$""", RegexOption.MULTILINE)

    /** The `path` the front matter of [file] sets (an absolute route), read from its document when open, else from disk. */
    internal fun frontMatterPath(file: VirtualFile): String? {
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence ?: try {
            VfsUtilCore.loadText(file, 4096)
        } catch (_: Exception) {
            return null
        }
        if (!text.startsWith("---")) return null
        val firstBreak = text.indexOf('\n').takeIf { it >= 0 } ?: return null
        if (text.substring(0, firstBreak).trimEnd() != "---") return null
        val end = Regex("""^(?:---|\.\.\.)\s*$""", RegexOption.MULTILINE).find(text, firstBreak + 1)?.range?.first ?: return null
        val path = FRONT_MATTER_PATH.find(text.substring(firstBreak + 1, end))?.groupValues?.get(2) ?: return null
        return if (path.length > 1) path.trimEnd('/') else path
    }

    private const val NODE_MODULES = "node_modules"
    private const val MANIFEST = "package.json"
    private const val CONTENT = "content"
}
