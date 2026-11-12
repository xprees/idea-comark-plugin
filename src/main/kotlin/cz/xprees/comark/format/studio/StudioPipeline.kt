package cz.xprees.comark.format.studio

/**
 * What Nuxt Studio 1.8.0 writes for a Markdown document: the tail of `contentFromMarkdownDocument`
 * (`dist/module/runtime/utils/document/generate.js`),
 *
 * ```js
 * const markdown = await renderMarkdown(body, { blockAttributesStyle: 'frontmatter' })
 * return padBlankLinesInComponents(markdown) + '\n'
 * ```
 *
 * given the comark tree `body` (after Studio's `unbindComarkTree`) and its front matter.
 */
object StudioPipeline {
    fun render(document: StudioDocument, observer: StudioRenderer.ComponentObserver? = null): String =
        StudioPostProcess.padBlankLinesInComponents(StudioRenderer.renderMarkdown(document, observer)) + "\n"

    fun render(nodes: List<StudioNode>, frontmatter: Map<String, Any?>): String =
        render(StudioDocument(frontmatter = frontmatter, nodes = nodes))
}
