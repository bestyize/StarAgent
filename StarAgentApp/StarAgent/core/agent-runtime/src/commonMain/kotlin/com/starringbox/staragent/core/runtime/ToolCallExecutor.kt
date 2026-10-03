package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 宿主负责实际工具执行；循环推进规则不认识解析器、计时或确认 UI。 */
fun interface ToolCallExecutor {
    suspend fun execute(call: ToolCall, host: ToolHost, registry: ToolRegistry): ToolResult
}

data class ToolConfirmationRequest(val call: ToolCall, val arguments: JsonObject)

fun interface ToolConfirmationPort {
    suspend fun confirm(request: ToolConfirmationRequest, host: ToolHost): ConfirmationDecision
}

/** 兼容现有提问端口的宿主适配器，只有明确的“允许”选项表示授权。 */
class UserInputToolConfirmation(
    private val describe: (ToolCall, JsonObject) -> String = { call, _ -> call.name },
) : ToolConfirmationPort {
    override suspend fun confirm(request: ToolConfirmationRequest, host: ToolHost): ConfirmationDecision {
        val answer = host.userInput.requestInput(
            agentId = host.agentId,
            question = "智能体申请执行操作「${describe(request.call, request.arguments)}」，是否允许？",
            options = listOf("允许", "拒绝"),
            placeholder = null,
        )
        return if (answer.trim() == "允许") ConfirmationDecision.Approved else ConfirmationDecision.Denied
    }
}

/** Kotlin 宿主的默认执行机制。其他宿主可以替换整个执行器或确认适配器。 */
class DefaultToolCallExecutor(
    private val toolTimeoutMillis: Long = AgentLoop.DEFAULT_TOOL_TIMEOUT_MILLIS,
    private val confirmationTimeoutMillis: Long = AgentLoop.DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
    private val confirmation: ToolConfirmationPort = UserInputToolConfirmation(),
) : ToolCallExecutor {
    init {
        require(toolTimeoutMillis > 0) { "Tool timeout must be positive" }
        require(confirmationTimeoutMillis > 0) { "Confirmation timeout must be positive" }
    }

    override suspend fun execute(call: ToolCall, host: ToolHost, registry: ToolRegistry): ToolResult {
        val tool = registry.find(call.name) ?: return ToolResult(
            callId = call.id,
            content = "没有名为「${call.name}」的工具。可用的有：" +
                registry.definitions().joinToString("、") { it.name },
            isError = true,
        )
        val args = runCatching { Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return ToolResult(call.id, "参数不是合法的 JSON，请重新调用并确保参数是一个 JSON 对象。", true)

        return try {
            if (requiresToolConfirmation(host.executionPolicy, tool.confirmationRequired(args), tool.alwaysConfirm)) {
                val decision = withTimeoutOrNull(confirmationTimeoutMillis) {
                    confirmation.confirm(ToolConfirmationRequest(call, args), host)
                } ?: return ToolResult(call.id, "操作确认超时（用户未在时限内回应），该操作已取消。", true)
                if (decision != ConfirmationDecision.Approved) {
                    return ToolResult(call.id, "用户未同意执行该操作，操作已取消。请向用户汇报并调整方案。", true)
                }
            }

            val timeout = tool.timeoutMillis ?: toolTimeoutMillis
            require(timeout > 0) { "Tool timeout must be positive" }
            withTimeoutOrNull(timeout) { tool.execute(args, host) } ?: ToolResult(
                callId = call.id,
                content = "这个操作超时了（超过 ${timeout / 1000} 秒还没有结果）。可以换个方式再试，或者先跳过这一步。",
                isError = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult(call.id, "工具执行出错：${error.message ?: error::class.simpleName}", true)
        }.copy(callId = call.id)
    }
}
