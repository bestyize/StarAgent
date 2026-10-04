package com.starringbox.staragent.core.toolhost

import com.starringbox.staragent.core.toolhost.device.AndroidAccessibilityController
import com.starringbox.staragent.core.tools.ports.DevicePort

actual fun platformDevicePort(): DevicePort = AndroidAccessibilityController
