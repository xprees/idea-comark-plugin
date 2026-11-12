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
 * `cwd` says otherwise) and the top-level fields of its `schema: z.object({ ... })` in declaration order, each with its column
 * type (from the zod type: `z.string()`, `z.number()`, `z.boolean()`, `z.date()`, `z.string().datetime()`, anything else is a
 * JSON column) and its literal `.default(...)`, if any. The first collection
 * whose source matches the file wins. Parsed configurations are cached per file and modification stamp, so an edited (saved)
 * configuration is read again on the next lookup.
 */
@Service(Service.Level.PROJECT)
class ComarkCollections(private val project: Project) {
    private class Parsed(val stamp: Long, val collections: List<Collection>)

    /** One `defineCollection`: [sources] are (cwd, include, excludes), cwd relative to the configuration's directory. */
    internal class Collection(
        val name: String,
        val type: StudioCollection.Type,
        val sources: List<Source>,
        val schema: List<StudioSchemaField>,
        val schemaKnown: Boolean = true,
    ) {
        val fields: List<String> get() = schema.map { it.name }
    }

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
                return StudioCollection(collection.name, collection.type, collection.schema, collection.schemaKnown)
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
                val schema = entries["schema"]?.let { schemaFields(code, it) }
                result += Collection(
                    name,
                    if (type == "data") StudioCollection.Type.DATA else StudioCollection.Type.PAGE,
                    sources,
                    schema.orEmpty(),
                    schemaKnown = entries["schema"] == null || schema != null,
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

        /** Top-level fields of `z.object({ ... })` (or a plain object) in [value], or null when it is not an inline object. */
        private fun schemaFields(code: String, value: IntRange): List<StudioSchemaField>? {
            val open = code.indexOf('{', value.first).takeIf { it in value } ?: return null
            val close = JsScanner.matching(code, open) ?: return null
            return JsScanner.entries(code, open, close).map { (name, range) -> field(name, code.substring(range.first, range.last + 1)) }
        }

        private val ZOD_BASE = Regex("""^\s*z\s*\.\s*(?:coerce\s*\.\s*)?(\w+)\s*\(""")
        private val PROPERTY_CALL = Regex("""^\s*property\s*\(""")

        /** A schema field from its zod expression [expression] (`z.string().default('x')`, `property(z.date()).editor(...)`). */
        internal fun field(name: String, expression: String): StudioSchemaField {
            // Nuxt Content's `property(schema)` wrapper: the schema is its argument, the chain after it only adds editor hints
            var expr = expression.trim()
            PROPERTY_CALL.find(expr)?.let { call ->
                val open = call.range.last
                val close = JsScanner.matching(expr, open)
                if (close != null) expr = expr.substring(open + 1, close).trim()
            }
            val base = ZOD_BASE.find(expr)?.groupValues?.get(1)
            val calls = chainCalls(expr)
            val type = when (base) {
                "string" -> if (calls.any { it.first == "datetime" }) StudioFieldType.DATETIME else StudioFieldType.STRING
                "enum", "nativeEnum" -> StudioFieldType.STRING
                "number" -> StudioFieldType.NUMBER
                "boolean" -> StudioFieldType.BOOLEAN
                "date" -> StudioFieldType.DATE
                else -> StudioFieldType.JSON
            }
            val default = calls.lastOrNull { it.first == "default" }?.second?.let { JsLiteral.parse(it) }
            return if (default != null) StudioSchemaField(name, type, true, default.value) else StudioSchemaField(name, type)
        }

        /** The method calls chained at the top level of [expr] (`.optional()`, `.default(x)`): name and argument text. */
        private fun chainCalls(expr: String): List<Pair<String, String>> {
            val result = ArrayList<Pair<String, String>>()
            var i = 0
            while (i < expr.length) {
                when (val c = expr[i]) {
                    '\'', '"', '`' -> {
                        i = JsScanner.skipStringAt(expr, i)
                        continue
                    }
                    '(', '[', '{' -> {
                        i = (JsScanner.matching(expr, i) ?: return result) + 1
                        continue
                    }
                    '.' -> {
                        var j = i + 1
                        while (j < expr.length && expr[j].isWhitespace()) j++
                        val start = j
                        while (j < expr.length && (expr[j].isLetterOrDigit() || expr[j] == '_' || expr[j] == '$')) j++
                        val method = expr.substring(start, j)
                        while (j < expr.length && expr[j].isWhitespace()) j++
                        if (method.isNotEmpty() && j < expr.length && expr[j] == '(') {
                            val close = JsScanner.matching(expr, j) ?: return result
                            result += method to expr.substring(j + 1, close)
                            i = close + 1
                            continue
                        }
                        i = j
                        continue
                    }
                    else -> if (c == ')' || c == ']' || c == '}') return result
                }
                i++
            }
            return result
        }
    }
}

/** Plain JavaScript literals (`'x'`, `1.5`, `true`, `null`, `[...]`, `{ ... }`) as values, for schema defaults. */
internal object JsLiteral {
    /** A parsed literal ([value] may be null for `null`). */
    class Literal(val value: Any?)

    /** [text] as a value, or null when it is not a plain literal (a function call, a variable, a template with `${}`). */
    fun parse(text: String): Literal? {
        val p = Parser(text)
        val value = p.value() ?: return null
        p.blanks()
        return if (p.at == text.length) value else null
    }

    private class Parser(val s: String) {
        var at = 0

        fun blanks() {
            while (at < s.length && s[at].isWhitespace()) at++
        }

        fun value(): Literal? {
            blanks()
            if (at >= s.length) return null
            val c = s[at]
            return when {
                c == '\'' || c == '"' || c == '`' -> string()?.let { Literal(it) }
                c == '[' -> array()
                c == '{' -> obj()
                c == '-' || c == '+' || c == '.' || c.isDigit() -> number()
                s.startsWith("true", at) -> word("true", true)
                s.startsWith("false", at) -> word("false", false)
                s.startsWith("null", at) -> word("null", null)
                else -> null
            }
        }

        private fun word(w: String, v: Any?): Literal? {
            val end = at + w.length
            if (end < s.length && (s[end].isLetterOrDigit() || s[end] == '_' || s[end] == '$')) return null
            at = end
            return Literal(v)
        }

        private fun number(): Literal? {
            val m = NUMBER.find(s.substring(at)) ?: return null
            at += m.value.length
            return m.value.replace("_", "").toDoubleOrNull()?.let { Literal(it) }
        }

        private fun string(): String? {
            val quote = s[at++]
            val sb = StringBuilder()
            while (at < s.length && s[at] != quote) {
                var c = s[at]
                if (quote == '`' && c == '$' && s.startsWith("\${", at)) return null
                if (c == '\\' && at + 1 < s.length) {
                    at++
                    c = when (val e = s[at]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        else -> e
                    }
                }
                sb.append(c)
                at++
            }
            if (at >= s.length) return null
            at++
            return sb.toString()
        }

        private fun array(): Literal? {
            at++
            val list = ArrayList<Any?>()
            while (true) {
                blanks()
                if (at < s.length && s[at] == ']') {
                    at++
                    return Literal(list)
                }
                list += (value() ?: return null).value
                blanks()
                if (at < s.length && s[at] == ',') at++ else if (at >= s.length || s[at] != ']') return null
            }
        }

        private fun obj(): Literal? {
            at++
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                blanks()
                if (at < s.length && s[at] == '}') {
                    at++
                    return Literal(map)
                }
                if (at >= s.length) return null
                val key = if (s[at] == '\'' || s[at] == '"') {
                    string() ?: return null
                } else {
                    val start = at
                    while (at < s.length && (s[at].isLetterOrDigit() || s[at] == '_' || s[at] == '$')) at++
                    s.substring(start, at).ifEmpty { return null }
                }
                blanks()
                if (at >= s.length || s[at] != ':') return null
                at++
                map[key] = (value() ?: return null).value
                blanks()
                if (at < s.length && s[at] == ',') at++ else if (at >= s.length || s[at] != '}') return null
            }
        }

        companion object {
            private val NUMBER = Regex("""^[+-]?(?:\d[\d_]*(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?""")
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
    fun skipStringAt(text: String, start: Int): Int = skipString(text, start)

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
