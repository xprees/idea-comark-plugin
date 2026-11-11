package cz.xprees.comark.lang

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.comark.settings.ComarkSettings
import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.regex.PatternSyntaxException

/**
 * The exclusion patterns of [ComarkSettings.excludedPatterns]: Markdown files that are never Comark.
 *
 * Patterns are globs, one per line (`#` starts a comment), matched like `.gitignore` entries against the file's path relative to
 * the project directory:
 * - a pattern without `/` matches a file or directory name at any level (`CHANGELOG.md`, `*.draft.md`, `legacy`);
 * - a pattern with `/` matches from the project directory (`docs/legacy`, `/README.md`; a leading `/` is optional);
 * - a pattern matching a directory excludes everything inside it (`docs/legacy`, `docs/legacy/`).
 *
 * `*` stays within a path segment, a double star crosses segments, `?`, `[...]` and `{a,b}` work as in [java.nio.file.FileSystem.getPathMatcher].
 */
object ComarkExclusions {
    private class Compiled(val source: String, val names: List<PathMatcher>, val paths: List<PathMatcher>)

    @Volatile
    private var compiled: Compiled? = null

    /** True when [file] (or a directory it is in) matches one of the configured patterns. */
    fun isExcluded(file: VirtualFile, project: Project): Boolean {
        val patterns = patterns(ComarkSettings.getInstance().excludedPatterns)
        if (patterns.names.isEmpty() && patterns.paths.isEmpty()) return false
        val base = project.guessProjectDir()
        val relative = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: return false
        return matches(relative, patterns)
    }

    /** True when the project-relative path [relativePath] (`/`-separated) matches one of the patterns in [patternText]. */
    fun matches(relativePath: String, patternText: String): Boolean = matches(relativePath, patterns(patternText))

    /** The patterns in [patternText] that are not valid globs (shown by the settings page). */
    fun invalidPatterns(patternText: String): List<String> = lines(patternText).filter { compile(it.trimStart('/').trimEnd('/')) == null }

    private fun matches(relativePath: String, patterns: Compiled): Boolean {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (patterns.names.isNotEmpty() && segments.any { segment -> patterns.names.any { it.matches(Path.of(segment)) } }) return true
        if (patterns.paths.isEmpty()) return false
        // the path itself and every directory it is in
        for (count in segments.size downTo 1) {
            val prefix = segments.subList(0, count).joinToString("/")
            val path = try {
                Path.of(prefix)
            } catch (_: InvalidPathException) {
                continue
            }
            if (patterns.paths.any { it.matches(path) }) return true
        }
        return false
    }

    private fun patterns(text: String): Compiled {
        compiled?.takeIf { it.source == text }?.let { return it }
        val names = ArrayList<PathMatcher>()
        val paths = ArrayList<PathMatcher>()
        for (line in lines(text)) {
            val pattern = line.trimStart('/').trimEnd('/')
            if (pattern.isEmpty()) continue
            val matcher = compile(pattern) ?: continue
            if ('/' in pattern || line.startsWith("/")) paths += matcher else names += matcher
        }
        return Compiled(text, names, paths).also { compiled = it }
    }

    private fun lines(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    private fun compile(pattern: String): PathMatcher? = try {
        FileSystems.getDefault().getPathMatcher("glob:$pattern")
    } catch (_: PatternSyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
