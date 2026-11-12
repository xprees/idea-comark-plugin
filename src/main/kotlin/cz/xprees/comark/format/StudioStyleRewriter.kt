package cz.xprees.comark.format

/**
 * Applies the options of the custom style ([StudioStyleOptions]) to what Nuxt Studio writes. It works on Studio's output, which
 * is regular (one form per construct: `-` bullets, `1.` items, `*em*`, `**strong**`, backtick fences, literal `*` always
 * escaped, ...), so every rewrite is a small text transformation of a construct whose shape is known. Blocks the engine kept as
 * written are never touched. Each rewrite produces a document that reads the same, and running Reformat Code again gives the
 * same text (Studio turns the custom form back into its own, and this turns it into the custom form again).
 *
 * What each option does:
 * - [StudioStyleOptions.indentSize]: lines inside nested components get [StudioStyleOptions.indentSize] spaces per component
 *   level instead of Studio's two;
 * - bullet marker and ordered delimiter: list item markers;
 * - emphasis and strong markers: `_` and `__` where CommonMark allows them (not inside a word: `snake*case*` keeps `*`);
 *   a paragraph with `***` runs and different markers is left as Studio writes it;
 * - fence marker: `~~~` fences, as long as needed for the content;
 * - [StudioStyleOptions.maxBlankLines]: the source's blank lines between top-level blocks, up to this many;
 * - [StudioStyleOptions.propsInlineMax] (0 to 3): block components with more props get a YAML props block;
 * - [StudioStyleOptions.sortProps]: props in `{...}` and top-level keys of YAML props blocks sorted by name;
 * - [StudioStyleOptions.keepFrontMatter]: the source's front matter instead of Studio's.
 */
internal class StudioStyleRewriter(private val options: StudioStyleOptions, private val yaml: Yaml) {
    /** YAML support from the engine. */
    interface Yaml {
        /** The YAML (without `---` lines) Studio writes for component props given as comark attributes, or null. */
        fun propsYaml(props: List<Pair<String, String>>): String?
    }

    /** The front matter as written in the source, and as Studio wrote it ([written] empty when Studio dropped it). */
    class FrontMatter(val source: String, val written: String)

    /** A top-level body block: the first line Studio wrote for it, and the blank lines before it in the source. */
    class BodyBlock(val firstLine: String, val blankLinesBefore: Int)

    private class Line(var text: String, val kept: Boolean)

    private enum class Kind { BLANK, TEXT, FRONT_MATTER, FENCE_OPEN, FENCE_CONTENT, FENCE_CLOSE, YAML, COMPONENT_OPEN, COMPONENT_CLOSE, KEPT }

    /**
     * [nesting]: Studio's indentation level of the line (two spaces each) from enclosing components; [depth]: the number of
     * components the line is in (for an opener or closer, not counting its own).
     */
    private class Info(val kind: Kind, val nesting: Int, val depth: Int = 0)

    fun rewrite(text: String, keptSources: List<String>, frontMatter: FrontMatter?, bodyBlocks: List<BodyBlock>): String {
        val raw = text.removeSuffix("\n").split('\n')
        val keptFlags = keptLines(raw, keptSources)
        var lines = raw.mapIndexed { i, s -> Line(s, keptFlags[i]) }.toMutableList()
        if (options.keepFrontMatter && frontMatter != null) lines = keepFrontMatter(lines, frontMatter)
        if (options.maxBlankLines > 1) blankLines(lines, bodyBlocks)

        val infos = analyze(lines)
        val out = ArrayList<Line>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val info = infos[i]
            when (info.kind) {
                Kind.FENCE_OPEN -> {
                    val close = (i + 1 until lines.size).firstOrNull { infos[it].kind == Kind.FENCE_CLOSE && !lines[it].kept }
                    if (close != null && options.fenceMarker == '~') rewriteFence(lines, i, close)
                }
                Kind.COMPONENT_OPEN -> {
                    val expanded = rewriteProps(lines, infos, i)
                    if (expanded != null) {
                        out += expanded
                        i++
                        continue
                    }
                }
                else -> {}
            }
            out += line
            i++
        }
        // markers, emphasis and indentation work line by line on the final lines
        val finalInfos = analyze(out)
        rewriteParagraphs(out, finalInfos)
        if (options.indentSize != ComarkStyle.STUDIO_INDENT) {
            for ((index, line) in out.withIndex()) {
                val nesting = finalInfos[index].nesting
                if (line.kept || nesting == 0 || line.text.isEmpty()) continue
                val studio = " ".repeat(ComarkStyle.STUDIO_INDENT * nesting)
                if (line.text.startsWith(studio)) line.text = " ".repeat(options.indentSize * nesting) + line.text.substring(studio.length)
            }
        }
        return out.joinToString("\n") { it.text } + "\n"
    }

    // ---- kept blocks ------------------------------------------------------------------------------------------------------

    /** Lines of [lines] that belong to a kept block (found in order, as the engine copies them unchanged). */
    private fun keptLines(lines: List<String>, keptSources: List<String>): BooleanArray {
        val flags = BooleanArray(lines.size)
        var cursor = 0
        for (source in keptSources) {
            val block = source.split('\n')
            if (block.isEmpty()) continue
            var at = -1
            for (start in cursor..lines.size - block.size) {
                if ((block.indices).all { lines[start + it] == block[it] }) {
                    at = start
                    break
                }
            }
            if (at < 0) continue
            for (k in block.indices) flags[at + k] = true
            cursor = at + block.size
        }
        return flags
    }

    // ---- front matter and blank lines --------------------------------------------------------------------------------------

    private fun keepFrontMatter(lines: MutableList<Line>, frontMatter: FrontMatter): MutableList<Line> {
        val source = frontMatter.source.split('\n')
        val written = if (frontMatter.written.isEmpty()) emptyList() else frontMatter.written.split('\n')
        if (written.isNotEmpty()) {
            if (lines.size < written.size || written.indices.any { lines[it].text != written[it] }) return lines
            return (source.map { Line(it, true) } + lines.subList(written.size, lines.size)).toMutableList()
        }
        if (source.all { it.isBlank() }) return lines
        val body = if (lines.all { it.text.isEmpty() }) emptyList() else listOf(Line("", false)) + lines
        return (source.map { Line(it, true) } + body).toMutableList()
    }

    private fun blankLines(lines: MutableList<Line>, bodyBlocks: List<BodyBlock>) {
        val infos = analyze(lines)
        // candidates: lines starting a top-level block (outside components, fences and the front matter, after a blank line)
        var cursor = 0
        for (block in bodyBlocks) {
            var found = -1
            for (j in cursor until lines.size) {
                val info = infos[j]
                if (lines[j].text != block.firstLine) continue
                // inside a kept block (only its first line starts a block)
                if (lines[j].kept && j > 0 && lines[j - 1].kept) continue
                val topLevel = info.depth == 0 && info.kind != Kind.FENCE_CONTENT && info.kind != Kind.FENCE_CLOSE &&
                    info.kind != Kind.YAML && info.kind != Kind.FRONT_MATTER
                if (topLevel && (j == 0 || lines[j - 1].text.isEmpty())) {
                    found = j
                    break
                }
            }
            if (found < 0) continue
            var blank = 0
            while (found - blank - 1 >= 0 && lines[found - blank - 1].text.isEmpty()) blank++
            val wanted = if (found - blank == 0) blank else block.blankLinesBefore.coerceIn(1, options.maxBlankLines)
            if (wanted > blank) {
                repeat(wanted - blank) { lines.add(found, Line("", false)) }
                infos.addAll(found, List(wanted - blank) { Info(Kind.BLANK, 0) })
                found += wanted - blank
            }
            cursor = found + 1
        }
    }

    // ---- line analysis ------------------------------------------------------------------------------------------------------

    private fun analyze(lines: List<Line>): MutableList<Info> {
        val result = ArrayList<Info>(lines.size)
        val openStack = ArrayList<Int>() // colons of the open components
        var fence: String? = null // the fence run
        var fenceIndent = 0
        var inYaml = false
        var inFrontMatter = false
        for ((index, line) in lines.withIndex()) {
            val text = line.text
            val trimmed = text.trim(' ', '\t')
            val bodyNesting = maxOf(0, openStack.size - 1)
            if (line.kept) {
                result += Info(Kind.KEPT, bodyNesting, openStack.size)
                continue
            }
            if (index == 0 && trimmed == "---") {
                inFrontMatter = true
                result += Info(Kind.FRONT_MATTER, 0)
                continue
            }
            if (inFrontMatter) {
                if (trimmed == "---") inFrontMatter = false
                result += Info(Kind.FRONT_MATTER, 0)
                continue
            }
            if (fence != null) {
                val run = FENCE.find(trimmed)?.value
                // a closing fence is indented at most three columns past the opening one
                val indent = text.length - text.trimStart(' ', '\t').length
                if (run != null && run[0] == fence[0] && run.length >= fence.length && trimmed == run && indent - fenceIndent in -fenceIndent..3) {
                    fence = null
                    result += Info(Kind.FENCE_CLOSE, bodyNesting, openStack.size)
                } else {
                    result += Info(Kind.FENCE_CONTENT, bodyNesting, openStack.size)
                }
                continue
            }
            if (inYaml) {
                if (trimmed == "---") inYaml = false
                result += Info(Kind.YAML, bodyNesting, openStack.size)
                continue
            }
            if (trimmed.isEmpty()) {
                result += Info(Kind.BLANK, bodyNesting, openStack.size)
                continue
            }
            val previous = result.lastOrNull()
            // Studio's props block: `---` right below the opener, then the YAML (never a blank line first)
            if (trimmed == "---" && previous?.kind == Kind.COMPONENT_OPEN && lines.getOrNull(index + 1)?.text?.isNotBlank() == true) {
                inYaml = true
                result += Info(Kind.YAML, bodyNesting, openStack.size)
                continue
            }
            val run = FENCE.find(trimmed)?.value
            if (run != null && !(run[0] == '`' && trimmed.substring(run.length).contains('`'))) {
                fence = run
                fenceIndent = text.length - text.trimStart(' ', '\t').length
                result += Info(Kind.FENCE_OPEN, bodyNesting, openStack.size)
                continue
            }
            val opener = OPENER.matchEntire(trimmed)
            if (opener != null) {
                result += Info(Kind.COMPONENT_OPEN, openStack.size, openStack.size)
                openStack += opener.groupValues[1].length
                continue
            }
            val closer = CLOSER.matchEntire(trimmed)
            if (closer != null && openStack.isNotEmpty()) {
                val colons = closer.groupValues[1].length
                val match = openStack.indexOfLast { it == colons }
                if (match >= 0) {
                    while (openStack.size > match) openStack.removeAt(openStack.size - 1)
                    result += Info(Kind.COMPONENT_CLOSE, openStack.size, openStack.size)
                    continue
                }
            }
            result += Info(Kind.TEXT, bodyNesting, openStack.size)
        }
        return result
    }

    // ---- fences -------------------------------------------------------------------------------------------------------------

    private fun rewriteFence(lines: List<Line>, open: Int, close: Int) {
        val opener = lines[open].text
        val indent = opener.substring(0, opener.length - opener.trimStart(' ', '\t').length)
        val run = FENCE.find(opener.trimStart(' ', '\t'))?.value ?: return
        if (run[0] != '`') return
        var maxTildes = 0
        for (k in open + 1 until close) {
            val t = lines[k].text.trimStart(' ', '\t')
            if (t.startsWith("~")) maxTildes = maxOf(maxTildes, t.takeWhile { it == '~' }.length)
        }
        val tildes = "~".repeat(maxOf(3, maxTildes + 1))
        lines[open].text = indent + tildes + opener.trimStart(' ', '\t').substring(run.length)
        val closer = lines[close].text
        lines[close].text = closer.substring(0, closer.length - closer.trimStart(' ', '\t').length) + tildes
    }

    // ---- props --------------------------------------------------------------------------------------------------------------

    /**
     * The lines replacing the component opener at [index] when its props change shape, or null when it stays one line (sorted in
     * place if needed). A YAML props block below the opener is sorted in place.
     */
    private fun rewriteProps(lines: List<Line>, infos: List<Info>, index: Int): List<Line>? {
        val line = lines[index]
        val text = line.text
        val indent = text.substring(0, text.length - text.trimStart(' ', '\t').length)
        val match = OPENER.matchEntire(text.trim(' ', '\t')) ?: return null
        val head = match.groupValues[1] + match.groupValues[2]
        val attrs = match.groupValues[3]
        if (attrs.isEmpty()) {
            if (options.sortProps && index + 1 < lines.size && infos[index + 1].kind == Kind.YAML) sortYaml(lines, infos, index + 1, indent)
            return null
        }
        if (!attrs.startsWith("{") || !attrs.endsWith("}")) return null
        val tokens = tokenize(attrs.substring(1, attrs.length - 1)) ?: return null
        val ordered = if (options.sortProps) tokens.sortedBy { it.name } else tokens
        if (tokens.size > options.propsInlineMax && options.propsInlineMax < ComarkCodeStyleSettings.STUDIO_PROPS_INLINE_MAX) {
            val dumped = yaml.propsYaml(ordered.map { it.key to it.value }) ?: return null
            return listOf(Line(indent + head, false), Line("$indent---", false)) +
                dumped.split('\n').map { Line(if (it.isEmpty()) it else indent + it, false) } + Line("$indent---", false)
        }
        if (options.sortProps && ordered != tokens) line.text = indent + head + "{" + ordered.joinToString(" ") { it.source } + "}"
        return null
    }

    /** A prop as Studio writes it inline: [source] text, the attribute [key] and [value] it stands for, and its [name]. */
    private data class Token(val source: String, val key: String, val value: String) {
        val name: String get() = key.removePrefix(":")
    }

    /** The props of Studio's `{...}` (comark's `comarkAttributes` output), or null for anything else. */
    private fun tokenize(s: String): List<Token>? {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < s.length) {
            if (s[i] == ' ') {
                i++
                continue
            }
            val start = i
            when (s[i]) {
                '#' -> {
                    while (i < s.length && s[i] != ' ') i++
                    tokens += Token(s.substring(start, i), "id", s.substring(start + 1, i))
                }
                '.' -> {
                    while (i < s.length && s[i] != ' ') i++
                    tokens += Token(s.substring(start, i), "class", s.substring(start + 1, i).split('.').filter { it.isNotEmpty() }.joinToString(" "))
                }
                else -> {
                    while (i < s.length && s[i] != ' ' && s[i] != '=') i++
                    val key = s.substring(start, i)
                    if (key.isEmpty()) return null
                    if (i >= s.length || s[i] == ' ') {
                        tokens += Token(key, ":$key", "true")
                        continue
                    }
                    i++ // '='
                    if (i >= s.length) return null
                    val quote = s[i]
                    if (quote != '"' && quote != '\'') return null
                    i++
                    val value = StringBuilder()
                    while (i < s.length && s[i] != quote) {
                        if (quote == '"' && s[i] == '\\' && i + 1 < s.length && s[i + 1] == '"') {
                            value.append('"')
                            i += 2
                            continue
                        }
                        value.append(s[i])
                        i++
                    }
                    if (i >= s.length) return null
                    i++ // closing quote
                    if (i < s.length && s[i] != ' ') return null
                    tokens += Token(s.substring(start, i), key, value.toString())
                }
            }
        }
        return tokens
    }

    /** Sorts the top-level keys of the YAML props block starting with the `---` line at [delimiter]. */
    private fun sortYaml(lines: List<Line>, infos: List<Info>, delimiter: Int, indent: String) {
        var end = delimiter + 1
        while (end < lines.size && infos[end].kind == Kind.YAML && lines[end].text.trim(' ', '\t') != "---") end++
        if (end >= lines.size) return
        val body = (delimiter + 1 until end).map { lines[it].text }
        if (body.any { "&ref_" in it || "*ref_" in it || (it.isNotEmpty() && !it.startsWith(indent)) }) return
        val entries = ArrayList<MutableList<String>>()
        for (l in body) {
            val relative = l.removePrefix(indent)
            val startsEntry = relative.isNotEmpty() && !relative[0].isWhitespace()
            if (startsEntry) {
                if (relative.startsWith("- ") || relative == "-" || relative.startsWith("#") || !relative.contains(':')) return
                entries += mutableListOf(l)
            } else {
                entries.lastOrNull()?.add(l) ?: return
            }
        }
        val sorted = entries.sortedBy { yamlKey(it[0].removePrefix(indent)) }
        if (sorted == entries) return
        val flat = sorted.flatten()
        for ((k, l) in flat.withIndex()) lines[delimiter + 1 + k].text = l
    }

    private fun yamlKey(entry: String): String {
        val raw = entry.substringBefore(':')
        return raw.trim(' ', '\t').removeSurrounding("'").removeSurrounding("\"")
    }

    // ---- paragraphs: list markers and emphasis ---------------------------------------------------------------------------

    private fun rewriteParagraphs(lines: List<Line>, infos: List<Info>) {
        var start = 0
        while (start < lines.size) {
            if (infos[start].kind != Kind.TEXT || lines[start].kept) {
                start++
                continue
            }
            var end = start
            while (end < lines.size && infos[end].kind == Kind.TEXT && !lines[end].kept) end++
            val paragraph = (start until end).map { lines[it] }
            if (options.emphasisMarker != '*' || options.strongMarker != '*') rewriteEmphasis(paragraph)
            for (line in paragraph) rewriteListMarker(line)
            start = end
        }
    }

    /** Where the inline content of [text] starts: after indentation, block quote markers, a list marker and a task box. */
    private fun contentStart(text: String): Int {
        var i = 0
        while (true) {
            while (i < text.length && text[i] == ' ') i++
            if (i < text.length && text[i] == '>') {
                i++
                continue
            }
            break
        }
        if (text.startsWith("- ", i)) {
            i += 2
        } else {
            val digits = text.substring(i).takeWhile { it.isDigit() }.length
            if (digits in 1..9 && text.startsWith(". ", i + digits)) i += digits + 2
        }
        if (text.startsWith("[ ] ", i) || text.startsWith("[x] ", i)) i += 4
        while (i < text.length && text[i] == '#') i++
        return i
    }

    private fun rewriteListMarker(line: Line) {
        val text = line.text
        var i = 0
        while (true) {
            while (i < text.length && text[i] == ' ') i++
            if (i < text.length && text[i] == '>') {
                i++
                continue
            }
            break
        }
        if (options.bulletMarker != '-' && (text.startsWith("- ", i) || text.length == i + 1 && text[i] == '-')) {
            line.text = text.substring(0, i) + options.bulletMarker + text.substring(i + 1)
            return
        }
        if (options.orderedDelimiter != '.') {
            val digits = text.substring(i).takeWhile { it.isDigit() }.length
            if (digits in 1..9 && i + digits < text.length && text[i + digits] == '.' &&
                (i + digits + 1 == text.length || text[i + digits + 1] == ' ')
            ) {
                line.text = text.substring(0, i + digits) + options.orderedDelimiter + text.substring(i + digits + 1)
            }
        }
    }

    private class Run(val line: Int, val pos: Int, val length: Int, val canOpen: Boolean, val canClose: Boolean)

    /** Rewrites `*`/`**` delimiter pairs of one paragraph (consecutive text lines) to the configured markers. */
    private fun rewriteEmphasis(paragraph: List<Line>) {
        val texts = paragraph.map { it.text }
        val runs = ArrayList<Run>()
        for ((index, text) in texts.withIndex()) collectRuns(text, index, runs)
        if (runs.any { it.length > 3 || it.length == 3 && options.emphasisMarker != options.strongMarker }) return
        val stack = ArrayList<Run>()
        val pairs = ArrayList<Pair<Run, Run>>()
        for (run in runs) {
            val top = stack.lastOrNull()
            if (top != null && top.length == run.length && run.canClose) {
                stack.removeAt(stack.size - 1)
                pairs += top to run
            } else if (run.canOpen) {
                stack += run
            } else {
                return // not the regular shape Studio writes: leave the paragraph alone
            }
        }
        val replacements = HashMap<Int, MutableList<Pair<Int, Char>>>() // line -> (position, marker) per run start
        for ((open, close) in pairs) {
            val marker = when (open.length) {
                1 -> options.emphasisMarker
                else -> options.strongMarker
            }
            if (marker == '*') continue
            val before = texts[open.line].getOrNull(open.pos - 1)
            val after = texts[close.line].getOrNull(close.pos + close.length)
            // `_` does not open or close inside a word
            if (before != null && before.isLetterOrDigit() || after != null && after.isLetterOrDigit()) continue
            replacements.getOrPut(open.line) { ArrayList() } += open.pos to marker
            replacements.getOrPut(close.line) { ArrayList() } += close.pos to marker
        }
        for ((lineIndex, list) in replacements) {
            val chars = texts[lineIndex].toCharArray()
            val runAt = runs.filter { it.line == lineIndex }.associateBy { it.pos }
            for ((pos, marker) in list) {
                val run = runAt[pos] ?: continue
                for (k in 0 until run.length) chars[pos + k] = marker
            }
            paragraph[lineIndex].text = String(chars)
        }
    }

    /** The `*` runs of [text] outside escapes, code spans, link destinations, attributes and HTML tags. */
    private fun collectRuns(text: String, lineIndex: Int, runs: MutableList<Run>) {
        var i = contentStart(text)
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i += 2
                c == '`' -> {
                    val n = text.substring(i).takeWhile { it == '`' }.length
                    val closing = findBacktickRun(text, i + n, n)
                    i = if (closing >= 0) closing + n else i + n
                }
                c == '<' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '!') -> {
                    val gt = text.indexOf('>', i)
                    i = if (gt >= 0) gt + 1 else i + 1
                }
                c == ']' && i + 1 < text.length && text[i + 1] == '(' -> i = skipParens(text, i + 1)
                c == '{' && attributeBraceAt(text, i) -> i = skipBraces(text, i)
                c == '*' -> {
                    val n = text.substring(i).takeWhile { it == '*' }.length
                    val before = text.getOrNull(i - 1)
                    val after = text.getOrNull(i + n)
                    val leftFlanking = after != null && !after.isWhitespace() &&
                        (!isPunctuation(after) || before == null || before.isWhitespace() || isPunctuation(before))
                    val rightFlanking = before != null && !before.isWhitespace() &&
                        (!isPunctuation(before) || after == null || after.isWhitespace() || isPunctuation(after))
                    runs += Run(lineIndex, i, n, leftFlanking, rightFlanking)
                    i += n
                }
                else -> i++
            }
        }
    }

    private fun findBacktickRun(text: String, from: Int, n: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '`') {
                val m = text.substring(i).takeWhile { it == '`' }.length
                if (m == n) return i
                i += m
            } else {
                i++
            }
        }
        return -1
    }

    private fun skipParens(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '(' -> depth++
                ')' -> if (--depth == 0) return i + 1
            }
            i++
        }
        return open + 1
    }

    private fun skipBraces(text: String, open: Int): Int {
        var i = open + 1
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '}') {
                return i + 1
            }
            i++
        }
        return open + 1
    }

    /** comark's attribute start: `{`, up to three blanks, then `.`, `#`, `:`, `_` or a letter. */
    private fun attributeBraceAt(s: String, offset: Int): Boolean {
        var i = offset + 1
        var spaces = 0
        while (i < s.length && (s[i] == ' ' || s[i] == '\t') && spaces < 3) {
            i++
            spaces++
        }
        if (i >= s.length) return false
        val c = s[i]
        return c == '.' || c == '#' || c == ':' || c == '_' || c in 'a'..'z' || c in 'A'..'Z'
    }

    private fun isPunctuation(c: Char): Boolean = !c.isLetterOrDigit() && !c.isWhitespace()

    private companion object {
        val FENCE = Regex("^(`{3,}|~{3,})")

        /** A block component opener as Studio writes it: colons, name, optional `{props}`. */
        val OPENER = Regex("""^(:{2,})([A-Za-z$][\w$.:-]*)(\{.*}|)$""")
        val CLOSER = Regex("""^(:{2,})$""")
    }
}
