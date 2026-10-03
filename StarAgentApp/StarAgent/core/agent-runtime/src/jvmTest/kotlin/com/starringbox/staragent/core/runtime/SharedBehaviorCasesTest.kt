package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** JSON 用例独立于 Kotlin 写法，供未来宿主或语言实现复用。 */
class SharedBehaviorCasesTest {
    @Test
    fun sharedCasesProduceTheirSpecifiedActionsAndHistory() {
        val text = requireNotNull(javaClass.getResourceAsStream("/agent-loop-cases.v1.json"))
            .bufferedReader().use { it.readText() }
        val document = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, document.getValue("format").jsonPrimitive.int)
        for (entry in document.getValue("cases").jsonArray) {
            val case = entry.jsonObject
            val id = case.string("id")
            val machine = AgentLoopMachine(
                listOf(LlmTurn(LlmRole.User, case.string("initial_text"))),
                case.getValue("max_iterations").jsonPrimitive.int,
            )
            val trace = mutableListOf(describe(machine.start()))
            for (item in case.getValue("events").jsonArray) {
                val event = item.jsonObject
                val step = when (event.string("type")) {
                    "model" -> machine.modelReply(
                        event.string("text"),
                        event["calls"]?.jsonArray?.map {
                            val call = it.jsonObject
                            ToolCall(call.string("id"), call.string("name"), call.string("arguments"))
                        }.orEmpty(),
                        event["failure"]?.jsonPrimitive?.content,
                    )
                    "tools" -> machine.toolResults(event.getValue("results").jsonArray.map {
                        val result = it.jsonObject
                        ToolResult(result.string("call_id"), result.string("content"), result["is_error"]?.jsonPrimitive?.booleanOrNull ?: false)
                    })
                    "cancel" -> machine.cancel()
                    else -> error("Unknown fixture event")
                }
                trace += describe(step)
            }
            val expected = case.getValue("expected").jsonObject
            assertEquals(expected.strings("trace"), trace, id)
            assertEquals(expected.getValue("iterations").jsonPrimitive.int, machine.iterations, id)
            assertEquals(expected.strings("roles"), machine.history().map { it.role.name.lowercase() }, id)
            assertEquals(expected.strings("result_call_ids"), machine.history().last().toolResults.map { it.callId }, id)
        }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun describe(step: AgentLoopStep): String = when (step) {
        is AgentLoopStep.CallModel -> "model:${step.history.size}"
        is AgentLoopStep.CallTools -> "tools:${step.calls.joinToString(",") { it.id }}"
        is AgentLoopStep.Done -> "done:${step.text}"
        is AgentLoopStep.Failed -> "failed:${step.message}"
        AgentLoopStep.IterationLimitReached -> "limit"
        AgentLoopStep.Cancelled -> "cancelled"
    }
}
