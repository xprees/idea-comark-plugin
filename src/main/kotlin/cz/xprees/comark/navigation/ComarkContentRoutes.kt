package cz.xprees.comark.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import cz.xprees.comark.lang.ComarkProjectDetector
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
 * Collections of Nuxt Content v3 can mount their files under a `prefix` (or a different source directory) in `content.config.ts`;
 * such routes are not known here and simply don't resolve.
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

    /** Routes of the pages in [contentDirectory], mapped to their files; cached until files are added, removed or renamed. */
    fun routes(project: Project, contentDirectory: VirtualFile): Map<String, VirtualFile> {
        val directory = PsiManager.getInstance(project).findDirectory(contentDirectory) ?: return emptyMap()
        return CachedValuesManager.getCachedValue(directory) {
            CachedValueProvider.Result.create(collect(contentDirectory), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
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

    private const val MANIFEST = "package.json"
    private const val CONTENT = "content"
}
