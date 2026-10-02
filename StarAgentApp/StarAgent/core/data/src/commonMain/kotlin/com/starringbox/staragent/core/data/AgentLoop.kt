package com.starringbox.staragent.core.data

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
internal sealed interface LoopOutcome {
    /** 模型不再要工具了，[text] 是它最后说的那段话。 */
    data class Done(val text: String) : LoopOutcome

    /** 撞上步数上限。[reason] 是给用户看的话。 */
    data class Stopped(val reason: String) : LoopOutcome

    data class Failed(val message: String) : LoopOutcome
}

/**
 * 一次多步执行：请求 → 调工具 → 回喂 → 再请求，直到模型不再要工具。
 *
 * [stream] 是**注入**的，不是直接调 ChatCompletion——这样循环能用假 LLM 单测，
 * 不用起 HTTP。这是它能被测的唯一前提。
 *
 * 工具是**串行**执行的：每个工具都可能是一次网络往返，串行让成本可预测，
 * 也跟现有"群里成员依次发言"的形状一致。并行是后续的优化。
 */
internal class AgentLoop(
    private val stream: suspend (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent>,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    /**
     * 单个工具的看门狗，毫秒。**不再是"多久没数据"那种被动超时**——
     * 工具能挂住整整 3 分钟（socketTimeout）到 15 分钟（requestTimeout）而不说一句话，
     * 而循环是串行的、`busy` 还立着，用户眼前只有一个转圈。见 [DEFAULT_TOOL_TIMEOUT_MILLIS]。
     *
     * 留成构造参数是给测试用的：不必为了让一条用例跑完而真等 30 秒。
     */
    private val toolTimeoutMillis: Long = DEFAULT_TOOL_TIMEOUT_MILLIS,
    /** 按需确认等待用户操作的超时，毫秒。默认 5 分钟。留成参数供测试注入短超时。 */
    private val confirmationTimeoutMillis: Long = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
) {

    suspend fun run(
        endpoint: LlmEndpoint,
        history: MutableList<LlmTurn>,
        host: ToolHost,
        registry: ToolRegistry,
        onText: (String) -> Unit,
        onToolStart: (ToolCall) -> Unit,
        onToolEnd: (ToolCall, ToolResult) -> Unit,
    ): LoopOutcome {
        try {
            var iteration = 0

            while (true) {
                iteration++
                if (iteration > maxIterations) {
                    // 明确告诉用户，不要静默截断——他得知道为什么停在这
                    return LoopOutcome.Stopped(
                        "这个任务需要的步骤有点多，我先停在这里。你可以把要求说得更具体一些。"
                    )
                }

                val buffer = StringBuilder()
                var calls: List<ToolCall> = emptyList()
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
                    // 终止条件：模型不再要工具了。这一轮没有工具调用，不必写回历史——
                    // 回喂只发生在"还要继续"的那几轮里，收尾的这轮写进去也没人再看。
                    return LoopOutcome.Done(spoken)
                }

                // assistant 这一轮要按原样写回历史：两家回喂时都要求把带工具调用的
                // assistant 轮带上，否则模型看到自己"没说过"那些调用，会重复请求。
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

                // 工具结果作为一轮回喂。两家的**放置位置不同**由 core:llm 内部处理，
                // 这里只是把结果挂到 turn 上。
                history += LlmTurn(
                    role = LlmRole.User,
                    text = "",
                    toolResults = results,
                )
            }
        } finally {
            host.device.finishOperationSession()
        }
    }

    /**
     * 执行一次调用。**所有失败都变成结果，不变成异常**——
     * 工具的错误是给模型看的信息，它看到"参数格式错误"会自己重试；
     * 异常穿出去只会让用户看到崩溃。
     */
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
            // 🔴 执行权限门控：如果当前策略是「按需（OnDemand）」且工具属于关键/有副作用操作（如建/改/取消任务），
            // 系统先原地向用户弹出提问卡片等待确认。用户允许后才真正执行；若用户拒绝或超时，操作直接取消。
            val isReadOnlyDeviceOp = call.name == "device_use" && (args["operation"] as? JsonPrimitive)?.content == "list_apps"
            if (host.executionPolicy == ExecutionPolicy.OnDemand && tool.requiresConfirmation && !isReadOnlyDeviceOp) {
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
            // 工具超时是**给模型看的信息**，不是异常：它看到"这个操作超时了"可以
            // 换个办法或换个地址重试。
            //
            // 用 `withTimeoutOrNull` 而不是 catch TimeoutCancellationException：
            // TimeoutCancellationException **就是** CancellationException，跟用户点停止
            // 抛的是同一类。去 catch 它就得在 catch 里再分辨"这是我自己的超时还是
            // 外面在取消"——分错了就会把用户的取消吞成一条错误结果，
            // 正是 cba73a0 修掉的那个 bug。返回 null 则天然只可能是这次的超时，
            // 外面的取消仍然从下面那个 catch 原样穿出去。
            withTimeoutOrNull(effectiveTimeout) {
                tool.execute(args, host)
            } ?: ToolResult(
                callId = call.id,
                content = "这个操作超时了（超过 ${effectiveTimeout / 1000} 秒还没有结果）。" +
                    "可以换个方式再试，或者先跳过这一步。",
                isError = true,
            )
        } catch (cancelled: CancellationException) {
            // 用户点了停止。这不是"工具出错"，是调用方在取消——必须原样抛出去，
            // 否则会被当成一条错误结果回喂，循环继续跑下去。
            throw cancelled
        } catch (error: Throwable) {
            ToolResult(
                callId = call.id,
                content = "工具执行出错：${error.message ?: error::class.simpleName}",
                isError = true,
            )
        }.copy(callId = call.id) // 工具自己不知道 callId，在这里补上
    }

    /** 把申请执行的操作翻译成易懂的人话，给确认卡片展示。 */
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

    /** 参数解析失败返回 null，由调用方变成一条错误结果——不抛。 */
    private fun parseArguments(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }.getOrNull()

    companion object {
        /**
         * 步数上限。
         *
         * 8 是个折中：够"查资料 → 读正文 → 记住 → 回答"这种多步任务，
         * 又不至于在模型死循环时烧掉太多 token。
         */
        const val DEFAULT_MAX_ITERATIONS = 8

        /** 按需确认等待用户操作的默认超时：5 分钟（300 秒）。 */
        const val DEFAULT_CONFIRMATION_TIMEOUT_MILLIS = 300_000L

        /**
         * 单个工具的看门狗，30 秒。设计文档 §5.3 三个护栏里的第二条。
         *
         * 为什么必须有：工具是串行跑的，一个挂住的请求会把后面全部堵住，
         * 而共用客户端的超时是 **socket 3 分钟 / 请求 15 分钟**——按那两档兜底，
         * 用户就是对着一个不动的界面等好几分钟，既没进展、也没法知道卡在哪。
         *
         * 30 秒的取舍：正常取网页远用不了这么久；超了就当它没结果，
         * 回喂一句"超时了"，模型还能换个办法。宁可早说，不要干等。
         */
        const val DEFAULT_TOOL_TIMEOUT_MILLIS = 30_000L
    }
}
