package cz.xprees.comark.parser

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Calls [MarkdownParser.parse] through the overload the running IDE has.
 *
 * Newer versions of the bundled Markdown library (IDE 2026.3) add `parse(IElementType, CharSequence, Boolean)` and deprecate the
 * `String` overload (and `buildMarkdownTreeFromString(String)`, which calls it); the oldest IDEs the plugin supports only have the
 * `String` overload. Calling either directly would either use deprecated API or break on older IDEs, so the overload is looked up
 * once at runtime: the `CharSequence` one when it exists, the `String` one otherwise.
 */
object MarkdownParserCompat {
    private class Parse(val handle: MethodHandle, val takesString: Boolean)

    private val parse: Parse = run {
        val lookup = MethodHandles.publicLookup()
        fun find(textType: Class<*>): MethodHandle? = try {
            lookup.findVirtual(
                MarkdownParser::class.java,
                "parse",
                MethodType.methodType(ASTNode::class.java, IElementType::class.java, textType, Boolean::class.javaPrimitiveType),
            )
        } catch (_: NoSuchMethodException) {
            null
        }
        find(CharSequence::class.java)?.let { Parse(it, takesString = false) }
            ?: Parse(checkNotNull(find(String::class.java)) { "MarkdownParser.parse not found" }, takesString = true)
    }

    /** Parses [text] as a [root] node, with or without inline elements. */
    fun parse(parser: MarkdownParser, root: IElementType, text: CharSequence, parseInlines: Boolean): ASTNode {
        val argument: Any = if (parse.takesString) text.toString() else text
        return parse.handle.invokeWithArguments(parser, root, argument, parseInlines) as ASTNode
    }

    /** A whole Markdown document with inline elements, like `buildMarkdownTreeFromString`. */
    fun parseDocument(parser: MarkdownParser, text: CharSequence): ASTNode = parse(parser, MarkdownElementTypes.MARKDOWN_FILE, text, true)
}
