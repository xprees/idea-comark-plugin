package cz.xprees.comark.meta

import com.intellij.openapi.vfs.VirtualFile

/**
 * Resolves the import specifiers of one component's scripts to TypeScript files, the way a Nuxt project sees them:
 * - relative paths (`./types`, `../types/alert`), against the importing file,
 * - `~/` and `@/`: the app's source directory, which is `app/` (Nuxt 4) when the root has one, else the root,
 * - `~~/` and `@@/`: the root directory itself.
 *
 * The root is the nearest directory above the importing file that holds a `package.json` or `nuxt.config.*` ([rootFallback]
 * when there is none). A specifier without an extension is tried as `.ts`, `.d.ts`, `/index.ts` and `/index.d.ts`.
 *
 * Files are read through [load] (the caller reads documents and VFS in a read action, never `java.io`). Every file read is
 * recorded in [dependencies] with the modification stamp [stamp] gives, so the component's parsed props can be invalidated when
 * one of them changes; [consulted] tells whether the component imported anything at all, in which case newly created files may
 * change the result too.
 */
internal class NuxtTypeModules(
    private val component: VirtualFile,
    private val rootFallback: (VirtualFile) -> VirtualFile?,
    private val stamp: (VirtualFile) -> Long,
    private val load: (VirtualFile) -> CharSequence?,
) : TypeModuleResolver {
    val dependencies = LinkedHashMap<VirtualFile, Long>()

    var consulted = false
        private set

    override fun resolve(specifier: String, from: TypeModule?): TypeModule? {
        consulted = true
        val importer = from?.file ?: component
        val target = findModule(specifier, importer) ?: return null
        val modificationStamp = stamp(target)
        val text = load(target) ?: return null
        dependencies[target] = modificationStamp
        return TypeModule(text, target)
    }

    /** The file [specifier] refers to when imported by [importer], or null when it is not a project file. */
    fun findModule(specifier: String, importer: VirtualFile): VirtualFile? {
        val directory = importer.parent ?: return null
        val (base, path) = when {
            specifier == "." || specifier == ".." || specifier.startsWith("./") || specifier.startsWith("../") -> directory to specifier
            specifier.startsWith("~~/") || specifier.startsWith("@@/") -> (root(directory) ?: return null) to specifier.substring(3)
            specifier.startsWith("~/") || specifier.startsWith("@/") -> (sourceRoot(directory) ?: return null) to specifier.substring(2)
            else -> return null
        }
        val trimmed = path.trimEnd('/')
        val candidates = if (MODULE_EXTENSIONS.any { trimmed.endsWith(it) }) {
            listOf(trimmed)
        } else {
            listOf("$trimmed.ts", "$trimmed.d.ts", "$trimmed/index.ts", "$trimmed/index.d.ts")
        }
        for (candidate in candidates) {
            base.findFileByRelativePath(candidate)?.takeIf { it.isValid && !it.isDirectory }?.let { return it }
        }
        return null
    }

    private fun root(directory: VirtualFile): VirtualFile? {
        var current: VirtualFile? = directory
        while (current != null) {
            val candidate: VirtualFile = current
            if (ROOT_MARKERS.any { candidate.findChild(it) != null }) return candidate
            current = candidate.parent
        }
        return rootFallback(directory)
    }

    private fun sourceRoot(directory: VirtualFile): VirtualFile? {
        val root = root(directory) ?: return null
        return root.findChild(NUXT_4_SOURCE_DIRECTORY)?.takeIf { it.isDirectory } ?: root
    }

    companion object {
        private const val NUXT_4_SOURCE_DIRECTORY = "app"

        private val MODULE_EXTENSIONS = listOf(".ts", ".mts", ".cts")

        /** Files that mark the root of a Nuxt project. */
        val ROOT_MARKERS = listOf("package.json", "nuxt.config.ts", "nuxt.config.js", "nuxt.config.mjs", "nuxt.config.mts")

        /** Whether a file named [name] can be a module that component types are imported from. */
        fun isTypeModule(name: String) = MODULE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
    }
}
