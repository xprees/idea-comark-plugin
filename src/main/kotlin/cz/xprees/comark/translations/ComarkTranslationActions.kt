package cz.xprees.comark.translations

import com.intellij.icons.AllIcons
import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import cz.xprees.comark.ComarkBundle
import cz.xprees.comark.settings.ComarkSettings
import java.util.Locale
import javax.swing.JSplitPane

/** The page of an action event: the editor's file, else the selected file. */
private fun AnActionEvent.page(): VirtualFile? = getData(CommonDataKeys.VIRTUAL_FILE)

private fun label(locale: String) = locale.uppercase(Locale.ROOT)

/** What the switcher lists for [translations]: switch, side by side and create items, by locale. */
private fun translationActions(project: Project, translations: ComarkTranslations.Translations): List<AnAction> {
    val result = ArrayList<AnAction>()
    result += CurrentLocaleAction(translations)
    for ((locale, files) in translations.others) {
        for (file in files) result += OpenTranslationAction(project, locale, file, files.size > 1, sideBySide = false)
    }
    if (translations.present.isNotEmpty()) {
        result += Separator.getInstance()
        for ((locale, files) in translations.others) {
            for (file in files) result += OpenTranslationAction(project, locale, file, files.size > 1, sideBySide = true)
        }
    }
    if (translations.missing.isNotEmpty()) {
        result += Separator.getInstance()
        for (locale in translations.missing) result += CreateTranslationAction(locale)
    }
    return result
}

/**
 * The translation switcher: a drop-down of the page's translations (switch to one, open one side by side, create a missing one),
 * shown as the current locale (`EN`) in the editor's floating toolbar ([ComarkTranslationsToolbarProvider]).
 */
class ComarkTranslationsGroup : ActionGroup() {
    init {
        templatePresentation.isPopupGroup = true
        templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.page()
        val locale = if (project != null && file != null) ComarkTranslations.getInstance(project).localeOf(file)?.first else null
        e.presentation.isEnabledAndVisible = locale != null
        if (locale != null) {
            e.presentation.text = label(locale)
            e.presentation.description = ComarkBundle.message("translations.group.description")
            e.presentation.icon = AllIcons.General.ChevronDown
        }
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val file = e.page() ?: return EMPTY_ARRAY
        val translations = ComarkTranslations.getInstance(project).translationsOf(file) ?: return EMPTY_ARRAY
        return translationActions(project, translations).toTypedArray()
    }

    companion object {
        const val ID = "Comark.Translations"
        const val TOOLBAR_ID = "Comark.TranslationsToolbar"
    }
}

/** The page's own locale, ticked and disabled at the top of the list. */
private class CurrentLocaleAction(translations: ComarkTranslations.Translations) :
    DumbAwareAction(ComarkBundle.message("translations.current", label(translations.locale), translations.file.name), null, AllIcons.Actions.Checked) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = false
    }

    override fun actionPerformed(e: AnActionEvent) {}
}

/** Opens a translation, in the current editor window or in a split to the right ([sideBySide]). */
private class OpenTranslationAction(
    private val project: Project,
    locale: String,
    private val file: VirtualFile,
    showPath: Boolean,
    private val sideBySide: Boolean,
) : DumbAwareAction(
    ComarkBundle.message(
        if (sideBySide) "translations.open.side" else "translations.open",
        label(locale),
        if (showPath) file.path.substringAfterLast("/$locale/", file.name) else file.name,
    ),
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = ComarkTranslationNavigation.open(project, file, sideBySide)
}

/** Creates the page in a locale where it is missing ([ComarkTranslationCreator]). */
private class CreateTranslationAction(private val locale: String) :
    DumbAwareAction(ComarkBundle.message("translations.create", label(locale)), null, AllIcons.General.Add) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.page() ?: return
        val translations = ComarkTranslations.getInstance(project).translationsOf(file) ?: return
        ComarkTranslationCreator.promptAndCreate(project, translations, locale)
    }
}

/** Opening translations. */
internal object ComarkTranslationNavigation {
    fun open(project: Project, file: VirtualFile, sideBySide: Boolean) {
        if (sideBySide) {
            val window = FileEditorManagerEx.getInstanceEx(project).currentWindow
            if (window != null) {
                window.split(JSplitPane.HORIZONTAL_SPLIT, true, file, true)
                return
            }
        }
        OpenFileDescriptor(project, file).navigate(true)
    }
}

/**
 * *Navigate › Switch to Translation*: opens the page's only translation, or lists them all (with side by side and create items)
 * when there are several or none.
 */
class ComarkSwitchTranslationAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.page()
        e.presentation.isEnabledAndVisible = project != null && file != null && ComarkTranslations.getInstance(project).localeOf(file) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.page() ?: return
        val translations = ComarkTranslations.getInstance(project).translationsOf(file) ?: return
        val only = translations.others.values.flatten().singleOrNull()
        if (only != null && translations.missing.isEmpty()) {
            ComarkTranslationNavigation.open(project, only, sideBySide = false)
            return
        }
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup(translationActions(project, translations))
        JBPopupFactory.getInstance()
            .createActionGroupPopup(ComarkBundle.message("translations.popup.title"), group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .showInBestPositionFor(e.dataContext)
    }
}

/** The page's translations in *Navigate › Related Symbol*. */
class ComarkTranslationsGotoRelatedProvider : GotoRelatedProvider() {
    override fun getItems(context: DataContext): List<GotoRelatedItem> {
        val project = context.getData(CommonDataKeys.PROJECT) ?: return emptyList()
        val file = context.getData(CommonDataKeys.VIRTUAL_FILE) ?: return emptyList()
        val translations = ComarkTranslations.getInstance(project).translationsOf(file) ?: return emptyList()
        val manager = PsiManager.getInstance(project)
        val group = ComarkBundle.message("translations.related.group")
        return translations.others.values.flatten().mapNotNull { manager.findFile(it) }.map { GotoRelatedItem(it, group) }
    }
}

/** Shows the translation switcher in the top-right corner of editors of localized Comark pages (*Show translation switcher*). */
class ComarkTranslationsToolbarProvider : FloatingToolbarProvider {
    override val actionGroup: ActionGroup
        get() = ActionManager.getInstance().getAction(ComarkTranslationsGroup.TOOLBAR_ID) as ActionGroup

    override val autoHideable: Boolean get() = false

    // shown only where it applies: the toolbar is not auto-hideable, so it stays hidden in every other editor
    override fun register(dataContext: DataContext, component: FloatingToolbarComponent, parentDisposable: Disposable) {
        if (applies(dataContext)) component.scheduleShow()
    }

    internal fun applies(dataContext: DataContext): Boolean {
        if (!ComarkSettings.getInstance().translationsEnabled) return false
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return false
        val project = editor.project ?: return false
        val file = editor.virtualFile ?: FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        return ComarkTranslations.getInstance(project).localeOf(file) != null
    }
}
