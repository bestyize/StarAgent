package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.tools.builtin.DeviceUseTool
import com.starringbox.staragent.core.tools.builtin.ListAppsTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

class DeviceAppToolsTest {

    @Test
    fun deviceUseLaunchAppWithPackageNameForwardsToDevicePort() = runTest {
        val fakeDevice = FakeDevicePort()
        val host = testHost(device = fakeDevice)

        val result = DeviceUseTool.execute(
            args("""{"operation":"launch_app","package_name":"网易云音乐"}"""),
            host,
        )

        assertFalse(result.isError)
        assertEquals(listOf("网易云音乐"), fakeDevice.launchedApps)
        assertTrue(result.content.contains("网易云音乐"))
    }

    @Test
    fun deviceUseLaunchAppWithAppNameParamAlsoWorks() = runTest {
        val fakeDevice = FakeDevicePort()
        val host = testHost(device = fakeDevice)

        val result = DeviceUseTool.execute(
            args("""{"operation":"launch_app","app_name":"微信"}"""),
            host,
        )

        assertFalse(result.isError)
        assertEquals(listOf("微信"), fakeDevice.launchedApps)
    }

    @Test
    fun deviceUseListAppsReturnsFormattedList() = runTest {
        val fakeDevice = FakeDevicePort()
        val host = testHost(device = fakeDevice)

        val result = DeviceUseTool.execute(
            args("""{"operation":"list_apps"}"""),
            host,
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("微信 (com.tencent.mm)"))
        assertTrue(result.content.contains("网易云音乐 (com.netease.cloudmusic)"))
    }

    @Test
    fun standaloneListAppsToolReturnsInstalledApps() = runTest {
        val fakeDevice = FakeDevicePort()
        val host = testHost(device = fakeDevice)

        val result = ListAppsTool.execute(args("{}"), host)

        assertFalse(result.isError)
        assertTrue(result.content.contains("共 2 个"))
        assertTrue(result.content.contains("微信"))
        assertTrue(result.content.contains("com.tencent.mm"))
    }
}
