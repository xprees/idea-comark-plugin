package cz.xprees.comark.images

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.util.ProcessingContext
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownImage
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDefinition
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/**
 * Image references ([ComarkImageReference]) on the destinations of Markdown images in Comark files: `![alt](/x.webp "title")` and
 * reference definitions used by images (`[hero]: /x.webp` for `![alt][hero]`, or any definition of an image file). Links to other
 * pages and relative paths are left to the Markdown plugin and [cz.xprees.comark.navigation.ComarkRouteReferenceContributor].
 */
class ComarkImageReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(MarkdownLinkDestination::class.java), Provider())
    }

    private class Provider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            if (element !is MarkdownLinkDestination) return PsiReference.EMPTY_ARRAY
            val file = element.containingFile
            if (!ComarkImageReferences.enabled(file)) return PsiReference.EMPTY_ARRAY
            val range = ElementManipulators.getValueTextRange(element)
            val raw = range.substring(element.text)
            if (!isImageDestination(element, raw)) return PsiReference.EMPTY_ARRAY
            val document = ComarkImageReferences.contextOf(file) ?: return PsiReference.EMPTY_ARRAY
            val reference = ComarkImageReferences.create(element, range, raw, document) ?: return PsiReference.EMPTY_ARRAY
            return arrayOf(reference)
        }
    }

    companion object {
        /**
         * True for the destination of an image (`IMAGE > INLINE_LINK > LINK_DESTINATION`, by element type: the inline link PSI class
         * is experimental) or of a reference definition that an image uses or that names an image file.
         */
        fun isImageDestination(destination: MarkdownLinkDestination, raw: String): Boolean {
            val parent = destination.parent ?: return false
            if (parent.elementType == MarkdownElementTypes.INLINE_LINK) return parent.parent is MarkdownImage
            if (parent !is MarkdownLinkDefinition) return false
            if (ImagePaths.isImageName(raw.substringBefore('?').substringBefore('#').trimEnd('>'))) return true
            // not `linkLabel`, which throws when the definition is malformed
            val label = parent.children.firstOrNull { it.elementType == MarkdownElementTypes.LINK_LABEL } ?: return false
            return normalizeLabel(label.text) in imageLabels(destination.containingFile)
        }

        /** Labels (normalised) of the reference-style images in [file]: `![alt][label]` and `![label]`. */
        private fun imageLabels(file: PsiFile): Set<String> = CachedValuesManager.getCachedValue(file) {
            val labels = HashSet<String>()
            for (image in PsiTreeUtil.findChildrenOfType(file, MarkdownImage::class.java)) {
                for (link in image.children) {
                    if (link.elementType != MarkdownElementTypes.FULL_REFERENCE_LINK && link.elementType != MarkdownElementTypes.SHORT_REFERENCE_LINK) continue
                    link.children.firstOrNull { it.elementType == MarkdownElementTypes.LINK_LABEL }?.let { labels += normalizeLabel(it.text) }
                }
            }
            CachedValueProvider.Result.create<Set<String>>(labels, file)
        }

        /** Labels match case-insensitively, with runs of whitespace collapsed (CommonMark). */
        private fun normalizeLabel(label: String): String = label.removePrefix("[").removeSuffix("]").trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
