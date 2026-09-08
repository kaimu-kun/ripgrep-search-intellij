package com.github.ss.ripgrepsearch.notifications

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object RipgrepNotifications {
    private const val GROUP_ID = "Ripgrep Search"

    fun error(project: Project?, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(content, NotificationType.ERROR)
            .notify(project)
    }
}
