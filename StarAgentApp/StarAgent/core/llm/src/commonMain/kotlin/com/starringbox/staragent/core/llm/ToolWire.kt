package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 工具的**渲染层**：把协议无关的 [ToolDefinition] 变成某一家 API 认识的形状。
 *
 * 这一层很薄，但它是"多服务商"成立的关键——工具的内部模型不认识协议，
 * 协议只是边缘的一个渲染器。将来加"文本协议降级"也只是多一个函数。
 */
internal object ToolWire {

    /**
     * OpenAI 兼容系：外面要包一层 `function`，schema 的键叫 `parameters`。
     *
     * `{"type":"function","function":{"name":..,"description":..,"parameters":<schema>}}`
     */
    fun renderOpenAi(defs: List<ToolDefinition>): JsonArray = buildJsonArray {
        defs.forEach { definition ->
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", definition.name)
                    put("description", definition.description)
                    put("parameters", definition.inputSchema)
                }
            }
        }
    }

    /**
     * Anthropic：**不包** `function`，schema 的键叫 `input_schema`。
     *
     * `{"name":..,"description":..,"input_schema":<schema>}`
     */
    fun renderAnthropic(defs: List<ToolDefinition>): JsonArray = buildJsonArray {
        defs.forEach { definition ->
            addJsonObject {
                put("name", definition.name)
                put("description", definition.description)
                put("input_schema", definition.inputSchema)
            }
        }
    }
}

/**
 * OpenAI 兼容系的工具调用累积器。
 *
 * 模型的 `arguments` 是**跨多帧拼出来的字符串**，而 `finish_reason` 单独到达，
 * 所以不能边收边解析。按 `index` 分别累积，流结束时才拼完整：
 *
 * ```
 * {"index":0,"id":"call_1","function":{"name":"remember","arguments":"{\"ca"}}
 * {"index":0,"function":{"arguments":"tegory\":\"Preference\","}}
 * {"index":0,"function":{"arguments":"\"text\":\"爱吃辣\"}"}}
 * ```
 *
 * 名字和参数可能落在不同帧里；并行调用会交错到达，所以结果**按 index 排序**，
 * 否则回喂时对不上 id。
 */
internal class OpenAiToolCallAccumulator {

    private val ids = mutableMapOf<Int, String>()
    private val names = mutableMapOf<Int, String>()
    private val args = mutableMapOf<Int, StringBuilder>()

    /**
     * 收**一条裸的 `tool_calls` 条目**（即调用方已经从 `choices[0].delta.tool_calls` 里
     * 取出单个元素）。也容忍把整个 `delta` 对象直接给进来（里面裹着 `tool_calls` 数组），
     * 但那是顺带兼容，**约定的一等形状是裸条目**。
     */
    fun accept(delta: JsonObject) {
        // 裸条目是约定形状；裹着 tool_calls 数组的 delta 也收，免得调用方记错传了哪一层。
        val entries = (delta["tool_calls"] as? JsonArray) ?: listOf(delta)
        entries.forEach { element ->
            val entry = element as? JsonObject ?: return@forEach
            val index = entry["index"].asIntOrNull() ?: return@forEach

            entry["id"].asTextOrNull()?.let { ids[index] = it }

            val function = entry["function"] as? JsonObject
            function?.get("name").asTextOrNull()?.let { names[index] = it }
            function?.get("arguments").asTextOrNull()?.let { fragment ->
                args.getOrPut(index) { StringBuilder() }.append(fragment)
            }
        }
    }

    /**
     * 流结束时收尾。
     *
     * 没有名字的条目直接丢掉——只有 id 和参数是没法执行的，造一个哑调用
     * 只会让模型收到一条莫名其妙的错误。没有参数则给 `{}`，无参工具就是这样。
     */
    fun finish(): List<ToolCall> = ids.keys
        .sorted()
        .mapNotNull { index ->
            val name = names[index] ?: return@mapNotNull null
            val id = ids[index] ?: return@mapNotNull null
            ToolCall(
                id = id,
                name = name,
                arguments = args[index]?.toString()?.takeIf { it.isNotBlank() } ?: "{}",
            )
        }
}

/**
 * Anthropic 的工具调用累积器。
 *
 * 与 OpenAI 的两处关键差别：
 * 1. 调用不是一个数组，而是 content 块流——`content_block_start` 开一块、
 *    若干 `content_block_delta` 往里灌
 * 2. **参数不是现成的对象**。非流式时 `input` 是解析好的；但 `stream: true`
 *    下收到的是 `input_json_delta` 里的 `partial_json` **片段字符串**，要自己拼。
 *    （这一条跟非流式的形状不同，按实际 SSE 事件为准。）
 */
internal class AnthropicToolCallAccumulator {

    private val ids = mutableMapOf<Int, String>()
    private val names = mutableMapOf<Int, String>()
    private val args = mutableMapOf<Int, StringBuilder>()

    /** `content_block_start`：开一个 tool_use 块。 */
    fun start(index: Int, id: String, name: String) {
        ids[index] = id
        names[index] = name
    }

    /** `content_block_delta` 里的 `input_json_delta`：追加一段 JSON 片段。 */
    fun acceptDelta(index: Int, partialJson: String) {
        args.getOrPut(index) { StringBuilder() }.append(partialJson)
    }

    /** 同 OpenAI：按 index 排序、无参给 `{}`、缺名字丢弃。 */
    fun finish(): List<ToolCall> = ids.keys
        .sorted()
        .mapNotNull { index ->
            val name = names[index] ?: return@mapNotNull null
            val id = ids[index] ?: return@mapNotNull null
            ToolCall(
                id = id,
                name = name,
                arguments = args[index]?.toString()?.takeIf { it.isNotBlank() } ?: "{}",
            )
        }
}

/** 从 JSON 里取整数。`index` 在真响应里一定是数字，但防御性地处理其他形状。 */
internal fun JsonElement?.asIntOrNull(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content.toIntOrNull()
}
