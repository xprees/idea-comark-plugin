package cz.xprees.comark.images

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.util.text.EditDistance
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.psi.ComarkAttributeValue
import cz.xprees.comark.psi.ComarkFile
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination
import kotlin.math.abs
import kotlin.math.max

/**
 * Reports site-absolute image paths ([ComarkImageReference]s) that no image directory of the Nuxt project serves: `public/` of the
 * app and its layers, the Nuxt Image directory, or the directories configured in the settings, see [NuxtImageDirectories]. Remote
 * URLs, `image.alias` paths and relative paths have no reference and are never reported; neither are paths outside detected Nuxt
 * projects, where a path may be served by something the plugin doesn't know. The Markdown plugin's own unresolved file warning on
 * the same destinations is suppressed ([cz.xprees.comark.highlighting.ComarkMarkdownInspectionSuppressor]), so a missing image is
 * reported once, with a quick fix to the closest existing path.
 *
 * Registered twice with the same class: for Markdown (image destinations, attribute values and the references the Comark file holds
 * itself) and, in `comark-yaml.xml`, for the YAML injected into props blocks and the front matter. It uses no YAML API: the YAML
 * references come from [ComarkImageYamlReferenceContributor].
 */
class ComarkMissingImageInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        val comark = file as? ComarkFile
            ?: InjectedLanguageManager.getInstance(file.project).getInjectionHost(file)?.containingFile as? ComarkFile
            ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!ComarkImageReferences.enabled(comark)) return PsiElementVisitor.EMPTY_VISITOR
        val context = ComarkImageReferences.contextOf(comark) ?: return PsiElementVisitor.EMPTY_VISITOR
        val directories = NuxtImageDirectories.getInstance(file.project)
        val roots = directories.forFile(context)?.takeIf { it.isNuxt } ?: return PsiElementVisitor.EMPTY_VISITOR
        val searched = roots.dirs.joinToString(", ") { it.label }.ifEmpty { ComarkBundle.message("inspection.missing.image.no.directories") }
        val inYaml = file !is ComarkFile

        fun check(references: Collection<*>) {
            for (reference in references) {
                if (reference !is ComarkImageReference || reference.resolveFile() != null) continue
                // build output (`/_nuxt/…`) is generated, not served from an image directory
                if (reference.src.path.startsWith("/_nuxt/")) continue
                val fixes = closest(reference.src.path, directories.listImages(context))?.let { arrayOf<LocalQuickFix>(ComarkReplaceImagePathFix(it)) }
                    ?: LocalQuickFix.EMPTY_ARRAY
                holder.registerProblem(
                    reference.element, reference.rangeInElement,
                    ComarkBundle.message("inspection.missing.image.message", reference.src.path, searched), *fixes,
                )
            }
        }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                // in injected YAML only the scalars of image keys have image references; in the Comark file only these hosts do
                if (inYaml || element is MarkdownLinkDestination || element is ComarkAttributeValue) check(element.references.asList())
            }

            override fun visitFile(psiFile: PsiFile) {
                if (psiFile is ComarkFile) check(ComarkImageReferences.fileHosted(psiFile))
            }
        }
    }

    companion object {
        /**
         * The existing image path closest to the missing [path] by edit distance, when it is close enough to be a typo or a moved
         * file (at most a third of the path's length, and at least 3 edits allowed); null when nothing is that close.
         */
        internal fun closest(path: String, images: List<ImageEntry>): String? {
            val limit = max(3, path.length / 3)
            var best: String? = null
            var bestDistance = limit + 1
            for (entry in images) {
                if (abs(entry.path.length - path.length) >= bestDistance) continue
                val distance = EditDistance.levenshtein(path, entry.path, true)
                if (distance < bestDistance) {
                    best = entry.path
                    bestDistance = distance
                }
            }
            return best
        }
    }
}

/** Replaces a missing image path with [path], an existing one; prefixes and `?query` / `#hash` outside the path are kept. */
class ComarkReplaceImagePathFix(private val path: String) : LocalQuickFix {
    override fun getName(): String = ComarkBundle.message("inspection.missing.image.fix", path)

    override fun getFamilyName(): String = ComarkBundle.message("inspection.missing.image.fix.family")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val range = descriptor.textRangeInElement ?: return
        val written = range.substring(element.text)
        ComarkImageReferences.replace(element, range, ComarkImageReferences.encode(path, keepSpaces = ' ' in written))
    }
}
