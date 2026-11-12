package cz.xprees.comark.format.studio

/**
 * GFM literal autolinks (`remark-gfm`: micromark-extension-gfm-autolink-literal and mdast-util-gfm-autolink-literal
 * `transformGfmAutolinkLiterals`), which Nuxt Content's parse applies and Studio's comark parse (`linkify: false`) does not:
 * a bare `https://…` or `www.…` in text becomes a link.
 *
 * Only the plain shape both parts of remark-gfm agree on is followed: the URL starts the text or follows a space, a line
 * ending or `(`, it ends at a space, a line ending or the end of the paragraph, its characters are letters, digits and
 * `-./?#=+:,;!'()`, and its domain passes `isCorrectDomain`. The trailing punctuation and parentheses are split off as
 * `splitUrl` does. Any other occurrence of `://`, `www.` or an e-mail-like `@` makes the document unsupported.
 */
internal object StudioDbAutolink {
    sealed interface Segment
    data class Text(val text: String) : Segment
    data class Link(val href: String, val text: String) : Segment

    private val START = Regex("(?i)https?://|www\\.")
    private val URL_CHARS = Regex("^[A-Za-z0-9\\-./?#=+:,;!'()]*\\z")
    private val EMAIL = Regex("[^\\s]@")

    /**
     * [text] (a text node whose content is at a line start when [atStart] and ends its paragraph when [atEnd]) with its
     * literal autolinks, or `null` when the text has one this port does not follow.
     */
    fun split(text: String, atStart: Boolean, atEnd: Boolean): List<Segment>? {
        if (EMAIL.containsMatchIn(text) || text.contains("mailto:", ignoreCase = true) || text.contains("xmpp:", ignoreCase = true)) return null
        if (!START.containsMatchIn(text) && !text.contains("://")) return listOf(Text(text))
        val out = ArrayList<Segment>()
        var from = 0
        var i = 0
        while (i < text.length) {
            val m = START.find(text, i) ?: break
            val start = m.range.first
            val prev = if (start == 0) null else text[start - 1]
            if (start == 0 && !atStart || prev != null && prev != ' ' && prev != '\n' && prev != '(') return null
            var end = start
            while (end < text.length && text[end] != ' ' && text[end] != '\n' && text[end] != '\t' && text[end] != '\r') end++
            if (end == text.length && !atEnd) return null
            val token = text.substring(start, end)
            if (!URL_CHARS.matches(token)) return null
            val link = link(token) ?: return null
            if (start > from) out += Text(text.substring(from, start))
            out += Link(link.first, link.second)
            val trail = token.substring(link.second.length)
            if (trail.isNotEmpty()) out += Text(trail)
            from = end
            i = end
        }
        if ("://" in text.substring(from) || START.containsMatchIn(text.substring(from))) return null
        if (from < text.length) out += Text(text.substring(from))
        return mergeTexts(out)
    }

    /** `findUrl` on one token: the link's href and text, or `null` when remark-gfm does not link it (or might not). */
    private fun link(token: String): Pair<String, String>? {
        val m = Regex("^(?i)(https?://|www(?=\\.))([\\w-]*\\.[\\w\\-.]*)(.*)\\z").find(token) ?: return null
        var protocol = m.groupValues[1]
        var domain = m.groupValues[2]
        val path = m.groupValues[3]
        var prefix = ""
        if (protocol[0] == 'w' || protocol[0] == 'W') {
            domain = protocol + domain
            protocol = ""
            prefix = "http://"
        }
        if (domain.isEmpty() || domain[0] == '-' || domain[0] == '.' || !correctDomain(domain)) return null
        val (url, trail) = splitUrl(domain + path)
        if (url.isEmpty()) return null
        if (trail.isNotEmpty() && trail.any { it == '(' }) return null
        return (prefix + protocol + url) to (protocol + url)
    }

    private fun correctDomain(domain: String): Boolean {
        val parts = domain.split('.')
        if (parts.size < 2) return false
        for (part in listOf(parts[parts.size - 1], parts[parts.size - 2])) {
            if (part.isNotEmpty() && ('_' in part || part.none { it.isLetterOrDigit() && it.code < 128 })) return false
        }
        // a domain this port also accepts as an anchor URL (validateProps): no empty labels, no underscores
        return parts.all { it.isNotEmpty() && Regex("^[A-Za-z0-9-]+\\z").matches(it) }
    }

    private const val TRAILING = "!\"&'),.:;<>?]}"

    /** `splitUrl`: trailing punctuation off, closing parentheses back while the URL has unbalanced opening ones. */
    private fun splitUrl(input: String): Pair<String, String> {
        var index = input.length
        while (index > 0 && input[index - 1] in TRAILING) index--
        if (index == input.length) return input to ""
        var trail = input.substring(index)
        var url = input.substring(0, index)
        var closingParenIndex = trail.indexOf(')')
        val openingParens = url.count { it == '(' }
        var closingParens = url.count { it == ')' }
        while (closingParenIndex != -1 && openingParens > closingParens) {
            url += trail.substring(0, closingParenIndex + 1)
            trail = trail.substring(closingParenIndex + 1)
            closingParenIndex = trail.indexOf(')')
            closingParens++
        }
        return url to trail
    }

    private fun mergeTexts(segments: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in segments) {
            val last = out.lastOrNull()
            if (s is Text && last is Text) out[out.size - 1] = Text(last.text + s.text) else out += s
        }
        return out
    }
}
