package cz.xprees.comark.lang

import com.intellij.openapi.vfs.VirtualFile

/**
 * Path and configuration-file helpers shared by the parts of the plugin that read a Nuxt project's layout from text
 * ([ComarkProjectDetector], [cz.xprees.comark.meta.NuxtTypeModules], [cz.xprees.comark.images.NuxtImageDirectories]). Paths are
 * `/`-separated strings so they also describe directories that don't exist (yet).
 */
internal object NuxtPaths {
    /** `content.config.ts` (Nuxt Content 3) and `nuxt.config.ts`, in every script flavour. */
    val CONFIG = Regex("""(?:content|nuxt)\.config\.(?:ts|js|mjs|mts|cjs|cts)""")

    /** `nuxt.config.ts` in every script flavour. */
    val NUXT_CONFIG = Regex("""nuxt\.config\.(?:ts|js|mjs|mts|cjs|cts)""")

    /** A string literal in a configuration file; group 1 is its content. */
    val STRING = Regex("""['"`]([^'"`]+)['"`]""")

    val WINDOWS_DRIVE = Regex("""^[A-Za-z]:[\\/]""")

    /** The Nuxt 4 source directory, used when a project has one and sets no explicit `srcDir`. */
    const val NUXT_4_SOURCE_DIRECTORY = "app"

    /** [path] resolved against the directory [root]: absolute paths (`/x`, `C:\x`) stay as they are, `.` and `..` are folded. */
    fun resolve(root: String, path: String): String {
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

    /** True when [path] is [root] or lies below it. */
    fun isUnder(path: String, root: String) = path == root || path.startsWith(if (root.endsWith('/')) root else "$root/")

    /**
     * The source directory (`srcDir`) of the Nuxt layer in [layer]: [explicitSrcDir] resolved against it when the configuration sets
     * one, else the layer itself in Nuxt 3 ([nuxt4] false), else `app/` the way Nuxt 4 picks it: when it exists and holds more than
     * `router.options.*` and `spa-loading-template.html` (which Nuxt 3 projects keep in `app/` too), or when the layer has no Nuxt 3
     * sources (`app.vue`, `pages/`, ...) of its own. [nuxt4] null (unknown version) applies the Nuxt 4 rule, which leaves Nuxt 3
     * layouts at the layer.
     */
    fun sourceRoot(layer: VirtualFile, explicitSrcDir: String? = null, nuxt4: Boolean? = null): String {
        if (!explicitSrcDir.isNullOrBlank()) return resolve(layer.path, explicitSrcDir)
        if (nuxt4 == false) return layer.path
        val app = layer.findChild(NUXT_4_SOURCE_DIRECTORY)?.takeIf { it.isDirectory } ?: return layer.path
        val sources = app.children.any { it.name != SPA_LOADING_TEMPLATE && !it.name.startsWith(ROUTER_OPTIONS) }
        if (sources) return app.path
        val rootSources = ROOT_SOURCE_FILES.any { layer.findChild(it)?.isDirectory == false } ||
                ROOT_SOURCE_DIRECTORIES.any { layer.findChild(it)?.isDirectory == true }
        return if (rootSources) layer.path else app.path
    }

    private const val SPA_LOADING_TEMPLATE = "spa-loading-template.html"
    private const val ROUTER_OPTIONS = "router.options."
    private val ROOT_SOURCE_FILES = listOf("app.vue", "App.vue")
    private val ROOT_SOURCE_DIRECTORIES = listOf("assets", "layouts", "middleware", "pages", "plugins")
}
