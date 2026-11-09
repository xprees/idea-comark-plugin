package cz.xprees.comark.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.lang.ComarkProjectDetector
import java.net.URI

/** Settings | Languages & Frameworks | Comark. */
class ComarkConfigurable : BoundSearchableConfigurable(ComarkBundle.message("settings.display.name"), "cz.xprees.comark.settings") {
    private val settings get() = ComarkSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group(ComarkBundle.message("settings.group.files")) {
            buttonsGroup(ComarkBundle.message("settings.mode.label")) {
                row {
                    radioButton(ComarkBundle.message("settings.mode.auto"), ComarkSettings.Mode.AUTO)
                        .comment(ComarkBundle.message("settings.mode.auto.comment"))
                }
                row { radioButton(ComarkBundle.message("settings.mode.always"), ComarkSettings.Mode.ALWAYS) }
                row { radioButton(ComarkBundle.message("settings.mode.never"), ComarkSettings.Mode.NEVER) }
            }.bind(settings::mode)
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
    }

    override fun apply() {
        val modeBefore = settings.mode
        super.apply()
        if (settings.mode != modeBefore) {
            // Which Markdown files are Comark just changed: drop cached decisions and re-parse open files in every project.
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) ComarkProjectDetector.getInstance(project).invalidate()
            }
        }
    }

    private fun isHttpUrl(text: String): Boolean = try {
        val uri = URI(text)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }
}
