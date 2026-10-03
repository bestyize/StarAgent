package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 循环的结局。 */
sealed interface LoopOutcome {
    /** 模型不再要工具了，[text] 是它最后说的那段话。 */
    data class Done(val text: String) : LoopOutcome

    /** 撞上步数上限。[reason] 是给用户看的话。 */
    data class Stopped(val reason: String) : LoopOutcome

    data class Failed(val message: String) : LoopOutcome
}

/**
 * 一次多步执行：请求 → 调工具 → 回喂 → 再请求，直到模型不再要工具。
 *
 * [stream] 是注入的，支持单元测试使用假 LLM，无需启动真实的 HTTP 服务。
 * 纯通用 Kotlin 运行时，不依赖任何 UI 框架。
 */
class AgentLoop(
    private val stream: suspend (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent>,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val toolTimeoutMillis: Long = DEFAULT_TOOL_TIMEOUT_MILLIS,
    private val confirmationTimeoutMillis: Long = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
) {

    suspend fun run(
        endpoint: LlmEndpoint,
        history: MutableList<LlmTurn>,
        host: ToolHost,
        registry: ToolRegistry,
        onText: suspend (String) -> Unit,
        onToolStart: suspend (ToolCall) -> Unit = {},
        onToolEnd: suspend (ToolCall, ToolResult) -> Unit = { _, _ -> },
        onRoundStarted: suspend () -> Unit = {},
    ): LoopOutcome {
        try {
            var iteration = 0

            while (true) {
                iteration++
                if (iteration > maxIterations) {
                    return LoopOutcome.Stopped(
                        "这个任务需要的步骤有点多，我先停在这里。你可以把要求说得更具体一些。"
                    )
                }

                onRoundStarted()

                val buffer = StringBuilder()
                var calls = emptyList<ToolCall>()
                var failure: String? = null

                stream(endpoint, history.toList()).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            buffer.append(event.text)
                            onText(event.text)
                        }
                        is ChatStreamEvent.ToolCallsReady -> calls = event.calls
                        is ChatStreamEvent.Failed -> failure = event.message
                        ChatStreamEvent.Completed -> Unit
                    }
                }

                failure?.let { return LoopOutcome.Failed(it) }

                val spoken = buffer.toString()

                if (calls.isEmpty()) {
                    return LoopOutcome.Done(spoken)
                }

                history += LlmTurn(
                    role = LlmRole.Assistant,
                    text = spoken,
                    toolCalls = calls,
                )

                val results = calls.map { call ->
                    onToolStart(call)
                    val result = execute(call, host, registry)
                    onToolEnd(call, result)
                    result
                }

                history += LlmTurn(
                    role = LlmRole.User,
                    text = "",
                    toolResults = results,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return LoopOutcome.Failed("调用模型出错了：${error.message ?: error::class.simpleName}")
        } finally {
            host.device.finishOperationSession()
        }
    }

    private suspend fun execute(
        call: ToolCall,
        host: ToolHost,
        registry: ToolRegistry,
    ): ToolResult {
        val tool = registry.find(call.name)
            ?: return ToolResult(
                callId = call.id,
                content = "没有名为「${call.name}」的工具。可用的有：" +
                    registry.definitions().joinToString("、") { it.name },
                isError = true,
            )

        val args = parseArguments(call.arguments)
            ?: return ToolResult(
                callId = call.id,
                content = "参数不是合法的 JSON，请重新调用并确保参数是一个 JSON 对象。",
                isError = true,
            )

        return try {
            val isReadOnlyDeviceOp = call.name == "device_use" && (args["operation"] as? JsonPrimitive)?.content == "list_apps"
            val needsConfirmation = tool.alwaysConfirm ||
                (host.executionPolicy == ExecutionPolicy.OnDemand && tool.requiresConfirmation && !isReadOnlyDeviceOp)
            if (needsConfirmation) {
                val operationDesc = describeOperationForConfirmation(call, args)
                val userChoice = withTimeoutOrNull(confirmationTimeoutMillis) {
                    host.userInput.requestInput(
                        agentId = host.agentId,
                        question = "智能体申请执行操作「$operationDesc」，是否允许？",
                        options = listOf("允许", "拒绝"),
                        placeholder = null,
                    )
                } ?: return ToolResult(
                    callId = call.id,
                    content = "操作确认超时（用户未在时限内回应），该操作已取消。",
                    isError = true,
                )

                if (!userChoice.trim().contains("允许")) {
                    return ToolResult(
                        callId = call.id,
                        content = "用户未同意执行该操作（用户选择：$userChoice），操作已取消。请向用户汇报并调整方案。",
                        isError = true,
                    )
                }
            }

            val effectiveTimeout = tool.timeoutMillis ?: toolTimeoutMillis
            withTimeoutOrNull(effectiveTimeout) {
                tool.execute(args, host)
            } ?: ToolResult(
                callId = call.id,
                content = "这个操作超时了（超过 ${effectiveTimeout / 1000} 秒还没有结果）。" +
                    "可以换个方式再试，或者先跳过这一步。",
                isError = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult(
                callId = call.id,
                content = "工具执行出错：${error.message ?: error::class.simpleName}",
                isError = true,
            )
        }.copy(callId = call.id)
    }

    private fun describeOperationForConfirmation(call: ToolCall, args: JsonObject): String =
        when (call.name) {
            "create_task" -> {
                val title = (args["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                if (title != null) "创建托管任务：$title" else "创建托管任务"
            }
            "update_task" -> {
                val id = (args["id"] as? JsonPrimitive)?.content
                val title = (args["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                when {
                    title != null && id != null -> "将任务 $id 改名为「$title」"
                    id != null -> "调整任务 $id 的执行时间"
                    else -> "调整托管任务"
                }
            }
            "cancel_task" -> {
                val id = (args["id"] as? JsonPrimitive)?.content
                if (id != null) "取消托管任务 $id" else "取消托管任务"
            }
            "create_skill" -> {
                val name = (args["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                val description = (args["description"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                "保存技能「${name ?: "未命名"}」${description?.let { "：$it" }.orEmpty()}"
            }
            "install_skill" -> {
                val path = (args["path"] as? JsonPrimitive)?.content
                if (path != null) "从 $path 安装技能" else "安装技能"
            }
            "device_use" -> {
                val op = (args["operation"] as? JsonPrimitive)?.content
                val target = (args["package_name"] as? JsonPrimitive)?.content ?: (args["app_name"] as? JsonPrimitive)?.content
                when (op) {
                    "launch_app" -> if (target != null) "启动手机应用「$target」" else "启动手机应用"
                    "click" -> "点击手机屏幕"
                    "type" -> "在手机屏幕输入文字"
                    "swipe" -> "在手机屏幕滑动"
                    "back" -> "在手机上执行返回"
                    else -> "执行手机操作"
                }
            }
            else -> readableLabelFor(call)
        }

    private fun parseArguments(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }.getOrNull()

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 30
        const val DEFAULT_TOOL_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_CONFIRMATION_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }
}
