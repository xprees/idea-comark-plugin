package cz.xprees.comark.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import cz.xprees.comark.meta.ComarkComponentService
import cz.xprees.comark.meta.ComponentUsages
import cz.xprees.comark.psi.ComarkBlockComponent
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.settings.ComarkSettings

/**
 * Component completion in Comark documents: block component names after `::`, inline component names after `:`, props inside
 * a component's `{...}` attributes, slot names after `#` and keys of a props block that is not injected as YAML.
 *
 * Registered for the Markdown language so it sees both the Comark tokens and the Markdown elements of a Comark file (inline
 * text is Markdown PSI); it does nothing outside Comark files. Sites are recognised on the original file, not on the completion
 * copy: the Markdown plugin's dummy identifier ends with a line break, which would split the line being completed.
 */
class ComarkComponentCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                if (!ComarkSettings.getInstance().componentCompletion) return
                val file = parameters.originalFile as? ComarkFile ?: return
                // a ```md fence of a Comark file is injected as Comark: no component completion in code examples
                if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return
                val text = parameters.editor.document.immutableCharSequence
                val site = ComarkCompletionSites.detect(file, text, parameters.offset) ?: return
                val index = ComarkComponentService.getInstance(file.project).index()
                if (index.isEmpty) return
                ComarkLookups.add(site, index, file, result)
            }
        })
    }
}

/**
 * Completion of prop keys in a component's `---` props block when the block is injected as YAML: the caret is then in a YAML
 * file, so the Markdown contributor above does not run. The injection host tells which component the block belongs to.
 */
class ComarkPropsYamlCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                if (!ComarkSettings.getInstance().componentCompletion) return
                val yaml = parameters.originalFile
                val host = ComponentUsages.propsHost(yaml) ?: return
                val component = host.parent?.parent as? ComarkBlockComponent ?: return
                val site = ComarkCompletionSites.propsSite(component, yaml.viewProvider.contents, parameters.offset) ?: return
                val index = ComarkComponentService.getInstance(yaml.project).index()
                if (index.isEmpty) return
                ComarkLookups.add(site, index, host.containingFile, result)
            }
        })
    }
}
