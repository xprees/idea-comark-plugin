package cz.xprees.comark.preview

/**
 * The preview stylesheet for components (`comark-preview.css`).
 *
 * It is inlined into the rendered page as a `<style>` element rather than contributed through an extension point: the
 * Markdown plugin's `previewStylesProvider` extension point is internal and not read by the preview, and browser preview
 * extensions are obsolete API. The preview's content security policy allows inline styles.
 */
internal object ComarkPreviewStyles {
    val css: String by lazy {
        ComarkPreviewStyles::class.java.getResource("/cz/xprees/comark/preview/comark-preview.css")?.readText().orEmpty()
    }
}
