package com.starringbox.staragent.core.platform.notify

interface Notifier {
    /**
     * 向系统通知中心发送一条本地通知。
     * [id] 为去重与更新标识；[title] 标题；[content] 摘要文本。
     */
    fun notify(id: String, title: String, content: String)

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
