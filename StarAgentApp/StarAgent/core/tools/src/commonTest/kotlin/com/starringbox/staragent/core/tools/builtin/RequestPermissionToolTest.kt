package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.tools.testHost
import com.starringbox.staragent.core.tools.ports.PermissionCapability
import com.starringbox.staragent.core.tools.ports.PermissionPort
import com.starringbox.staragent.core.tools.ports.PermissionRequestResult
import com.starringbox.staragent.core.tools.ports.PermissionStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestPermissionToolTest {
    private class FakePermissionPort(
        var currentStatus: PermissionStatus = PermissionStatus.NotGranted,
        var response: PermissionRequestResult = PermissionRequestResult(
            PermissionStatus.Granted,
            "权限已开启。",
        ),
    ) : PermissionPort {
        var requested: PermissionCapability? = null

        override fun status(capability: PermissionCapability): PermissionStatus = currentStatus

        override suspend fun request(capability: PermissionCapability): PermissionRequestResult {
            requested = capability
            return response
        }
    }

    @Test
    fun alreadyGrantedPermissionDoesNotOpenSystemUi() = runTest {
        val permissions = FakePermissionPort(currentStatus = PermissionStatus.Granted)
        val result = RequestPermissionTool.execute(
            buildJsonObject { put("capability", "accessibility") },
            testHost(permissions = permissions),
        )

        assertTrue(permissions.requested == null)
        assertTrue(result.content.contains("已经开启"))
        assertFalse(result.isError)
    }

    @Test
    fun routesSupportedCapabilityToPlatformPort() = runTest {
        val permissions = FakePermissionPort()
        val result = RequestPermissionTool.execute(
            buildJsonObject { put("capability", "microphone") },
            testHost(permissions = permissions),
        )

        assertEquals(PermissionCapability.Microphone, permissions.requested)
        assertEquals("权限已开启。", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun routesOverlayCapabilityToPlatformPort() = runTest {
        val permissions = FakePermissionPort()
        val result = RequestPermissionTool.execute(
            buildJsonObject { put("capability", "overlay") },
            testHost(permissions = permissions),
        )

        assertEquals(PermissionCapability.Overlay, permissions.requested)
        assertEquals("权限已开启。", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun routesBatteryOptimizationCapabilityToPlatformPort() = runTest {
        val permissions = FakePermissionPort()
        val result = RequestPermissionTool.execute(
            buildJsonObject { put("capability", "battery_optimization") },
            testHost(permissions = permissions),
        )

        assertEquals(PermissionCapability.BatteryOptimization, permissions.requested)
        assertEquals("权限已开启。", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun userDecliningSettingsPromptCancelsOperation() = runTest {
        val permissions = FakePermissionPort()
        val fakeUserInput = object : com.starringbox.staragent.core.tools.ports.UserInputPort {
            override suspend fun requestInput(
                agentId: String,
                question: String,
                options: List<String>,
                placeholder: String?,
            ): String = "暂不开启"
        }

        val result = RequestPermissionTool.execute(
            buildJsonObject { put("capability", "accessibility") },
            testHost(permissions = permissions, userInput = fakeUserInput),
        )

        assertTrue(permissions.requested == null)
        assertTrue(result.content.contains("暂不同意开启"))
        assertTrue(result.isError)
    }

    @Test
    fun rejectsArbitraryPermissionNames() = runTest {
        val permissions = FakePermissionPort()
        val result = RequestPermissionTool.execute(
            buildJsonObject { put("capability", "android.permission.WRITE_SETTINGS") },
            testHost(permissions = permissions),
        )

        assertTrue(permissions.requested == null)
        assertTrue(result.isError)
    }

    @Test
    fun unsupportedPlatformReturnsActionableResult() = runTest {
        val permissions = FakePermissionPort(
            response = PermissionRequestResult(PermissionStatus.Unsupported, "当前平台不支持。"),
        )
        val result = RequestPermissionTool.execute(
            buildJsonObject { put("capability", "notifications") },
            testHost(permissions = permissions),
        )

        assertEquals("当前平台不支持。", result.content)
        assertTrue(result.isError)
    }
}
