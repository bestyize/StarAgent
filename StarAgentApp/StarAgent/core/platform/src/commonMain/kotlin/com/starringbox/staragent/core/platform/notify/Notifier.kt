package com.starringbox.staragent.core.platform.notify

/** 通知点击后的应用内承接目标。Android 用它携带安全的类型与对象 id。 */
sealed interface NotificationTarget {
    data class Task(val taskId: String) : NotificationTarget
    data class Artifact(val artifactId: String) : NotificationTarget
}

interface Notifier {
    /**
     * 向系统通知中心发送一条本地通知。
     * [id] 为去重与更新标识；[title] 标题；[content] 摘要文本。
     */
    fun notify(id: String, title: String, content: String)

    /** 不支持深链的平台保留原通知行为。 */
    fun notify(id: String, title: String, content: String, destination: NotificationTarget) =
        notify(id, title, content)

    object Noop : Notifier {
        override fun notify(id: String, title: String, content: String) = Unit
    }
}

object Notifiers {
    private var installed: Notifier = Notifier.Noop

    fun install(notifier: Notifier) {
        installed = notifier
    }

    fun reset() {
        installed = Notifier.Noop
    }

    val current: Notifier get() = installed
    val available: Boolean get() = installed !== Notifier.Noop
}
