package cz.xprees.comark.images

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.elementType
import com.intellij.util.ProcessingContext
import cz.xprees.comark.psi.ComarkPropsContent
import org.intellij.plugins.markdown.lang.MarkdownElementTypes

/**
 * Image path completion in YAML injected into a Comark document: values of image-like keys in props blocks (`cover: /…`) and in
 * the front matter (`image: /…`, `seo.image`, `og:image`; see [ImageKeys.isImageKeyPath]). With the caret in an injection the
 * Markdown contributor does not run, so this one covers those places.
 *
 * Works on the injected text only (no YAML PSI) but is registered for the YAML language, so only from `comark-yaml.xml`.
 */
class ComarkImageYamlCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val yaml = parameters.originalFile
                val host = InjectedLanguageManager.getInstance(yaml.project).getInjectionHost(yaml) ?: return
                if (host !is ComarkPropsContent && host.elementType != MarkdownElementTypes.FRONT_MATTER_HEADER) return
                if (!ComarkImageReferences.enabled(host.containingFile)) return
                val site = ComarkImageCompletion.yamlSite(yaml.viewProvider.contents, parameters.offset) ?: return
                val document = ComarkImageReferences.contextOf(yaml) ?: return
                ComarkImageCompletion.addTo(parameters, result, site, document, yaml.project)
            }
        })
    }
}
