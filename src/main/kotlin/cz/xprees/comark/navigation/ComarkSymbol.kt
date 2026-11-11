package cz.xprees.comark.navigation

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import cz.xprees.comark.ComarkIcons
import cz.xprees.comark.meta.ComarkComponent
import cz.xprees.comark.meta.ComarkProp
import cz.xprees.comark.meta.ComarkSlotInfo
import cz.xprees.comark.meta.ComponentUsage
import javax.swing.Icon

/** A component, or one of its props or slots: what a lookup item, documentation popup or navigation target is about. */
class ComarkSymbol(val component: ComarkComponent, val prop: ComarkProp? = null, val slot: ComarkSlotInfo? = null) {
    /** How the symbol is written in Markdown: `prose-alert`, `icon-name` or `#footer`. */
    val presentableName: String
        get() = when {
            prop != null -> prop.attributeName
            slot != null -> "#${slot.name}"
            else -> component.kebabName
        }

    val icon: Icon
        get() = when {
            prop != null -> AllIcons.Nodes.Property
            slot != null -> AllIcons.Nodes.Tag
            else -> ComarkIcons.COMPONENT
        }

    companion object {
        /** The symbol a usage refers to in [component], or null when the component has no such prop / slot. */
        fun of(component: ComarkComponent, usage: ComponentUsage): ComarkSymbol? = when (usage) {
            is ComponentUsage.Name -> ComarkSymbol(component)
            is ComponentUsage.Prop -> component.findProp(usage.propName)?.let { ComarkSymbol(component, prop = it) }
            is ComponentUsage.Slot -> component.findSlot(usage.slotName)?.let { ComarkSymbol(component, slot = it) }
        }
    }
}

/** A PSI element standing for a [ComarkSymbol]; documentation is provided for it by [ComarkPsiDocumentationTargetProvider]. */
interface ComarkSymbolElement {
    val symbol: ComarkSymbol
}

/**
 * The object of a completion item. Being a PSI element makes the platform ask [ComarkPsiDocumentationTargetProvider] for its
 * documentation when the item is selected with the documentation popup open. [context] is the file completion runs in.
 */
class ComarkLookupObject(private val context: PsiElement, override val symbol: ComarkSymbol) : FakePsiElement(), ComarkSymbolElement {
    override fun getParent(): PsiElement = context

    override fun getName(): String = symbol.presentableName

    override fun getIcon(open: Boolean): Icon = symbol.icon

    override fun equals(other: Any?): Boolean =
        other is ComarkLookupObject && other.symbol.component === symbol.component && other.symbol.prop === symbol.prop && other.symbol.slot === symbol.slot

    override fun hashCode(): Int = symbol.presentableName.hashCode()
}

/**
 * A navigation target at an offset of a source file (a prop declaration or `<slot>` in a `.vue` file). The platform navigates
 * to [getTextOffset] of [getContainingFile]; wrapping the place instead of returning the PSI leaf there keeps navigation precise
 * even when the file's language has no fine-grained PSI (`.vue` files without the Vue plugin are plain text).
 */
class ComarkSourceElement(
    private val file: PsiFile,
    private val offset: Int,
    private val length: Int,
    override val symbol: ComarkSymbol,
) : FakePsiElement(), ComarkSymbolElement {
    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getTextOffset(): Int = offset

    override fun getTextRange(): TextRange = TextRange(offset, offset + length)

    override fun getName(): String = symbol.presentableName

    override fun getLocationString(): String = file.name

    override fun getIcon(open: Boolean): Icon = symbol.icon

    override fun isValid(): Boolean = file.isValid

    override fun equals(other: Any?): Boolean = other is ComarkSourceElement && other.file == file && other.offset == offset

    override fun hashCode(): Int = file.hashCode() * 31 + offset

    override fun toString(): String = "ComarkSourceElement(${file.name}:$offset ${symbol.presentableName})"
}
