package cz.xprees.comark.format

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * The nuxt-studio and comark versions a package uses, read textually from its `package.json` and the nearest lockfile, compared
 * with the versions the Nuxt Studio style reproduces ([StudioStyleAdapter.STUDIO_VERSION], [StudioStyleAdapter.COMARK_VERSION]).
 *
 * The package is the nearest directory, from a content file up, whose `package.json` depends on `nuxt-studio`. Its lockfile is
 * the first of `package-lock.json`, `npm-shrinkwrap.json`, `pnpm-lock.yaml`, `yarn.lock` (classic and Berry) and `bun.lock`
 * found in that directory or above (a monorepo keeps it at the workspace root). Resolved versions from the lockfile win; without
 * one, the `package.json` range is used when it pins a release line (`1.9.0`, `~1.9.0`, `^1.9.0`). Results are cached per
 * directory and file modification stamps.
 */
@Service(Service.Level.PROJECT)
class ComarkStudioVersions {
    private class Cached(val stamp: Long, val info: StudioDependencies?)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** What the package of [file] uses, or null when no `package.json` above it depends on nuxt-studio. */
    fun dependenciesFor(file: VirtualFile): StudioDependencies? {
        val start = (if (file.isDirectory) file else file.parent) ?: return null
        var directory: VirtualFile? = start
        while (directory != null) {
            val manifest = directory.findChild(MANIFEST)
            if (manifest != null && !manifest.isDirectory) {
                val text = load(manifest)
                val range = text?.let { StudioVersionParser.packageJsonRange(it, STUDIO) }
                if (range != null) return dependencies(directory, manifest, text, range)
            }
            directory = directory.parent
        }
        return null
    }

    private fun dependencies(directory: VirtualFile, manifest: VirtualFile, manifestText: String, range: String): StudioDependencies {
        val lockfile = lockfileFrom(directory)
        val stamp = manifest.modificationStamp * 31 + (lockfile?.modificationStamp ?: -1) + (lockfile?.path?.hashCode() ?: 0)
        cache[directory.path]?.takeIf { it.stamp == stamp }?.let { return it.info!! }
        val lockText = lockfile?.let(::load)
        val studio = lockText?.let { StudioVersionParser.lockfileVersions(lockfile.name, it, STUDIO) }.orEmpty()
        val comark = lockText?.let { StudioVersionParser.lockfileVersions(lockfile.name, it, COMARK) }.orEmpty()
        val info = StudioDependencies(
            studioRange = range,
            studioVersions = studio,
            comarkRange = StudioVersionParser.packageJsonRange(manifestText, COMARK),
            comarkVersions = comark,
            lockfile = lockfile?.name,
        )
        cache[directory.path] = Cached(stamp, info)
        return info
    }

    private fun lockfileFrom(directory: VirtualFile): VirtualFile? {
        var current: VirtualFile? = directory
        while (current != null) {
            for (name in StudioVersionParser.LOCKFILES) current.findChild(name)?.takeIf { !it.isDirectory }?.let { return it }
            current = current.parent
        }
        return null
    }

    private fun load(file: VirtualFile): String? = try {
        VfsUtilCore.loadText(file)
    } catch (_: Exception) {
        null
    }

    companion object {
        const val STUDIO = "nuxt-studio"
        const val COMARK = "comark"
        private const val MANIFEST = "package.json"

        fun getInstance(project: Project): ComarkStudioVersions = project.service()
    }
}

/**
 * The nuxt-studio and comark dependencies of a package: [studioRange] / [comarkRange] as `package.json` declares them (comark is
 * usually only a dependency of nuxt-studio, so its range is often null), and the versions [lockfile] resolves.
 */
data class StudioDependencies(
    val studioRange: String,
    val studioVersions: List<String>,
    val comarkRange: String?,
    val comarkVersions: List<String>,
    val lockfile: String?,
) {
    /**
     * The packages whose version differs from the pinned one, as `name version` (e.g. `nuxt-studio 1.9.2`), empty when they match
     * or cannot be told. nuxt-studio differs when no resolved version (or, without a lockfile, its range) is on the 1.8 line;
     * comark when versions are known and none is on the 0.7 line.
     */
    val mismatches: List<String>
        get() {
            val result = ArrayList<String>()
            val studio = if (studioVersions.isNotEmpty()) studioVersions else listOf(studioRange)
            if (studio.none { StudioVersionParser.matches(it, StudioStyleAdapter.STUDIO_VERSION) != false }) {
                result += "${ComarkStudioVersions.STUDIO} ${studio.joinToString(", ")}"
            }
            val comark = comarkVersions.ifEmpty { listOfNotNull(comarkRange) }
            if (comark.isNotEmpty() && comark.none { StudioVersionParser.matches(it, StudioStyleAdapter.COMARK_VERSION) != false }) {
                result += "${ComarkStudioVersions.COMARK} ${comark.joinToString(", ")}"
            }
            return result
        }
}

/** Textual readers of `package.json` and lockfiles. Pure functions, unit tested on their own. */
object StudioVersionParser {
    /** Lockfile names in the order they are looked for. */
    val LOCKFILES = listOf("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock")

    /** The version range [name] has as a dependency key in a `package.json` [text], or null. */
    fun packageJsonRange(text: String, name: String): String? =
        Regex(""""${Regex.escape(name)}"\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1)

    /** The versions of [name] that the lockfile [fileName] with content [text] resolves, distinct, in file order. */
    fun lockfileVersions(fileName: String, text: String, name: String): List<String> = when (fileName) {
        "package-lock.json", "npm-shrinkwrap.json" -> packageLock(text, name)
        "pnpm-lock.yaml" -> pnpmLock(text, name)
        "yarn.lock" -> yarnLock(text, name)
        "bun.lock" -> bunLock(text, name)
        else -> emptyList()
    }

    /** npm lockfile v2/v3 (`"node_modules/name": { "version": ... }`) and v1 (`"name": { "version": ... }`). */
    fun packageLock(text: String, name: String): List<String> {
        val n = Regex.escape(name)
        val packages = Regex(""""(?:[^"]*/)?node_modules/$n"\s*:\s*\{[^{}]*?"version"\s*:\s*"([^"]+)"""")
        val legacy = Regex(""""$n"\s*:\s*\{\s*"version"\s*:\s*"([^"]+)"""")
        return (packages.findAll(text) + legacy.findAll(text)).map { it.groupValues[1] }.distinct().toList()
    }

    /** pnpm lockfile: package keys `name@1.8.0`, `/name@1.8.0(peer)` (v6+), `/name/1.8.0` (v5). */
    fun pnpmLock(text: String, name: String): List<String> {
        val n = Regex.escape(name)
        val key = Regex("""(?m)^[ \t]+['"]?/?$n(?:@|/)(\d[^\s:('"]*)""")
        return key.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    /** yarn.lock, classic (`"name@^1.8.0":` / `version "1.8.0"`) and Berry (`"name@npm:^1.8.0":` / `version: 1.8.0`). */
    fun yarnLock(text: String, name: String): List<String> {
        val result = ArrayList<String>()
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            if (line.isEmpty() || line[0].isWhitespace() || line.startsWith("#") || !line.trimEnd().endsWith(":")) continue
            val specs = line.trimEnd().removeSuffix(":").split(',').map { it.trim().trim('"') }
            if (specs.none { it.startsWith("$name@") }) continue
            for (next in index + 1 until lines.size) {
                val entry = lines[next]
                if (entry.isNotEmpty() && !entry[0].isWhitespace()) break
                val version = YARN_VERSION.find(entry) ?: continue
                result += version.groupValues[1]
                break
            }
        }
        return result.distinct()
    }

    /** bun.lock (JSONC): package entries `"name": ["name@1.8.0", ...]`. */
    fun bunLock(text: String, name: String): List<String> =
        Regex(""""${Regex.escape(name)}@(?:npm:)?(\d[^"]*)"""").findAll(text).map { it.groupValues[1] }.distinct().toList()

    private val YARN_VERSION = Regex("""^\s+version:?\s+"?([^"\s]+)"?""")

    /**
     * Whether [spec] (a resolved version, or a `package.json` range) is on the release line of [pinned] (same major and minor):
     * true or false, or null when the range doesn't tell (`*`, `latest`, `>=1`, `workspace:`, git URLs, ...). `^1.7.0` covers
     * 1.8, `~1.7.0` does not.
     */
    fun matches(spec: String, pinned: String): Boolean? {
        val (pinnedMajor, pinnedMinor) = majorMinor(pinned) ?: return null
        val s = spec.trim().removePrefix("npm:").trim()
        val match = RANGE.matchEntire(s) ?: return null
        val operator = match.groupValues[1]
        val major = match.groupValues[2].toInt()
        val minor = match.groupValues[3].toIntOrNull()
        return when (operator) {
            "^" -> if (major != pinnedMajor) false else if (major == 0) minor == null || minor == pinnedMinor else minor == null || minor <= pinnedMinor
            "~", "", "=", "v" -> major == pinnedMajor && (minor == null || minor == pinnedMinor)
            else -> null
        }
    }

    private fun majorMinor(version: String): Pair<Int, Int>? =
        Regex("""^(\d+)\.(\d+)""").find(version)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** An optional `^`, `~`, `=` or `v`, then major, optional minor, optional rest (`.patch`, `-beta.1`, `.x`, build). */
    private val RANGE = Regex("""^(\^|~|=|v|)\s*(\d+)(?:\.(\d+|x|\*))?(?:\.[\dx*]+)?(?:[-+][\w.-]*)?$""")
}
