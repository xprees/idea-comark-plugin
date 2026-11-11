package cz.xprees.comark.images

import cz.xprees.comark.lang.NuxtPaths

/**
 * What a `nuxt.config.*` says about where images live. Directory values are as written (relative to the layer, or absolute); null
 * when the configuration doesn't set them or computes them from something other than a string literal (`process.env.X`).
 */
data class NuxtConfigInfo(
    /** `srcDir`. */
    val srcDir: String? = null,
    /** `dir.public`. */
    val publicDir: String? = null,
    /** `image.dir` (Nuxt Image). */
    val imageDir: String? = null,
    /** `image.alias` entries that point at remote URLs, keys with a leading `/` (`/unsplash` to `https://images.unsplash.com`). */
    val imageAliases: Map<String, String> = emptyMap(),
    /** `app.baseURL`; null when it is `/`. */
    val baseURL: String? = null,
    /** `app.cdnURL`. */
    val cdnURL: String? = null,
    /** Local `extends` entries (`./theme`, `../base`, `~~/layer`); remote and package layers are dropped. */
    val extends: List<String> = emptyList(),
    /** `modules` lists `@nuxt/image`. */
    val hasImageModule: Boolean = false,
)

/**
 * Text-based reader of `nuxt.config.*` and `package.json`, in the style of [cz.xprees.comark.lang.ComarkProjectDetector]: it never
 * evaluates the configuration, so computed values (spreads, imported objects, environment variables) are not understood. Keys are
 * only matched at the top level of the configuration object, so `image: { dir }` is never taken for `dir`.
 */
internal object NuxtImageConfig {
    private val ROOT_OBJECT = Regex("""(?:defineNuxtConfig\s*\(|export\s+default|module\.exports\s*=)\s*\{""")
    private val IMAGE_MODULE = Regex("""['"`]@nuxt/image(?:-edge)?['"`]""")
    private val NUXT_IMAGE_DEPENDENCY = Regex(""""@nuxt/image(?:-edge)?"\s*:""")
    private val NUXT_DEPENDENCY = Regex(""""(?:nuxt|nuxt3|nuxt-nightly|@nuxt/content)"\s*:""")
    private val LOCAL_EXTENDS = listOf("./", "../", "/", "~/", "~~/", "@/", "@@/")

    fun parse(text: String): NuxtConfigInfo {
        val code = stripComments(text)
        val root = ROOT_OBJECT.find(code)?.let { balanced(code, it.range.last) } ?: code
        val entries = entries(root)
        val dir = entries["dir"]?.let(::objectBody)?.let(::entries).orEmpty()
        val image = entries["image"]?.let(::objectBody)?.let(::entries).orEmpty()
        val app = entries["app"]?.let(::objectBody)?.let(::entries).orEmpty()
        val aliases = image["alias"]?.let(::objectBody)?.let(::entries).orEmpty().mapNotNull { (key, value) ->
            val url = scalar(value)?.takeIf { "://" in it } ?: return@mapNotNull null
            "/" + key.trimStart('/') to url
        }.toMap()
        val extends = entries["extends"]?.let { value ->
            NuxtPaths.STRING.findAll(value).map { it.groupValues[1].trim() }
                .filter { path -> LOCAL_EXTENDS.any(path::startsWith) || NuxtPaths.WINDOWS_DRIVE.containsMatchIn(path) }.toList()
        }.orEmpty()
        return NuxtConfigInfo(
            srcDir = scalar(entries["srcDir"]),
            publicDir = scalar(dir["public"]),
            imageDir = scalar(image["dir"]),
            imageAliases = aliases,
            baseURL = scalar(app["baseURL"])?.takeIf { it.trim('/').isNotEmpty() },
            cdnURL = scalar(app["cdnURL"])?.takeIf { it.trim('/').isNotEmpty() },
            extends = extends,
            hasImageModule = entries["modules"]?.let(IMAGE_MODULE::containsMatchIn) == true,
        )
    }

    /** True when a `package.json` text lists Nuxt Image as a dependency. */
    fun manifestHasNuxtImage(manifest: CharSequence) = NUXT_IMAGE_DEPENDENCY.containsMatchIn(manifest)

    /** True when a `package.json` text lists Nuxt (or Nuxt Content, which implies it) as a dependency. */
    fun manifestIsNuxt(manifest: CharSequence) = NUXT_DEPENDENCY.containsMatchIn(manifest)

    /** [text] without `//` and `/* */` comments; string literals (which may contain `//`, as in URLs) are kept intact. */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    val end = stringEnd(text, i)
                    out.append(text, i, end)
                    i = end
                }

                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }

                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    out.append(' ')
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** The body (between the braces) of the object literal of the top-level [key] in [body], or null when it isn't an object. */
    fun topLevelObject(body: String, key: String): String? = entries(body)[key]?.let(::objectBody)

    /** The string value of the top-level [key] in [body]: the last string literal of its value (`resolve(__dirname, 'src')` gives `src`). */
    fun scalar(body: String, key: String): String? = scalar(entries(body)[key])

    private fun scalar(value: String?): String? =
        value?.let { NuxtPaths.STRING.findAll(it).lastOrNull()?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() }

    private fun objectBody(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("{")) return null
        return balanced(trimmed, 0)
    }

    /** The text between the `{` at [open] and its matching `}` (or the end of [text] when unclosed). */
    private fun balanced(text: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '\'', '"', '`' -> {
                    i = stringEnd(text, i)
                    continue
                }

                '{', '[', '(' -> depth++
                '}', ']', ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
            i++
        }
        return text.substring(minOf(open + 1, text.length))
    }

    /** The `key: value` entries at depth 0 of an object body; spreads, shorthand properties and methods are skipped. */
    private fun entries(body: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (entry in splitTopLevel(body)) {
            val colon = topLevelColon(entry)
            if (colon < 0) continue
            val key = entry.substring(0, colon).trim().removeSurrounding("'").removeSurrounding("\"").removeSurrounding("`")
            if (key.isEmpty() || key.startsWith("...")) continue
            result.putIfAbsent(key, entry.substring(colon + 1))
        }
        return result
    }

    private fun splitTopLevel(body: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '\'', '"', '`' -> {
                    i = stringEnd(body, i)
                    continue
                }

                '{', '[', '(' -> depth++
                '}', ']', ')' -> depth--
                ',' -> if (depth == 0) {
                    parts += body.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        parts += body.substring(start)
        return parts
    }

    /** The index of the first `:` outside strings and brackets, or -1 (a key may be quoted: `'og:image': …`). */
    private fun topLevelColon(entry: String): Int {
        var depth = 0
        var i = 0
        while (i < entry.length) {
            when (entry[i]) {
                '\'', '"', '`' -> {
                    i = stringEnd(entry, i)
                    continue
                }

                '{', '[', '(' -> depth++
                '}', ']', ')' -> depth--
                ':' -> if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** The index just past the string literal starting with the quote at [start]. */
    private fun stringEnd(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            // an unterminated '…' or "…" ends at the line break
            if (c == '\n' && quote != '`') return i
            i++
        }
        return text.length
    }
}
