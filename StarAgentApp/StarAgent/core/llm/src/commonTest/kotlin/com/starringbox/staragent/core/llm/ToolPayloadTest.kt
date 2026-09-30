package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 测试里用的 role 串。生产那份 `wire()` 是 private，测试看不到，这里照抄一份。 */
private fun LlmRole.wireForTest(): String = if (this == LlmRole.User) "user" else "assistant"

/** 把 [handleAnthropicEvent] 发出的事件收进一个 list，替代真实 SSE 的接收端。 */
private fun eventSink(sink: MutableList<ChatStreamEvent>): FlowCollector<ChatStreamEvent> =
    FlowCollector { sink.add(it) }

/**
 * 回喂形状的钉子。
 *
 * 这两套形状**完全不同**，而且写错了的后果是模型收到一个它不认识的请求——
 * 表现是莫名其妙地不调工具或直接 400，很难从现象反推原因。所以用固定期望钉死。
 */
class ToolPayloadTest {

    private fun openAiTurnOf(turn: LlmTurn): String = buildJsonObject {
        put("role", turn.role.wireForTest())
        putOpenAiContent(turn)
        putOpenAiToolTurns(turn)
    }.toString()

    private fun anthropicTurnOf(turn: LlmTurn): String = buildJsonObject {
        put("role", turn.role.wireForTest())
        putAnthropicContent(turn)
        putAnthropicToolBlocks(turn)
    }.toString()

    @Test
    fun openAiAssistantCarriesToolCallsAsArray() {
        val json = openAiTurnOf(
            LlmTurn(
                role = LlmRole.Assistant,
                text = "",
                toolCalls = listOf(ToolCall("call_1", "remember", "{\"text\":\"爱吃辣\"}")),
            )
        )
        // OpenAI 的 arguments 必须回成**字符串**，不能再包对象
        assertEquals(
            "{\"role\":\"assistant\",\"content\":\"\"," +
                "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\"," +
                "\"function\":{\"name\":\"remember\",\"arguments\":\"{\\\"text\\\":\\\"爱吃辣\\\"}\"}}]}",
            json,
        )
    }

    @Test
    fun anthropicAssistantCarriesToolUseBlocks() {
        val json = anthropicTurnOf(
            LlmTurn(
                role = LlmRole.Assistant,
                text = "",
                toolCalls = listOf(ToolCall("toolu_1", "remember", "{\"a\":1}")),
            )
        )
        // Anthropic 的 input 是**对象**，不是字符串——跟 OpenAI 正好相反
        assertEquals(
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\"," +
                "\"id\":\"toolu_1\",\"name\":\"remember\",\"input\":{\"a\":1}}]}",
            json,
        )
    }

    /**
     * OpenAI 的工具结果是**独立的 `role:"tool"` 消息**（用 `tool_call_id` 对上调用），
     * 不是塞进 user 轮——这是 spec §4.2 钉死的回喂形状，也是 `streamOpenAi` 实际发出的东西。
     */
    @Test
    fun openAiToolResultIsItsOwnMessageWithToolRole() {
        val json = buildJsonObject {
            putOpenAiToolResult(ToolResult(callId = "call_1", content = "记住了"))
        }.toString()
        assertEquals(
            "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"记住了\"}",
            json,
        )
    }

    /**
     * 同一轮里带图片的 assistant 消息**也可以同时带着工具调用**：
     * `putAnthropicContent` 先把 content 变成块数组，之后追加工具块时不能把调用悄悄丢掉。
     */
    @Test
    fun anthropicImageTurnKeepsItsToolCallsWhenBlocksAreAppended() {
        val turn = LlmTurn(
            role = LlmRole.Assistant,
            text = "",
            images = listOf(MediaAsset(id = "a1", mimeType = "image/png", bytes = byteArrayOf(1, 2, 3, 4))),
            toolCalls = listOf(ToolCall("toolu_1", "remember", "{\"a\":1}")),
        )
        val content = buildJsonObject {
            put("role", "assistant")
            putAnthropicContent(turn)
            putAnthropicToolBlocks(turn)
        }["content"]!!.jsonArray

        // 图片块还在，工具调用也没丢
        assertEquals(
            listOf("image", "tool_use"),
            content.map { it.jsonObject["type"]!!.jsonPrimitive.content },
        )
    }

    /**
     * 直接把 Anthropic 的 SSE 事件喂给解析分支，钉住整条工具解析链路：
     * `content_block_start` 开块 → 两帧 `input_json_delta` 拼参数 → `message_stop` 收尾成
     * `ToolCallsReady`。正文 delta 与工具 delta 在同一路事件里分流，两边都不能错。
     */
    @Test
    fun anthropicParsePathEmitsToolCallsReadyWhenTheStreamStops() = runTest {
        val events = listOf(
            """{"type":"message_start","message":{"id":"msg_1"}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"正在记"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"remember","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"category\":\"Pref"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"erence\",\"text\":\"爱吃辣\"}"}}""",
            """{"type":"message_stop"}""",
        )

        val calls = AnthropicToolCallAccumulator()
        val emitted = mutableListOf<ChatStreamEvent>()
        var stopped = false
        for (raw in events) {
            val event = Json.parseToJsonElement(raw).jsonObject
            // 返回值即"该收尾了"，等价于读流循环里的 break
            if (handleAnthropicEvent(event, calls, eventSink(emitted))) {
                stopped = true
                break
            }
        }

        assertTrue(stopped, "message_stop 之后应当返回 true 让循环停下")
        assertEquals(
            listOf(
                ChatStreamEvent.Delta("正在记"),
                ChatStreamEvent.ToolCallsReady(
                    listOf(ToolCall("toolu_1", "remember", "{\"category\":\"Preference\",\"text\":\"爱吃辣\"}")),
                ),
            ),
            emitted,
        )
    }

    /**
     * 真正发请求时拼出来的 OpenAI `messages`：
     * `[assistant 带 tool_calls, role:"tool" 的结果消息]`——就两条，**不多不少**。
     *
     * 钉住两件最容易悄悄坏掉的事：结果**没有**被顺带再渲染成一条普通 user 消息
     * （多一条会让模型看到两份同样的结果），以及 `arguments` 仍是**字符串**。
     */
    @Test
    fun openAiMessagesEmitToolResultsAsStandaloneToolMessages() {
        val endpoint = LlmEndpoint(LlmProtocol.OpenAi, "http://x", "k", "m")
        val history = listOf(
            LlmTurn(
                role = LlmRole.Assistant,
                text = "",
                toolCalls = listOf(ToolCall("call_1", "remember", "{\"text\":\"爱吃辣\"}")),
            ),
            LlmTurn(
                role = LlmRole.User,
                text = "",
                toolResults = listOf(ToolResult(callId = "call_1", content = "记住了")),
            ),
        )

        assertEquals(
            "[{\"role\":\"assistant\",\"content\":\"\"," +
                "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\"," +
                "\"function\":{\"name\":\"remember\",\"arguments\":\"{\\\"text\\\":\\\"爱吃辣\\\"}\"}}]}," +
                "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"记住了\"}]",
            buildOpenAiMessages(endpoint, history).toString(),
        )
    }

    /**
     * 真正发请求时拼出来的 Anthropic `messages`：
     * `[assistant 带 tool_use 块, **user** 消息里的 tool_result 块]`。
     *
     * 结果装进 **user** 消息、块用 `tool_use_id`（不是 `id`）、`input` 是**对象**（不是字符串），
     * 以及错误结果带 `is_error:true`——这几处写反了都只有真发出去才会发现，所以钉死。
     */
    @Test
    fun anthropicMessagesPutToolResultsInUserToolResultBlocks() {
        val history = listOf(
            LlmTurn(
                role = LlmRole.Assistant,
                text = "",
                toolCalls = listOf(ToolCall("toolu_1", "remember", "{\"a\":1}")),
            ),
            LlmTurn(
                role = LlmRole.User,
                text = "",
                toolResults = listOf(ToolResult(callId = "toolu_1", content = "记住了", isError = true)),
            ),
        )

        assertEquals(
            "[" +
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\"," +
                "\"name\":\"remember\",\"input\":{\"a\":1}}]}," +
                "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\"," +
                "\"content\":\"记住了\",\"is_error\":true}]}]",
            buildAnthropicMessages(history).toString(),
        )
    }
}
