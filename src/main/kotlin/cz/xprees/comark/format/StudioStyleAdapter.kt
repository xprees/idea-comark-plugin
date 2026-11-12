package cz.xprees.comark.format

import cz.xprees.comark.format.studio.StudioBlock
import cz.xprees.comark.format.studio.StudioBlockKind
import cz.xprees.comark.format.studio.StudioEmojiData
import cz.xprees.comark.format.studio.StudioEntities
import cz.xprees.comark.format.studio.StudioFormatter
import cz.xprees.comark.format.studio.StudioJs
import cz.xprees.comark.format.studio.StudioYaml
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place where the IDE talks to the Nuxt Studio engine (`format/studio/`): everything else (Reformat Code, the settings
 * preview, the inspections) calls [format] and reads a [StudioStyleResult], and never imports the engine.
 *
 * Today this is the engine's *text path* with kept blocks ([StudioFormatter.formatPreserving]: what Studio's code editor writes,
 * except for blocks Studio would damage, which stay as written), followed by the custom options ([StudioStyleRewriter]).
 * [StudioStyleContext.collection] and [StudioStyleContext.fileName] are not used yet: they are for the front matter data rules
 * of Studio's database path (title / description injection, key order by the collection schema), which come with the engine's
 * dual fixed point API. Switching to that API means replacing the [StudioFormatter.formatPreserving] call and the mapping of its
 * blocks in [format]; the result type stays.
 */
object StudioStyleAdapter {
    /**
     * [text] in the style [context] describes, or `null` when the engine cannot read the document at all (the caller leaves it
     * unchanged). Line numbers in the result are 0-based lines of [text] (separated by `\n`, `\r\n` or `\r`).
     */
    fun format(text: String, context: StudioStyleContext): StudioStyleResult? {
        val preserved = StudioFormatter.formatPreserving(text) ?: return null
        val blocks = preserved.blocks
        val kept = blocks.filter { it.kept }.map { block ->
            KeptBlock(
                lines = block.startLine until maxOf(block.endLine, block.startLine + 1),
                reasons = block.reasons.map { it.name }.sorted(),
                message = block.reasons.joinToString("; ") { it.description },
            )
        }
        val options = context.options
        val output = if (options == StudioStyleOptions.STUDIO) {
            preserved.text
        } else {
            val frontMatter = blocks.firstOrNull { it.kind == StudioBlockKind.FRONTMATTER }
            val sourceLines = text.lines()
            StudioStyleRewriter(options, yaml).rewrite(
                text = preserved.text,
                keptSources = blocks.filter { it.kept }.map { it.source },
                frontMatter = frontMatter?.let { StudioStyleRewriter.FrontMatter(it.source, if (it.kept) it.source else it.studioOutput.orEmpty()) },
                bodyBlocks = blocks.filter { it.kind == StudioBlockKind.BLOCK }.mapNotNull { block -> bodyBlock(sourceLines, block) },
            )
        }
        return StudioStyleResult(
            text = output,
            blocks = blocks.map { it.startLine until maxOf(it.endLine, it.startLine + 1) },
            keptBlocks = kept,
        )
    }

    // ---- pinned versions --------------------------------------------------------------------------------------------------

    /** The nuxt-studio release the engine reproduces. */
    const val STUDIO_VERSION = "1.8.0"

    /** The comark release that nuxt-studio [STUDIO_VERSION] renders with. */
    const val COMARK_VERSION = "0.7.0"

    // ---- conversions ------------------------------------------------------------------------------------------------------

    /**
     * Emoji shortcodes (`:smile:`) and character references (`&copy;`, `&#169;`) in [text] between [start] and [end] that Studio
     * turns into characters when it saves, with what it writes instead. The caller passes inline text only (paragraphs, headings,
     * table cells) and drops matches inside code, links' destinations and the like; escaped markers (`\:smile:`) are skipped here.
     * Detection uses the engine's tables (comark 0.7.0's emoji list, markdown-it's entity rule); the replacement is the engine's
     * own output for the token, so escapes come out as Studio writes them (`&lt;` becomes `\<`).
     */
    fun conversions(text: CharSequence, start: Int, end: Int): List<StudioConversion> {
        val result = ArrayList<StudioConversion>()
        var i = start
        while (i < end) {
            val c = text[i]
            val match = when {
                c != ':' && c != '&' -> null
                isEscaped(text, i) -> null
                c == ':' -> emojiAt(text, i, end)
                else -> entityAt(text, i, end)
            }
            if (match == null) {
                i++
                continue
            }
            val (tokenEnd, kind) = match
            val token = text.substring(i, tokenEnd)
            var replacement = replacementFor(token, atLineStart(text, i), followingWord(text, tokenEnd, end))
            if (replacement == "&" && ENTITY_LIKE.matchesAt(text, tokenEnd)) replacement = "\\&"
            if (replacement != null) result += StudioConversion(i, tokenEnd, kind, token, replacement)
            // markdown-it's emoji rule fails at a `:` without consuming it, so the next `:` may start a shortcode
            i = if (replacement != null) tokenEnd else i + 1
        }
        return result
    }

    private val emojiNames: Set<String> by lazy {
        StudioEmojiData.ENTRIES.split(',').map { it.substringBefore('=') }.toHashSet()
    }

    /** comark's emoji rule: `:` name `:` where the name is in its table. */
    private fun emojiAt(text: CharSequence, at: Int, end: Int): Pair<Int, StudioConversion.Kind>? {
        var pos = at + 1
        while (pos < end) {
            val ch = text[pos]
            if (ch == ':') {
                val name = text.substring(at + 1, pos)
                return if (name in emojiNames) (pos + 1) to StudioConversion.Kind.EMOJI else null
            }
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-' || ch == '+') pos++ else return null
        }
        return null
    }

    /** markdown-it's entity rule: numeric references, and named ones (with `;`) that decode. */
    private fun entityAt(text: CharSequence, at: Int, end: Int): Pair<Int, StudioConversion.Kind>? {
        val match = ENTITY.matchAt(text.subSequence(0, end), at) ?: return null
        if (match.value[1] != '#' && StudioEntities.decodeHtmlStrict(match.value) == match.value) return null
        return (at + match.value.length) to StudioConversion.Kind.ENTITY
    }

    private val replacements = ConcurrentHashMap<String, Optional<String>>()
    private const val MAX_PROBES = 10_000

    /**
     * What Studio writes for [token] in a paragraph, followed by [following] (the rest of the word, which decides some escapes:
     * `&lt; x` stays `<`, `&lt;tag>` becomes `\<tag>`), at the start of a line when [lineStart]; null when it keeps the token.
     */
    private fun replacementFor(token: String, lineStart: Boolean, following: String): String? {
        if (following.isNotEmpty()) probe(token, lineStart, following)?.let { return it.orElse(null) }
        return probe(token, lineStart, "")?.orElse(null)
    }

    /** The probe's answer, or null when Studio's output doesn't keep [following] as it is (then the answer is unknown). */
    private fun probe(token: String, lineStart: Boolean, following: String): Optional<String>? {
        val key = "$lineStart:$token\u0000$following"
        replacements[key]?.let { return it }
        val prefix = if (lineStart) "" else "x "
        val suffix = "$following x\n"
        val out = StudioFormatter.format("$prefix$token$suffix") ?: return null
        if (!out.startsWith(prefix) || !out.endsWith(suffix) || out.length < prefix.length + suffix.length) return null
        val written = out.substring(prefix.length, out.length - suffix.length)
        val result = Optional.ofNullable(written.takeIf { it != token && it.isNotEmpty() && '\n' !in it && '\r' !in it })
        if (replacements.size > MAX_PROBES) replacements.clear()
        replacements[key] = result
        return result
    }

    /** The text after a token up to the next whitespace, `:` or `&` (at most 32 characters). */
    private fun followingWord(text: CharSequence, from: Int, end: Int): String {
        var i = from
        while (i < end && i - from < 32 && !text[i].isWhitespace() && text[i] != ':' && text[i] != '&') i++
        return text.substring(from, i)
    }

    private fun isEscaped(text: CharSequence, at: Int): Boolean {
        var backslashes = 0
        var i = at - 1
        while (i >= 0 && text[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    private fun atLineStart(text: CharSequence, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return i < 0 || text[i] == '\n'
    }

    private val ENTITY = Regex("&(?:#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]{1,31});")
    private val ENTITY_LIKE = Regex("#?[A-Za-z0-9]+;")

    /** A body block for the blank line option: the first line it writes, and the blank lines before it in the source. */
    private fun bodyBlock(lines: List<String>, block: StudioBlock): StudioStyleRewriter.BodyBlock? {
        val written = if (block.kept) block.source else block.studioOutput ?: return null
        val firstLine = written.substringBefore('\n').takeIf { it.isNotBlank() } ?: return null
        var blank = 0
        var line = block.startLine - 1
        while (line >= 0 && line < lines.size && lines[line].all { it == ' ' || it == '\t' }) {
            blank++
            line--
        }
        return StudioStyleRewriter.BodyBlock(firstLine, blank)
    }

    /** YAML for the props options, written as Studio writes component props (comark's `comarkYamlAttributes`). */
    private val yaml = object : StudioStyleRewriter.Yaml {
        override fun propsYaml(props: List<Pair<String, String>>): String? {
            val normalized = LinkedHashMap<String, Any?>()
            for ((key, value) in props) {
                if (key.startsWith(":")) {
                    try {
                        normalized[key.substring(1)] = StudioJs.jsonParse(value)
                    } catch (_: StudioJs.JsonSyntaxError) {
                        normalized[key] = value
                    }
                } else {
                    normalized[key] = when (value) {
                        "true" -> true
                        "false" -> false
                        else -> value
                    }
                }
            }
            return try {
                StudioJs.trim(StudioYaml.stringify(normalized))
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}

/**
 * What [StudioStyleAdapter.format] needs to know about a document besides its text.
 *
 * @property fileName the file's name, for the front matter title Studio derives from it when there is no `# heading`
 * @property collection the Nuxt Content collection the file belongs to, or `null` for Studio's defaults
 * @property options the custom style's options ([StudioStyleOptions.STUDIO] for exactly what Studio writes)
 */
data class StudioStyleContext(
    val fileName: String,
    val collection: StudioCollection? = null,
    val options: StudioStyleOptions = StudioStyleOptions.STUDIO,
)

/**
 * A collection of `content.config.ts` as Studio's front matter rules see it: its [type] and the field names of its schema in
 * declaration order (the order Studio writes front matter keys in).
 */
data class StudioCollection(val name: String, val type: Type, val schemaFields: List<String>) {
    enum class Type { PAGE, DATA }
}

/**
 * The options of the custom style. [STUDIO] holds Nuxt Studio's own choices; every other value is applied on top of Studio's
 * output (see [StudioStyleRewriter]).
 *
 * @property indentSize spaces per level of component nesting (Studio: 2)
 * @property maxBlankLines blank lines kept between top-level blocks, from the source, at most this many (Studio: always 1)
 * @property propsInlineMax a block component with more props writes them as a YAML block (Studio: 3; only 0 to 3 are
 *   supported, larger values act as 3)
 * @property keepFrontMatter leave the front matter as written instead of Studio's re-dump
 */
data class StudioStyleOptions(
    val indentSize: Int = 2,
    val bulletMarker: Char = '-',
    val orderedDelimiter: Char = '.',
    val emphasisMarker: Char = '*',
    val strongMarker: Char = '*',
    val fenceMarker: Char = '`',
    val maxBlankLines: Int = 1,
    val propsInlineMax: Int = 3,
    val sortProps: Boolean = false,
    val keepFrontMatter: Boolean = false,
) {
    companion object {
        val STUDIO = StudioStyleOptions()
    }
}

/**
 * The formatted text and how it relates to the source.
 *
 * @property text the whole formatted document (ends with a line break)
 * @property blocks source lines of the front matter and of every top-level block, in order (blank lines between blocks belong
 *   to none); range formatting rewrites whole blocks
 * @property keptBlocks the blocks left exactly as written, because Studio would damage them
 */
data class StudioStyleResult(val text: String, val blocks: List<IntRange>, val keptBlocks: List<KeptBlock>)

/**
 * A block [StudioStyleAdapter] left as written: its source [lines], the [reasons] (stable identifiers, e.g. `RAW_HTML`) and a
 * human readable [message] saying what Studio would do to it.
 */
data class KeptBlock(val lines: IntRange, val reasons: List<String>, val message: String)

/**
 * An emoji shortcode or character reference that Studio replaces when it saves ([StudioStyleAdapter.conversions]): [token] at
 * `[start, end)` of the text becomes [replacement].
 */
data class StudioConversion(val start: Int, val end: Int, val kind: Kind, val token: String, val replacement: String) {
    enum class Kind { EMOJI, ENTITY }
}
