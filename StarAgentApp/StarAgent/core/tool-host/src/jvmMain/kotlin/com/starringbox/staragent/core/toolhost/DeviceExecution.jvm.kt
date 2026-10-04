package com.starringbox.staragent.core.toolhost

import com.starringbox.staragent.core.tools.ports.DevicePort
import com.starringbox.staragent.core.tools.ports.NoopDevicePort

actual fun platformDevicePort(): DevicePort = NoopDevicePort
