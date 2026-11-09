package cz.xprees.comark.preview

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import cz.xprees.comark.lang.ComarkLanguage
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanelProvider
import org.intellij.plugins.markdown.ui.preview.SourceTextPreprocessor
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil
import org.intellij.plugins.markdown.ui.preview.jcef.JCEFHtmlPanelProvider

/**
 * Makes the Markdown preview render Comark components.
 *
 * The preview turns a document into HTML with its panel provider's [SourceTextPreprocessor], and the built-in (JCEF) one parses
 * the text with the Markdown plugin's fixed default flavour: neither the file's flavour nor any extension point is consulted, so a
 * Comark document would be rendered as if it were plain Markdown (component markers as text, indented component content as code
 * blocks). This provider stands in for the JCEF provider: it reports the JCEF provider's identity (so the preview, which picks the
 * provider by the identity stored in the Markdown settings, picks this one, ordered first) and delegates the panel itself to it,
 * but its preprocessor renders Comark files with [ComarkPreviewRenderer]. Every other file is passed to the JCEF preprocessor
 * unchanged, so plain Markdown previews are exactly what they were.
 *
 * Side effect: the Markdown settings page lists the JCEF preview twice; both entries are the same choice.
 */
class ComarkPreviewPanelProvider : MarkdownHtmlPanelProvider() {
    /** The provider this one stands in for, or null when the IDE has none (then this provider is never chosen). */
    private val delegate: MarkdownHtmlPanelProvider?
        get() = getProviders().firstOrNull { it is JCEFHtmlPanelProvider }

    override fun createHtmlPanel(): MarkdownHtmlPanel = requireDelegate().createHtmlPanel()

    override fun createHtmlPanel(project: Project, virtualFile: VirtualFile): MarkdownHtmlPanel =
        requireDelegate().createHtmlPanel(project, virtualFile)

    override fun isAvailable(): AvailabilityInfo = delegate?.isAvailable() ?: AvailabilityInfo.UNAVAILABLE

    override fun getProviderInfo(): ProviderInfo = delegate?.providerInfo ?: ProviderInfo("Comark", javaClass.name)

    override fun getSourceTextPreprocessor(): SourceTextPreprocessor =
        ComarkSourceTextPreprocessor(delegate?.sourceTextPreprocessor ?: SourceTextPreprocessor.default)

    private fun requireDelegate(): MarkdownHtmlPanelProvider = checkNotNull(delegate) { "The JCEF Markdown preview is not available" }
}

/**
 * Renders Comark files with [ComarkPreviewRenderer] (Markdown snippets by the Markdown plugin's own [MarkdownUtil]) and every other
 * file with [fallback]. Produces the same page shape as the JCEF preprocessor (`<html><head></head><body>...</body></html>`).
 */
internal class ComarkSourceTextPreprocessor(private val fallback: SourceTextPreprocessor) : SourceTextPreprocessor {
    override fun preprocessText(project: Project, document: Document, file: VirtualFile): String {
        if (!isComarkFile(project, file)) return fallback.preprocessText(project, document, file)
        val text = document.immutableCharSequence.toString()
        val body = ComarkPreviewRenderer(text) { markdown -> MarkdownUtil.generateMarkdownHtml(file, markdown, project) }.renderBody()
        return "<html><head></head>$body</html>"
    }

    private fun isComarkFile(project: Project, file: VirtualFile): Boolean = ReadAction.computeBlocking<Boolean, RuntimeException> {
        !project.isDisposed && file.isValid && PsiManager.getInstance(project).findViewProvider(file)?.baseLanguage == ComarkLanguage
    }
}
