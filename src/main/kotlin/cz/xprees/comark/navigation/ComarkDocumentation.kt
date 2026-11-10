package cz.xprees.comark.navigation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.meta.ComarkComponent
import cz.xprees.comark.meta.ComarkComponentService
import cz.xprees.comark.meta.ComarkProp
import cz.xprees.comark.meta.ComarkSlotInfo
import cz.xprees.comark.meta.ComponentUsages
import cz.xprees.comark.psi.ComarkFile
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Quick documentation (Ctrl+Q, hover) for component names, props and slots at an offset of a Comark document, including inline
 * components inside Markdown paragraphs and keys of a props block (injected as YAML or not).
 */
class ComarkDocumentationTargetProvider : DocumentationTargetProvider, DumbAware {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (file !is ComarkFile && ComponentUsages.propsHost(file) == null) return emptyList()
        val usage = ComponentUsages.find(file, offset) ?: return emptyList()
        val index = ComarkComponentService.getInstance(file.project).index()
        val component = index.find(usage.componentName, ComponentUsages.contextFile(file)) ?: return emptyList()
        val symbol = ComarkSymbol.of(component, usage) ?: return emptyList()
        return listOf(ComarkDocumentationTarget(file.project, symbol))
    }
}

/** Documentation of completion items and navigation targets, which stand for a [ComarkSymbol] ([ComarkSymbolElement]). */
class ComarkPsiDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? =
        (element as? ComarkSymbolElement)?.let { ComarkDocumentationTarget(element.project, it.symbol) }
}

/**
 * The documentation of a [ComarkSymbol]. The symbol is an immutable snapshot of discovered metadata (no PSI), so a hard pointer
 * to the target is safe. The popup's "go to source" opens the component's `.vue` file, at the prop or slot when its position
 * is known.
 */
class ComarkDocumentationTarget(private val project: Project, val symbol: ComarkSymbol) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override val navigatable: Navigatable?
        get() {
            val file = symbol.component.file?.takeIf { it.isValid } ?: return null
            val offset = symbol.prop?.offset ?: symbol.slot?.offset ?: -1
            return if (offset >= 0) OpenFileDescriptor(project, file, offset) else OpenFileDescriptor(project, file)
        }

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(symbol.presentableName)
        .icon(symbol.icon)
        .containerText(if (symbol.prop != null || symbol.slot != null) symbol.component.kebabName else null)
        .presentation()

    override fun computeDocumentationHint(): String = ComarkDocumentationRenderer.hint(symbol)

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(ComarkDocumentationRenderer.render(symbol))
}

/** HTML for component, prop and slot documentation in the platform's documentation markup. */
object ComarkDocumentationRenderer {
    fun render(symbol: ComarkSymbol): String = when {
        symbol.prop != null -> renderProp(symbol.component, symbol.prop)
        symbol.slot != null -> renderSlot(symbol.component, symbol.slot)
        else -> renderComponent(symbol.component)
    }

    /** One-line summary for the Ctrl+hover hint. */
    fun hint(symbol: ComarkSymbol): String {
        val builder = HtmlBuilder()
        when {
            symbol.prop != null -> {
                builder.append(HtmlChunk.text(symbol.prop.attributeName).bold())
                symbol.prop.displayType?.let { builder.append(": ").append(it) }
                builder.append(HtmlChunk.text("  " + symbol.component.kebabName).wrapWith(DocumentationMarkup.GRAYED_ELEMENT))
            }

            symbol.slot != null -> builder.append(HtmlChunk.text("#" + symbol.slot.name).bold())
                .append(HtmlChunk.text("  " + symbol.component.kebabName).wrapWith(DocumentationMarkup.GRAYED_ELEMENT))

            else -> {
                builder.append(HtmlChunk.text(symbol.component.kebabName).bold())
                symbol.component.description?.let { builder.append(" – ").append(it.lineSequence().first()) }
            }
        }
        return builder.toString()
    }

    private fun renderComponent(component: ComarkComponent): String {
        val definition = HtmlBuilder().append(HtmlChunk.text("::" + component.kebabName).bold())
        if (component.name != component.kebabName) definition.append(HtmlChunk.text("  " + component.name).wrapWith(DocumentationMarkup.GRAYED_ELEMENT))

        val content = HtmlBuilder()
        component.description?.let { content.appendRaw(markdownToHtml(it)) }
        component.documentation?.takeIf { it != component.description }?.let { content.appendRaw(markdownToHtml(it)) }
        if (component.props.isNotEmpty()) {
            content.append(HtmlChunk.p().child(HtmlChunk.text(ComarkBundle.message("documentation.props")).bold()))
            content.append(propsTable(component.props))
        }
        if (component.slots.isNotEmpty()) {
            content.append(HtmlChunk.p().child(HtmlChunk.text(ComarkBundle.message("documentation.slots")).bold()))
            content.append(slotsList(component.slots))
        }

        val sections = HtmlBuilder()
        sections.append(section(ComarkBundle.message("documentation.source"), HtmlChunk.text(sourceText(component))))
        component.docsUrl?.let { url -> sections.append(section(ComarkBundle.message("documentation.docs"), HtmlChunk.link(url, url))) }
        return document(definition, content, sections)
    }

    private fun renderProp(component: ComarkComponent, prop: ComarkProp): String {
        val definition = HtmlBuilder().append(HtmlChunk.text(prop.attributeName).bold())
        prop.displayType?.let { definition.append(": ").append(it) }
        val content = HtmlBuilder()
        prop.description?.let { content.appendRaw(markdownToHtml(it)) }
        val sections = HtmlBuilder()
        sections.append(section(ComarkBundle.message("documentation.component"), HtmlChunk.text(component.kebabName)))
        sections.append(
            section(
                ComarkBundle.message("documentation.required"),
                HtmlChunk.text(ComarkBundle.message(if (prop.required) "documentation.yes" else "documentation.no")),
            ),
        )
        prop.defaultValue?.let { sections.append(section(ComarkBundle.message("documentation.default"), HtmlChunk.text(it).code())) }
        if (prop.name != prop.attributeName) sections.append(section(ComarkBundle.message("documentation.declared.as"), HtmlChunk.text(prop.name).code()))
        return document(definition, content, sections)
    }

    private fun renderSlot(component: ComarkComponent, slot: ComarkSlotInfo): String {
        val definition = HtmlBuilder().append(HtmlChunk.text("#" + slot.name).bold())
        val content = HtmlBuilder()
        slot.description?.let { content.appendRaw(markdownToHtml(it)) }
        val sections = HtmlBuilder()
        sections.append(section(ComarkBundle.message("documentation.component"), HtmlChunk.text(component.kebabName)))
        slot.type?.takeIf { it.isNotBlank() && it != "{}" && it != "any" }?.let {
            sections.append(section(ComarkBundle.message("documentation.slot.props"), HtmlChunk.text(it).code()))
        }
        return document(definition, content, sections)
    }

    private fun propsTable(props: List<ComarkProp>): HtmlChunk {
        val header = HtmlChunk.tag("tr").children(
            listOf("documentation.prop.name", "documentation.prop.type", "documentation.prop.required", "documentation.prop.default")
                .map { HtmlChunk.tag("th").attr("align", "left").addText(ComarkBundle.message(it)) },
        )
        val rows = props.map { prop ->
            HtmlChunk.tag("tr").children(
                HtmlChunk.tag("td").child(HtmlChunk.text(prop.attributeName).code()),
                HtmlChunk.tag("td").child(prop.displayType?.let { HtmlChunk.text(it).code() } ?: HtmlChunk.empty()),
                HtmlChunk.tag("td").addText(if (prop.required) ComarkBundle.message("documentation.yes") else ""),
                HtmlChunk.tag("td").child(prop.defaultValue?.let { HtmlChunk.text(it).code() } ?: HtmlChunk.empty()),
            )
        }
        val descriptions = props.filter { it.description != null }.map { prop ->
            HtmlChunk.tag("li").children(HtmlChunk.text(prop.attributeName).code(), HtmlChunk.text(" – " + prop.description!!.lineSequence().first()))
        }
        val table = HtmlChunk.tag("table").children(listOf(header) + rows)
        return if (descriptions.isEmpty()) table else HtmlChunk.fragment(table, HtmlChunk.tag("ul").children(descriptions))
    }

    private fun slotsList(slots: List<ComarkSlotInfo>): HtmlChunk = HtmlChunk.tag("ul").children(
        slots.map { slot ->
            val item = HtmlChunk.tag("li").child(HtmlChunk.text("#" + slot.name).code())
            slot.description?.let { item.addText(" – " + it.lineSequence().first()) } ?: item
        },
    )

    private fun section(title: String, content: HtmlChunk): HtmlChunk = HtmlChunk.tag("tr").children(
        DocumentationMarkup.SECTION_HEADER_CELL.child(HtmlChunk.text("$title:")),
        DocumentationMarkup.SECTION_CONTENT_CELL.child(content),
    )

    private fun document(definition: HtmlBuilder, content: HtmlBuilder, sections: HtmlBuilder): String {
        val result = HtmlBuilder()
        result.append(definition.wrapWith(DocumentationMarkup.PRE_ELEMENT).wrapWith(DocumentationMarkup.DEFINITION_ELEMENT))
        if (!content.isEmpty) result.append(content.wrapWith(DocumentationMarkup.CONTENT_ELEMENT))
        if (!sections.isEmpty) result.append(sections.wrapWith(DocumentationMarkup.SECTIONS_TABLE))
        return result.toString()
    }

    private fun sourceText(component: ComarkComponent): String {
        val file = component.file ?: return component.source.displayName
        val relative = component.root?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
        return "$relative (${component.source.displayName})"
    }

    /** Descriptions and documentation are Markdown (JSDoc, nuxt-component-meta, remote `documentation_markdown`). */
    internal fun markdownToHtml(markdown: String): String {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour, false).generateHtml().removePrefix("<body>").removeSuffix("</body>")
    }
}
