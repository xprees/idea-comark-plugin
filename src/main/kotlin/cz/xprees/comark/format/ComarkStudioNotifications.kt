package cz.xprees.comark.format

import com.intellij.application.options.CodeStyle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.lang.ComarkLanguage
import cz.xprees.comark.psi.ComarkFile
import java.util.function.Function
import javax.swing.JComponent

/**
 * A banner on Comark files formatted in a Studio based style when the package uses another nuxt-studio (or comark) release than
 * the one the style reproduces ([ComarkStudioVersions]). *Dismiss* hides it for that version in this project; a project that
 * moves to yet another version sees it again.
 */
class ComarkStudioVersionNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val mismatch = mismatch(project, file) ?: return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = ComarkBundle.message("studio.version.banner", StudioStyleAdapter.STUDIO_VERSION, StudioStyleAdapter.COMARK_VERSION, mismatch)
                createActionLabel(ComarkBundle.message("studio.version.banner.dismiss")) {
                    dismiss(project, mismatch)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }
    }

    companion object {
        private const val DISMISSED = "comark.studio.version.dismissed"

        /**
         * The versions to show for [file] (`nuxt-studio 1.9.2`), or null: not a Comark file, its style is not Studio based, the
         * package doesn't depend on nuxt-studio or uses the pinned versions, or the banner was dismissed for these versions.
         */
        fun mismatch(project: Project, file: VirtualFile): String? {
            if (project.isDisposed || !file.isValid) return null
            val usesStudio = ReadAction.compute<Boolean, RuntimeException> {
                val psi = PsiManager.getInstance(project).findFile(file)
                psi is ComarkFile && ComarkStyle.of(psi).usesStudio
            }
            if (!usesStudio) return null
            val mismatches = ComarkStudioVersions.getInstance(project).dependenciesFor(file)?.mismatches.orEmpty()
            if (mismatches.isEmpty()) return null
            val text = mismatches.joinToString(", ")
            return text.takeUnless { it in PropertiesComponent.getInstance(project).getList(DISMISSED).orEmpty() }
        }

        fun dismiss(project: Project, mismatch: String) {
            val properties = PropertiesComponent.getInstance(project)
            properties.setList(DISMISSED, properties.getList(DISMISSED).orEmpty() + mismatch)
        }
    }
}

/**
 * Offers the Nuxt Studio style once a project that depends on nuxt-studio is opened while its code style still uses the VS Code
 * preset: *Use Nuxt Studio style* switches the project code style to it (moving the project to a project scheme, stored in
 * `.idea/codeStyles`, if it used an IDE scheme). The notification is shown once per project: *Not now*, *Don't ask again* and
 * closing it all leave it at that. A project whose `.editorconfig` sets the style preset (`ij_comark_style_preset`) has made its
 * choice there and is not asked.
 */
object ComarkStudioAdoption {
    private const val NEVER = "comark.studio.adoption.never"
    private const val SHOWN = "comark.studio.adoption.shown"
    private const val GROUP = "Comark Nuxt Studio"
    private const val NODE_MODULES = "/node_modules/"

    /** True when the notification should be shown for [project]. Needs a read action and indexes. */
    fun shouldOffer(project: Project): Boolean {
        if (project.isDisposed) return false
        val properties = PropertiesComponent.getInstance(project)
        return !properties.getBoolean(NEVER) && !properties.getBoolean(SHOWN) &&
            projectPreset(project) == ComarkStylePreset.VSCODE && !editorConfigSetsPreset(project) && dependsOnNuxtStudio(project)
    }

    private val PRESET_KEY = Regex("^[ \t]*ij_comark_style_preset[ \t]*=", RegexOption.MULTILINE)

    /** Whether an `.editorconfig` of the project (outside `node_modules`) sets the Comark style preset. */
    fun editorConfigSetsPreset(project: Project): Boolean =
        FilenameIndex.getVirtualFilesByName(".editorconfig", GlobalSearchScope.projectScope(project)).any { file ->
            !file.path.contains(NODE_MODULES) && try {
                PRESET_KEY.containsMatchIn(VfsUtilCore.loadText(file))
            } catch (_: Exception) {
                false
            }
        }

    /** Records that the notification was shown for [project], so it is not shown again. */
    fun markShown(project: Project) = PropertiesComponent.getInstance(project).setValue(SHOWN, true)

    /** The preset of the project's code style (EditorConfig not included). */
    fun projectPreset(project: Project): ComarkStylePreset = ComarkCodeStyleSettings.getInstance(CodeStyle.getSettings(project)).STYLE_PRESET

    /** Whether a `package.json` of the project (outside `node_modules`) lists nuxt-studio as a dependency. */
    fun dependsOnNuxtStudio(project: Project): Boolean =
        FilenameIndex.getVirtualFilesByName("package.json", GlobalSearchScope.projectScope(project)).any { file ->
            !file.path.contains(NODE_MODULES) && try {
                StudioVersionParser.packageJsonRange(VfsUtilCore.loadText(file), ComarkStudioVersions.STUDIO) != null
            } catch (_: Exception) {
                false
            }
        }

    /** Selects the Nuxt Studio preset in the project's code style (and turns off wrap on typing, as the Style tab does). */
    fun useNuxtStudioStyle(project: Project) {
        val manager = CodeStyleSettingsManager.getInstance(project)
        val current = manager.mainProjectCodeStyle
        val settings = if (manager.USE_PER_PROJECT_SETTINGS && current != null) {
            current
        } else {
            manager.cloneSettings(CodeStyle.getSettings(project)).also {
                manager.setMainProjectCodeStyle(it)
                manager.USE_PER_PROJECT_SETTINGS = true
            }
        }
        ComarkCodeStyleSettings.getInstance(settings).applyPreset(ComarkStylePreset.NUXT_STUDIO)
        settings.getCommonSettings(ComarkLanguage).WRAP_ON_TYPING = CommonCodeStyleSettings.WrapOnTyping.NO_WRAP.intValue
        manager.notifyCodeStyleSettingsChanged()
    }

    fun neverAskAgain(project: Project) = PropertiesComponent.getInstance(project).setValue(NEVER, true)

    /** The notification, with its three actions. */
    fun createNotification(project: Project): Notification =
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(
            ComarkBundle.message("studio.adoption.title"),
            ComarkBundle.message("studio.adoption.content"),
            NotificationType.INFORMATION,
        ).addAction(NotificationAction.createSimpleExpiring(ComarkBundle.message("studio.adoption.use")) {
            useNuxtStudioStyle(project)
            EditorNotifications.getInstance(project).updateAllNotifications()
        }).addAction(NotificationAction.createSimpleExpiring(ComarkBundle.message("studio.adoption.later")) {})
            .addAction(NotificationAction.createSimpleExpiring(ComarkBundle.message("studio.adoption.never")) { neverAskAgain(project) })

    /** Checks [project] in the background once indexes are ready and shows the notification when [shouldOffer]. */
    fun check(project: Project) {
        ReadAction.nonBlocking<Boolean> { shouldOffer(project) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.nonModal()) { offer ->
                if (offer) {
                    markShown(project)
                    createNotification(project).notify(project)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}

/** Runs [ComarkStudioAdoption.check] when a project opens. */
class ComarkStudioAdoptionActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        ComarkStudioAdoption.check(project)
    }
}
