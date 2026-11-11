package cz.xprees.comark.format.studio

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The tree that comark 0.7.0's `renderMarkdown` receives (a `ComarkTree`), modelled after its array tuples:
 *
 * - a text node is a plain string,
 * - an element is `[tag, attrs, ...children]`,
 * - a comment is `[null, attrs, text]`.
 *
 * Attribute and front matter values are JavaScript values: [String], [Boolean], [Double] (every JavaScript number),
 * `null`, [List], [Map] with [String] keys, [JsUndefined] and [JsDate]. Maps keep insertion order; the renderer applies
 * JavaScript's own-key order (integer-like keys first) wherever comark enumerates an object, so callers do not have to.
 */
sealed interface StudioNode

/** A text node (a string child in comark's tree). */
data class StudioText(val text: String) : StudioNode

/** An element `[tag, attrs, ...children]`. `attrs` may hold comark's internal `$` object (`{html: 1, block: 0|1}`). */
data class StudioElement(
    val tag: String,
    val attrs: Map<String, Any?> = emptyMap(),
    val children: List<StudioNode> = emptyList(),
) : StudioNode

/** A comment `[null, attrs, text]`. [text] is `null` when the tuple has no third item (rendered as `undefined`, like comark). */
data class StudioComment(val text: String?, val attrs: Map<String, Any?> = emptyMap()) : StudioNode

/** A document: the front matter object, comark's `meta` (unused by the renderer) and the top-level nodes. */
data class StudioDocument(
    val frontmatter: Map<String, Any?> = emptyMap(),
    val nodes: List<StudioNode> = emptyList(),
    val meta: Map<String, Any?> = emptyMap(),
)

/** JavaScript `undefined` as a value (an object property that exists but holds `undefined`). */
object JsUndefined {
    override fun toString(): String = "undefined"
}

/** A JavaScript `Date`, kept as the string its `toISOString()` returns, or `null` for an invalid date. */
data class JsDate(val iso: String?)

/**
 * Reads comark trees and values from JSON, in the encoding the Studio oracle (`src/test/testData/studio/oracle.json`) uses:
 * nodes are arrays, values JSON cannot hold are objects `{"$type": "date" | "number" | "undefined", "value": ...}`, and
 * arrays or objects reached more than once are written once as `{"$type": "shared", ...}` (see [Refs]).
 */
object StudioJson {
    /**
     * Shared arrays and objects of one encoded root: `{"$type": "shared", "id": n, "value": ...}` defines one,
     * `{"$type": "ref", "id": n}` refers back to it, so both places hold the same [List] or [Map] instance, as YAML
     * aliases do in JavaScript (js-yaml's dump then writes `&ref_N` / `*ref_N`).
     */
    class Refs internal constructor() {
        internal val byId = HashMap<Int, Any>()
    }

    fun document(tree: JsonElement, frontmatter: JsonElement?): StudioDocument {
        val t = tree.asJsonObject
        val nodeRefs = Refs()
        @Suppress("UNCHECKED_CAST")
        return StudioDocument(
            frontmatter = (frontmatter?.takeUnless { it.isJsonNull }?.let { value(it) } as? Map<String, Any?>) ?: emptyMap(),
            nodes = t.getAsJsonArray("nodes").map { node(it, nodeRefs) },
            meta = (t.get("meta")?.let { value(it) } as? Map<String, Any?>) ?: emptyMap(),
        )
    }

    fun document(json: String): StudioDocument {
        val o = JsonParser.parseString(json).asJsonObject
        return document(o, o.get("frontmatter"))
    }

    fun node(e: JsonElement, refs: Refs = Refs()): StudioNode {
        if (e.isJsonPrimitive && e.asJsonPrimitive.isString) return StudioText(e.asString)
        require(e.isJsonArray) { "not a comark node: $e" }
        val a = e.asJsonArray
        @Suppress("UNCHECKED_CAST")
        val attrs = (if (a.size() > 1) value(a[1], refs) as? Map<String, Any?> else null) ?: emptyMap()
        val head = a[0]
        if (head.isJsonNull) {
            val text = if (a.size() > 2) a[2].takeIf { it.isJsonPrimitive }?.asString else null
            return StudioComment(text, attrs)
        }
        return StudioElement(head.asString, attrs, (2 until a.size()).map { node(a[it], refs) })
    }

    fun value(e: JsonElement, refs: Refs = Refs()): Any? = when {
        e.isJsonNull -> null
        e.isJsonPrimitive -> {
            val p = e.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asString.toDouble()
                else -> p.asString
            }
        }
        e.isJsonArray -> e.asJsonArray.map { value(it, refs) }
        else -> shared(e.asJsonObject, refs) ?: special(e.asJsonObject) ?: LinkedHashMap<String, Any?>().also { m ->
            for ((k, v) in e.asJsonObject.entrySet()) m[k] = value(v, refs)
        }
    }

    private fun shared(o: JsonObject, refs: Refs): Any? {
        val type = o.get("\$type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        val id = o.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: return null
        return when {
            type == "ref" && o.keySet() == setOf("\$type", "id") ->
                refs.byId[id] ?: throw IllegalArgumentException("reference to unknown shared value $id")
            type == "shared" && o.keySet() == setOf("\$type", "id", "value") -> {
                val inner = o.get("value")
                if (inner.isJsonArray) {
                    ArrayList<Any?>().also { list ->
                        refs.byId[id] = list
                        for (x in inner.asJsonArray) list += value(x, refs)
                    }
                } else {
                    LinkedHashMap<String, Any?>().also { map ->
                        refs.byId[id] = map
                        for ((k, v) in inner.asJsonObject.entrySet()) map[k] = value(v, refs)
                    }
                }
            }
            else -> null
        }
    }

    private fun special(o: JsonObject): Any? {
        val type = o.get("\$type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        if (!o.keySet().all { it == "\$type" || it == "value" }) return null
        val v = o.get("value")?.takeIf { it.isJsonPrimitive }?.asString
        return when (type) {
            "date" -> JsDate(v?.takeUnless { it == "Invalid Date" })
            "number" -> when (v) {
                "NaN" -> Double.NaN
                "Infinity" -> Double.POSITIVE_INFINITY
                "-Infinity" -> Double.NEGATIVE_INFINITY
                "-0" -> -0.0
                else -> v?.toDouble()
            }
            "undefined" -> JsUndefined
            else -> null
        }
    }
}
