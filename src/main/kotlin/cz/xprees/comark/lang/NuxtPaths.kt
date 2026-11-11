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
     * one, else `app/` when it exists (Nuxt 4), else the layer itself (Nuxt 3).
     */
    fun sourceRoot(layer: VirtualFile, explicitSrcDir: String? = null): String {
        if (!explicitSrcDir.isNullOrBlank()) return resolve(layer.path, explicitSrcDir)
        return (layer.findChild(NUXT_4_SOURCE_DIRECTORY)?.takeIf { it.isDirectory } ?: layer).path
    }
}
