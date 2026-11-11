package cz.xprees.comark.meta

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.util.concurrent.CancellationException

/**
 * One component described by component metadata, before it is matched with files of the project. [filePath] is relative to the
 * Nuxt root (nuxt-component-meta's `filePath` / `shortPath`), [fullPath] absolute.
 */
class MetaComponentRecord(
    val name: String,
    val kebabName: String?,
    val filePath: String?,
    val fullPath: String?,
    val description: String?,
    val documentation: String?,
    val docsUrl: String?,
    val props: List<ComarkProp>,
    val slots: List<ComarkSlotInfo>,
) {
    override fun toString(): String = "MetaComponentRecord($name, props=${props.map { it.name }}, slots=${slots.map { it.name }})"
}

/**
 * Reads component metadata in the formats the VS Code extension accepts:
 * - nuxt-component-meta output: an object keyed by component name whose values carry `pascalName`, `kebabName`, `filePath`,
 *   `fullPath` and `meta` (`props`, `slots`, `events` as produced by vue-component-meta), either as JSON (`component-meta.json`,
 *   a remote endpoint) or as an ES module exporting it (`.nuxt/component-meta.mjs`);
 * - the extension's own format: an array of `{ mdc_name, description, documentation_markdown, docs_url, component_meta }`.
 *
 * Current nuxt-component-meta versions deduplicate the module they write: it declares a string table and shared objects first
 * (`const _s = ["string | undefined", ...]; const _ref7 = {type: 1, props: _ref6};`) and exports an object literal with
 * unquoted keys that refers to them (`export default {Alert: {mode: _s[3], meta: _ref7}}`). The parser therefore reads the
 * restricted JavaScript those modules consist of ([JsLiteralReader]): `const` declarations of literals, then `export default`.
 *
 * The files can be large (vue-component-meta embeds full type schemas), so values Comark has no use for (`schema`,
 * `declarations`, `events`, ...) are skipped while reading instead of being built.
 */
object ComponentMetaParser {
    private val EXPORT_DEFAULT = Regex("""\bexport\s+default\b""")

    /** Keys whose values are never needed, wherever they appear; their (often huge) values are scanned without building them. */
    private val SKIPPED_KEYS = setOf("schema", "declarations", "events", "exposed")

    /** Components described by [text], or null when it is neither JSON nor a module exporting component metadata. */
    fun parse(text: CharSequence): List<MetaComponentRecord>? {
        return try {
            when (val root = rootValue(text)) {
                is List<*> -> readArray(root)
                is Map<*, *> -> readComponentMap(root)
                else -> null
            }?.sortedBy { it.name }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            // pathologically nested values
            null
        }
    }

    /** The exported value of a module (resolving its constants), or the value of a JSON document; null when there is none. */
    private fun rootValue(text: CharSequence): Any? {
        val reader = JsLiteralReader(text, SKIPPED_KEYS)
        reader.skipTrivia()
        if (reader.peek() == '{' || reader.peek() == '[') return reader.value()
        while (true) {
            reader.skipTrivia()
            when {
                reader.atEnd() -> return null
                reader.word("const") || reader.word("let") || reader.word("var") -> reader.declarations()
                reader.word("export") -> {
                    reader.skipTrivia()
                    if (!reader.word("default")) return exportDefault(text, reader)
                    return reader.value()
                }

                else -> return exportDefault(text, reader)
            }
        }
    }

    /** Fallback for statements the reader does not understand: the value after the next `export default`, without constants. */
    private fun exportDefault(text: CharSequence, reader: JsLiteralReader): Any? {
        val match = EXPORT_DEFAULT.find(text, reader.position) ?: return null
        val fresh = JsLiteralReader(text, SKIPPED_KEYS, match.range.last + 1)
        return fresh.value()
    }

    private fun readArray(items: List<*>): List<MetaComponentRecord> = items.mapNotNull { item ->
        ProgressManager.checkCanceled()
        (item as? Map<*, *>)?.let { readComponent(it, null) }
    }

    /** `{ "Name": { pascalName, meta, ... }, ... }`; a single wrapping `components` object is unwrapped. */
    private fun readComponentMap(map: Map<*, *>): List<MetaComponentRecord> {
        val result = ArrayList<MetaComponentRecord>()
        for ((key, value) in map) {
            ProgressManager.checkCanceled()
            if (value !is Map<*, *>) continue
            if (key == "components") {
                result += readComponentMap(value)
                continue
            }
            readComponent(value, key as? String)?.let(result::add)
        }
        return result
    }

    /** Raw fields of one component; the custom format nests the nuxt-component-meta fields under `component_meta`. */
    private class ComponentFields {
        var mdcName: String? = null
        var pascalName: String? = null
        var kebabName: String? = null
        var filePath: String? = null
        var fullPath: String? = null
        var description: String? = null
        var documentation: String? = null
        var docsUrl: String? = null
        var props: List<ComarkProp>? = null
        var slots: List<ComarkSlotInfo>? = null
        var hasMeta = false
    }

    private fun readComponent(map: Map<*, *>, key: String?): MetaComponentRecord? {
        val fields = ComponentFields()
        readComponentFields(map, fields)
        val rawName = fields.pascalName ?: fields.kebabName ?: fields.mdcName ?: key ?: return null
        if (!fields.hasMeta && fields.pascalName == null && fields.kebabName == null && fields.mdcName == null) return null
        val name = ComponentNaming.pascalCase(rawName).ifEmpty { return null }
        return MetaComponentRecord(
            name = name,
            kebabName = fields.mdcName ?: fields.kebabName,
            filePath = fields.filePath,
            fullPath = fields.fullPath,
            description = fields.description,
            documentation = fields.documentation,
            docsUrl = fields.docsUrl,
            props = fields.props.orEmpty(),
            slots = fields.slots.orEmpty(),
        )
    }

    private fun readComponentFields(map: Map<*, *>, into: ComponentFields) {
        for ((key, value) in map) {
            when (key) {
                "mdc_name" -> into.mdcName = text(value)
                "pascalName" -> into.pascalName = text(value)
                "kebabName" -> into.kebabName = text(value)
                "filePath" -> into.filePath = text(value) ?: into.filePath
                "shortPath" -> text(value)?.let { if (into.filePath == null) into.filePath = it }
                "fullPath" -> into.fullPath = text(value)
                "description" -> text(value)?.takeIf { it.isNotBlank() }?.let { into.description = it }
                "documentation_markdown" -> into.documentation = text(value)?.takeIf { it.isNotBlank() }
                "docs_url" -> into.docsUrl = text(value)?.takeIf { it.isNotBlank() }
                "component_meta" -> if (value is Map<*, *>) readComponentFields(value, into)
                "meta" -> if (value is Map<*, *>) {
                    into.hasMeta = true
                    readMeta(value, into)
                }
            }
        }
    }

    /** vue-component-meta's `ComponentMeta`: `props`, `slots` (and a `description` some generators add). */
    private fun readMeta(map: Map<*, *>, into: ComponentFields) {
        for ((key, value) in map) {
            when (key) {
                "props" -> into.props = objects(value).mapNotNull(::readProp)
                "slots" -> into.slots = objects(value).mapNotNull(::readSlot)
                "description" -> text(value)?.takeIf { it.isNotBlank() }?.let { if (into.description == null) into.description = it }
            }
        }
    }

    private fun objects(value: Any?): List<Map<*, *>> = (value as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    private fun readProp(map: Map<*, *>): ComarkProp? {
        val name = text(map["name"])
        // global props are the attributes every component accepts (`class`, `style`, `key`, ...)
        if (name.isNullOrBlank() || flag(map["global"])) return null
        val default = if (map.containsKey("default")) scalarText(map["default"]) else null
        return ComarkProp(
            name = name,
            type = text(map["type"]),
            required = flag(map["required"]),
            defaultValue = default ?: defaultTag(map["tags"]),
            description = text(map["description"])?.takeIf { it.isNotBlank() },
        )
    }

    /** The `@default` JSDoc tag in vue-component-meta's `tags: [{ name, text }]`. */
    private fun defaultTag(tags: Any?): String? = objects(tags).firstOrNull { text(it["name"]) == "default" || text(it["name"]) == "defaultValue" }
        ?.let { scalarText(it["text"]) }

    private fun readSlot(map: Map<*, *>): ComarkSlotInfo? {
        val name = text(map["name"])
        if (name.isNullOrBlank()) return null
        return ComarkSlotInfo(name, text(map["description"])?.takeIf { it.isNotBlank() }, text(map["type"]))
    }

    /** A string or number as text; anything else is null. */
    private fun text(value: Any?): String? = when (value) {
        is String -> value
        is JsLiteralReader.JsNumber -> value.text
        else -> null
    }

    private fun flag(value: Any?): Boolean = value == true || value == "true"

    /** A scalar as text (`"x"` -> `x`, `1` -> `1`, `true` -> `true`, `null` -> `null`); structured values are null. */
    private fun scalarText(value: Any?): String? = when (value) {
        null -> "null"
        is String -> value
        is JsLiteralReader.JsNumber -> value.text
        is Boolean -> value.toString()
        else -> null
    }
}

/**
 * Reads the JavaScript literals nuxt-component-meta modules (and JSON documents) consist of: objects with quoted or bare keys,
 * arrays, strings, numbers, `true` / `false` / `null` / `undefined`, and references to constants declared earlier in the module
 * (`_ref7`, `_s[3]`). Values are built as [Map]s, [List]s, [String]s, [JsNumber]s, [Boolean]s and null; values of [skippedKeys]
 * are scanned but not built. Throws [IllegalArgumentException] on anything else.
 */
internal class JsLiteralReader(private val text: CharSequence, private val skippedKeys: Set<String>, start: Int = 0) {
    /** A number literal, kept as written. */
    class JsNumber(val text: String) {
        override fun toString(): String = text
    }

    var position: Int = start
        private set

    private val constants = HashMap<String, Any?>()
    private var steps = 0

    fun atEnd(): Boolean = position >= text.length

    fun peek(): Char? = text.getOrNull(position)

    /** Skips whitespace, comments and a byte order mark. */
    fun skipTrivia() {
        while (position < text.length) {
            val c = text[position]
            when {
                c.isWhitespace() || c == '﻿' -> position++
                c == '/' && text.getOrNull(position + 1) == '/' -> {
                    while (position < text.length && text[position] != '\n') position++
                }

                c == '/' && text.getOrNull(position + 1) == '*' -> {
                    val close = indexOf("*/", position + 2)
                    position = if (close < 0) text.length else close + 2
                }

                else -> return
            }
        }
    }

    /** Consumes the keyword [word] at the current position when it is there as a whole word. */
    fun word(word: String): Boolean {
        if (position + word.length > text.length) return false
        for (k in word.indices) if (text[position + k] != word[k]) return false
        if (text.getOrNull(position + word.length)?.let(::isIdentifierPart) == true) return false
        position += word.length
        return true
    }

    /** `a = <value>, b = <value>;` after `const` / `let` / `var`: remembers each constant for later references. */
    fun declarations() {
        while (true) {
            skipTrivia()
            val name = identifier()
            skipTrivia()
            expect('=')
            constants[name] = value()
            skipTrivia()
            when (peek()) {
                ',' -> position++
                ';' -> {
                    position++
                    return
                }

                else -> return
            }
        }
    }

    /** The value at the current position (leading trivia skipped). */
    fun value(): Any? = value(build = true)

    private fun value(build: Boolean): Any? {
        if ((++steps and 0xFFF) == 0) ProgressManager.checkCanceled()
        skipTrivia()
        val c = peek() ?: fail("unexpected end")
        return when {
            c == '{' -> objectValue(build)
            c == '[' -> arrayValue(build)
            c == '"' || c == '\'' -> string(build)
            c == '-' || c == '+' || c == '.' || c.isDigit() -> number()
            isIdentifierStart(c) -> reference(build)
            else -> fail("unexpected '$c'")
        }
    }

    private fun objectValue(build: Boolean): Map<String, Any?>? {
        position++
        val result = if (build) LinkedHashMap<String, Any?>() else null
        while (true) {
            skipTrivia()
            when (peek()) {
                null -> fail("unterminated object")
                '}' -> {
                    position++
                    return result
                }
            }
            val key = when (val k = peek()!!) {
                '"', '\'' -> string(build = true) as String
                else -> if (isIdentifierStart(k)) identifier() else if (k.isDigit()) number().text else fail("unexpected '$k' in object")
            }
            skipTrivia()
            expect(':')
            val keep = build && key !in skippedKeys
            val value = value(keep)
            if (keep) result!![key] = value
            skipTrivia()
            when (peek()) {
                ',' -> position++
                '}' -> {}
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun arrayValue(build: Boolean): List<Any?>? {
        position++
        val result = if (build) ArrayList<Any?>() else null
        while (true) {
            skipTrivia()
            when (peek()) {
                null -> fail("unterminated array")
                ']' -> {
                    position++
                    return result
                }
            }
            val value = value(build)
            result?.add(value)
            skipTrivia()
            when (peek()) {
                ',' -> position++
                ']' -> {}
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun string(build: Boolean): String? {
        val quote = text[position++]
        val builder = if (build) StringBuilder() else null
        while (true) {
            if (position >= text.length) fail("unterminated string")
            val c = text[position++]
            when (c) {
                quote -> return builder?.toString()
                '\n' -> fail("line break in string")
                '\\' -> {
                    if (position >= text.length) fail("unterminated string")
                    val e = text[position++]
                    val decoded = when (e) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'v' -> '\u000B'
                        '0' -> '\u0000'
                        'u' -> unicodeEscape()
                        'x' -> hexEscape(2)
                        '\n' -> null
                        else -> e
                    }
                    if (decoded != null) builder?.append(decoded)
                }

                else -> builder?.append(c)
            }
        }
    }

    private fun unicodeEscape(): Char {
        if (peek() == '{') {
            val close = indexOf("}", position)
            if (close < 0) fail("bad escape")
            val code = text.subSequence(position + 1, close).toString().toIntOrNull(16) ?: fail("bad escape")
            position = close + 1
            return if (code <= 0xFFFF) code.toChar() else '�'
        }
        return hexEscape(4)
    }

    private fun hexEscape(digits: Int): Char {
        if (position + digits > text.length) fail("bad escape")
        val code = text.subSequence(position, position + digits).toString().toIntOrNull(16) ?: fail("bad escape")
        position += digits
        return code.toChar()
    }

    private fun number(): JsNumber {
        val start = position
        while (position < text.length && (text[position].isLetterOrDigit() || text[position] in "+-.")) position++
        val literal = text.subSequence(start, position).toString()
        if (literal.none(Char::isDigit) && literal != "Infinity") fail("bad number")
        return JsNumber(literal)
    }

    /** A keyword literal, a constant (`_ref7`) or an element of a constant array (`_s[3]`). */
    private fun reference(build: Boolean): Any? {
        val name = identifier()
        val value = when (name) {
            "true" -> return true
            "false" -> return false
            "null", "undefined" -> return null
            "NaN", "Infinity" -> return JsNumber(name)
            else -> if (constants.containsKey(name)) constants[name] else fail("unknown identifier '$name'")
        }
        skipTrivia()
        if (peek() != '[') return if (build) value else null
        position++
        val index = value(build = true)
        skipTrivia()
        expect(']')
        val element = (index as? JsNumber)?.text?.toIntOrNull()?.let { (value as? List<*>)?.getOrNull(it) }
        return if (build) element else null
    }

    private fun identifier(): String {
        val start = position
        if (position >= text.length || !isIdentifierStart(text[position])) fail("expected an identifier")
        while (position < text.length && isIdentifierPart(text[position])) position++
        return text.subSequence(start, position).toString()
    }

    private fun expect(c: Char) {
        if (peek() != c) fail("expected '$c'")
        position++
    }

    private fun indexOf(needle: String, from: Int): Int {
        var i = from
        while (i + needle.length <= text.length) {
            var k = 0
            while (k < needle.length && text[i + k] == needle[k]) k++
            if (k == needle.length) return i
            i++
        }
        return -1
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at offset $position")

    private fun isIdentifierStart(c: Char) = c.isLetter() || c == '_' || c == '$'

    private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
