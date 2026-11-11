package cz.xprees.comark.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import javax.swing.JRadioButton
import com.intellij.ui.components.JBCheckBox
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.images.NuxtImageDirectories
import cz.xprees.comark.lang.ComarkExclusions
import cz.xprees.comark.lang.ComarkProjectDetector
import java.net.URI

/** Settings | Languages & Frameworks | Comark. */
class ComarkConfigurable : BoundSearchableConfigurable(ComarkBundle.message("settings.display.name"), "cz.xprees.comark.settings") {
    private val settings get() = ComarkSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group(ComarkBundle.message("settings.group.files")) {
            lateinit var auto: Cell<JRadioButton>
            buttonsGroup(ComarkBundle.message("settings.mode.label")) {
                row {
                    auto = radioButton(ComarkBundle.message("settings.mode.auto"), ComarkSettings.Mode.AUTO)
                        .comment(ComarkBundle.message("settings.mode.auto.comment"))
                }
                indent {
                    row(ComarkBundle.message("settings.content.config")) {
                        textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                            .align(AlignX.FILL)
                            .bindText(settings::contentConfigPath)
                            .comment(ComarkBundle.message("settings.content.config.comment"))
                    }.enabledIf(auto.selected)
                }
                row { radioButton(ComarkBundle.message("settings.mode.always"), ComarkSettings.Mode.ALWAYS) }
                row { radioButton(ComarkBundle.message("settings.mode.never"), ComarkSettings.Mode.NEVER) }
            }.bind(settings::mode)
            row {
                label(ComarkBundle.message("settings.excluded.label"))
            }
            row {
                textArea()
                    .applyToComponent { rows = 4 }
                    .align(AlignX.FILL)
                    .bindText(settings::excludedPatterns)
                    .comment(ComarkBundle.message("settings.excluded.comment"))
                    .validationOnApply {
                        val invalid = ComarkExclusions.invalidPatterns(it.text)
                        if (invalid.isEmpty()) null else error(ComarkBundle.message("settings.excluded.invalid", invalid.joinToString(", ")))
                    }
            }
        }
        group(ComarkBundle.message("settings.group.dialect")) {
            buttonsGroup(ComarkBundle.message("settings.dialect.label")) {
                row {
                    radioButton(ComarkBundle.message("settings.dialect.nuxt"), ComarkSettings.Dialect.NUXT_CONTENT)
                        .comment(ComarkBundle.message("settings.dialect.nuxt.comment"))
                }
                row {
                    radioButton(ComarkBundle.message("settings.dialect.comark"), ComarkSettings.Dialect.COMARK)
                        .comment(ComarkBundle.message("settings.dialect.comark.comment"))
                }
            }.bind(settings::dialect)
        }
        group(ComarkBundle.message("settings.group.formatting")) {
            row {
                checkBox(ComarkBundle.message("settings.formatter.enabled"))
                    .bindSelected(settings::formatterEnabled)
                    .comment(ComarkBundle.message("settings.formatter.enabled.comment"))
            }
        }
        group(ComarkBundle.message("settings.group.components")) {
            row {
                checkBox(ComarkBundle.message("settings.completion.enabled"))
                    .bindSelected(settings::componentCompletion)
                    .comment(ComarkBundle.message("settings.completion.enabled.comment"))
            }
            row(ComarkBundle.message("settings.metadata.url")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(settings::componentMetadataUrl)
                    .comment(ComarkBundle.message("settings.metadata.url.comment"))
                    .validationOnApply {
                        val text = it.text.trim()
                        if (text.isEmpty() || isHttpUrl(text)) null else error(ComarkBundle.message("settings.metadata.url.invalid"))
                    }
            }
            row(ComarkBundle.message("settings.metadata.cache")) {
                spinner(0..24 * 60, 5).bindIntValue(settings::componentMetadataCacheMinutes)
                    .gap(com.intellij.ui.dsl.builder.RightGap.SMALL)
                label(ComarkBundle.message("settings.metadata.cache.unit"))
            }
        }
        group(ComarkBundle.message("settings.group.images")) {
            lateinit var enabled: Cell<JBCheckBox>
            row {
                enabled = checkBox(ComarkBundle.message("settings.images.enabled"))
                    .bindSelected(settings::imagesEnabled)
                    .applyToComponent { name = IMAGES_ENABLED }
                    .comment(ComarkBundle.message("settings.images.enabled.comment"))
            }
            row {
                label(ComarkBundle.message("settings.images.directories.label"))
            }.enabledIf(enabled.selected)
            row {
                textArea()
                    .applyToComponent {
                        rows = 3
                        name = IMAGE_DIRECTORIES
                    }
                    .align(AlignX.FILL)
                    .bindText(settings::imageDirectories)
                    .comment(ComarkBundle.message("settings.images.directories.comment"))
            }.enabledIf(enabled.selected)
            row(ComarkBundle.message("settings.images.cdn")) {
                textField()
                    .align(AlignX.FILL)
                    .applyToComponent { name = IMAGE_CDN_URL }
                    .bindText(settings::imageCdnUrl)
                    .comment(ComarkBundle.message("settings.images.cdn.comment"))
                    .validationOnApply {
                        if (isCdnUrl(it.text)) null else error(ComarkBundle.message("settings.images.cdn.invalid"))
                    }
            }.enabledIf(enabled.selected)
            row {
                checkBox(ComarkBundle.message("settings.images.cdn.preview"))
                    .bindSelected(settings::previewImagesFromCdn)
                    .applyToComponent { name = PREVIEW_FROM_CDN }
                    .comment(ComarkBundle.message("settings.images.cdn.preview.comment"))
            }.enabledIf(enabled.selected)
            row(ComarkBundle.message("settings.images.detected")) {
                // Not bound: what the resolver currently finds for the project in front (the setting itself is application-wide).
                comment(detectedImageDirectories()).applyToComponent { name = DETECTED_DIRECTORIES }
            }
        }
    }

    override fun apply() {
        val modeBefore = settings.mode
        val excludedBefore = settings.excludedPatterns
        val configBefore = settings.contentConfigPath
        val dialectBefore = settings.dialect
        val imagesBefore = imageSettings()
        super.apply()
        if (settings.mode != modeBefore || settings.excludedPatterns != excludedBefore || settings.contentConfigPath != configBefore ||
            settings.dialect != dialectBefore
        ) {
            // Which Markdown files are Comark just changed: drop cached decisions and re-parse open files in every project.
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) ComarkProjectDetector.getInstance(project).invalidate()
            }
        }
        if (imageSettings() != imagesBefore) {
            // Image directories, the CDN URL or the switch changed: re-resolve image paths and re-highlight in every project. An open
            // preview picks the change up on its next update (an edit, or reopening it): the preview offers no public way to re-render.
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) NuxtImageDirectories.getInstance(project).invalidate()
            }
        }
    }

    private fun imageSettings() = listOf(settings.imagesEnabled, settings.imageDirectories, settings.imageCdnUrl, settings.previewImagesFromCdn)

    /** The detected image directories of the focused project (or the first open one), for the read-only line of the Images group. */
    private fun detectedImageDirectories(): String {
        val project = imageProject() ?: return ComarkBundle.message("settings.images.detected.none")
        val label = NuxtImageDirectories.getInstance(project).detectedLabel().ifEmpty { ComarkBundle.message("settings.images.detected.none") }
        // The comment is HTML: settings entries and project names may hold '<' or '&'.
        return ComarkBundle.message("settings.images.detected.value", StringUtil.escapeXmlEntities(label), StringUtil.escapeXmlEntities(project.name))
    }

    private fun imageProject(): Project? {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }
        val focused = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        return focused?.takeIf { it in open } ?: open.firstOrNull()
    }

    /** Empty, an http(s) URL, or a protocol-relative `//host/...` URL as `app.cdnURL` allows. */
    internal fun isCdnUrl(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isEmpty() || isHttpUrl(trimmed) || trimmed.startsWith("//") && isHttpUrl("https:$trimmed")
    }

    private fun isHttpUrl(text: String): Boolean = try {
        val uri = URI(text)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }

    internal companion object {
        // Component names, so tests (and UI inspectors) can find the image fields.
        const val IMAGES_ENABLED = "comark.images.enabled"
        const val IMAGE_DIRECTORIES = "comark.images.directories"
        const val IMAGE_CDN_URL = "comark.images.cdn"
        const val PREVIEW_FROM_CDN = "comark.images.cdn.preview"
        const val DETECTED_DIRECTORIES = "comark.images.detected"
    }
}
