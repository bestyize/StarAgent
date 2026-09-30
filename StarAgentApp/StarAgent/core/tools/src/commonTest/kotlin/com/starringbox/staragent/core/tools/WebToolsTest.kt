package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.tools.builtin.WebFetchTool
import com.starringbox.staragent.core.tools.ports.FetchResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

class WebToolsTest {

    @Test
    fun returnsFetchedTextAndMarksItAsExternal() = runTest {
        // **最重要的一条断言**：externalContext 必须为真。
        // 不为真的话，网页正文里写的"用户喜欢简洁回答"会被当成事实写进记忆，
        // 而且用户永远不知道——这是提示注入在记忆层面的表现。
        val result = WebFetchTool.execute(
            args("{\"url\":\"https://example.com\"}"),
            testHost(web = FakeWebPort(FetchResult(true, "正文内容"))),
        )

        assertTrue(result.content.contains("正文内容"))
        assertTrue(result.externalContext, "网页内容必须标成外部内容，否则会污染记忆")
    }

    @Test
    fun reportsFetchFailureAsAnErrorWithoutThrowing() = runTest {
        val result = WebFetchTool.execute(
            args("{\"url\":\"https://example.com\"}"),
            testHost(web = FakeWebPort(FetchResult(false, "", "取不到"))),
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("取不到"))
    }

    @Test
    fun rejectsNonHttpsBeforeCallingThePort() = runTest {
        // http:// 明文会把用户看的地址暴露在链路上
        val web = FakeWebPort()
        val result = WebFetchTool.execute(args("{\"url\":\"http://example.com\"}"), testHost(web = web))

        assertTrue(result.isError)
        assertEquals(emptyList(), web.fetched, "不合格的地址不该发出去")
    }

    @Test
    fun rejectsBlankUrl() = runTest {
        assertTrue(WebFetchTool.execute(args("{\"url\":\"  \"}"), testHost()).isError)
    }

    @Test
    fun rejectsMissingUrl() = runTest {
        assertTrue(WebFetchTool.execute(args("{}"), testHost()).isError)
    }

    @Test
    fun passesTheUrlThroughUnchangedOnSuccess() = runTest {
        val web = FakeWebPort(FetchResult(true, "x"))
        WebFetchTool.execute(args("{\"url\":\"https://example.com/a?b=1\"}"), testHost(web = web))
        assertEquals(listOf("https://example.com/a?b=1"), web.fetched)
    }

    @Test
    fun externalContextIsFalseOnFailure() = runTest {
        // 失败的结果里没有外部内容，不该误伤记忆
        val result = WebFetchTool.execute(
            args("{\"url\":\"https://example.com\"}"),
            testHost(web = FakeWebPort(FetchResult(false, "", "断网"))),
        )
        assertFalse(result.externalContext)
    }
}
