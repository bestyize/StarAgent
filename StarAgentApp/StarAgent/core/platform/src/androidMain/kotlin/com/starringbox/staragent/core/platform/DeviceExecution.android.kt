package com.starringbox.staragent.core.platform

import com.starringbox.staragent.core.platform.device.AndroidAccessibilityController
import com.starringbox.staragent.core.tools.ports.DevicePort

actual fun platformDevicePort(): DevicePort = AndroidAccessibilityController
