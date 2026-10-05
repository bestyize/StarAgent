package com.starringbox.staragent.core.toolhost.device

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AndroidAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { AndroidDeviceOperationService.onForegroundPackageChanged(currentForegroundPackage()) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AndroidAccessibilityController.attach(this)
        AndroidDeviceOperationService.onForegroundPackageChanged(currentForegroundPackage())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 窗口切换是一串连续事件：退出中的 Activity 会补发迟到事件，新窗口也可能尚未就绪。
        // 每次都推迟一小段时间再读活跃窗口，等过渡结束后只按最终状态判定一次。
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MILLIS)
    }

    /**
     * 真实前台包，取自当前活跃窗口，而不是事件的 packageName。
     *
     * 切换应用时，正在退出的旧 Activity 会补发一个迟到的 TYPE_WINDOW_STATE_CHANGED，
     * 它可能晚于新应用的同类事件到达。若直接采信 event.packageName，就会把刚切到的
     * 目标应用误判回旧应用（星环自己），悬浮球刚显示便被隐藏。
     */
    private fun currentForegroundPackage(): String? {
        val active = windows.firstOrNull { it.isActive }
            ?: windows.firstOrNull { it.isFocused }
        return active?.root?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(settle)
        AndroidAccessibilityController.detach(this)
        super.onDestroy()
    }

    private companion object {
        const val SETTLE_MILLIS = 200L
    }
}
