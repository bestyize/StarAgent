package com.starringbox.staragent.core.platform.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AndroidAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AndroidAccessibilityController.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AndroidAccessibilityController.detach(this)
        super.onDestroy()
    }
}
