package com.starringbox.staragent.core.platform

import com.starringbox.staragent.core.tools.ports.McpManagerPort
import com.starringbox.staragent.core.tools.ports.NoopMcpManagerPort

actual fun platformMcpManager(): McpManagerPort = NoopMcpManagerPort
