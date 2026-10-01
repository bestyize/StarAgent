package com.starringbox.staragent.core.platform.schedule

/**
 * 系统唤醒反向触发器。
 *
 * 操作系统在后台唤醒广播/线程/后台任务时，直接调用 [fire]；
 * 数据层（AppState）在构造时向其注册自己的执行函数。
 */
object WakeupTrigger {
    private var handler: (suspend () -> Unit)? = null

    fun registerHandler(action: suspend () -> Unit) {
        handler = action
    }

    suspend fun fire() {
        handler?.invoke()
    }
}
