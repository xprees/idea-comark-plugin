package cz.xprees.comark.format

import cz.xprees.comark.format.studio.StudioBlock
import cz.xprees.comark.format.studio.StudioBlockKind
import cz.xprees.comark.format.studio.StudioFormatter
import cz.xprees.comark.format.studio.StudioJs
import cz.xprees.comark.format.studio.StudioYaml

/**
 * The one place where the IDE talks to the Nuxt Studio engine (`format/studio/`): everything else (Reformat Code, the settings
 * preview, later the inspections) calls [format] and reads a [StudioStyleResult], and never imports the engine.
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
