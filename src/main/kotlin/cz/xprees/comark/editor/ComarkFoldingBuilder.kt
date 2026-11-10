package cz.xprees.comark.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilder
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.psi.ComarkPropsBlock
import cz.xprees.comark.psi.ComarkSlot
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

/**
 * Code folding for Comark files: block components, slots, props blocks and header sections, plus everything Markdown folds.
 *
 * `LanguageFolding` only uses the builders of the most specific language that has any, so registering a builder for Comark hides
 * the Markdown plugin's. This builder therefore runs the Markdown builders itself (code fences, front matter, lists, tables, block
 * quotes, paragraphs, links, table of contents, custom regions) and adds the Comark regions on top. Builders registered for any
 * language are still added by the platform.
 *
 * Header sections are the one Markdown region that is replaced. The Markdown builder finds a header's section through the Markdown
 * file element type, which a Comark file doesn't have: it only folds the chain of headers leading to the last header of the
 * document, and folds it to the end of the file, so a section that starts inside a component runs across the component's closing
 * marker, overlaps the component's own region and one of them gets dropped. Sections are computed here per container instead (the
 * file, a component or a slot): from the header to the next header of the same or a higher level in that container.
 */
class ComarkFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (root.containingFile !is ComarkFile) return FoldingDescriptor.EMPTY_ARRAY
        val descriptors = ArrayList<FoldingDescriptor>()
        val ranges = HashSet<TextRange>()
        val collector = { descriptor: FoldingDescriptor -> if (ranges.add(descriptor.range)) descriptors += descriptor }
        val headerPlaceholders = collectMarkdownRegions(root, document, quick, collector)
        RegionCollector(document, headerPlaceholders, collector).container(root)
        return descriptors.toTypedArray()
    }

    /**
     * Adds the regions of the builders registered for Markdown, except header sections. Returns the builder that folded headers (its
     * placeholder text is used for the replacement sections, so they look the same as in a Markdown file), if any.
     */
    private fun collectMarkdownRegions(root: PsiElement, document: Document, quick: Boolean, sink: (FoldingDescriptor) -> Unit): FoldingBuilderEx? {
        var headerBuilder: FoldingBuilderEx? = null
        for (builder in LanguageFolding.INSTANCE.allForLanguage(MarkdownLanguage.INSTANCE)) {
            if (builder is ComarkFoldingBuilder) continue
            for (descriptor in LanguageFolding.buildFoldingDescriptors(builder, root, document, quick)) {
                ProgressManager.checkCanceled()
                if (descriptor.element.psi is MarkdownHeader) {
                    if (headerBuilder == null && builder is FoldingBuilderEx) headerBuilder = builder
                    continue
                }
                sink(copyOf(descriptor, builder))
            }
        }
        return headerBuilder
    }

    /**
     * A copy of a Markdown descriptor that no longer needs its builder: the platform asks the builder registered for the descriptor's
     * language for missing placeholders and collapse states, and for Markdown elements that is a composite which answers with the
     * element text. Both are therefore resolved here, with the builder that made the descriptor.
     */
    private fun copyOf(descriptor: FoldingDescriptor, builder: FoldingBuilder): FoldingDescriptor {
        val node = descriptor.element
        var placeholder = descriptor.placeholderText
        if (placeholder == null || (placeholder.length == node.textLength && placeholder == node.text)) {
            placeholder = (if (builder is FoldingBuilderEx) builder.getPlaceholderText(node, descriptor.range) else builder.getPlaceholderText(node))
                ?: placeholder
        }
        val collapsed = descriptor.isCollapsedByDefault ?: builder.isCollapsedByDefault(descriptor)
        return FoldingDescriptor(node, descriptor.range, descriptor.group, descriptor.dependencies, descriptor.isNonExpandable, placeholder, collapsed)
            .also {
                it.setCanBeRemovedWhenCollapsed(descriptor.canBeRemovedWhenCollapsed())
                it.isGutterMarkEnabledForSingleLine = descriptor.isGutterMarkEnabledForSingleLine
            }
    }

    override fun getPlaceholderText(node: ASTNode): String = PLACEHOLDER

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    /** Walks the Comark structure: the file and, recursively, components and slots (Markdown nodes never contain components). */
    private class RegionCollector(
        private val document: Document,
        private val headerPlaceholders: FoldingBuilderEx?,
        private val sink: (FoldingDescriptor) -> Unit,
    ) {
        private val text = document.charsSequence

        fun container(container: PsiElement) {
            var child = container.firstChild
            while (child != null) {
                ProgressManager.checkCanceled()
                when (child) {
                    is ComarkBlockComponent -> component(child)
                    is ComarkSlot -> slot(child)
                    is ComarkPropsBlock -> props(child)
                    is MarkdownHeader -> section(child)
                }
                child = child.nextSibling
            }
        }

        /** `::name{attrs}...::`: from the end of the opener line to the closing marker, which stays visible. */
        private fun component(component: ComarkBlockComponent) {
            val start = component.header?.textRange?.endOffset ?: (component.textRange.startOffset + component.markerCount)
            val end = component.closeMarker?.textRange?.startOffset ?: trimBack(component.textRange.endOffset, start)
            fold(component, start, end, PLACEHOLDER)
            container(component)
        }

        /** `#name{attrs}...`: from the end of the slot line to the end of its content. */
        private fun slot(slot: ComarkSlot) {
            val lineEnd = slot.node.getChildren(SLOT_LINE).maxOfOrNull { it.textRange.endOffset } ?: return
            fold(slot, lineEnd, trimBack(slot.textRange.endOffset, lineEnd), PLACEHOLDER)
            container(slot)
        }

        /** `--- title, icon ---`: the YAML between the fences, shown as its top level keys. */
        private fun props(props: ComarkPropsBlock) {
            val fences = props.node.getChildren(PROPS_FENCES)
            if (fences.size < 2) return
            val start = fences.first().textRange.endOffset
            val end = fences.last().textRange.startOffset
            fold(props, start, end, " " + propsSummary(props.content?.text.orEmpty()) + " ")
        }

        /** A header's section: up to the next header of the same or a higher level, slot or closing marker in the same container. */
        private fun section(header: MarkdownHeader) {
            var last: PsiElement = header
            var next = header.nextSibling
            while (next != null) {
                ProgressManager.checkCanceled()
                if (next is MarkdownHeader && next.level <= header.level) break
                if (next is ComarkSlot || next.elementType == ComarkTypes.COMPONENT_CLOSE) break
                if (!next.isBlankLeaf()) last = next
                next = next.nextSibling
            }
            val start = header.textRange.startOffset
            val end = trimBack(last.textRange.endOffset, start)
            if (!spansLines(start, end)) return
            val range = TextRange(start, end)
            val placeholder = headerPlaceholders?.getPlaceholderText(header.node, range)?.takeUnless { it == header.text }
                ?: StringUtil.shortenTextWithEllipsis("#".repeat(header.level) + " " + (header.name ?: ""), 30, 5)
            sink(FoldingDescriptor(header.node, range, null, placeholder))
        }

        private fun fold(element: PsiElement, start: Int, end: Int, placeholder: String) {
            if (spansLines(start, end)) sink(FoldingDescriptor(element.node, TextRange(start, end), null, placeholder))
        }

        /** True for a non-empty range whose first and last characters are on different lines. */
        private fun spansLines(start: Int, end: Int): Boolean =
            start in 0 until end && end <= document.textLength && document.getLineNumber(start) != document.getLineNumber(end - 1)

        private fun trimBack(end: Int, limit: Int): Int {
            var i = minOf(end, text.length)
            while (i > limit && text[i - 1].isWhitespace()) i--
            return i
        }
    }

    companion object {
        private const val PLACEHOLDER = "..."
        private val SLOT_LINE = TokenSet.create(ComarkTypes.SLOT_MARKER, ComarkTypes.SLOT_NAME, ComarkTypes.ATTRIBUTES)
        private val PROPS_FENCES = TokenSet.create(ComarkTypes.PROPS_FENCE)
        private const val SUMMARY_LENGTH = 40

        /** Top level keys of a props block (`title, icon`), or `...` when there are none (a list, say). */
        internal fun propsSummary(yaml: CharSequence): String {
            val lines = yaml.lines()
            val indent = lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
            val keys = lines.mapNotNull { line ->
                if (line.length <= indent || line[indent].isWhitespace()) return@mapNotNull null
                val content = line.substring(indent)
                if (content[0] == '#' || content[0] == '-') return@mapNotNull null
                val colon = content.indexOf(':')
                if (colon <= 0) null else content.substring(0, colon).trim().removeSurrounding("\"").removeSurrounding("'").ifEmpty { null }
            }
            if (keys.isEmpty()) return PLACEHOLDER
            return StringUtil.first(keys.joinToString(", "), SUMMARY_LENGTH, true)
        }
    }
}
