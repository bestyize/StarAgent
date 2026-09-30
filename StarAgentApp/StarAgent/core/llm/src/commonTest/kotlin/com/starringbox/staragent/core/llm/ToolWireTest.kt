package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

private val rememberDef = ToolDefinition(
    name = "remember",
    description = "存一条记忆",
    inputSchema = buildJsonObject { put("type", "object") },
)

private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

class ToolWireTest {

    // ── 渲染 ──────────────────────────────────────

    @Test
    fun openAiRenderWrapsInFunctionObject() {
        val rendered = ToolWire.renderOpenAi(listOf(rememberDef)).toString()
        assertEquals(
            "[{\"type\":\"function\",\"function\":{\"name\":\"remember\"," +
                "\"description\":\"存一条记忆\",\"parameters\":{\"type\":\"object\"}}}]",
            rendered,
        )
    }

    @Test
    fun anthropicRenderUsesInputSchemaAndNoWrapper() {
        val rendered = ToolWire.renderAnthropic(listOf(rememberDef)).toString()
        assertEquals(
            "[{\"name\":\"remember\",\"description\":\"存一条记忆\"," +
                "\"input_schema\":{\"type\":\"object\"}}]",
            rendered,
        )
    }

    // ── OpenAI 流式累积 ────────────────────────────

    @Test
    fun openAiAccumulatesArgumentsSplitAcrossManyFrames() {
        // 参数被切成 3 片——这是最容易出偶发 bug 的地方
        val acc = OpenAiToolCallAccumulator()
        acc.accept(obj("{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"remember\",\"arguments\":\"{\\\"ca\"}}"))
        acc.accept(obj("{\"index\":0,\"function\":{\"arguments\":\"tegory\\\":\\\"Preference\\\",\"}}"))
        acc.accept(obj("{\"index\":0,\"function\":{\"arguments\":\"\\\"text\\\":\\\"爱吃辣\\\"}\"}}"))

        assertEquals(
            listOf(ToolCall("call_1", "remember", "{\"category\":\"Preference\",\"text\":\"爱吃辣\"}")),
            acc.finish(),
        )
    }

    @Test
    fun openAiHandlesNameAndArgumentsInDifferentFrames() {
        val acc = OpenAiToolCallAccumulator()
        acc.accept(obj("{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"remember\"}}"))
        acc.accept(obj("{\"index\":0,\"function\":{\"arguments\":\"{}\"}}"))
        assertEquals(listOf(ToolCall("call_1", "remember", "{}")), acc.finish())
    }

    @Test
    fun openAiKeepsParallelCallsInIndexOrderEvenWhenInterleaved() {
        // 两个调用交错到达：先开 0，再开 1，然后 1 的参数先到、0 的后到。
        // 结果必须仍按 index 排序，否则回喂时对不上 id。
        val acc = OpenAiToolCallAccumulator()
        acc.accept(obj("{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"remember\",\"arguments\":\"{\"}}"))
        acc.accept(obj("{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"create_task\",\"arguments\":\"{\"}}"))
        acc.accept(obj("{\"index\":1,\"function\":{\"arguments\":\"}\"}}"))
        acc.accept(obj("{\"index\":0,\"function\":{\"arguments\":\"}\"}}"))

        assertEquals(
            listOf(
                ToolCall("call_a", "remember", "{}"),
                ToolCall("call_b", "create_task", "{}"),
            ),
            acc.finish(),
        )
    }

    @Test
    fun openAiTreatsEmptyArgumentsAsEmptyObject() {
        // 无参工具：模型可能连 arguments 都不给
        val acc = OpenAiToolCallAccumulator()
        acc.accept(obj("{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"list_tasks\"}}"))
        assertEquals(listOf(ToolCall("call_1", "list_tasks", "{}")), acc.finish())
    }

    @Test
    fun openAiMissingNameDropsTheCall() {
        // 只有 id 和参数、没有名字——这条没法执行，丢掉比造一个哑调用好
        val acc = OpenAiToolCallAccumulator()
        acc.accept(obj("{\"index\":0,\"id\":\"call_1\",\"function\":{\"arguments\":\"{}\"}}"))
        assertEquals(emptyList(), acc.finish())
    }

    // ── Anthropic 流式累积 ─────────────────────────

    @Test
    fun anthropicAccumulatesPartialJsonAcrossDeltas() {
        val acc = AnthropicToolCallAccumulator()
        acc.start(0, "toolu_1", "remember")
        acc.acceptDelta(0, "{\"category\":\"Pref")
        acc.acceptDelta(0, "erence\",\"text\":\"爱")
        acc.acceptDelta(0, "吃辣\"}")
        assertEquals(
            listOf(ToolCall("toolu_1", "remember", "{\"category\":\"Preference\",\"text\":\"爱吃辣\"}")),
            acc.finish(),
        )
    }

    @Test
    fun anthropicEmptyInputBecomesEmptyObject() {
        // 无参工具：content_block_start 里 input 是 {}，且不会有 delta
        val acc = AnthropicToolCallAccumulator()
        acc.start(0, "toolu_1", "list_tasks")
        assertEquals(listOf(ToolCall("toolu_1", "list_tasks", "{}")), acc.finish())
    }

    @Test
    fun anthropicKeepsBlocksInIndexOrder() {
        val acc = AnthropicToolCallAccumulator()
        acc.start(1, "toolu_b", "create_task")
        acc.start(0, "toolu_a", "remember")
        acc.acceptDelta(0, "{\"a\":1}")
        acc.acceptDelta(1, "{\"b\":2}")
        assertEquals(
            listOf(
                ToolCall("toolu_a", "remember", "{\"a\":1}"),
                ToolCall("toolu_b", "create_task", "{\"b\":2}"),
            ),
            acc.finish(),
        )
    }

    // ── 非法 JSON 不崩 ─────────────────────────────

    @Test
    fun malformedArgumentsArePreservedVerbatimNotThrown() {
        // 模型不总生成合法 JSON。协议层**不解析参数**，原样带上去，
        // 由工具执行前的那一步把它变成一条给模型看的错误。
        val acc = OpenAiToolCallAccumulator()
        acc.accept(obj("{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"remember\",\"arguments\":\"{oops\"}}"))
        assertEquals(listOf(ToolCall("call_1", "remember", "{oops")), acc.finish())
    }
}
