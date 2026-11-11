package cz.xprees.comark.meta

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Rediscovers components and re-fetches remote metadata right away (the VS Code extension's "Refresh component metadata"),
 * for when files changed outside the IDE without a file system event or the remote endpoint was updated before its cache expired.
 */
class ComarkRefreshComponentsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ComarkComponentService.getInstance(project).refresh()
    }
}
