package cz.xprees.comark.highlighting

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import cz.xprees.comark.images.ComarkImageReference
import cz.xprees.comark.images.ComarkImageReferences
import cz.xprees.comark.images.ImagePaths
import cz.xprees.comark.images.NuxtImageDirectories
import cz.xprees.comark.lang.ComarkProjectDetector
import cz.xprees.comark.navigation.ComarkContentRoutes
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/**
 * Silences Markdown inspections that misread Comark syntax as Markdown links.
 *
 * - "Unresolved link label": the Markdown parser takes `[text]` for a reference link, so spans (`Hello [World]{.primary}`) and
 *   inline component content followed by a span (`:badge[New][label]`) are reported once link labels are validated. In Comark
 *   those brackets never refer to a link definition. Only labels that the inline scanner identifies as spans or component
 *   content are suppressed, so genuine reference links (`[text][ref]`) keep being checked.
 * - "Unresolved header reference": links to anchors that Comark attributes define (`{#id}`, or a heading's id computed without
 *   its trailing attributes, see [ComarkAnchors]) are unknown to the Markdown plugin.
 * - "Unresolved file reference": in a Nuxt Content package, absolute links without a file extension (`/guide/install`) are page
 *   routes, not file paths (`content/1.guide/2.install.md`). Routes of content files resolve through
 *   [cz.xprees.comark.navigation.ComarkRouteReference], while the Markdown plugin's own file references on them never do; routes
 *   that match no content file may still exist (custom pages, collection prefixes). Neither is reported. Links with an extension
 *   (`/images/logo.png`) are still checked.
 *   Absolute image paths (`![](/threats/x.webp)`) are served from Nuxt's public and Nuxt Image directories, which the Markdown plugin
 *   doesn't know: they resolve through [cz.xprees.comark.images.ComarkImageReference], and in Nuxt projects missing ones are left
 *   to the missing-image inspection, so the Markdown plugin's warning is dropped there. Paths under a Nuxt Image `image.alias` are remote
 *   images and are not reported either. Outside Nuxt projects only paths that resolve
 *   (through configured image directories) are suppressed; relative paths are always checked.
 */
class ComarkMarkdownInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean = when (toolId) {
        UNRESOLVED_LINK_LABEL -> {
            val file = element.containingFile as? ComarkFile
            file != null && ComarkInlineSyntax.isCoveredBySpans(file, element.textRange)
        }

        UNRESOLVED_HEADER_REFERENCE -> element is MarkdownLinkDestination && ComarkAnchors.isComarkAnchor(element, element.text)
        UNRESOLVED_FILE_REFERENCE -> isRouteLink(element) || isNuxtImagePath(element)
        else -> false
    }

    private fun isRouteLink(element: PsiElement): Boolean {
        val file = element.containingFile as? ComarkFile ?: return false
        val destination = PsiTreeUtil.getParentOfType(element, MarkdownLinkDestination::class.java, false) ?: return false
        ComarkContentRoutes.parseRouteLink(destination.text, ElementManipulators.getValueTextRange(destination)) ?: return false
        val virtualFile = file.originalFile.virtualFile ?: return false
        return ComarkProjectDetector.getInstance(file.project).isComarkContext(virtualFile)
    }

    private fun isNuxtImagePath(element: PsiElement): Boolean {
        val destination = PsiTreeUtil.getParentOfType(element, MarkdownLinkDestination::class.java, false) ?: return false
        val reference = destination.references.filterIsInstance<ComarkImageReference>().firstOrNull()
            ?: return isImageAlias(destination)
        return NuxtImageDirectories.getInstance(element.project).isNuxt(reference.context) || reference.resolve() != null
    }

    /** True for a path under a Nuxt Image `image.alias` (`/unsplash/photo.jpg`): a remote image, which has no image reference. */
    private fun isImageAlias(destination: MarkdownLinkDestination): Boolean {
        val file = destination.containingFile
        if (!ComarkImageReferences.enabled(file)) return false
        val context = ComarkImageReferences.contextOf(file) ?: return false
        val roots = NuxtImageDirectories.getInstance(file.project).forFile(context)?.takeIf { it.aliases.isNotEmpty() } ?: return false
        val value = ElementManipulators.getValueTextRange(destination).substring(destination.text)
        return ImagePaths.normalize(value, roots.baseURL, roots.cdnURL, roots.aliases)?.remoteUrl != null
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY

    companion object {
        const val UNRESOLVED_LINK_LABEL = "MarkdownUnresolvedLinkLabel"
        const val UNRESOLVED_HEADER_REFERENCE = "MarkdownUnresolvedHeaderReference"
        const val UNRESOLVED_FILE_REFERENCE = "MarkdownUnresolvedFileReference"
    }
}
