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
 * `source` (a glob, `{ include, exclude, cwd, prefix }`, or an array of these; relative to `content/` next to the configuration
 * unless `cwd` says otherwise) and the top-level fields of its `schema` in declaration order (`z.object({ ... })`, also held in
 * a variable and changed by `.extend()`, `.merge()`, `.omit()` or `.pick()`, see [resolveSchema]), each with its column
 * type (from the zod type: `z.string()`, `z.number()`, `z.boolean()`, `z.date()`, `z.string().datetime()`, anything else is a
 * JSON column) and its literal `.default(...)`, if any. The first collection
 * whose source matches the file wins. Parsed configurations are cached per file and modification stamp, so an edited (saved)
 * configuration is read again on the next lookup.
 */
@Service(Service.Level.PROJECT)
class ComarkCollections(private val project: Project) {
    /** Collections of a configuration, valid while it and the files it imports schemas from keep their stamps. */
    private class Parsed(val stamp: Long, val imports: Map<VirtualFile, Long>, val collections: List<Collection>)

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

    /**
     * One collection source: files matching [include] (minus [exclude]) below [cwd]. Routes are [prefix] (by default the static
     * part of [include], see [fixed]) followed by the file's path below [fixed], as Nuxt Content's `defineLocalSource` builds them.
     */
    internal class Source(val cwd: String, val include: String, val exclude: List<String>, val prefix: String? = null) {
        /** The part of [include] before its first `*` (`threats/en/` for `threats/en/**/*.md`), empty without a `*`. */
        val fixed: String get() = include.removePrefix("./").let { if ('*' in it) it.substringBefore('*') else "" }

        /** [prefix], or the static part of [include] with a leading and no trailing slash. */
        val routePrefix: String get() = prefix ?: ("/" + fixed.trim('/')).trimEnd('/')
    }

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

    /**
     * The Nuxt Content configuration that applies to the directory [directory] (the configured one, else the nearest
     * `content.config.*` in it or above) and its collections; null without a configuration.
     */
    internal fun configurationAt(directory: VirtualFile): Pair<VirtualFile, List<Collection>>? {
        val config = configFor(directory, startAt = directory) ?: return null
        return config to parsed(config)
    }

    private fun configFor(file: VirtualFile, startAt: VirtualFile? = file.parent): VirtualFile? {
        val setting = ComarkSettings.getInstance().contentConfigPath
        if (setting.isNotEmpty()) {
            val path = FileUtil.toSystemIndependentName(setting)
            return (if (FileUtil.isAbsolute(setting)) LocalFileSystem.getInstance().findFileByPath(path)
            else project.guessProjectDir()?.findFileByRelativePath(path))?.takeIf { !it.isDirectory }
        }
        var directory = startAt
        while (directory != null) {
            directory.children.firstOrNull { !it.isDirectory && CONTENT_CONFIG.matches(it.name) }?.let { return it }
            directory = directory.parent
        }
        return null
    }

    private fun parsed(config: VirtualFile): List<Collection> {
        val stamp = config.modificationStamp
        cache[config.path]?.takeIf { cached -> cached.stamp == stamp && cached.imports.all { (file, fileStamp) -> file.isValid && file.modificationStamp == fileStamp } }
            ?.let { return it.collections }
        val text = load(config).orEmpty()
        val imports = LinkedHashMap<VirtualFile, Long>()
        val imported = importedVariables(config, JsScanner.blankComments(text), imports, 0)
        val collections = parse(text, imported)
        cache[config.path] = Parsed(stamp, imports, collections)
        return collections
    }

    private fun load(file: VirtualFile): String? = try {
        VfsUtilCore.loadText(file)
    } catch (_: Exception) {
        null
    }

    /**
     * Top-level variables that [code] (the text of [file], comments blanked) imports by name from project files (`import {
     * pageSchema, base as baseSchema } from './schemas'`), under their local names, with the other top-level variables of those
     * files their initializers may name. Imports of imported files are followed too; every file read goes to [imports].
     */
    private fun importedVariables(file: VirtualFile, code: String, imports: MutableMap<VirtualFile, Long>, depth: Int): Map<String, String> {
        if (depth >= MAX_IMPORT_DEPTH) return emptyMap()
        val directory = file.parent ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val aliases = LinkedHashMap<String, String>()
        for (match in IMPORT.findAll(code)) {
            val target = module(directory, match.groupValues[2]) ?: continue
            if (target in imports) continue
            val targetText = load(target) ?: continue
            imports[target] = target.modificationStamp
            val targetCode = JsScanner.blankComments(targetText)
            val theirs = LinkedHashMap(importedVariables(target, targetCode, imports, depth + 1)).apply { putAll(variables(targetCode)) }
            for ((name, expression) in theirs) result.putIfAbsent(name, expression)
            for (binding in match.groupValues[1].split(',')) {
                val parts = binding.trim().removePrefix("type ").split(Regex("""\s+as\s+"""))
                val exported = parts[0].trim()
                val local = parts.getOrNull(1)?.trim() ?: exported
                if (local != exported && IDENTIFIER.matches(local)) theirs[exported]?.let { aliases[local] = it }
            }
        }
        // an alias names the imported variable's initializer under the local name
        result.putAll(aliases)
        return result
    }

    /** The project file a relative import [specifier] names below [directory]; package imports are not followed. */
    private fun module(directory: VirtualFile, specifier: String): VirtualFile? {
        if (!specifier.startsWith("./") && !specifier.startsWith("../")) return null
        val path = specifier.trimEnd('/')
        val candidates = if (MODULE_EXTENSION.containsMatchIn(path)) {
            listOf(path)
        } else {
            listOf("$path.ts", "$path.mts", "$path.js", "$path.mjs", "$path/index.ts", "$path/index.js")
        }
        return candidates.firstNotNullOfOrNull { directory.findFileByRelativePath(it)?.takeIf { file -> !file.isDirectory } }
    }

    companion object {
        private val CONTENT_CONFIG = Regex("""content\.config\.(?:ts|js|mjs|mts|cjs|cts)""")
        private val DEFINE = Regex("""(?:([A-Za-z_$][\w$]*)|'([^']+)'|"([^"]+)")\s*:\s*defineCollection\s*\(""")
        private val VARIABLE_DEFINE = Regex("""\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*defineCollection\s*\(""")
        private val COLLECTIONS = Regex("""\bcollections\s*:\s*\{""")
        private val IDENTIFIER = Regex("""^[A-Za-z_$][\w$]*$""")
        private const val DEFAULT_CWD = "content"
        private const val MAX_IMPORT_DEPTH = 4
        private val IMPORT = Regex("""\bimport\s+(?:type\s+)?\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]""")
        private val MODULE_EXTENSION = Regex("""\.(?:ts|mts|cts|js|mjs|cjs)$""")

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

        /**
         * The collections of a `content.config.*` [text]; [imported] are the variables it imports from other files (see
         * [importedVariables]), which its own top-level variables shadow.
         */
        internal fun parse(text: String, imported: Map<String, String> = emptyMap()): List<Collection> {
            val code = JsScanner.blankComments(text)
            val result = ArrayList<Collection>()
            for (match in DEFINE.findAll(code)) {
                val name = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
                collection(code, name, match.range.last, imported)?.let { result += it }
            }
            // `const blog = defineCollection({...})`, listed as `collections: { blog }` or `collections: { posts: blog }`
            val variables = LinkedHashMap<String, Collection>()
            for (match in VARIABLE_DEFINE.findAll(code)) {
                collection(code, match.groupValues[1], match.range.last, imported)?.let { variables[it.name] = it }
            }
            if (variables.isEmpty()) return result
            val listed = COLLECTIONS.findAll(code).mapNotNull { m ->
                val open = m.range.last
                JsScanner.matching(code, open)?.let { close -> JsScanner.items(code, open, close) }
            }.flatten().mapNotNull { item ->
                val entry = code.substring(item.first, item.last + 1).trim()
                val colon = entry.indexOf(':')
                if (colon < 0) {
                    entry.takeIf { IDENTIFIER.matches(it) }?.let { it to it }
                } else {
                    val key = entry.substring(0, colon).trim().trim('\'', '"')
                    val value = entry.substring(colon + 1).trim()
                    if (IDENTIFIER.matches(value)) key to value else null
                }
            }.toList()
            if (listed.isEmpty()) {
                result += variables.values
            } else {
                for ((name, variable) in listed) variables[variable]?.let { result += Collection(name, it.type, it.sources, it.schema, it.schemaKnown) }
            }
            return result
        }

        /** The collection of the `defineCollection(` call whose `(` ends at [callEnd], named [name]. */
        private fun collection(code: String, name: String, callEnd: Int, imported: Map<String, String>): Collection? {
            val open = code.indexOf('{', callEnd)
            if (open < 0) return null
            val close = JsScanner.matching(code, open) ?: return null
            val entries = JsScanner.entries(code, open, close)
            val type = entries["type"]?.let { JsScanner.string(code, it) }
            val sources = entries["source"]?.let { sources(code, it) }.orEmpty()
            val schema = entries["schema"]?.let { resolveSchema(code.substring(it.first, it.last + 1), imported + variables(code), 0) }
            return Collection(
                name,
                if (type == "data") StudioCollection.Type.DATA else StudioCollection.Type.PAGE,
                sources,
                schema.orEmpty(),
                schemaKnown = entries["schema"] == null || schema != null,
            )
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
                    val prefix = entries["prefix"]?.let { JsScanner.string(code, it) }
                    listOf(Source(cwd, include, exclude, prefix))
                }
                else -> listOfNotNull(JsScanner.string(code, value)?.let { Source(DEFAULT_CWD, it, emptyList()) })
            }
        }

        private val ZOD_OBJECT = Regex("""^z\s*\.\s*(?:object|strictObject|looseObject)\s*\(\s*\{""")
        private val LEADING_IDENTIFIER = Regex("""^([A-Za-z_$][\w$]*)""")
        private val DECLARATION = Regex("""\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*(?::[^=;\n]+)?=\s*""")

        /** Chained calls that keep an object schema's fields and their column types. */
        private val FIELD_KEEPING = setOf(
            "passthrough", "strict", "strip", "catchall", "describe", "meta", "optional", "nullable", "nullish", "readonly",
            "brand", "partial", "required", "deepPartial", "default",
        )
        private const val MAX_SCHEMA_DEPTH = 8

        /** Top-level `const` / `let` / `var` initializers of [code], by name (the first one wins). */
        private fun variables(code: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (m in DECLARATION.findAll(code)) {
                val start = m.range.last + 1
                result.putIfAbsent(m.groupValues[1], code.substring(start, expressionEnd(code, start)))
            }
            return result
        }

        /**
         * The end of the expression starting at [start]: a `;` or `,` outside brackets, or a line break not followed by a chained
         * `.call`.
         */
        private fun expressionEnd(code: String, start: Int): Int {
            var i = start
            while (i < code.length) {
                when (val c = code[i]) {
                    '\'', '"', '`' -> {
                        i = JsScanner.skipStringAt(code, i)
                        continue
                    }
                    '(', '[', '{' -> {
                        i = (JsScanner.matching(code, i) ?: return code.length) + 1
                        continue
                    }
                    ';', ',', ')', ']', '}' -> return i
                    '\n' -> {
                        val next = JsScanner.skipBlanks(code, i)
                        if (next >= code.length || code[next] != '.' && !code.startsWith("?.", next)) return i
                        i = next
                        continue
                    }
                    else -> if (c == '=' && code.startsWith("=>", i)) i++
                }
                i++
            }
            return code.length
        }

        /**
         * The top-level fields of the object schema [expression], or null when it cannot be read: `z.object({ ... })` (also
         * `strictObject`, `looseObject`) or a plain object, or a variable holding one, followed by chained calls. Calls that change
         * the fields are applied: `.extend({ ... })` and `.merge(other)` add or replace fields (a replaced field keeps its place,
         * as with JavaScript's object spread), `.omit({ a: true })` and `.pick({ a: true })` drop fields; `.passthrough()`,
         * `.partial()`, `.describe()` and the like keep them. Anything else (`.and()`, `.transform()`, an imported schema, a
         * spread in an object) makes the schema unknown.
         */
        private fun resolveSchema(expression: String, variables: Map<String, String>, depth: Int): List<StudioSchemaField>? {
            if (depth > MAX_SCHEMA_DEPTH) return null
            val e = expression.trim()
            if (e.isEmpty()) return null
            val fields: LinkedHashMap<String, StudioSchemaField>
            val rest: String
            when {
                e[0] == '{' -> {
                    val close = JsScanner.matching(e, 0) ?: return null
                    fields = objectFields(e, 0, close) ?: return null
                    rest = e.substring(close + 1)
                }
                ZOD_OBJECT.containsMatchIn(e) -> {
                    val open = e.indexOf('{')
                    val close = JsScanner.matching(e, open) ?: return null
                    val callClose = JsScanner.matching(e, e.indexOf('(')) ?: return null
                    if (e.substring(close + 1, callClose).isNotBlank()) return null
                    fields = objectFields(e, open, close) ?: return null
                    rest = e.substring(callClose + 1)
                }
                else -> {
                    val name = LEADING_IDENTIFIER.find(e)?.value ?: return null
                    val value = variables[name] ?: return null
                    val base = resolveSchema(value, variables, depth + 1) ?: return null
                    fields = LinkedHashMap(base.associateBy { it.name })
                    rest = e.substring(name.length)
                }
            }
            val calls = chainCalls(rest)
            // anything after the calls (`as Schema`, an operator) is not followed
            if (!rest.trim().let { it.isEmpty() || it.startsWith(".") }) return null
            for ((method, argument) in calls) {
                when (method) {
                    in FIELD_KEEPING -> {}
                    "extend", "merge", "safeExtend" -> {
                        val added = resolveSchema(argument, variables, depth + 1) ?: return null
                        for (field in added) fields[field.name] = field
                    }
                    "omit", "pick" -> {
                        val a = argument.trim()
                        if (!a.startsWith("{")) return null
                        val close = JsScanner.matching(a, 0) ?: return null
                        val keys = JsScanner.entries(a, 0, close).keys
                        if (method == "omit") keys.forEach { fields.remove(it) } else fields.keys.retainAll(keys)
                    }
                    else -> return null
                }
            }
            return fields.values.toList()
        }

        /** The fields of the object literal between [open] and [close] of [code], or null when it holds a spread. */
        private fun objectFields(code: String, open: Int, close: Int): LinkedHashMap<String, StudioSchemaField>? {
            if (JsScanner.items(code, open, close).any { code.substring(it.first, it.last + 1).trimStart().startsWith("...") }) return null
            val result = LinkedHashMap<String, StudioSchemaField>()
            for ((name, range) in JsScanner.entries(code, open, close)) result[name] = field(name, code.substring(range.first, range.last + 1))
            return result
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
