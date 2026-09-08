package com.github.ss.ripgrepsearch.action

import com.github.ss.ripgrepsearch.notifications.RipgrepNotifications
import com.github.ss.ripgrepsearch.ui.RipgrepSearchPopup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import java.nio.file.Path

class RipgrepSearchAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath
        if (basePath == null) {
            RipgrepNotifications.error(project, "Ripgrep Search needs a project with a usable base directory.")
            return
        }

        RipgrepSearchPopup(project, e.dataContext, Path.of(basePath)).show()
    }
}
