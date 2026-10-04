package com.starringbox.staragent.core.toolhost

import com.starringbox.staragent.core.tools.ports.McpManagerPort
import com.starringbox.staragent.core.tools.ports.NoopMcpManagerPort

actual fun platformMcpManager(): McpManagerPort = NoopMcpManagerPort
