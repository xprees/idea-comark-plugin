package cz.xprees.comark.editor

import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownTokenTypeSets

/**
 * Structural support for block components: the platform highlights the ranges returned by [getCodeBlockMarkerRanges] while the caret
 * is on one of them (identifier highlighting, "structural" markers like Ruby's `def`/`end`), and uses [getCodeBlockRange] for
 * *Move Caret to Code Block Start/End* and *Move Caret to Matching Brace*.
 *
 * Registered for Markdown, not Comark: the element at the caret is often a Markdown token (the line break after a closing `::`, a
 * paragraph inside a component), and handlers are looked up by the language of that element. Registrations for Markdown also apply
 * to Comark elements, as Comark is a Markdown dialect; plain Markdown files are skipped.
 */
class ComarkCodeBlockSupportHandler : CodeBlockSupportHandler {
    override fun getCodeBlockMarkerRanges(elementAtCursor: PsiElement): List<TextRange> {
        if (elementAtCursor.containingFile !is ComarkFile) return emptyList()
        val component = ComarkComponentPairs.componentForMarker(elementAtCursor) ?: return emptyList()
        return ComarkComponentPairs.markerRanges(component).orEmpty()
    }

    override fun getCodeBlockRange(elementAtCursor: PsiElement): TextRange {
        if (elementAtCursor.containingFile !is ComarkFile) return TextRange.EMPTY_RANGE
        val component = ComarkComponentPairs.componentForMarker(elementAtCursor)
            ?: PsiTreeUtil.getParentOfType(elementAtCursor, ComarkBlockComponent::class.java, false)
            ?: return TextRange.EMPTY_RANGE
        return ComarkComponentPairs.blockRange(component)
    }
}

/**
 * *Highlight Usages in File* for component markers: with the caret on `::name` or on its closing `::`, both are highlighted. The
 * platform asks the same factories while the caret moves, so this also drives the automatic highlighting of the pair, the way the
 * VS Code extension highlights matching colons.
 */
class ComarkHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactoryBase(), DumbAware {
    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile, target: PsiElement): HighlightUsagesHandlerBase<*>? {
        if (file !is ComarkFile) return null
        val component = ComarkComponentPairs.componentForMarker(target) ?: return null
        val ranges = ComarkComponentPairs.markerRanges(component) ?: return null
        return ComponentMarkersHandler(editor, file, target, ranges)
    }

    private class ComponentMarkersHandler(
        editor: Editor,
        file: PsiFile,
        private val target: PsiElement,
        private val ranges: List<TextRange>,
    ) : HighlightUsagesHandlerBase<PsiElement>(editor, file), DumbAware {
        override fun getTargets(): List<PsiElement> = listOf(target)

        override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) {
            selectionConsumer.consume(targets)
        }

        override fun computeUsages(targets: List<PsiElement>) {
            myReadUsages.addAll(ranges)
        }
    }
}

/**
 * Brace matching for the `{attributes}` and `[content]` of opener lines. Brace matchers work on the editor highlighter's tokens, so this
 * only takes effect where the editor highlights a Comark file with Comark tokens; component markers are deliberately not braces
 * here, because pairing `::` tokens by counting would ignore colon counts and unclosed components (see
 * [ComarkCodeBlockSupportHandler] for those).
 */
class ComarkBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    /** Typing `{` or `[` inserts the closing brace only at the end of the line or before whitespace / another closing brace. */
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean =
        contextType == null || contextType in MarkdownTokenTypeSets.WHITE_SPACES ||
            contextType == ComarkTypes.ATTR_RBRACE || contextType == ComarkTypes.CONTENT_RBRACKET

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(ComarkTypes.ATTR_LBRACE, ComarkTypes.ATTR_RBRACE, false),
            BracePair(ComarkTypes.CONTENT_LBRACKET, ComarkTypes.CONTENT_RBRACKET, false),
        )
    }
}
