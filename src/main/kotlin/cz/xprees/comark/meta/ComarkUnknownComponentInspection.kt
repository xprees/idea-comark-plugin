package cz.xprees.comark.meta

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.elementType
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.parser.ComarkTypes
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

/**
 * Flags block and inline components that no metadata source knows. Off by default and only a weak warning: a project can
 * render components the plugin cannot see (registered by a module, resolved at runtime), so it only speaks up when at least one
 * component was discovered, which means discovery works for this project at all.
 *
 * Registered for Markdown so its visitor also sees the Markdown paragraphs of Comark files, where inline components live.
 * Markdown injected into code fences (examples) is not checked.
 */
class ComarkUnknownComponentInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? ComarkFile ?: return PsiElementVisitor.EMPTY_VISITOR
        // Markdown examples in code fences of a Comark file are Comark too, but their components are illustrations
        if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return PsiElementVisitor.EMPTY_VISITOR
        val index = ComarkComponentService.getInstance(holder.project).index()
        if (index.isEmpty) return PsiElementVisitor.EMPTY_VISITOR
        val context = file.originalFile.virtualFile
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val type = element.elementType
                when {
                    type == ComarkTypes.COMPONENT_NAME -> check(element, element.text, TextRange(0, element.textLength))
                    type == MarkdownElementTypes.PARAGRAPH || type == MarkdownElementTypes.TABLE_CELL || type == ComarkTypes.INLINE_CONTENT ||
                        element is MarkdownHeader -> checkInline(element)
                }
            }

            private fun checkInline(element: PsiElement) {
                val text = file.viewProvider.contents
                val range = element.textRange
                for (component in InlineComponentSyntax.findAll(text, range.startOffset, range.endOffset)) {
                    // `:smile:` is an emoji shortcode when comark's emoji plugin is on (its rule runs before components)
                    if (component.contentOpen < 0 && component.attributes == null && text.getOrNull(component.nameEnd) == ':') continue
                    val leaf = file.findElementAt(component.nameStart) ?: continue
                    if (ComponentUsages.isInsideNonContent(leaf)) continue
                    check(element, component.name(text), TextRange(component.nameStart, component.nameEnd).shiftLeft(range.startOffset))
                }
            }

            private fun check(element: PsiElement, name: String, rangeInElement: TextRange) {
                if (index.find(name, context) != null || isHtmlElement(name)) return
                holder.registerProblem(element, rangeInElement, ComarkBundle.message("inspection.unknown.component.message", name))
            }
        }
    }

    companion object {
        /**
         * Standard HTML element names (Vue's `isHTMLTag` list and `search`) plus the `svg` and `math` roots. Comark renders a
         * component with such a name as the plain element (`::div`, `:br`, `::table`), so it needs no component. Other SVG and
         * MathML element names (`text`, `image`, `path`, ...) are left out: they only mean something inside `svg` / `math` and
         * are common component names.
         */
        private val HTML_ELEMENTS = (
            "html,body,base,head,link,meta,style,title,address,article,aside,footer,header,hgroup,h1,h2,h3,h4,h5,h6,nav,section," +
                "div,dd,dl,dt,figcaption,figure,picture,hr,img,li,main,ol,p,pre,ul,a,b,abbr,bdi,bdo,br,cite,code,data,dfn,em,i,kbd," +
                "mark,q,rp,rt,ruby,s,samp,small,span,strong,sub,sup,time,u,var,wbr,area,audio,map,track,video,embed,object,param," +
                "source,canvas,script,noscript,del,ins,caption,col,colgroup,table,thead,tbody,td,th,tr,button,datalist,fieldset,form," +
                "input,label,legend,meter,optgroup,option,output,progress,select,textarea,details,dialog,menu,summary,template," +
                "blockquote,iframe,tfoot,search,svg,math"
            ).split(',').toSet()

        /** Whether [name] is a standard HTML element name (case-insensitive), which Comark renders without a component. */
        fun isHtmlElement(name: String): Boolean = name.lowercase() in HTML_ELEMENTS
    }
}
