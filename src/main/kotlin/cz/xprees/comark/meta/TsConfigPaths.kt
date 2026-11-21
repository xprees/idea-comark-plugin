package cz.xprees.comark.meta

import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.comark.format.studio.StudioJs

/**
 * The `compilerOptions.paths` mappings that apply to a project's TypeScript files, read from its `tsconfig.json` the way the
 * TypeScript compiler reads them:
 * - a configuration inherits the options of the ones it `extends` (relative paths only; packages in `node_modules` are not
 *   read), its own `baseUrl` and `paths` replacing theirs;
 * - targets are relative to `baseUrl`, or, without one, to the directory of the configuration that declares `paths`;
 * - a pattern matches exactly or, with one `*`, by prefix and suffix; the matching pattern with the longest prefix wins, and its
 *   targets are tried in order.
 *
 * A solution-style configuration (Nuxt 4: `"files": []` and `references` to `.nuxt/tsconfig.app.json`, ...) has no paths of
 * its own; the configurations it `references` are consulted in order. The files are JSON with comments and trailing commas.
 */
internal class TsConfigPaths private constructor(private val configurations: List<Mappings>) {
    /** Where [specifier] may point: directory and path below it, in the order the compiler tries them. */
    fun targets(specifier: String): List<Pair<VirtualFile, String>> = configurations.flatMap { it.targets(specifier) }

    private class Mappings(val base: VirtualFile, val paths: Map<String, List<String>>) {
        fun targets(specifier: String): List<Pair<VirtualFile, String>> {
            paths[specifier]?.takeIf { '*' !in specifier }?.let { exact -> return exact.map { base to it } }
            var best: String? = null
            var star = ""
            for (pattern in paths.keys) {
                val index = pattern.indexOf('*')
                if (index < 0 || pattern.indexOf('*', index + 1) >= 0) continue
                val prefix = pattern.substring(0, index)
                val suffix = pattern.substring(index + 1)
                if (specifier.length < prefix.length + suffix.length || !specifier.startsWith(prefix) || !specifier.endsWith(suffix)) continue
                if (best == null || prefix.length > best.indexOf('*')) {
                    best = pattern
                    star = specifier.substring(prefix.length, specifier.length - suffix.length)
                }
            }
            val pattern = best ?: return emptyList()
            return paths.getValue(pattern).map { base to it.replace("*", star) }
        }
    }

    companion object {
        private const val CONFIG = "tsconfig.json"
        private const val MAX_DEPTH = 8
        private val EMPTY = TsConfigPaths(emptyList())

        /** The mappings of [root]'s `tsconfig.json`, its files read through [load]. */
        fun read(root: VirtualFile, load: (VirtualFile) -> CharSequence?): TsConfigPaths {
            val file = root.findChild(CONFIG)?.takeIf { !it.isDirectory } ?: return EMPTY
            val reader = Reader(load)
            val own = reader.resolved(file, 0) ?: return EMPTY
            own.mappings()?.let { return TsConfigPaths(listOf(it)) }
            // a solution-style configuration: the referenced configurations, in order
            val referenced = own.references.mapNotNull { reader.resolved(it, 0)?.mappings() }
            return TsConfigPaths(referenced)
        }
    }

    /** The options of one configuration after `extends`, with the directories their relative values are relative to. */
    private class Resolved(
        val baseUrl: VirtualFile?,
        val paths: Map<String, List<String>>?,
        val pathsDirectory: VirtualFile?,
        val references: List<VirtualFile>,
    ) {
        fun mappings(): Mappings? {
            val paths = paths?.takeIf { it.isNotEmpty() } ?: return null
            val base = baseUrl ?: pathsDirectory ?: return null
            return Mappings(base, paths)
        }
    }

    private class Reader(private val load: (VirtualFile) -> CharSequence?) {
        private val cache = HashMap<VirtualFile, Resolved?>()

        fun resolved(file: VirtualFile, depth: Int): Resolved? {
            if (depth > MAX_DEPTH) return null
            if (file in cache) return cache[file]
            cache[file] = null // a cycle of `extends` ends here
            val result = compute(file, depth)
            cache[file] = result
            return result
        }

        private fun compute(file: VirtualFile, depth: Int): Resolved? {
            val directory = file.parent ?: return null
            val json = parse(load(file) ?: return null) ?: return null
            var baseUrl: VirtualFile? = null
            var paths: Map<String, List<String>>? = null
            var pathsDirectory: VirtualFile? = null
            // inherited options first, the last configuration of an `extends` array winning
            val extends = when (val value = json["extends"]) {
                is String -> listOf(value)
                is List<*> -> value.filterIsInstance<String>()
                else -> emptyList()
            }
            for (parent in extends.mapNotNull { configFile(directory, it) }) {
                val inherited = resolved(parent, depth + 1) ?: continue
                if (inherited.baseUrl != null) baseUrl = inherited.baseUrl
                if (inherited.paths != null) {
                    paths = inherited.paths
                    pathsDirectory = inherited.pathsDirectory
                }
            }
            val options = json["compilerOptions"] as? Map<*, *>
            (options?.get("baseUrl") as? String)?.let { value -> directory.findFileByRelativePath(value.ifEmpty { "." })?.takeIf { it.isDirectory }?.let { baseUrl = it } }
            (options?.get("paths") as? Map<*, *>)?.let { value ->
                paths = value.entries.mapNotNull { (key, targets) ->
                    val pattern = key as? String ?: return@mapNotNull null
                    val list = (targets as? List<*>)?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    pattern to list
                }.toMap()
                pathsDirectory = directory
            }
            val references = (json["references"] as? List<*>).orEmpty().mapNotNull { reference ->
                val path = (reference as? Map<*, *>)?.get("path") as? String ?: return@mapNotNull null
                val target = directory.findFileByRelativePath(path) ?: return@mapNotNull null
                if (target.isDirectory) target.findChild(CONFIG)?.takeIf { !it.isDirectory } else target
            }
            return Resolved(baseUrl, paths, pathsDirectory, references)
        }

        /** The configuration an `extends` value names relative to [directory]; packages are not followed. */
        private fun configFile(directory: VirtualFile, value: String): VirtualFile? {
            if (!value.startsWith("./") && !value.startsWith("../") && !value.startsWith("/")) return null
            val file = (if (value.startsWith("/")) directory.fileSystem.findFileByPath(value) else directory.findFileByRelativePath(value))
                ?: if (!value.endsWith(".json")) directory.findFileByRelativePath("$value.json") else null
            return file?.takeIf { !it.isDirectory }
        }

        private fun parse(text: CharSequence): Map<*, *>? = try {
            StudioJs.jsonParse(stripJsonc(text.toString())) as? Map<*, *>
        } catch (_: StudioJs.JsonSyntaxError) {
            null
        }
    }
}

/** [text] without the comments and trailing commas that `tsconfig.json` allows, so a strict JSON parser reads it. */
internal fun stripJsonc(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> {
                val start = i
                i++
                while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                i++
                out.append(text, start, minOf(i, text.length))
                continue
            }
            c == '/' && text.getOrNull(i + 1) == '/' -> {
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            c == '/' && text.getOrNull(i + 1) == '*' -> {
                val end = text.indexOf("*/", i + 2)
                i = if (end < 0) text.length else end + 2
                out.append(' ')
                continue
            }
            c == ',' -> {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) {
                    i++
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}
