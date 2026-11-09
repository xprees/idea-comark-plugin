package cz.xprees.comark.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.WhitespacesBinders
import com.intellij.lexer.LexerBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.tree.IElementType
import cz.xprees.comark.lang.ComarkFlavour
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.ast.visitors.RecursiveVisitor
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypeSets
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicReference
import org.intellij.markdown.ast.ASTNode as MdNode

/** Parses text into the combined Comark/Markdown tree, remembering the last result (the lexer and parser ask for the same text). */
object ComarkParseCache {
    private class Entry(val text: String, val flavour: MarkdownFlavourDescriptor, val tree: MdNode)

    private val last = AtomicReference<SoftReference<Entry>?>()

    fun parse(text: CharSequence, flavour: MarkdownFlavourDescriptor = ComarkFlavour.INSTANCE): MdNode {
        val cached = last.get()?.get()
        if (cached != null && cached.flavour === flavour && cached.text.contentEquals(text)) return cached.tree
        val string = text.toString()
        val tree = ComarkTreeBuilder(string, flavour).build()
        last.set(SoftReference(Entry(string, flavour, tree)))
        return tree
    }
}

/**
 * Top level lexer: the leaves of the combined tree, like the Markdown plugin's `MarkdownToplevelLexer`. Paragraphs, headers and
 * table cells come out as single lazy tokens that the Markdown plugin parses into inline elements on demand.
 */
class ComarkLexer(private val flavour: MarkdownFlavourDescriptor = ComarkFlavour.INSTANCE) : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferStart = 0
    private var bufferEnd = 0
    private var types: Array<IElementType?> = emptyArray()
    private var starts = IntArray(0)
    private var ends = IntArray(0)
    private var count = 0
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        if (buffer === this.buffer && startOffset == bufferStart && endOffset == bufferEnd && count > 0) {
            index = initialState
            return
        }
        this.buffer = buffer
        bufferStart = startOffset
        bufferEnd = endOffset
        val tree = ComarkParseCache.parse(buffer.subSequence(startOffset, endOffset), flavour)
        val collectedTypes = ArrayList<IElementType>()
        val collectedStarts = ArrayList<Int>()
        val collectedEnds = ArrayList<Int>()
        tree.acceptLeaves { node ->
            collectedTypes += ComarkTypes.platformType(node.type)
            collectedStarts += node.startOffset
            collectedEnds += node.endOffset
        }
        count = collectedTypes.size
        types = collectedTypes.toTypedArray()
        starts = collectedStarts.toIntArray()
        ends = collectedEnds.toIntArray()
        index = initialState.coerceIn(0, count)
    }

    override fun getState(): Int = index

    override fun getTokenType(): IElementType? = if (index < count) types[index] else null

    override fun getTokenStart(): Int = if (index < count) bufferStart + starts[index] else bufferEnd

    override fun getTokenEnd(): Int = if (index < count) bufferStart + ends[index] else bufferEnd

    override fun advance() {
        index++
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEnd
}

/** Visits the non-empty leaves of an intellij-markdown tree in order. */
internal fun MdNode.acceptLeaves(action: (MdNode) -> Unit) {
    val stack = ArrayDeque<MdNode>()
    stack.addLast(this)
    while (stack.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val node = stack.removeLast()
        if (node.startOffset == node.endOffset) continue
        val children = node.children
        if (children.isEmpty()) {
            action(node)
        } else {
            for (i in children.indices.reversed()) stack.addLast(children[i])
        }
    }
}

/** Builds the PSI tree from the combined tree, mirroring the Markdown plugin's `PsiBuilderFillingVisitor`. */
class ComarkPsiParser(private val flavour: MarkdownFlavourDescriptor = ComarkFlavour.INSTANCE) : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        rootMarker.setCustomEdgeTokenBinders(WhitespacesBinders.GREEDY_LEFT_BINDER, WhitespacesBinders.GREEDY_RIGHT_BINDER)
        val tree = ComarkParseCache.parse(builder.originalText, flavour)
        FillingVisitor(builder).visitNode(tree)
        while (!builder.eof()) builder.advanceLexer()
        rootMarker.done(root)
        return builder.treeBuilt
    }

    private class FillingVisitor(private val builder: PsiBuilder) : RecursiveVisitor() {
        private var seenFirstMarker = true
        private val headers = MarkdownTokenTypeSets.HEADERS.types.map {
            org.intellij.plugins.markdown.lang.MarkdownElementType.markdownType(it)
        }.toSet()

        override fun visitNode(node: MdNode) {
            ProgressManager.checkCanceled()
            val type = node.type
            if (type == GFMTokenTypes.CELL && node.startOffset == node.endOffset) {
                ensurePosition(node.startOffset)
                builder.mark().done(org.intellij.plugins.markdown.lang.MarkdownElementTypes.TABLE_CELL)
            }
            val isLeaf = node is LeafASTNode || node.children.isEmpty()
            if (isLeaf && type != MarkdownElementTypes.LINK_LABEL && type != MarkdownElementTypes.LINK_DESTINATION) return
            if (type == MarkdownElementTypes.MARKDOWN_FILE) {
                ensurePosition(node.startOffset)
                super.visitNode(node)
                ensurePosition(node.endOffset)
                return
            }
            if (isLeaf && node.startOffset == node.endOffset) return
            ensurePosition(node.startOffset)
            val marker = builder.mark()
            if (!seenFirstMarker) {
                seenFirstMarker = true
                marker.setCustomEdgeTokenBinders(WhitespacesBinders.GREEDY_LEFT_BINDER, WhitespacesBinders.GREEDY_RIGHT_BINDER)
            } else if (type in headers) {
                marker.setCustomEdgeTokenBinders(LEADING_WHITESPACE, null)
            }
            super.visitNode(node)
            ensurePosition(node.endOffset)
            val platformType = ComarkTypes.platformType(type)
            if (COLLAPSABLE.contains(platformType.javaClass.name)) marker.collapse(platformType) else marker.done(platformType)
        }

        private fun ensurePosition(position: Int) {
            while (!builder.eof() && builder.currentOffset < position) builder.advanceLexer()
        }

        companion object {
            private val COLLAPSABLE = setOf("org.intellij.plugins.markdown.lang.parser.MarkdownCollapsableElementType")

            private val LEADING_WHITESPACE = com.intellij.lang.WhitespacesAndCommentsBinder { tokens, _, getter ->
                var i = 0
                while (i < tokens.size) {
                    if (tokens[i] == org.intellij.plugins.markdown.lang.MarkdownTokenTypes.WHITE_SPACE && getter.get(i).isBlank()) break
                    i++
                }
                i
            }
        }
    }
}
