package cz.xprees.comark.format.studio

import java.util.IdentityHashMap

/**
 * Nuxt Studio 1.8.0's **database path** D (`src/test/node/studio-oracle/DB-PATH.md`): what Studio writes when it saves a
 * document it opened from Nuxt Content's database, for documents that are already text-path output (y = T(y), where T is
 * [StudioFormatter.format]).
 *
 * D(y) is computed from the text path's tree of y ([StudioTreeBuilder]): the body by [StudioDbBody] (the systematic
 * differences between Nuxt Content's parse and comark's), the front matter by [StudioDbFrontMatter] (Nuxt Content's row and
 * Studio's `cleanDataKeys`), then the same renderer as T ([StudioPipeline]).
 *
 * The emulation is exact on its domain and declines everything else: [emulateOutcome] says why. Outside the domain are
 * documents that are not T-normal, and constructs that Nuxt Content's parser (remark-mdc, micromark, remark-gfm, rehype-raw)
 * reads differently from comark in ways this port does not follow, such as raw HTML, comments or bare URLs.
 */
object StudioDbPath {
    /** Why [emulateOutcome] declines a document. */
    enum class Reason {
        /** The text path throws on the document, or this port of it cannot follow it. */
        TEXT_PATH_FAILS,

        /** T(y) != y: only text-path output is supported. */
        NOT_T_NORMAL,

        /** The front matter is not a mapping (Studio spreads a string or list into the document). */
        NON_MAPPING_FRONT_MATTER,

        /** A front matter scalar the `yaml` package (YAML 1.2 core) reads differently from js-yaml. */
        FRONT_MATTER_YAML,

        /** A front matter value whose column conversion depends on V8 (its date parser) or on JavaScript object quirks. */
        FRONT_MATTER_VALUE,

        /** Raw HTML: Nuxt Content parses it with rehype-raw. */
        RAW_HTML,

        /** An HTML comment: dropped by minimark, and `<!--more-->` adds an excerpt. */
        HTML_COMMENT,

        /** A bare `[text]` span: text for remark-mdc. */
        BRACKET_SPAN,

        /** Attributes on paragraphs, headings and other Markdown blocks, inline code or emphasis. */
        BLOCK_ATTRIBUTES,

        /** An attribute name hast-util-raw renames or whose value it parses (HTML boolean, number and list attributes). */
        ATTRIBUTE_NAME,

        /** An attribute value remark-mdc's attribute syntax reads differently (escapes, references, quotes). */
        ATTRIBUTE_VALUE,

        /** A component name remark-mdc rejects or rewrites, or one hast-util-raw's HTML tree construction treats specially. */
        COMPONENT_NAME,

        /** A URL attribute the two parsers encode or validate differently. */
        URL,

        /** A component whose content starts with `---`: remark-mdc reads YAML props there. */
        COMPONENT_HR,

        /** Component props that remark-mdc's `flat.unflatten` changes (dotted or numeric keys). */
        COMPONENT_PROPS,

        /** Slot attributes other than the name. */
        SLOT,

        /** List structure this port does not follow (components in list items). */
        LIST,

        /** Code blocks this port does not follow (empty blocks, attributes). */
        CODE_BLOCK,

        /** Inline syntax remark-mdc, micromark or remark-gfm read differently from comark. */
        INLINE_SYNTAX,

        /** Block structure micromark reads differently from markdown-exit. */
        BLOCK_SYNTAX,
    }

    /** Thrown inside the emulation for a construct outside the supported domain. */
    class UnsupportedException(val reason: Reason, detail: String) : RuntimeException(detail)

    /** The result of [emulateOutcome]. */
    sealed interface Outcome {
        /** What Studio writes. */
        data class Written(val text: String) : Outcome

        /** Studio has no document to write: Nuxt Content cannot parse or store this file (an invalid date or number). */
        data class NoDocument(val message: String) : Outcome

        /** Outside the emulation's domain; [detail] names the construct. */
        data class Unsupported(val reason: Reason, val detail: String) : Outcome
    }

    /** D([text]) for a T-normal document in [collection], or `null` where Studio has no document or [text] is outside the domain. */
    fun emulate(text: String, collection: StudioCollection = StudioCollection.DEFAULTS): String? =
        (emulateOutcome(text, collection) as? Outcome.Written)?.text

    fun emulateOutcome(text: String, collection: StudioCollection = StudioCollection.DEFAULTS): Outcome = try {
        Outcome.Written(emulateChecked(text, collection))
    } catch (e: UnsupportedException) {
        Outcome.Unsupported(e.reason, e.message ?: "")
    } catch (e: StudioDbFrontMatter.NoDocument) {
        Outcome.NoDocument(e.message ?: "")
    } catch (_: StackOverflowError) {
        Outcome.Unsupported(Reason.TEXT_PATH_FAILS, "nesting too deep")
    }

    private fun emulateChecked(text: String, collection: StudioCollection): String {
        val doc = try {
            StudioTreeBuilder.build(text)
        } catch (e: StudioTextPathException) {
            throw UnsupportedException(Reason.TEXT_PATH_FAILS, e.message ?: "text path fails")
        } catch (e: StudioUnsupportedException) {
            throw UnsupportedException(Reason.TEXT_PATH_FAILS, e.message ?: "text path not followed")
        }
        val styles = IdentityHashMap<StudioElement, StudioRenderer.ComponentStyle>()
        if (StudioPipeline.render(doc) { node, style -> styles[node] = style } != text) throw UnsupportedException(Reason.NOT_T_NORMAL, "T(y) != y")
        val yaml = doc.frontmatterValue
        if (yaml !is Map<*, *>) throw UnsupportedException(Reason.NON_MAPPING_FRONT_MATTER, "front matter is not a mapping")
        checkYaml12(yaml)
        checkDelimiters(text)
        if (CONTROL.containsMatchIn(text)) throw UnsupportedException(Reason.INLINE_SYNTAX, "control characters or Unicode line separators")
        if (Regex("\\*{4,}|_{4,}").containsMatchIn(text)) throw UnsupportedException(Reason.INLINE_SYNTAX, "a long run of emphasis delimiters")
        // a `***` run between two non-alphanumeric characters: the delimiter algorithms of the two parsers can pair it differently
        if (Regex("(?<![\\p{L}\\p{N}])(\\*{3}|_{3})(?![\\p{L}\\p{N}])").containsMatchIn(text)) {
            throw UnsupportedException(Reason.INLINE_SYNTAX, "a run of three emphasis delimiters between punctuation")
        }
        if ("[^" in text) throw UnsupportedException(Reason.INLINE_SYNTAX, "footnote syntax (remark-gfm)")
        val body = StudioDbBody.transform(doc.nodes, styles, StudioTreeBuilder.listTightness(text))
        val nodes = body.nodes
        // contentHeading reads the compiled body, before Studio's mdcToComark wraps inline runs at the root in paragraphs
        val heading = if (collection.type == StudioCollection.Type.PAGE) contentHeading(body.compiledRoot) else null
        @Suppress("UNCHECKED_CAST")
        val frontmatter = StudioDbFrontMatter.frontMatter(yaml as Map<String, Any?>, collection, heading)
        return StudioPipeline.render(StudioDocument(frontmatter = frontmatter, nodes = nodes))
    }

    /** `contentHeading(body)` (`@nuxtjs/mdc` `parser/index.js`): the first h1 and the paragraph after it, `hr`s skipped. */
    private fun contentHeading(nodes: List<StudioNode>): StudioDbFrontMatter.Heading {
        val children = nodes.filter { it is StudioElement && it.tag != "hr" }.toMutableList()
        var title = ""
        var description = ""
        if (children.isNotEmpty() && (children[0] as StudioElement).tag == "h1") title = textContent(children.removeAt(0))
        if (children.isNotEmpty() && (children[0] as StudioElement).tag == "p") description = textContent(children.removeAt(0))
        return StudioDbFrontMatter.Heading(title, description)
    }

    private fun textContent(node: StudioNode): String = when (node) {
        is StudioText -> node.text
        // remark-rehype writes a line ending after every hard break; compileHast drops it unless text follows
        is StudioElement -> node.children.withIndex().joinToString("") { (i, c) ->
            if (c is StudioElement && c.tag == "br") (if (node.children.getOrNull(i + 1) is StudioText) "\n" else "") else textContent(c)
        }
        is StudioComment -> ""
    }

    /**
     * Emphasis and strikethrough delimiters next to non-ASCII punctuation, symbols or spaces: micromark classifies those
     * characters by a newer Unicode punctuation definition than markdown-exit, so the delimiter runs can flank differently.
     */
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\u2028\\u2029]")

    private fun checkDelimiters(text: String) {
        for (i in text.indices) {
            val c = text[i]
            if (c != '*' && c != '_' && c != '~') continue
            val before = if (i > 0) text.codePointBefore(i) else 'a'.code
            val after = if (i + 1 < text.length) text.codePointAt(i + 1) else 'a'.code
            if (before > 0x7F && !Character.isLetterOrDigit(before) || after > 0x7F && !Character.isLetterOrDigit(after)) {
                throw UnsupportedException(Reason.INLINE_SYNTAX, "an emphasis delimiter next to non-ASCII punctuation or a symbol")
            }
        }
    }

    // ---- YAML 1.2 core vs js-yaml ----------------------------------------------------------------------------------------

    /** Plain scalars YAML 1.2 core resolves to numbers that js-yaml's dump leaves unquoted (js-yaml reads them as strings). */
    private val YAML12_ONLY = Regex("^[-+]\\.[0-9]+(?:[eE][-+]?[0-9]+)?\\z")

    internal fun checkYaml12(v: Any?) {
        when (v) {
            is String -> if (YAML12_ONLY.matches(v)) throw UnsupportedException(Reason.FRONT_MATTER_YAML, "YAML 1.2 number $v")
            is Map<*, *> -> for ((k, x) in v) {
                checkYaml12(k)
                checkYaml12(x)
            }
            is List<*> -> v.forEach { checkYaml12(it) }
        }
    }
}
