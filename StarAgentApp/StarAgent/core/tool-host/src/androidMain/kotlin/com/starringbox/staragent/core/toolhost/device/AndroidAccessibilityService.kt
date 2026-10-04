package com.starringbox.staragent.core.toolhost.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AndroidAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AndroidAccessibilityController.attach(this)
        AndroidDeviceOperationService.onForegroundPackageChanged(
            rootInActiveWindow?.packageName?.toString(),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        AndroidDeviceOperationService.onForegroundPackageChanged(event.packageName?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AndroidAccessibilityController.detach(this)
        super.onDestroy()
    }
}
