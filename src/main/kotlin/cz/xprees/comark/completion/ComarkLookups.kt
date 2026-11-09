package cz.xprees.comark.completion

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentsOfType
import com.intellij.util.DocumentUtil
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.meta.ComarkComponent
import cz.xprees.comark.meta.ComarkProp
import cz.xprees.comark.meta.ComponentIndex
import cz.xprees.comark.meta.ComponentNaming
import cz.xprees.comark.meta.PropKind
import cz.xprees.comark.navigation.ComarkLookupObject
import cz.xprees.comark.navigation.ComarkSymbol
import cz.xprees.comark.parser.ComarkSyntax
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile

/**
 * Turns a [CompletionSite] into lookup items: component names (block and inline), props as attributes (with `:binding`
 * variants for values that are not strings), slots and props-block keys. Items carry a [ComarkLookupObject] so the
 * documentation popup can describe them, show the prop type / requiredness / component source, and put required props first.
 */
object ComarkLookups {
    private const val REQUIRED_PRIORITY = 10.0
    private const val PROP_PRIORITY = 5.0
    private const val BINDING_PRIORITY = 1.0

    fun add(site: CompletionSite, index: ComponentIndex, context: PsiElement, result: CompletionResultSet) {
        val contextFile = context.containingFile?.originalFile?.virtualFile
        val sink = result.withPrefixMatcher(site.prefix)
        when (site) {
            is CompletionSite.BlockName -> index.visible(contextFile).forEach { sink.addElement(componentItem(it, context, site, block = true)) }
            is CompletionSite.InlineName -> index.visible(contextFile).forEach { sink.addElement(componentItem(it, context, site, block = false)) }
            is CompletionSite.Attribute -> index.find(site.componentName, contextFile)?.let { component ->
                for (prop in component.props) {
                    if (ComponentNaming.looseKey(prop.name) in site.existing) continue
                    attributeItems(component, prop, context).forEach(sink::addElement)
                }
            }

            is CompletionSite.Slot -> index.find(site.componentName, contextFile)?.let { component ->
                for (slot in component.slots) {
                    if (slot.name in site.existing) continue
                    sink.addElement(
                        LookupElementBuilder.create(ComarkLookupObject(context, ComarkSymbol(component, slot = slot)), slot.name)
                            .withIcon(ComarkSymbol(component, slot = slot).icon)
                            .withTailText(slot.description?.let { " " + summary(it) }, true)
                            .withTypeText(component.kebabName),
                    )
                }
            }

            is CompletionSite.PropsKey -> index.find(site.componentName, contextFile)?.let { component ->
                for (prop in component.props) {
                    if (ComponentNaming.looseKey(prop.name) in site.existing) continue
                    sink.addElement(prioritized(propItem(component, prop, context, prop.attributeName).withInsertHandler(YamlKeyInsertHandler(prop, site.indent)), prop))
                }
            }
        }
    }

    // ---- Components -------------------------------------------------------------------------------------------------

    private fun componentItem(component: ComarkComponent, context: PsiElement, site: CompletionSite, block: Boolean): LookupElement {
        val pascalTyped = site.prefix.firstOrNull()?.isUpperCase() == true
        return LookupElementBuilder.create(ComarkLookupObject(context, ComarkSymbol(component)), component.kebabName)
            .withLookupStrings(component.allNames + component.allNames.map { ComponentNaming.kebabCase(it) })
            .withIcon(ComarkSymbol(component).icon)
            .withTailText(component.description?.let { " " + summary(it) }, true)
            .withTypeText(sourceText(component), true)
            .withInsertHandler(ComponentInsertHandler(component, pascalTyped, block))
    }

    private fun sourceText(component: ComarkComponent): String {
        val file = component.file ?: return component.source.displayName
        val root = component.root
        val relative = root?.let { VfsUtilCore.getRelativePath(file, it) }
        return relative ?: file.name
    }

    /**
     * Inserts the component name (PascalCase when the user started typing PascalCase) and, for a block component that is not
     * closed yet, the closing marker with an empty line for the content, like the VS Code extension's snippet.
     */
    private class ComponentInsertHandler(private val component: ComarkComponent, private val pascal: Boolean, private val block: Boolean) :
        InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val document = context.document
            if (pascal) {
                document.replaceString(context.startOffset, context.tailOffset, component.name)
                context.editor.caretModel.moveToOffset(context.tailOffset)
            }
            if (!block) return
            val tail = context.tailOffset
            val lineEnd = DocumentUtil.getLineEndOffset(tail, document)
            val chars = document.immutableCharSequence
            if (!ComarkSyntax.isBlank(chars, tail, lineEnd)) return
            val nextLineStart = lineEnd + 1
            if (nextLineStart < chars.length) {
                val nextLineEnd = DocumentUtil.getLineEndOffset(nextLineStart, document)
                if (chars.subSequence(nextLineStart, nextLineEnd).trim() == "---") return // a props block follows: just a rename
            }
            context.commitDocument()
            val file = context.file as? ComarkFile ?: return
            val element = file.findElementAt(context.startOffset) ?: return
            val opened = PsiTreeUtil.getParentOfType(element, ComarkBlockComponent::class.java) ?: return
            if (opened.nameElement?.textRange?.startOffset != context.startOffset) return
            // Unclosed, or closed by a marker that belonged to an enclosing component (which is now unclosed itself).
            if (opened.closeMarker != null && opened.parentsOfType<ComarkBlockComponent>(withSelf = false).none { it.closeMarker == null }) return
            val lineStart = DocumentUtil.getLineStartOffset(tail, document)
            val indent = chars.subSequence(lineStart, ComarkSyntax.skipSpaces(chars, lineStart, lineEnd)).toString()
            val markers = ":".repeat(opened.markerCount)
            document.insertString(lineEnd, "\n$indent\n$indent$markers")
            context.editor.caretModel.moveToOffset(lineEnd + 1 + indent.length)
        }
    }

    // ---- Props ------------------------------------------------------------------------------------------------------

    private fun propItem(component: ComarkComponent, prop: ComarkProp, context: PsiElement, lookup: String): LookupElementBuilder =
        LookupElementBuilder.create(ComarkLookupObject(context, ComarkSymbol(component, prop = prop)), lookup)
            .withLookupString(lookup.removePrefix(":").let { ComponentNaming.camelCase(it) }.let { if (lookup.startsWith(":")) ":$it" else it })
            .withIcon(ComarkSymbol(component, prop = prop).icon)
            .withTypeText(prop.displayType?.let { StringUtil.shortenTextWithEllipsis(it, 40, 0) })
            .withTailText(propTail(prop), true)
            .withBoldness(prop.required)

    /** ` (required)` or ` = default`, then the first sentence of the description: `title (required) Heading shown above`. */
    private fun propTail(prop: ComarkProp): String? {
        val requirement = when {
            prop.required -> " " + ComarkBundle.message("completion.prop.required")
            prop.defaultValue != null -> " = " + StringUtil.shortenTextWithEllipsis(prop.defaultValue, 30, 0)
            else -> ""
        }
        val description = prop.description?.let { "  " + summary(it) }.orEmpty()
        return (requirement + description).ifEmpty { null }
    }

    /**
     * `name=""` (bare `name` for booleans) and, for props that are not plain strings, the `:name=""` binding whose value Comark
     * parses as JSON (numbers, booleans, arrays, objects).
     */
    private fun attributeItems(component: ComarkComponent, prop: ComarkProp, context: PsiElement): List<LookupElement> {
        val kind = prop.kind
        val items = ArrayList<LookupElement>(2)
        items += prioritized(propItem(component, prop, context, prop.attributeName).withInsertHandler(AttributeInsertHandler(kind != PropKind.BOOLEAN)), prop)
        if (kind != PropKind.STRING) {
            val binding = propItem(component, prop, context, ":" + prop.attributeName).withInsertHandler(AttributeInsertHandler(true))
            items += PrioritizedLookupElement.withPriority(binding, BINDING_PRIORITY)
        }
        return items
    }

    private fun prioritized(item: LookupElementBuilder, prop: ComarkProp): LookupElement =
        PrioritizedLookupElement.withPriority(item, if (prop.required) REQUIRED_PRIORITY else PROP_PRIORITY)

    /** Adds `=""` with the caret between the quotes, unless the attribute already has a value. */
    private class AttributeInsertHandler(private val withValue: Boolean) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            if (!withValue) return
            val chars = context.document.immutableCharSequence
            val tail = context.tailOffset
            if (tail < chars.length && chars[tail] == '=') return
            context.document.insertString(tail, "=\"\"")
            context.editor.caretModel.moveToOffset(tail + 2)
        }
    }

    /** Adds `: ` and a value skeleton matching the prop type (`true`, a list item, a nested mapping), unless a `:` follows. */
    private class YamlKeyInsertHandler(private val prop: ComarkProp, private val indent: String) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val document = context.document
            val chars = document.immutableCharSequence
            val keyEnd = context.tailOffset
            var tail = keyEnd
            while (tail < chars.length && (chars[tail] == ' ' || chars[tail] == '\t')) tail++
            if (tail < chars.length && chars[tail] == ':') return
            val nested = "\n$indent  "
            val (text, caret) = when (prop.kind) {
                PropKind.BOOLEAN -> ": true" to ": true".length
                PropKind.ARRAY -> ":$nested- " to (":$nested- ").length
                PropKind.OBJECT -> ":$nested" to (":$nested").length
                else -> ": " to 2
            }
            document.insertString(keyEnd, text)
            context.editor.caretModel.moveToOffset(keyEnd + caret)
        }
    }

    /** First sentence (or line) of a description, short enough for a lookup item's tail. */
    internal fun summary(description: String): String {
        val firstLine = description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val sentence = firstLine.substringBefore(". ").removeSuffix(".")
        return StringUtil.shortenTextWithEllipsis(sentence, 60, 0)
    }
}
