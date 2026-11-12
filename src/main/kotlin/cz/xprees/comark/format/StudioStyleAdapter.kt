package cz.xprees.comark.format

import cz.xprees.comark.format.studio.StudioEmojiData
import cz.xprees.comark.format.studio.StudioEntities
import cz.xprees.comark.format.studio.StudioFormatter
import cz.xprees.comark.format.studio.StudioFrontMatterMode
import cz.xprees.comark.format.studio.StudioStyle
import cz.xprees.comark.format.studio.StudioTextPathException
import cz.xprees.comark.format.studio.StudioTreeBuilder
import cz.xprees.comark.format.studio.StudioUnsupportedException
import java.util.Optional
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import cz.xprees.comark.format.studio.StudioCollection as EngineCollection
import cz.xprees.comark.format.studio.StudioStyleOptions as EngineOptions

/**
 * The one place where the IDE talks to the Nuxt Studio engine (`format/studio/`): everything else (Reformat Code, the settings
 * preview, the inspections) calls [format] and reads a [StudioStyleResult], and never imports the engine.
 *
 * [format] is the engine's Nuxt Studio code style ([StudioStyle.format]): per block the form that both of Studio's save paths
 * (its code editor, and a document opened from Nuxt Content's database) leave unchanged, with the blocks Studio would damage
 * kept as written, and the front matter completed by Studio's data rules for the file's collection. The Custom options go to
 * the engine as well; only [StudioStyleOptions.maxBlankLines], which the engine does not have, is applied here afterwards.
 */
object StudioStyleAdapter {
    /**
     * [text] in the style [context] describes, or `null` when the engine cannot read the document at all (the caller leaves it
     * unchanged). Line numbers in the result are 0-based lines of [text] (separated by `\n`, `\r\n` or `\r`).
     */
    fun format(text: String, context: StudioStyleContext): StudioStyleResult? {
        formatCalls.incrementAndGet()
        val styled = StudioStyle.format(text, engineOptions(context)) ?: return null
        val kept = styled.keptBlocks.map { block ->
            val lines = block.inputLines
            KeptBlock(
                lines = if (lines.isEmpty()) lines.first until lines.first + 1 else lines,
                reasons = block.reasons.map { it.name }.sorted(),
                message = block.messages.joinToString("; ") { it.removePrefix(MESSAGE_PREFIX) },
            )
        }
        val blocks = sourceBlocks(text)
        var output = styled.text
        if (context.options.maxBlankLines > 1 && blocks != null) output = withSourceBlankLines(text, output, context.options.maxBlankLines)
        return StudioStyleResult(text = output, blocks = blocks.orEmpty().map { it.lines }, keptBlocks = kept)
    }

    /** How many times [format] ran, for tests that check a style never reaches the engine. */
    @TestOnly
    internal val formatCalls = AtomicInteger()

    private const val MESSAGE_PREFIX = "Nuxt Studio will rewrite this: "

    /** The engine's options for [context]: Nuxt Studio's own values unless the Custom style changes them. */
    internal fun engineOptions(context: StudioStyleContext): EngineOptions {
        val o = context.options
        return EngineOptions(
            collection = engineCollection(context),
            indentSize = o.indentSize.coerceIn(0, 8),
            bulletMarker = o.bulletMarker,
            orderedDelimiter = o.orderedDelimiter,
            emphasisChar = o.emphasisMarker,
            strongChar = o.strongMarker,
            fenceChar = o.fenceMarker,
            propsBlockThreshold = o.propsInlineMax.coerceAtLeast(0),
            sortProps = o.sortProps,
            frontMatter = when (o.frontMatter) {
                FrontMatterMode.STUDIO -> StudioFrontMatterMode.STUDIO_RULES
                FrontMatterMode.REDUMP -> StudioFrontMatterMode.REDUMP_ONLY
                FrontMatterMode.KEEP -> StudioFrontMatterMode.KEEP
            },
        )
    }

    private fun engineCollection(context: StudioStyleContext): EngineCollection {
        val collection = context.collection ?: return EngineCollection.defaults(context.fileName)
        val fields = collection.fields.map { field ->
            EngineCollection.Field(
                field.name,
                when (field.type) {
                    StudioFieldType.STRING -> EngineCollection.ColumnType.STRING
                    StudioFieldType.NUMBER -> EngineCollection.ColumnType.NUMBER
                    StudioFieldType.BOOLEAN -> EngineCollection.ColumnType.BOOLEAN
                    StudioFieldType.DATE -> EngineCollection.ColumnType.DATE
                    StudioFieldType.DATETIME -> EngineCollection.ColumnType.DATETIME
                    StudioFieldType.JSON -> EngineCollection.ColumnType.JSON
                },
                field.hasDefault,
                field.default,
            )
        }
        val type = if (collection.type == StudioCollection.Type.DATA) EngineCollection.Type.DATA else EngineCollection.Type.PAGE
        return EngineCollection(type, fields, context.fileName, collection.schemaKnown)
    }

    // ---- blocks and blank lines -------------------------------------------------------------------------------------------

    /** A top-level block of a document: its [lines] and the blank lines right above it. */
    private class SourceBlock(val lines: IntRange, val blankLinesBefore: Int, val body: Boolean)

    /** The front matter and the top-level blocks of [text] (0-based line ranges), or null when it does not parse. */
    private fun sourceBlocks(text: String): List<SourceBlock>? {
        val doc = try {
            StudioTreeBuilder.buildSegmented(text)
        } catch (_: StudioTextPathException) {
            return null
        } catch (_: StudioUnsupportedException) {
            return null
        } catch (_: StackOverflowError) {
            return null
        }
        val lines = text.split(LINE_BREAK)
        val result = ArrayList<SourceBlock>()
        doc.frontmatter?.let { result += SourceBlock(0 until it.contentLine + 1, 0, false) }
        for (segment in doc.segments) {
            val start = doc.sourceLine(segment.startLine)
            val end = maxOf(doc.sourceLine(segment.endLine), start + 1)
            var blank = 0
            while (start - blank - 1 >= 0 && lines.getOrNull(start - blank - 1)?.isBlank() == true) blank++
            result += SourceBlock(start until end, if (start - blank == 0) 0 else blank, true)
        }
        return result
    }

    /**
     * [output] with the blank lines between its top-level blocks taken from [source] (at least one, at most [max]), block by
     * block. Extra blank lines between top-level blocks do not change the document; when the two texts do not have the same
     * blocks (the engine dropped or merged one), [output] is returned as it is.
     */
    private fun withSourceBlankLines(source: String, output: String, max: Int): String {
        val sourceBlocks = sourceBlocks(source)?.filter { it.body } ?: return output
        val outputBlocks = sourceBlocks(output)?.filter { it.body } ?: return output
        if (sourceBlocks.size != outputBlocks.size) return output
        val lines = output.split('\n').toMutableList()
        for (i in outputBlocks.indices.reversed()) {
            val block = outputBlocks[i]
            val start = block.lines.first
            if (start == 0 || block.blankLinesBefore == 0) continue
            val wanted = sourceBlocks[i].blankLinesBefore.coerceIn(1, max)
            repeat(wanted - block.blankLinesBefore) { lines.add(start, "") }
        }
        return lines.joinToString("\n")
    }

    private val LINE_BREAK = Regex("\r\n?|\n")

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
 * A collection of `content.config.ts` as Studio's front matter rules see it: its [type] and the top-level [fields] of its
 * schema in declaration order. Nuxt Content orders the front matter keys itself (`id`, `title`, then the schema columns sorted
 * by name), so the order of [fields] does not decide the output. [schemaKnown] is false when the schema could not be read
 * (for example a variable instead of an inline `z.object({...})`).
 */
data class StudioCollection(
    val name: String,
    val type: Type,
    val fields: List<StudioSchemaField> = emptyList(),
    val schemaKnown: Boolean = true,
) {
    enum class Type { PAGE, DATA }

    /** The names of [fields], in declaration order. */
    val schemaFields: List<String> get() = fields.map { it.name }
}

/** The SQL column kind Nuxt Content gives a schema field, which decides how its front matter value is stored and written back. */
enum class StudioFieldType { STRING, NUMBER, BOOLEAN, DATE, DATETIME, JSON }

/**
 * A field of a collection schema: its [name], column [type] and, when [hasDefault], the zod `.default(...)` value as a plain
 * value ([String], [Double], [Boolean], `null`, [List], [Map]), which Nuxt Content writes into documents that lack the key.
 */
data class StudioSchemaField(val name: String, val type: StudioFieldType = StudioFieldType.STRING, val hasDefault: Boolean = false, val default: Any? = null)

/**
 * The options of the style. [STUDIO] holds Nuxt Studio's own choices (the Nuxt Studio preset); the Custom preset changes them.
 * All of them except [maxBlankLines] are the engine's options ([StudioStyle]): blocks are written again with them where the
 * result reads the same. With any value other than Studio's, Studio rewrites the document to its own style when it saves it.
 *
 * @property indentSize spaces per level of component nesting (Studio: 2)
 * @property maxBlankLines blank lines kept between top-level blocks, from the source, at most this many (Studio: always 1)
 * @property propsInlineMax a block component with more props writes them as a YAML block (Studio: 3)
 * @property sortProps props in the order Studio's database path gives them (by how often each name occurs on the same tag in
 *   the document; Studio: true); false keeps the source's order
 * @property frontMatter Studio's data rules, a plain js-yaml re-dump, or the front matter as written
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
    val sortProps: Boolean = true,
    val frontMatter: FrontMatterMode = FrontMatterMode.STUDIO,
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
