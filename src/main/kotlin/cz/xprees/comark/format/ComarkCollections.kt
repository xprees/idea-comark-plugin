package cz.xprees.comark.format

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import cz.xprees.comark.lang.NuxtPaths
import cz.xprees.comark.settings.ComarkSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * The Nuxt Content collection a content file belongs to, read textually from `content.config.*` (as the rest of the plugin reads
 * Nuxt configuration): for the front matter rules of the Nuxt Studio style ([StudioCollection]).
 *
 * The configuration is the one set in [ComarkSettings.contentConfigPath], else the nearest `content.config.*` in the file's
 * directory or above. Each `name: defineCollection({ ... })` gives a collection with its `type` (`page` by default), its
 * `source` (a glob, `{ include, exclude, cwd }`, or an array of these; relative to `content/` next to the configuration unless
 * `cwd` says otherwise) and the top-level fields of its `schema: z.object({ ... })` in declaration order. The first collection
 * whose source matches the file wins. Parsed configurations are cached per file and modification stamp, so an edited (saved)
 * configuration is read again on the next lookup.
 */
@Service(Service.Level.PROJECT)
class ComarkCollections(private val project: Project) {
    private class Parsed(val stamp: Long, val collections: List<Collection>)

    /** One `defineCollection`: [sources] are (cwd, include, excludes), cwd relative to the configuration's directory. */
    internal class Collection(val name: String, val type: StudioCollection.Type, val sources: List<Source>, val fields: List<String>)

    internal class Source(val cwd: String, val include: String, val exclude: List<String>)

    private val cache = ConcurrentHashMap<String, Parsed>()

    /** The collection [file] belongs to, or null (no configuration, or no collection matches). */
    fun collectionFor(file: PsiFile): StudioCollection? {
        val virtualFile = file.originalFile.virtualFile ?: return null
        val config = configFor(virtualFile) ?: return null
        val root = config.parent?.path ?: return null
        val collections = parsed(config)
        for (collection in collections) {
            if (collection.sources.any { matches(root, it, virtualFile.path) }) {
                return StudioCollection(collection.name, collection.type, collection.fields)
            }
        }
        return null
    }

    private fun configFor(file: VirtualFile): VirtualFile? {
        val setting = ComarkSettings.getInstance().contentConfigPath
        if (setting.isNotEmpty()) {
            val path = FileUtil.toSystemIndependentName(setting)
            return (if (FileUtil.isAbsolute(setting)) LocalFileSystem.getInstance().findFileByPath(path)
            else project.guessProjectDir()?.findFileByRelativePath(path))?.takeIf { !it.isDirectory }
        }
        var directory = file.parent
        while (directory != null) {
            directory.children.firstOrNull { !it.isDirectory && CONTENT_CONFIG.matches(it.name) }?.let { return it }
            directory = directory.parent
        }
        return null
    }

    private fun parsed(config: VirtualFile): List<Collection> {
        val stamp = config.modificationStamp
        cache[config.path]?.takeIf { it.stamp == stamp }?.let { return it.collections }
        val text = try {
            VfsUtilCore.loadText(config)
        } catch (_: Exception) {
            ""
        }
        val collections = parse(text)
        cache[config.path] = Parsed(stamp, collections)
        return collections
    }

    companion object {
        private val CONTENT_CONFIG = Regex("""content\.config\.(?:ts|js|mjs|mts|cjs|cts)""")
        private val DEFINE = Regex("""(?:([A-Za-z_$][\w$]*)|'([^']+)'|"([^"]+)")\s*:\s*defineCollection\s*\(""")
        private const val DEFAULT_CWD = "content"

        fun getInstance(project: Project): ComarkCollections = project.service()

        /** True when the file at [path] lies in [source] of a configuration in directory [root]. */
        internal fun matches(root: String, source: Source, path: String): Boolean {
            val cwd = NuxtPaths.resolve(root, source.cwd)
            if (!NuxtPaths.isUnder(path, cwd) || path == cwd) return false
            val relative = path.substring(cwd.length).trimStart('/')
            return globRegex(source.include).matches(relative) && source.exclude.none { globRegex(it).matches(relative) }
        }

        /** A micromatch-like glob: `**` spans directories (`**` + `/` also matches none), `*` and `?` stay within one, `{a,b}`. */
        internal fun globRegex(glob: String): Regex {
            val sb = StringBuilder()
            var i = 0
            var braces = 0
            val pattern = glob.removePrefix("./")
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    pattern.startsWith("**/", i) -> {
                        sb.append("(?:.*/)?")
                        i += 3
                        continue
                    }
                    pattern.startsWith("**", i) -> {
                        sb.append(".*")
                        i += 2
                        continue
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '{' -> {
                        braces++
                        sb.append("(?:")
                    }
                    c == '}' && braces > 0 -> {
                        braces--
                        sb.append(')')
                    }
                    c == ',' && braces > 0 -> sb.append('|')
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.toString())
        }

        /** The collections of a `content.config.*` [text]. */
        internal fun parse(text: String): List<Collection> {
            val code = JsScanner.blankComments(text)
            val result = ArrayList<Collection>()
            for (match in DEFINE.findAll(code)) {
                val name = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
                val open = code.indexOf('{', match.range.last)
                if (open < 0) continue
                val close = JsScanner.matching(code, open) ?: continue
                val entries = JsScanner.entries(code, open, close)
                val type = entries["type"]?.let { JsScanner.string(code, it) }
                val sources = entries["source"]?.let { sources(code, it) }.orEmpty()
                val fields = entries["schema"]?.let { schemaFields(code, it) }.orEmpty()
                result += Collection(
                    name,
                    if (type == "data") StudioCollection.Type.DATA else StudioCollection.Type.PAGE,
                    sources,
                    fields,
                )
            }
            return result
        }

        private fun sources(code: String, value: IntRange): List<Source> {
            val start = JsScanner.skipBlanks(code, value.first)
            if (start > value.last) return emptyList()
            return when (code[start]) {
                '[' -> {
                    val close = JsScanner.matching(code, start) ?: return emptyList()
                    JsScanner.items(code, start, close).flatMap { sources(code, it) }
                }
                '{' -> {
                    val close = JsScanner.matching(code, start) ?: return emptyList()
                    val entries = JsScanner.entries(code, start, close)
                    val include = entries["include"]?.let { JsScanner.string(code, it) } ?: return emptyList()
                    val cwd = entries["cwd"]?.let { JsScanner.lastString(code, it) } ?: DEFAULT_CWD
                    val exclude = entries["exclude"]?.let { range ->
                        val s = JsScanner.skipBlanks(code, range.first)
                        if (s <= range.last && code[s] == '[') {
                            JsScanner.matching(code, s)?.let { c -> JsScanner.items(code, s, c).mapNotNull { JsScanner.string(code, it) } }
                        } else {
                            listOfNotNull(JsScanner.string(code, range))
                        }
                    }.orEmpty()
                    listOf(Source(cwd, include, exclude))
                }
                else -> listOfNotNull(JsScanner.string(code, value)?.let { Source(DEFAULT_CWD, it, emptyList()) })
            }
        }

        /** Top-level fields of `z.object({ ... })` (or a plain object) in [value]. */
        private fun schemaFields(code: String, value: IntRange): List<String> {
            val open = code.indexOf('{', value.first).takeIf { it in value } ?: return emptyList()
            val close = JsScanner.matching(code, open) ?: return emptyList()
            return JsScanner.entries(code, open, close).keys.toList()
        }
    }
}

/** Just enough of a JavaScript / TypeScript reader for configuration objects. */
internal object JsScanner {
    private val STRING = Regex("""^\s*(['"`])((?:\\.|(?!\1).)*)\1\s*$""", RegexOption.DOT_MATCHES_ALL)

    /** [text] with comments replaced by spaces (strings kept), so offsets stay the same. */
    fun blankComments(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' || c == '"' || c == '`' -> i = skipString(text, i)
                text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') sb.setCharAt(i++, ' ')
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    while (i < end) {
                        if (text[i] != '\n') sb.setCharAt(i, ' ')
                        i++
                    }
                }
                else -> i++
            }
        }
        return sb.toString()
    }

    /** The offset after the string literal starting at [start]. */
    private fun skipString(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length && text[i] != quote) {
            if (text[i] == '\\') i++
            i++
        }
        return i + 1
    }

    /** The offset of the bracket closing the one at [open], or null. */
    fun matching(code: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < code.length) {
            val c = code[i]
            when (c) {
                '\'', '"', '`' -> {
                    i = skipString(code, i)
                    continue
                }
                '{', '[', '(' -> depth++
                '}', ']', ')' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    /** The values of the comma separated items between [open] and [close] (exclusive), as ranges. */
    fun items(code: String, open: Int, close: Int): List<IntRange> {
        val result = ArrayList<IntRange>()
        var start = open + 1
        var i = open + 1
        while (i < close) {
            when (code[i]) {
                '\'', '"', '`' -> {
                    i = skipString(code, i)
                    continue
                }
                '{', '[', '(' -> {
                    i = (matching(code, i) ?: return result) + 1
                    continue
                }
                ',' -> {
                    if (code.substring(start, i).isNotBlank()) result += start until i
                    start = i + 1
                }
            }
            i++
        }
        if (start < close && code.substring(start, close).isNotBlank()) result += start until close
        return result
    }

    private val KEY = Regex("""^\s*(?:([A-Za-z_$][\w$]*)|'([^']*)'|"([^"]*)")\s*:""")

    /** The `key: value` entries of the object literal between [open] and [close], in order (keys without values are skipped). */
    fun entries(code: String, open: Int, close: Int): LinkedHashMap<String, IntRange> {
        val result = LinkedHashMap<String, IntRange>()
        for (item in items(code, open, close)) {
            val text = code.substring(item.first, item.last + 1)
            val key = KEY.find(text) ?: continue
            val name = key.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
            result.putIfAbsent(name, item.first + key.range.last + 1..item.last)
        }
        return result
    }

    fun skipBlanks(code: String, from: Int): Int {
        var i = from
        while (i < code.length && code[i].isWhitespace()) i++
        return i
    }

    /** The string literal that is the whole [range], or null. */
    fun string(code: String, range: IntRange): String? = STRING.find(code.substring(range.first, range.last + 1))?.groupValues?.get(2)

    /** The last string literal in [range] (`resolve(__dirname, 'docs')` gives `docs`), or null. */
    fun lastString(code: String, range: IntRange): String? =
        NuxtPaths.STRING.findAll(code.substring(range.first, range.last + 1)).lastOrNull()?.groupValues?.get(1)
}
