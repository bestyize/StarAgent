package com.starringbox.staragent.core.platform

import com.starringbox.staragent.core.tools.ports.DevicePort
import com.starringbox.staragent.core.tools.ports.NoopDevicePort

actual fun platformDevicePort(): DevicePort = NoopDevicePort
