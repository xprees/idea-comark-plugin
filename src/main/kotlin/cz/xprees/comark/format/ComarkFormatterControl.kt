package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.lang.LanguageFormattingRestriction
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import java.util.BitSet
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The platform's formatter controls, applied to Comark files by [ComarkFormattingService] and [ComarkFormattingModelBuilder]:
 * - formatting restrictions ([LanguageFormattingRestriction]), among them *Settings › Editor › Code Style › Formatter › Do not
 *   format* (files or scopes that are never formatted);
 * - formatter markers (*Code Style › Formatter › Turn formatter on/off with markers in code comments*): the lines from a comment
 *   holding the off marker (`<!-- @formatter:off -->` by default, or a `[//]: # (@formatter:off)` comment) to the next comment
 *   holding the on marker are left as they are. Markers inside code fences don't count; an off marker without an on marker
 *   protects the rest of the file. Regular expression markers are supported when the setting accepts them.
 */
object ComarkFormatterControl {
    /** False when a formatting restriction (such as a *Do not format* entry) excludes [file]. */
    fun isFormattingAllowed(file: PsiFile): Boolean = LanguageFormattingRestriction.EP_NAME.extensionList.all { it.isFormatterAllowed(file) }

    /** Lines of [text] (0-based) that formatter markers protect in [file], or null when there are none. */
    fun protectedLines(file: PsiFile, text: CharSequence): BitSet? = protectedLines(text, CodeStyle.getSettings(file))

    fun protectedLines(text: CharSequence, settings: CodeStyleSettings): BitSet? {
        if (!settings.FORMATTER_TAGS_ENABLED) return null
        return protectedLines(text, settings.FORMATTER_OFF_TAG.orEmpty(), settings.FORMATTER_ON_TAG.orEmpty(), settings.FORMATTER_TAGS_ACCEPT_REGEXP)
    }

    /**
     * Lines that formatter markers mark as kept in [file], whether or not markers are enabled in its code style: the configured
     * markers when they are, else the default `@formatter:off` / `@formatter:on`. For the Nuxt Studio inspections, whose
     * *Suppress for this block* fix writes these markers.
     */
    fun markedLines(file: PsiFile, text: CharSequence): BitSet? {
        val settings = CodeStyle.getSettings(file)
        return if (settings.FORMATTER_TAGS_ENABLED) protectedLines(text, settings) else protectedLines(text, DEFAULT_OFF_TAG, DEFAULT_ON_TAG, false)
    }

    /** The off and on markers [file] uses, for writing them: the configured ones unless they are regular expressions or disabled. */
    fun markers(file: PsiFile): Pair<String, String> {
        val settings = CodeStyle.getSettings(file)
        val usable = settings.FORMATTER_TAGS_ENABLED && !settings.FORMATTER_TAGS_ACCEPT_REGEXP &&
            !settings.FORMATTER_OFF_TAG.isNullOrBlank() && !settings.FORMATTER_ON_TAG.isNullOrBlank()
        return if (usable) settings.FORMATTER_OFF_TAG to settings.FORMATTER_ON_TAG else DEFAULT_OFF_TAG to DEFAULT_ON_TAG
    }

    const val DEFAULT_OFF_TAG = "@formatter:off"
    const val DEFAULT_ON_TAG = "@formatter:on"

    fun protectedLines(text: CharSequence, offTag: String, onTag: String, regexp: Boolean): BitSet? {
        // compiled here from the current tags (the settings' own compiled patterns are cached)
        val offPattern = if (regexp) compile(offTag) else null
        val onPattern = if (regexp) compile(onTag) else null
        fun isOff(comment: String) = if (offPattern != null) offPattern.matcher(comment).find() else offTag.isNotEmpty() && comment.contains(offTag)
        fun isOn(comment: String) = if (onPattern != null) onPattern.matcher(comment).find() else onTag.isNotEmpty() && comment.contains(onTag)

        val lines = text.split('\n')
        val result = BitSet()
        var offFrom = -1
        var fence: String? = null
        var htmlComment: StringBuilder? = null
        var htmlCommentStart = 0
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (htmlComment == null) {
                val fenceMarker = FENCE.find(trimmed)?.value
                if (fence != null) {
                    if (fenceMarker != null && fenceMarker[0] == fence[0] && fenceMarker.length >= fence.length && trimmed.trimEnd() == fenceMarker) fence = null
                    continue
                }
                if (fenceMarker != null) {
                    fence = fenceMarker
                    continue
                }
            }
            // comments on this line, in order: HTML comments (possibly spanning lines) and Markdown link-definition comments
            var rest = line
            val comments = ArrayList<Pair<String, Int>>() // comment text, line where it starts
            while (rest.isNotEmpty()) {
                if (htmlComment != null) {
                    val end = rest.indexOf("-->")
                    if (end < 0) {
                        htmlComment.append(rest).append('\n')
                        rest = ""
                    } else {
                        htmlComment.append(rest, 0, end)
                        comments += htmlComment.toString() to htmlCommentStart
                        htmlComment = null
                        rest = rest.substring(end + 3)
                    }
                    continue
                }
                val start = rest.indexOf("<!--")
                if (start < 0) break
                htmlComment = StringBuilder()
                htmlCommentStart = index
                rest = rest.substring(start + 4)
            }
            if (MARKDOWN_COMMENT.matches(trimmed)) comments += trimmed to index
            for ((comment, startLine) in comments) {
                if (offFrom < 0) {
                    if (isOff(comment)) offFrom = startLine
                } else if (isOn(comment)) {
                    result.set(offFrom, index + 1)
                    offFrom = -1
                }
            }
        }
        if (offFrom >= 0) result.set(offFrom, lines.size)
        return result.takeUnless { it.isEmpty }
    }

    private fun compile(tag: String): Pattern? = try {
        if (tag.isEmpty()) null else Pattern.compile(tag)
    } catch (_: PatternSyntaxException) {
        null
    }

    private val FENCE = Regex("^(`{3,}|~{3,})")

    /** `[//]: # (text)`, `[//]: # "text"`, `[comment]: <> (text)` and similar link definitions used as comments. */
    private val MARKDOWN_COMMENT = Regex("""^\[(?://|comment)]:\s*(?:#|<>)\s*.*$""")
}
