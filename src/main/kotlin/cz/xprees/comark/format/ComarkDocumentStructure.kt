package cz.xprees.comark.format

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import cz.xprees.comark.psi.ComarkBlockComponent
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownListItem

/** Builds the [DocumentStructure] of a Comark file from its PSI, for [ComarkDocumentFormatter]. */
internal object ComarkDocumentStructure {
    /** The structure of [file], with the line numbers of its PSI text (callers commit the document first). */
    fun of(file: PsiFile): DocumentStructure {
        val text = file.viewProvider.contents
        val lineStarts = ArrayList<Int>().apply {
            add(0)
            for (i in text.indices) if (text[i] == '\n') add(i + 1)
        }
        val components = ArrayList<DocumentStructure.ListItemComponent>()
        val codeBlocks = ArrayList<DocumentStructure.CodeBlock>()
        val fences = ArrayList<DocumentStructure.Fence>()
        fun line(offset: Int): Int {
            val index = lineStarts.binarySearch(offset.coerceIn(0, text.length))
            return if (index >= 0) index else -index - 2
        }
        fun lastLine(element: PsiElement) = line(maxOf(element.textRange.startOffset, element.textRange.endOffset - 1))

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                when {
                    element is ComarkBlockComponent -> {
                        val item = element.parent
                        val close = element.closeMarker
                        if (item is MarkdownListItem && close != null) {
                            components += DocumentStructure.ListItemComponent(line(element.textOffset), line(close.textOffset), line(item.textOffset))
                        }
                    }

                    element.elementType == MarkdownElementTypes.CODE_BLOCK -> {
                        val anchor = PsiTreeUtil.getParentOfType(element, ComarkBlockComponent::class.java)
                        codeBlocks += DocumentStructure.CodeBlock(line(element.textOffset)..lastLine(element), anchor?.let { line(it.textOffset) } ?: -1)
                        return
                    }

                    element.elementType == MarkdownElementTypes.CODE_FENCE -> {
                        val open = line(element.textOffset)
                        val end = element.node.findChildByType(MarkdownTokenTypes.CODE_FENCE_END)
                        val last = if (end != null) line(end.startOffset) - 1 else lastLine(element)
                        if (last > open) fences += DocumentStructure.Fence(open, open + 1..last)
                        return
                    }
                }
                super.visitElement(element)
            }
        })
        return DocumentStructure(components, codeBlocks, fences)
    }
}
