package cz.xprees.comark.format.studio

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The front matter Nuxt Studio 1.8.0's database path writes (DB-PATH.md section 2): the row Nuxt Content 3.16.2 stores for
 * the document, read back and passed through Studio's `cleanDataKeys` (`utils/document/schema.js`).
 *
 * The steps, in order, each a port of the code named:
 *
 * 1. `remark-mdc` `parseFrontMatter`: the YAML, then `flat.unflatten` ([StudioFlat]).
 * 2. `@nuxtjs/mdc` `parseMarkdown`: `data = Object.assign(contentHeading(body), frontmatter)` for page collections.
 * 3. `@nuxt/content` `createParser`: `{...data, excerpt, body, id, title: data.title || undefined}`, split into columns and
 *    the `meta` column, the `seo` defaults, then `pathMeta` (a title from the file name).
 * 4. `generateCollectionInsert` + `refineContentFields`: defaults, and each column's SQL round trip.
 * 5. `cleanDataKeys`: reserved keys, `navigation: true`, `seo` duplicates, empty title/description, top-level `null`s and
 *    empty lists, with the `meta` keys appended in their order.
 */
internal object StudioDbFrontMatter {
    /** Studio has no document for this front matter: Nuxt Content cannot parse or insert it. */
    class NoDocument(message: String) : RuntimeException(message)

    private val RESERVED = listOf("id", "fsPath", "stem", "extension", "__hash__", "path", "body", "meta", "rawbody")

    /** The body-derived title and description of a page (`contentHeading`), or `null` for data collections. */
    class Heading(val title: String, val description: String)

    /**
     * The front matter Studio renders for a document whose front matter YAML loaded as [yaml], in [collection], with the
     * [heading] Nuxt Content extracted from its body (page collections).
     *
     * @throws NoDocument where Nuxt Content has no row for the document
     * @throws StudioDbPath.UnsupportedException where this port does not follow Nuxt Content
     */
    fun frontMatter(yaml: Map<String, Any?>, collection: StudioCollection, heading: Heading?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val frontmatter = StudioFlat.unflatten(yaml) as Map<String, Any?>
        val page = collection.type == StudioCollection.Type.PAGE
        // parseMarkdown: Object.assign(contentHeading(body), frontmatter, file.data)
        val data = LinkedHashMap<String, Any?>()
        if (page) {
            data["title"] = heading?.title ?: ""
            data["description"] = heading?.description ?: ""
        }
        for (k in StudioJs.keys(frontmatter)) data[k] = frontmatter[k]
        // createParser: { ...parsed.data, excerpt, body, id, title: parsed.data?.title || undefined }
        val parsed = LinkedHashMap<String, Any?>(data)
        parsed["excerpt"] = JsUndefined
        parsed["id"] = collection.fileName
        parsed["title"] = data["title"].takeIf { StudioJs.truthy(it) } ?: JsUndefined
        parsed.remove("__metadata")
        val keys = collection.orderedKeys
        val result = LinkedHashMap<String, Any?>()
        val meta = LinkedHashMap<String, Any?>()
        for (k in StudioJs.keys(parsed)) {
            if (k == "id") continue
            if (k in keys) result[k] = parsed[k] else meta[k] = parsed[k]
        }
        result["meta"] = meta
        if ("seo" in keys) {
            val current = result["seo"]
            val seo: Any = when {
                !StudioJs.truthy(current) -> LinkedHashMap<String, Any?>()
                current is Map<*, *> -> LinkedHashMap(current)
                // seo.title = ... on an array sets properties JSON.stringify drops
                current is List<*> -> current
                // strict mode: creating a property on a primitive throws, Nuxt Content skips the file
                else -> throw NoDocument("seo is not an object")
            }
            if (seo is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val s = seo as MutableMap<String, Any?>
                s["title"] = s["title"].takeIf { StudioJs.truthy(it) } ?: result["title"] ?: JsUndefined
                s["description"] = s["description"].takeIf { StudioJs.truthy(it) } ?: result["description"] ?: JsUndefined
            }
            result["seo"] = seo
        }
        // pathMeta fills path, title, stem and extension when the result does not have them
        if ("title" in keys && (!result.containsKey("title") || result["title"] === JsUndefined)) {
            result["title"] = generateTitle(refineUrlPart(collection.baseName))
        }
        // generateCollectionInsert + refineContentFields, in column order
        val row = LinkedHashMap<String, Any?>()
        for (key in keys) {
            val column = collection.columns.getValue(key)
            val raw = if (result.containsKey(key)) result[key] else JsUndefined
            val value = if (raw === JsUndefined || StudioJs.str(raw) == "null") {
                if (column.hasDefault) column.default else null
            } else raw
            row[key] = if (value == null) null else convert(value, column)
        }
        return cleanDataKeys(row)
    }

    private fun convert(value: Any?, column: StudioCollection.Column): Any? = when (column.type) {
        StudioCollection.ColumnType.JSON -> jsonRoundTrip(value)
        StudioCollection.ColumnType.BOOLEAN -> StudioJs.truthy(value)
        StudioCollection.ColumnType.NUMBER -> {
            val n = StudioJs.toNumber(value)
            if (n.isNaN() || n.isInfinite()) throw NoDocument("Invalid number value")
            n
        }
        StudioCollection.ColumnType.DATE -> formatDate(value)
        StudioCollection.ColumnType.DATETIME -> formatDateTime(value)
        StudioCollection.ColumnType.STRING -> StudioJs.str(value)
    }

    /** `JSON.parse(JSON.stringify(v))`. */
    fun jsonRoundTrip(v: Any?): Any? {
        val s = StudioJs.jsonStringify(v) ?: return JsUndefined
        return StudioJs.jsonParse(s)
    }

    private fun cleanDataKeys(document: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for (k in StudioJs.keys(document)) if (k !in RESERVED) result[k] = document[k]
        val navigation = result["navigation"]
        if (navigation == true || navigation == "true") result.remove("navigation")
        val docSeo = document["seo"]
        if (StudioJs.truthy(docSeo)) {
            val seo = LinkedHashMap<String, Any?>()
            when (docSeo) {
                is Map<*, *> -> for ((k, v) in docSeo) seo[k as String] = v
                is List<*> -> docSeo.forEachIndexed { i, v -> seo[i.toString()] = v }
                is String -> docSeo.forEachIndexed { i, c -> seo[i.toString()] = c.toString() }
                else -> {}
            }
            if (!StudioJs.truthy(seo["title"]) || strictEquals(seo["title"], document["title"])) seo.remove("title")
            if (!StudioJs.truthy(seo["description"]) || strictEquals(seo["description"], document["description"])) seo.remove("description")
            if (seo.isEmpty()) result.remove("seo") else result["seo"] = seo
        }
        if (!StudioJs.truthy(document["title"])) result.remove("title")
        if (!StudioJs.truthy(document["description"])) result.remove("description")
        val meta = document["meta"]
        if (meta is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val m = meta as Map<String, Any?>
            for (k in StudioJs.keys(m)) if (k !in RESERVED) result[k] = m[k]
        }
        for (k in result.keys.toList()) {
            val v = result[k]
            if (v == null) result.remove(k)
            else if (v is List<*> && v.isEmpty()) result.remove(k)
        }
        return result
    }

    private fun strictEquals(a: Any?, b: Any?): Boolean = when {
        a is Double && b is Double -> a == b
        a is String && b is String -> a == b
        a is Boolean && b is Boolean -> a == b
        a == null && b == null -> true
        a === JsUndefined && b === JsUndefined -> true
        else -> a === b
    }

    // ---- title from the file name (module.mjs generateTitle / refineUrlPart) ---------------------------------------------

    private val SEMVER = Regex("^\\d+(?:\\.\\d+)*(?:\\.x)?\\z")

    fun refineUrlPart(nameIn: String): String {
        val name = nameIn.split('/', ':').last()
        if (SEMVER.matches(name)) return name
        var s = Regex("^(\\d+\\.)?").replaceFirst(name, "")
        s = Regex("^index(\\.draft)?\\z").replaceFirst(s, "")
        s = Regex("\\.draft\\z").replaceFirst(s, "")
        return s
    }

    /** `path.split(/[\s-]/g).map(pascalCase).join(' ')`. */
    fun generateTitle(path: String): String =
        path.split(Regex("[${StudioJs.RE_S_CHARS}-]")).joinToString(" ") { pascalCase(it) }

    private val SPLITTERS = setOf("-", "_", "/", ".")

    /** scule `splitByCase` with the default splitters. */
    fun splitByCase(str: String): List<String> {
        val parts = ArrayList<String>()
        if (str.isEmpty()) return parts
        var buff = ""
        var previousUpper: Boolean? = null
        var previousSplitter: Boolean? = null
        var i = 0
        while (i < str.length) {
            val cp = str.codePointAt(i)
            val char = String(Character.toChars(cp))
            i += char.length
            if (char in SPLITTERS) {
                parts += buff
                buff = ""
                previousUpper = null
                continue
            }
            // isUppercase: undefined for an ASCII digit (/\d/), else whether toLowerCase changes the character
            val isUpper: Boolean? = if (char.length == 1 && char[0] in '0'..'9') null else char != char.lowercase(java.util.Locale.ROOT)
            if (previousSplitter == false) {
                if (previousUpper == false && isUpper == true) {
                    parts += buff
                    buff = char
                    previousUpper = isUpper
                    continue
                }
                if (previousUpper == true && isUpper == false && buff.length > 1) {
                    val last = lastCodeUnitChar(buff)
                    parts += buff.substring(0, maxOf(0, buff.length - last.length))
                    buff = last + char
                    previousUpper = isUpper
                    continue
                }
            }
            buff += char
            previousUpper = isUpper
            previousSplitter = false
        }
        parts += buff
        return parts
    }

    /** `buff.at(-1)`: the last UTF-16 code unit. */
    private fun lastCodeUnitChar(s: String): String = s.substring(s.length - 1)

    private fun upperFirst(s: String): String = if (s.isEmpty()) "" else s.substring(0, 1).uppercase(java.util.Locale.ROOT) + s.substring(1)

    fun pascalCase(s: String): String = if (s.isEmpty()) "" else splitByCase(s).joinToString("") { upperFirst(it) }

    // ---- DATE / DATETIME columns (module.mjs formatDate / formatDateTime / toUtcDate) ------------------------------------

    private val STRUCTURED = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?)?\\z",
        RegexOption.IGNORE_CASE,
    )

    private fun formatDate(v: Any?): String = DateTimeFormatter.ISO_LOCAL_DATE.format(toUtcDate(v).atOffset(ZoneOffset.UTC))

    private fun formatDateTime(v: Any?): String =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").format(toUtcDate(v).atOffset(ZoneOffset.UTC))

    /** `toUtcDate(value)`; throws [NoDocument] for an invalid date, [StudioDbPath.UnsupportedException] where V8's date parser decides. */
    private fun toUtcDate(value: Any?): Instant {
        val input = StudioJs.trim(StudioJs.str(value))
        val m = STRUCTURED.find(input)
        if (m == null) {
            // new Date(input): only the ISO year and year-month forms are followed here
            Regex("^(\\d{4})(?:-(\\d{2}))?\\z").find(input)?.let { ym ->
                val month = ym.groupValues[2].ifEmpty { "01" }.toInt()
                if (month !in 1..12) throw NoDocument("Invalid date value")
                return LocalDateTime.of(ym.groupValues[1].toInt(), month, 1, 0, 0).toInstant(ZoneOffset.UTC)
            }
            throw StudioDbPath.UnsupportedException(StudioDbPath.Reason.FRONT_MATTER_VALUE, "date value V8 would parse: $input")
        }
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].ifEmpty { "0" }.toInt()
        val minute = g[5].ifEmpty { "0" }.toInt()
        val second = g[6].ifEmpty { "0" }.toInt()
        // Date.UTC maps years 0-99 to 1900-1999, and every component overflow is caught by the comparison
        if (year < 100 || month !in 1..12 || hour > 23 || minute > 59 || second > 59) throw NoDocument("Invalid date value")
        val maxDay = java.time.YearMonth.of(year, month).lengthOfMonth()
        if (day !in 1..maxDay) throw NoDocument("Invalid date value")
        val offset = g[7]
        val utc = LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC)
        if (offset.isNotEmpty() && !offset.equals("Z", ignoreCase = true)) {
            // new Date(ISO string with an offset); the fraction of a second does not reach the formatted output
            val om = Regex("^([+-])(\\d{2}):(\\d{2})\\z").find(offset)
                ?: throw StudioDbPath.UnsupportedException(StudioDbPath.Reason.FRONT_MATTER_VALUE, "date offset without a colon")
            val oh = om.groupValues[2].toInt()
            val omin = om.groupValues[3].toInt()
            if (oh > 23 || omin > 59) throw StudioDbPath.UnsupportedException(StudioDbPath.Reason.FRONT_MATTER_VALUE, "date offset out of range")
            if (!input.contains('T') && !input.contains(' ')) throw StudioDbPath.UnsupportedException(StudioDbPath.Reason.FRONT_MATTER_VALUE, "date offset without a time")
            val seconds = (oh * 3600 + omin * 60).toLong()
            return utc.minusSeconds(if (om.groupValues[1] == "+") seconds else -seconds)
        }
        return utc
    }
}

/**
 * A port of `flat` 6.0.1's `unflatten(target, {})` (the copy `remark-mdc` uses on front matter and component YAML props),
 * on JavaScript values ([Map], [List], scalars). It first flattens nested objects and arrays to dotted keys and then
 * rebuilds them: dotted keys become nested objects, and numeric segments arrays.
 */
internal object StudioFlat {
    fun unflatten(target: Any?): Any? {
        if (target !is Map<*, *>) return target
        @Suppress("UNCHECKED_CAST")
        val t = target as Map<String, Any?>
        val flat = LinkedHashMap<String, Any?>()
        for (key in StudioJs.keys(t)) {
            val v = t[key]
            val isObject = v is Map<*, *> || v is List<*>
            if (!isObject || isEmpty(v)) flat[key] = v
            else for ((k, x) in flatten(v)) flat["$key.$k"] = x
        }
        val result = LinkedHashMap<String, Any?>()
        for (key in StudioJs.keys(flat)) {
            val split = ArrayDeque(key.split("."))
            var key1: Any? = getKey(split.removeFirst())
            var key2: Any? = getKey(split.firstOrNull())
            var recipient: Any = result
            var skip = false
            while (key2 != null) {
                if (key1 == "__proto__") { skip = true; break }
                val existing = get(recipient, key1)
                val isObject = existing is Map<*, *> || existing is List<*>
                if (!isObject && existing !== JsUndefined && existing !== MISSING) { skip = true; break }
                if (existing == null || existing === JsUndefined || existing === MISSING) {
                    set(recipient, key1, if (key2 is Double) ArrayList<Any?>() else LinkedHashMap<String, Any?>())
                }
                recipient = get(recipient, key1)!!
                if (split.isNotEmpty()) {
                    key1 = getKey(split.removeFirst())
                    key2 = getKey(split.firstOrNull())
                }
            }
            if (skip) continue
            set(recipient, key1, unflatten(flat[key]))
        }
        return result
    }

    private val MISSING = Any()

    private fun isEmpty(v: Any?): Boolean = when (v) {
        is List<*> -> v.isEmpty()
        is Map<*, *> -> v.isEmpty()
        else -> !StudioJs.truthy(v)
    }

    /** `flatten(target)`: leaves under dotted keys (empty objects and arrays are leaves). */
    private fun flatten(target: Any?): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        fun step(obj: Any?, prev: String?) {
            for ((k, v) in entries(obj)) {
                val newKey = if (prev != null) "$prev.$k" else k
                val isObject = v is Map<*, *> || v is List<*>
                if (isObject && entries(v).isNotEmpty()) step(v, newKey) else out[newKey] = v
            }
        }
        step(target, null)
        return out
    }

    private fun entries(v: Any?): List<Pair<String, Any?>> = when (v) {
        is List<*> -> v.mapIndexed { i, x -> i.toString() to x }
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            StudioJs.entries(v as Map<String, Any?>)
        }
        else -> emptyList()
    }

    /** `getkey`: a [Double] when `Number(key)` is a number and the key has no `.`, else the key ([String]), `null` for none. */
    private fun getKey(key: String?): Any? {
        if (key == null) return null
        val n = StudioJs.toNumber(key)
        return if (n.isNaN() || key.contains('.')) key else n
    }

    /** An array slot never assigned (`[]` with a larger index set); JSON writes `null` for it. */
    val HOLE = JsUndefined

    private fun propertyName(key: Any?): String = if (key is Double) StudioJs.numberToString(key) else key as String

    private fun get(recipient: Any, key: Any?): Any? = when (recipient) {
        is Map<*, *> -> propertyName(key).let { if (recipient.containsKey(it)) recipient[it] else MISSING }
        is List<*> -> arrayIndex(key)?.let { recipient.getOrElse(it) { MISSING } }
            ?: throw StudioDbPath.UnsupportedException(StudioDbPath.Reason.FRONT_MATTER_VALUE, "non-index key on an array")
        else -> throw StudioDbPath.UnsupportedException(StudioDbPath.Reason.FRONT_MATTER_VALUE, "property of a scalar")
    }

    @Suppress("UNCHECKED_CAST")
    private fun set(recipient: Any, key: Any?, value: Any?) {
        when (recipient) {
            is MutableMap<*, *> -> (recipient as MutableMap<String, Any?>)[propertyName(key)] = value
            is MutableList<*> -> {
                val list = recipient as MutableList<Any?>
                val i = arrayIndex(key)
                    ?: throw StudioDbPath.UnsupportedException(StudioDbPath.Reason.FRONT_MATTER_VALUE, "non-index key on an array")
                while (list.size <= i) list += HOLE
                list[i] = value
            }
            else -> throw StudioDbPath.UnsupportedException(StudioDbPath.Reason.FRONT_MATTER_VALUE, "property of a scalar")
        }
    }

    private fun arrayIndex(key: Any?): Int? {
        val s = propertyName(key)
        if (!Regex("^(0|[1-9][0-9]{0,5})\\z").matches(s)) return null
        return s.toInt()
    }
}
