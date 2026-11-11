package cz.xprees.comark.images

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parents
import com.intellij.util.ProcessingContext
import cz.xprees.comark.completion.ComarkCompletionSites
import cz.xprees.comark.completion.CompletionSite
import cz.xprees.comark.lang.NuxtPaths
import cz.xprees.comark.psi.ComarkFile
import cz.xprees.comark.psi.ComarkPropsContent
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

/**
 * Where an image path is being typed: [prefix] is the text typed so far, starting at [prefixStart]; lookup items replace it.
 * [destination] marks Markdown destinations, where the Markdown plugin offers its own path variants too.
 */
class ImageCompletionSite(val prefixStart: Int, val prefix: String, val destination: Boolean)

/**
 * Completion of Nuxt image paths (`/threats/phishing.png`) in Comark documents: Markdown image destinations and reference
 * definitions, image-like component attributes (`{image="/…"}`), image-like keys of props blocks and of the front matter.
 *
 * Every image the document can use is offered once, from the directory that wins ([NuxtImageDirectories.listImages]), with a
 * thumbnail and the directory as gray type text. Sites are recognised on the original text: the Markdown plugin's dummy identifier
 * ends with a line break, and the value being typed is often not parsed as a destination yet.
 */
object ComarkImageCompletion {
    private val IMAGE_DESTINATION = Regex("""!\[[^\]\n]*]\([ \t]*<?(/[^)\s>]*)$""")
    private val REFERENCE_DEFINITION = Regex("""^[ \t]{0,3}\[[^\]\n]+]:[ \t]*<?(/\S*)$""")

    /** Elements whose text holds no image paths to complete. */
    private val NO_IMAGES = TokenSet.create(
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.INLINE_MATH,
        MarkdownElementTypes.BLOCK_MATH,
        MarkdownElementTypes.HTML_BLOCK,
        MarkdownElementTypes.AUTOLINK,
        MarkdownElementTypes.LINK_COMMENT,
        MarkdownTokenTypes.HTML_TAG,
    )

    /** The image completion site at [offset] of the Comark [file] whose current text is [text], or null. */
    fun site(file: ComarkFile, text: CharSequence, offset: Int): ImageCompletionSite? {
        if (offset < 0 || offset > text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val probe = file.findElementAt(if (offset > lineStart) offset - 1 else offset) ?: file.findElementAt(offset)
        // props blocks and the front matter hold YAML; when it is injected, the YAML contributor below handles them
        val yamlHost = listOf(file.findElementAt(offset), probe).firstNotNullOfOrNull { element ->
            element?.parents(withSelf = true)?.firstOrNull { it is ComarkPropsContent || it.elementType == MarkdownElementTypes.FRONT_MATTER_HEADER }
        }
        if (yamlHost != null) {
            if (isInjected(yamlHost)) return null
            val start = yamlHost.textRange.startOffset
            return yamlSite(yamlHost.text, offset - start)?.let { ImageCompletionSite(it.prefixStart + start, it.prefix, false) }
        }
        if (probe != null && probe.parents(withSelf = true).any { it.elementType in NO_IMAGES }) return null
        val before = text.subSequence(lineStart, offset).toString()
        (IMAGE_DESTINATION.find(before) ?: REFERENCE_DEFINITION.find(before))?.let { match ->
            val prefix = match.groupValues[1]
            return ImageCompletionSite(offset - prefix.length, prefix, destination = true)
        }
        val value = ComarkCompletionSites.detect(file, text, offset) as? CompletionSite.PropValue ?: return null
        if (value.binding || !ImageKeys.isImageProp(value.propName) || !isPathPrefix(value.prefix)) return null
        return ImageCompletionSite(value.prefixStart, value.prefix, destination = false)
    }

    /** The image value typed at [offset] of YAML [text] (a props block or the front matter), when its key is image-like. */
    fun yamlSite(text: CharSequence, offset: Int): ImageCompletionSite? {
        val site = ImageValueLocator.yamlValueSite(text, offset) ?: return null
        if (!ImageKeys.isImageKeyPath(site.keys) || !isPathPrefix(site.prefix)) return null
        return ImageCompletionSite(site.prefixStart, site.prefix, destination = false)
    }

    /**
     * Adds the image lookups for [site] to [result]. In destinations the remaining contributors (the Markdown plugin's path
     * completion) run through a filter, so an image offered here is not listed a second time as one of their variants.
     */
    fun addTo(parameters: CompletionParameters, result: CompletionResultSet, site: ImageCompletionSite, context: VirtualFile, project: Project) {
        val items = lookups(context, project, site.prefix, keepSpaces = !site.destination)
        if (items.isEmpty()) return
        val sink = result.withPrefixMatcher(site.prefix)
        items.forEach(sink::addElement)
        if (!site.destination) return
        val strings = items.mapTo(HashSet()) { it.lookupString }
        val dirs = NuxtImageDirectories.getInstance(project).forFile(context)?.dirs.orEmpty()
        result.runRemainingContributors(parameters) { other ->
            val element = other.lookupElement
            // also an image the list leaves out because another directory shadows it: Nuxt never serves that one
            val image = (element.psiElement as? PsiFileSystemItem)?.virtualFile?.takeIf(ImagePaths::isImageFile)
            val ours = image != null && dirs.any { NuxtPaths.isUnder(image.path, it.path) }
            if (element.lookupString !in strings && !ours) result.passResult(other)
        }
    }

    /**
     * One lookup per image the documents like [context] can use. The lookup string is the path as it is written (with the
     * `baseURL` in front when [prefix] starts with it, `%`-encoded; spaces stay only when [keepSpaces]); the object is the
     * [ImageEntry].
     */
    fun lookups(context: VirtualFile, project: Project, prefix: String, keepSpaces: Boolean): List<LookupElementBuilder> {
        val directories = NuxtImageDirectories.getInstance(project)
        val roots = directories.forFile(context) ?: return emptyList()
        val base = roots.baseURL?.trim()?.trim('/').orEmpty().let { if (it.isEmpty()) "" else "/$it" }
        val withBase = base.isNotEmpty() && (prefix == base || prefix.startsWith("$base/"))
        val thumbnails = ComarkImageThumbnails.getInstance()
        return directories.listImages(context).map { entry ->
            val written = (if (withBase) base else "") + ComarkImageReferences.encode(entry.path, keepSpaces)
            LookupElementBuilder.create(entry, written)
                .withIcon(thumbnails.cachedIcon(entry.file) ?: ComarkImageThumbnails.GENERIC)
                .withTypeText(entry.dir.label, true)
                .withExpensiveRenderer(ThumbnailRenderer)
        }
    }

    /** Decodes the thumbnail and reads the pixel size off the UI thread, once the item is shown. */
    private object ThumbnailRenderer : LookupElementRenderer<LookupElement>() {
        override fun renderElement(element: LookupElement, presentation: LookupElementPresentation) {
            element.renderElement(presentation)
            val file = (element.`object` as? ImageEntry)?.file?.takeIf { it.isValid } ?: return
            val thumbnails = ComarkImageThumbnails.getInstance()
            presentation.icon = thumbnails.icon(file)
            thumbnails.dimensions(file)?.let { presentation.setTailText(" ${it.width}×${it.height}", true) }
        }
    }

    /** Image paths are site-absolute: nothing typed yet, or a `/…` path. */
    private fun isPathPrefix(prefix: String) = prefix.isEmpty() || prefix.startsWith("/")

    private fun isInjected(host: PsiElement): Boolean =
        host is PsiLanguageInjectionHost && !InjectedLanguageManager.getInstance(host.project).getInjectedPsiFiles(host).isNullOrEmpty()
}

/**
 * Image path completion in the Markdown text of Comark documents (destinations, attribute values, props blocks and front matter
 * that are not injected as YAML). Registered `order="first"` so it can filter the Markdown plugin's duplicate path variants.
 */
class ComarkImageCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val file = parameters.originalFile as? ComarkFile ?: return
                if (!ComarkImageReferences.enabled(file)) return
                if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return
                val site = ComarkImageCompletion.site(file, parameters.editor.document.immutableCharSequence, parameters.offset) ?: return
                val document = ComarkImageReferences.contextOf(file) ?: return
                ComarkImageCompletion.addTo(parameters, result, site, document, file.project)
            }
        })
    }
}
