package cz.xprees.comark.format.studio

/**
 * The Nuxt Studio text-path formatter T(x): what Nuxt Studio 1.8.0 writes when a document is saved from its code editor
 * (`contentFromMarkdownDocument(documentFromContent(x))`), computed without Node: [StudioTreeBuilder] builds the comark
 * tree and [StudioPipeline] renders it.
 */
object StudioFormatter {
    /** T([text]), or `null` when Studio's text path would throw (invalid YAML, for example) or this port cannot follow it. */
    fun format(text: String): String? = try {
        StudioPipeline.render(StudioTreeBuilder.build(text))
    } catch (_: StudioTextPathException) {
        null
    } catch (_: StudioUnsupportedException) {
        null
    }
}
