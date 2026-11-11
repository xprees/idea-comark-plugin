package cz.xprees.comark.images

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parents
import com.intellij.util.ProcessingContext
import cz.xprees.comark.psi.ComarkPropsContent
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Image references ([ComarkImageReference]) on YAML values of Comark documents: image-like props in props blocks (`image: /x.webp`)
 * and image keys of the front matter (`cover`, `og:image`, `seo.image`, `image.src`, see [ImageKeys.isImageKeyPath]). Both are YAML
 * injected into the document (props blocks by [cz.xprees.comark.editor.yaml.ComarkPropsYamlInjector], the front matter by the
 * Markdown plugin); without the YAML plugin the Comark file hosts text-based references instead ([ComarkImageReferences.fileHosted]).
 *
 * Registered only when the YAML plugin is installed (`comark-yaml.xml`).
 */
class ComarkImageYamlReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLScalar::class.java), Provider())
    }

    private class Provider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            if (element !is YAMLScalar || element.isMultiline) return PsiReference.EMPTY_ARRAY
            val yaml = element.containingFile ?: return PsiReference.EMPTY_ARRAY
            val host = InjectedLanguageManager.getInstance(element.project).getInjectionHost(yaml) ?: return PsiReference.EMPTY_ARRAY
            if (host !is ComarkPropsContent && host.elementType != MarkdownElementTypes.FRONT_MATTER_HEADER) return PsiReference.EMPTY_ARRAY
            if (!ComarkImageReferences.enabled(host.containingFile)) return PsiReference.EMPTY_ARRAY
            val keys = element.parents(withSelf = false).filterIsInstance<YAMLKeyValue>().map { it.keyText }.toList().asReversed()
            if (!ImageKeys.isImageKeyPath(keys)) return PsiReference.EMPTY_ARRAY
            val document = ComarkImageReferences.contextOf(yaml) ?: return PsiReference.EMPTY_ARRAY
            val range = ElementManipulators.getValueTextRange(element)
            val reference = ComarkImageReferences.create(element, range, range.substring(element.text), document) ?: return PsiReference.EMPTY_ARRAY
            return arrayOf(reference)
        }
    }
}
