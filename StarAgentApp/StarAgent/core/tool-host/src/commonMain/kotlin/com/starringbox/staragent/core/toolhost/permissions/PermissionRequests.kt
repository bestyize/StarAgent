package com.starringbox.staragent.core.toolhost.permissions

import com.starringbox.staragent.core.tools.ports.NoopPermissionPort
import com.starringbox.staragent.core.tools.ports.PermissionCapability
import com.starringbox.staragent.core.tools.ports.PermissionPort
import com.starringbox.staragent.core.tools.ports.PermissionRequestResult
import com.starringbox.staragent.core.tools.ports.PermissionStatus

/**
 * 平台权限实现的注册表。
 *
 * Android 在 PlatformInstall 阶段注入运行时权限和系统设置页实现；
 * 未安装实现的平台会明确返回 Unsupported。
 */
object PermissionRequests : PermissionPort {
    private var installed: PermissionPort = NoopPermissionPort

    fun install(port: PermissionPort) {
        installed = port
    }

    override fun status(capability: PermissionCapability): PermissionStatus =
        installed.status(capability)

    override suspend fun request(capability: PermissionCapability): PermissionRequestResult =
        installed.request(capability)
}
