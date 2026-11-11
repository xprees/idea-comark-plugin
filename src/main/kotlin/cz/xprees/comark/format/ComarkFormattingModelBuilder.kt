package cz.xprees.comark.format

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.LanguageFormatting
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/**
 * The formatting model of Comark files, for everything that adjusts indentation through a model rather than through
 * `CodeStyleManager.reformat*` (which [ComarkFormattingService] handles): Code | Auto-Indent Lines, `adjustLineIndent`, paste with
 * "Reformat block" / "Indent block", and the formatter based fallback of line indentation. Without it these would use the Markdown
 * plugin's model, which puts every component line at column 0 and so undoes Reformat Code.
 *
 * With the Comark formatter enabled, the model is flat: one leaf block per non-blank line, whose indentation is the one
 * [ComarkDocumentFormatter] gives the line - the same rules as Reformat Code and Enter. A line that already has that indentation
 * gets a block starting at the line start, so its whitespace is never rewritten; so does a line whose indentation should hold a
 * tab (in code), which the model cannot write. A blank line gets the indentation of the line above it. When the formatter fails
 * ([ComarkFormatterException]) every line keeps its indentation. With the formatter disabled this hands over to the Markdown
 * plugin's model, as before.
 */
class ComarkFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        if (file !is ComarkFile || !ComarkSettings.getInstance().formatterEnabled) {
            markdownBuilder()?.let { return it.createModel(formattingContext) }
        }
        return FormattingModelProvider.createFormattingModelForPsiFile(file, rootBlock(file), formattingContext.codeStyleSettings)
    }

    /** The builder a Markdown file gets (the Markdown plugin's). */
    private fun markdownBuilder(): FormattingModelBuilder? =
        LanguageFormatting.INSTANCE.forLanguage(MarkdownLanguage.INSTANCE)?.takeUnless { it is ComarkFormattingModelBuilder }

    private fun rootBlock(file: PsiFile): Block {
        val text = file.viewProvider.contents.toString()
        val lines = text.split('\n')
        val formatted = try {
            ComarkDocumentFormatter.format(
                text, ComarkIndent.indentSize(file), ComarkDocumentStructure.of(file), ProgressManager::checkCanceled,
            )
        } catch (_: ComarkFormatterException) {
            lines
        }
        val blocks = ArrayList<LineBlock>()
        var lineStart = 0
        for ((i, line) in lines.withIndex()) {
            val indentLength = line.takeWhile { it == ' ' || it == '\t' }.length
            val contentEnd = line.trimEnd(' ', '\t').length
            if (contentEnd > indentLength) {
                val indent = line.substring(0, indentLength)
                val wanted = formatted.getOrElse(i) { line }.takeWhile { it == ' ' || it == '\t' }
                val columns = wanted.length
                // a block from the line start keeps the line as it is: already right, or tabs in code the model cannot write
                blocks += if (wanted == indent || wanted.any { it != ' ' }) {
                    LineBlock(TextRange(lineStart, lineStart + line.length), Indent.getNoneIndent(), columns)
                } else {
                    LineBlock(TextRange(lineStart + indentLength, lineStart + line.length), Indent.getSpaceIndent(columns), columns)
                }
            }
            lineStart += line.length + 1
        }
        return RootBlock(TextRange(0, text.length), blocks)
    }

    /** The whole file; its children are the lines. */
    private class RootBlock(private val range: TextRange, private val lines: List<LineBlock>) : Block {
        override fun getTextRange(): TextRange = range
        override fun getSubBlocks(): List<Block> = lines
        override fun getWrap(): Wrap? = null
        override fun getIndent(): Indent = Indent.getNoneIndent()
        override fun getAlignment(): Alignment? = null
        override fun getSpacing(child1: Block?, child2: Block): Spacing? = null

        /** A new line gets the indentation of the line above it (Enter has its own rules, see [ComarkLineIndentProvider]). */
        override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
            ChildAttributes(lines.getOrNull(newChildIndex - 1)?.let { Indent.getSpaceIndent(it.columns) } ?: Indent.getNoneIndent(), null)

        override fun isIncomplete(): Boolean = false
        override fun isLeaf(): Boolean = lines.isEmpty()
    }

    /**
     * One non-blank line, up to its end: trailing whitespace stays inside the block, so adjusting indentation cannot strip a
     * hard line break. [columns] is the width of the indentation the line should have.
     */
    private class LineBlock(private val range: TextRange, private val indent: Indent, val columns: Int) : Block {
        override fun getTextRange(): TextRange = range
        override fun getSubBlocks(): List<Block> = emptyList()
        override fun getWrap(): Wrap? = null
        override fun getIndent(): Indent = indent
        override fun getAlignment(): Alignment? = null
        override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
        override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)
        override fun isIncomplete(): Boolean = false
        override fun isLeaf(): Boolean = true
    }
}
