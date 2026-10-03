package com.starringbox.staragent.samples.headless

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.runtime.AgentLoop
import com.starringbox.staragent.core.runtime.AgentLoopMachine
import com.starringbox.staragent.core.runtime.AgentLoopStep
import com.starringbox.staragent.core.runtime.LoopOutcome
import com.starringbox.staragent.core.runtime.TurnEvent
import com.starringbox.staragent.core.runtime.TurnRunner
import com.starringbox.staragent.core.runtime.extensions.AgentExtension
import com.starringbox.staragent.core.runtime.extensions.AgentExtensions
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ports.NoopMemoryPort
import com.starringbox.staragent.core.tools.ports.NoopTaskPort
import com.starringbox.staragent.core.tools.ports.NoopWebPort
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val input = listOf(LlmTurn(LlmRole.User, "Calculate 19 + 23"))
private val call = ToolCall("sum-1", "add", "{\"a\":19,\"b\":23}")

private fun calculate(args: JsonObject): String =
    ((args.getValue("a").jsonPrimitive.int) + (args.getValue("b").jsonPrimitive.int)).toString()

private object AddTool : Tool {
    override val definition = ToolDefinition("add", "Add two integers", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("a") { put("type", "integer") }
            putJsonObject("b") { put("type", "integer") }
        }
        put("required", buildJsonArray { add("a"); add("b") })
    })
    override suspend fun execute(args: JsonObject, host: ToolHost) = ToolResult("", calculate(args))
}

private object CalculatorExtension : AgentExtension {
    override val id = "calculator"
    override fun tools(agent: Agent) = listOf(AddTool)
    override fun contributePrompt(agent: Agent, toolsAvailable: Boolean): String? =
        if (toolsAvailable) "Use add for arithmetic." else null
}

/** 可重复的模型边界输入。宿主可在同一契约上换成真实模型连接器。 */
private class ScriptedModel {
    var requests = 0
    var lastHistory = emptyList<LlmTurn>()

    fun reply(history: List<LlmTurn>): Pair<String, List<ToolCall>> {
        requests++
        lastHistory = history
        val results = history.last().toolResults
        return if (results.isEmpty()) "" to listOf(call)
        else {
            check(results.single().callId == call.id && !results.single().isError)
            "Answer: ${results.single().content}" to emptyList()
        }
    }
}

/** 同步宿主：不使用协程，直接消费核心给出的动作。 */
private fun runMachine(): Pair<String, List<LlmTurn>> {
    val machine = AgentLoopMachine(input)
    val model = ScriptedModel()
    var step = machine.start()
    while (true) {
        step = when (val next = step) {
            is AgentLoopStep.CallModel -> {
                val (text, calls) = model.reply(next.history)
                machine.modelReply(text, calls)
            }
            is AgentLoopStep.CallTools -> machine.toolResults(next.calls.map {
                ToolResult(it.id, calculate(Json.parseToJsonElement(it.arguments).jsonObject))
            })
            is AgentLoopStep.Done -> {
                check(model.requests == 2)
                return next.text to machine.history()
            }
            else -> error("Unexpected machine result: $next")
        }
    }
}

/** Kotlin 协程宿主：使用与移动 App 相同的 TurnRunner，显式装配扩展和释放回调。 */
private fun runCoroutineHost(): Pair<String, List<LlmTurn>> = runBlocking {
    val model = ScriptedModel()
    val extensions = AgentExtensions(listOf(CalculatorExtension))
    val agent = Agent("headless", "Calculator", "", "", "scripted")
    var released = 0
    var completedTool = false
    val runner = TurnRunner(AgentLoop(stream = { _, history ->
        flow {
            val (text, calls) = model.reply(history)
            if (text.isNotEmpty()) emit(ChatStreamEvent.Delta(text))
            if (calls.isNotEmpty()) emit(ChatStreamEvent.ToolCallsReady(calls))
            emit(ChatStreamEvent.Completed)
        }
    }))
    val result = runner.run(
        endpoint = LlmEndpoint(LlmProtocol.OpenAi, "", "", "scripted", extensions.promptSections(agent, true).joinToString("\n")),
        history = input,
        registry = extensions.registryFor(agent),
        createHost = {
            ToolHost(
                memory = NoopMemoryPort, tasks = NoopTaskPort, web = NoopWebPort,
                agentId = agent.id, conversationKey = agent.id,
                onTurnFinished = { released++ },
            )
        },
        onEvent = { if (it is TurnEvent.StepsChanged && it.steps.single().done) completedTool = true },
    )
    check(result.outcome == LoopOutcome.Done("Answer: 42"))
    check(result.calls.size == 1 && model.requests == 2 && released == 1 && completedTool)
    result.text to model.lastHistory
}

fun main() {
    val direct = runMachine()
    val hosted = runCoroutineHost()
    check(direct == hosted) { "Execution mechanisms produced different core behavior" }
    println("HEADLESS_OK answer=42 model_calls=2 tool_calls=1 cleanup=1")
    println("Synchronous machine and coroutine host produced identical results and history.")
}
