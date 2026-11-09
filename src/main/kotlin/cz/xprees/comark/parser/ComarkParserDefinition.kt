package cz.xprees.comark.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.psi.tree.IStubFileElementType
import com.intellij.psi.tree.TokenSet
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.psi.ComarkAttribute
import cz.xprees.comark.psi.ComarkAttributes
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkComponentHeader
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.psi.ComarkPropsBlock
import cz.xprees.comark.psi.ComarkSlot
import org.intellij.plugins.markdown.lang.MarkdownTokenTypeSets
import org.intellij.plugins.markdown.lang.parser.MarkdownParserDefinition

class ComarkParserDefinition : ParserDefinition {
    private val markdown = MarkdownParserDefinition()

    override fun createLexer(project: Project?): Lexer = ComarkLexer()

    override fun createParser(project: Project?): PsiParser = ComarkPsiParser()

    override fun getFileNodeType(): IStubFileElementType<PsiFileStub<ComarkFile>> = FILE

    override fun getWhitespaceTokens(): TokenSet = MarkdownTokenTypeSets.WHITE_SPACES

    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY

    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        ComarkTypes.BLOCK_COMPONENT -> ComarkBlockComponent(node)
        ComarkTypes.COMPONENT_HEADER -> ComarkComponentHeader(node)
        ComarkTypes.PROPS_BLOCK -> ComarkPropsBlock(node)
        ComarkTypes.SLOT -> ComarkSlot(node)
        ComarkTypes.ATTRIBUTES -> ComarkAttributes(node)
        ComarkTypes.ATTRIBUTE -> ComarkAttribute(node)
        else -> markdown.createElement(node)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = ComarkFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode?, right: ASTNode?) = ParserDefinition.SpaceRequirements.MAY

    companion object {
        /**
         * Stub file type: the Markdown plugin's header stubs (used to resolve `#anchor` links across files) are built for Comark
         * files too. The version follows the parser: bump it whenever the tree shape changes.
         */
        @JvmField
        val FILE = object : IStubFileElementType<PsiFileStub<ComarkFile>>("ComarkFile", ComarkLanguage) {
            override fun getStubVersion(): Int = 1

            override fun getExternalId(): String = "comark.file"
        }
    }
}
