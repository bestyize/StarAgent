package com.starringbox.staragent.core.platform.notify

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS 端系统通知：基于 UserNotifications 框架投递本地推送。
 */
@OptIn(ExperimentalForeignApi::class)
class IosNotifier : Notifier {

    override fun notify(id: String, title: String, content: String) {
        val notificationContent = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(content)
        }

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = id,
            content = notificationContent,
            trigger = null, // null 表示立即推送
        )

        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }
}
