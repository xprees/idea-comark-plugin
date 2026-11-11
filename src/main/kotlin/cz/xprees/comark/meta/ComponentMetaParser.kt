package cz.xprees.comark.meta

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.openapi.progress.ProgressManager
import java.io.Reader

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
 *   a remote endpoint) or as an ES module exporting it (`.nuxt/component-meta.mjs`: `export default {...}`);
 * - the extension's own format: an array of `{ mdc_name, description, documentation_markdown, docs_url, component_meta }`.
 *
 * The files can be several megabytes (vue-component-meta embeds full type schemas), so they are read with a streaming reader
 * that skips everything Comark has no use for (`schema`, `declarations`, `events`, ...) instead of building a JSON tree.
 */
object ComponentMetaParser {
    private val EXPORT_DEFAULT = Regex("""\bexport\s+default\s*""")

    /** Components described by [text], or null when it is neither JSON nor a module exporting JSON. */
    fun parse(text: CharSequence): List<MetaComponentRecord>? {
        val start = jsonStart(text) ?: return null
        return try {
            val reader = JsonReader(CharSequenceReader(text, start))
            reader.strictness = Strictness.LENIENT
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> readArray(reader)
                JsonToken.BEGIN_OBJECT -> readComponentMap(reader)
                else -> null
            }?.sortedBy { it.name }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Offset of the JSON value: after `export default` in a module, else the first `{` / `[` of a JSON document. */
    private fun jsonStart(text: CharSequence): Int? {
        EXPORT_DEFAULT.find(text)?.let { match ->
            var i = match.range.last + 1
            while (i < text.length && text[i].isWhitespace()) i++
            return if (i < text.length && (text[i] == '{' || text[i] == '[')) i else null
        }
        var i = 0
        if (text.isNotEmpty() && text[0] == '﻿') i++
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length && (text[i] == '{' || text[i] == '[')) i else null
    }

    private fun readArray(reader: JsonReader): List<MetaComponentRecord> {
        val result = ArrayList<MetaComponentRecord>()
        reader.beginArray()
        while (reader.hasNext()) {
            ProgressManager.checkCanceled()
            if (reader.peek() == JsonToken.BEGIN_OBJECT) readComponent(reader, null)?.let(result::add) else reader.skipValue()
        }
        reader.endArray()
        return result
    }

    /** `{ "Name": { pascalName, meta, ... }, ... }`; a single wrapping `components` object is unwrapped. */
    private fun readComponentMap(reader: JsonReader): List<MetaComponentRecord> {
        val result = ArrayList<MetaComponentRecord>()
        reader.beginObject()
        while (reader.hasNext()) {
            ProgressManager.checkCanceled()
            val key = reader.nextName()
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            if (key == "components") {
                result += readComponentMap(reader)
                continue
            }
            readComponent(reader, key)?.let(result::add)
        }
        reader.endObject()
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

    private fun readComponent(reader: JsonReader, key: String?): MetaComponentRecord? {
        val fields = ComponentFields()
        readComponentFields(reader, fields)
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

    private fun readComponentFields(reader: JsonReader, into: ComponentFields) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "mdc_name" -> into.mdcName = stringOrNull(reader)
                "pascalName" -> into.pascalName = stringOrNull(reader)
                "kebabName" -> into.kebabName = stringOrNull(reader)
                "filePath" -> into.filePath = stringOrNull(reader) ?: into.filePath
                "shortPath" -> stringOrNull(reader)?.let { if (into.filePath == null) into.filePath = it }
                "fullPath" -> into.fullPath = stringOrNull(reader)
                "description" -> stringOrNull(reader)?.takeIf { it.isNotBlank() }?.let { into.description = it }
                "documentation_markdown" -> into.documentation = stringOrNull(reader)?.takeIf { it.isNotBlank() }
                "docs_url" -> into.docsUrl = stringOrNull(reader)?.takeIf { it.isNotBlank() }
                "component_meta" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) readComponentFields(reader, into) else reader.skipValue()
                "meta" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                    into.hasMeta = true
                    readMeta(reader, into)
                } else {
                    reader.skipValue()
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    /** vue-component-meta's `ComponentMeta`: `props`, `slots` (and a `description` some generators add). */
    private fun readMeta(reader: JsonReader, into: ComponentFields) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "props" -> into.props = readList(reader, ::readProp)
                "slots" -> into.slots = readList(reader, ::readSlot)
                "description" -> stringOrNull(reader)?.takeIf { it.isNotBlank() }?.let { if (into.description == null) into.description = it }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun <T> readList(reader: JsonReader, item: (JsonReader) -> T?): List<T> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val result = ArrayList<T>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) item(reader)?.let(result::add) else reader.skipValue()
        }
        reader.endArray()
        return result
    }

    private fun readProp(reader: JsonReader): ComarkProp? {
        var name: String? = null
        var type: String? = null
        var required = false
        var default: String? = null
        var description: String? = null
        var global = false
        var tagDefault: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = stringOrNull(reader)
                "type" -> type = stringOrNull(reader)
                "required" -> required = booleanOrFalse(reader)
                "default" -> default = scalarText(reader)
                "description" -> description = stringOrNull(reader)?.takeIf { it.isNotBlank() }
                "global" -> global = booleanOrFalse(reader)
                "tags" -> tagDefault = readDefaultTag(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        // global props are the attributes every component accepts (`class`, `style`, `key`, ...)
        if (name.isNullOrBlank() || global) return null
        return ComarkProp(name, type, required, default ?: tagDefault, description)
    }

    /** The `@default` JSDoc tag in vue-component-meta's `tags: [{ name, text }]`. */
    private fun readDefaultTag(reader: JsonReader): String? {
        var result: String? = null
        for (tag in readList(reader) { r ->
            var name: String? = null
            var text: String? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "name" -> name = stringOrNull(r)
                    "text" -> text = scalarText(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (name != null) name to text else null
        }) {
            if (result == null && (tag.first == "default" || tag.first == "defaultValue")) result = tag.second
        }
        return result
    }

    private fun readSlot(reader: JsonReader): ComarkSlotInfo? {
        var name: String? = null
        var type: String? = null
        var description: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = stringOrNull(reader)
                "type" -> type = stringOrNull(reader)
                "description" -> description = stringOrNull(reader)?.takeIf { it.isNotBlank() }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return if (name.isNullOrBlank()) null else ComarkSlotInfo(name, description, type)
    }

    private fun stringOrNull(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }

        else -> {
            reader.skipValue()
            null
        }
    }

    private fun booleanOrFalse(reader: JsonReader): Boolean = when (reader.peek()) {
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.STRING -> reader.nextString() == "true"
        else -> {
            reader.skipValue()
            false
        }
    }

    /** A scalar as text (`"x"` -> `x`, `1` -> `1`, `true` -> `true`); structured values are skipped. */
    private fun scalarText(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> {
            reader.nextNull()
            "null"
        }

        else -> {
            reader.skipValue()
            null
        }
    }

    /** A [Reader] over a [CharSequence] from [start], so large documents are not copied into a String first. */
    private class CharSequenceReader(private val text: CharSequence, start: Int) : Reader() {
        private var position = start

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (position >= text.length) return -1
            val count = minOf(length, text.length - position)
            for (k in 0 until count) buffer[offset + k] = text[position + k]
            position += count
            return count
        }

        override fun close() {}
    }
}
