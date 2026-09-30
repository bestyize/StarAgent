package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 把关于用户的一条信息存进长期记忆。
 *
 * 描述里**明确说了什么时候不要调**——这是工具描述里最要紧的一句。
 * 不写的话模型会把每轮闲聊都记下来，记忆很快就被噪音塞满，
 * 而且用户会开始怀疑"它到底记住了些什么"。
 */
object RememberTool : Tool {

    override val definition = ToolDefinition(
        name = "remember",
        description = "把关于用户的一条长期信息存下来，让他下次不用重复说。" +
            "只在用户**明确**表达了喜好、身边重要的人、生活习惯、经历或决策时调用。" +
            "**不要把闲聊、问候、以及临时性的请求记下来**——那样记忆会被噪音淹没。" +
            "同一件事已经记过就不要重复记。",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("category") {
                    put("type", "string")
                    put("description", "这条信息属于哪一类")
                    put("enum", buildJsonArray {
                        MemoryCategory.entries.forEach { add(it.name) }
                    })
                }
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "要记住的这件事，写成一句完整的话")
                }
            }
            putJsonArray("required") {
                add("category")
                add("text")
            }
        },
    )

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val rawCategory = args.stringOrNull("category")
            ?: return ToolResult(callId = "", content = "缺参数 category", isError = true)

        val category = MemoryCategory.entries.firstOrNull { it.name == rawCategory }
            ?: return ToolResult(
                callId = "",
                content = "category 只能是这些之一：${MemoryCategory.entries.joinToString("、") { it.name }}",
                isError = true,
            )

        val text = args.stringOrNull("text")
        if (text.isNullOrBlank()) {
            return ToolResult(callId = "", content = "text 不能为空", isError = true)
        }

        host.memory.remember(host.agentId, category, text.trim())
        return ToolResult(callId = "", content = "已记住：$text")
    }
}

/**
 * 按关键词检索当前智能体的记忆。
 *
 * 这是这一批里价值最高的一个：它把记忆从"静态列表"变成"参与推理"。
 * 在此之前记忆只能靠系统提示灌最近若干条，模型没法主动按需查。
 */
object RecallMemoryTool : Tool {

    override val definition = ToolDefinition(
        name = "recall_memory",
        description = "从你记得的关于这位用户的事里查找相关条目。" +
            "当用户提到过去说过的事、你拿不准他偏好什么、或者要做跟他的习惯相关的判断时，先查一下。" +
            "不要为了每条消息都查一次。",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "要查的关键词或短句")
                }
            }
            putJsonArray("required") { add("query") }
        },
    )

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val query = args.stringOrNull("query").orEmpty()
        if (query.isBlank()) {
            return ToolResult(callId = "", content = "query 不能为空", isError = true)
        }

        val hits = host.memory.recall(host.agentId, query, limit = DEFAULT_LIMIT)
        if (hits.isEmpty()) {
            // 明确说"没有"，否则模型会以为工具坏了、反复重试
            return ToolResult(callId = "", content = "没有查到跟「$query」相关的记忆。")
        }

        val text = hits.joinToString("\n") { "- ${it.category.label}：${it.text}" }
        return ToolResult(callId = "", content = "查到 ${hits.size} 条：\n$text")
    }

    private const val DEFAULT_LIMIT = 10
}

/** 从 JSON 对象里取字符串。缺字段或类型不对都返回 null。 */
internal fun JsonObject.stringOrNull(key: String): String? {
    val value = this[key] ?: return null
    return (value as? kotlinx.serialization.json.JsonPrimitive)?.content
}
