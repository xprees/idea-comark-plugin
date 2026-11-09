package cz.xprees.comark.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.util.TextRange
import com.intellij.psi.FileViewProvider
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.ILeafElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import cz.xprees.comark.ComarkIcons
import cz.xprees.comark.lang.ComarkFlavour
import cz.xprees.comark.parser.ComarkElementType
import cz.xprees.comark.parser.ComarkTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import javax.swing.Icon

/**
 * A Comark document. It is a [MarkdownFile] (so every Markdown editor feature that checks for one keeps working) whose language
 * is [cz.xprees.comark.lang.ComarkLanguage], found by `PsiFileBase` through the dialect relationship.
 */
class ComarkFile(viewProvider: FileViewProvider) : MarkdownFile(viewProvider, ComarkFlavour.INSTANCE) {
    /** Top level block components, in document order. */
    val components: List<ComarkBlockComponent>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ComarkBlockComponent::class.java)

    override fun toString(): String = "ComarkFile:$name"
}

/** `::name{attrs} ... ::` */
class ComarkBlockComponent(node: ASTNode) : ASTWrapperPsiElement(node) {
    val header: ComarkComponentHeader? get() = findChildByClass(ComarkComponentHeader::class.java)

    val nameElement: PsiElement? get() = header?.nameElement

    /** Component name as written (`my-component`, `MyComponent`, ...). */
    val componentName: String? get() = nameElement?.text

    val propsBlock: ComarkPropsBlock? get() = findChildByClass(ComarkPropsBlock::class.java)

    val slots: List<ComarkSlot> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ComarkSlot::class.java)

    val closeMarker: PsiElement? get() = node.findChildByType(ComarkTypes.COMPONENT_CLOSE)?.psi

    /** Number of colons of the opening marker (2 for top level `::`, 3 for nested `:::`, ...). */
    val markerCount: Int get() = header?.markerElement?.textLength ?: 2

    val attributes: ComarkAttributes? get() = header?.attributes

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = componentName ?: "component"

        override fun getLocationString(): String? = attributes?.text

        override fun getIcon(unused: Boolean): Icon = ComarkIcons.COMPONENT
    }

    override fun toString(): String = "ComarkBlockComponent(${componentName})"
}

/** The opener line content: marker, name, optional `[content]` and `{attrs}`. */
class ComarkComponentHeader(node: ASTNode) : ASTWrapperPsiElement(node) {
    val markerElement: PsiElement? get() = node.findChildByType(ComarkTypes.COMPONENT_MARKER)?.psi

    val nameElement: PsiElement? get() = node.findChildByType(ComarkTypes.COMPONENT_NAME)?.psi

    val attributes: ComarkAttributes? get() = findChildByClass(ComarkAttributes::class.java)

    override fun toString(): String = "ComarkComponentHeader"
}

/** `---` YAML block giving a component its props. */
class ComarkPropsBlock(node: ASTNode) : ASTWrapperPsiElement(node) {
    val content: ComarkPropsContent? get() = node.findChildByType(ComarkTypes.PROPS_CONTENT)?.psi as? ComarkPropsContent

    override fun toString(): String = "ComarkPropsBlock"
}

/** `#name` slot and its content. */
class ComarkSlot(node: ASTNode) : ASTWrapperPsiElement(node) {
    val nameElement: PsiElement? get() = node.findChildByType(ComarkTypes.SLOT_NAME)?.psi

    val slotName: String? get() = nameElement?.text

    val component: ComarkBlockComponent? get() = parent as? ComarkBlockComponent

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = "#" + (slotName ?: "")

        override fun getIcon(unused: Boolean): Icon? = null
    }

    override fun toString(): String = "ComarkSlot(${slotName})"
}

/** `{.class #id key="value" bool}` */
class ComarkAttributes(node: ASTNode) : ASTWrapperPsiElement(node) {
    val attributeList: List<ComarkAttribute> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ComarkAttribute::class.java)

    override fun toString(): String = "ComarkAttributes"
}

/** `key="value"` or a boolean `key` */
class ComarkAttribute(node: ASTNode) : ASTWrapperPsiElement(node) {
    val nameElement: PsiElement? get() = node.findChildByType(ComarkTypes.ATTR_NAME)?.psi

    /** Name without a leading `:` binding prefix. */
    val attributeName: String? get() = nameElement?.text?.removePrefix(":")

    val isBinding: Boolean get() = nameElement?.text?.startsWith(":") == true

    val valueElement: PsiElement? get() = node.findChildByType(ComarkTypes.ATTR_VALUE)?.psi

    override fun toString(): String = "ComarkAttribute(${nameElement?.text})"
}

/** Leaf type of the YAML text inside a props block; its leaves are injection hosts for YAML. */
class ComarkPropsContentElementType : ComarkElementType("PROPS_CONTENT"), ILeafElementType {
    override fun createLeafNode(leafText: CharSequence): LeafElement = ComarkPropsContent(this, leafText)
}

class ComarkPropsContent(type: ComarkPropsContentElementType, text: CharSequence) : LeafPsiElement(type, text), PsiLanguageInjectionHost {
    override fun isValidHost(): Boolean = true

    override fun updateText(text: String): PsiLanguageInjectionHost = replaceWithText(text) as PsiLanguageInjectionHost

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> =
        LiteralTextEscaper.createSimple(this, false)

    override fun toString(): String = "ComarkPropsContent"
}

internal val PsiElement.isComarkName get() = elementType == ComarkTypes.COMPONENT_NAME

internal fun PsiElement.rangeInParent(): TextRange = TextRange.from(startOffsetInParent, textLength)
