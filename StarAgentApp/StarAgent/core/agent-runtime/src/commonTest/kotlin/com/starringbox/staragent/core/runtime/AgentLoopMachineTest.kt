package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentLoopMachineTest {
    private val calls = listOf(ToolCall("a", "first", "{}"), ToolCall("b", "second", "{}"))

    @Test
    fun toolsAreMatchedAndOrderedBeforeNextModelRequest() {
        val input = listOf(LlmTurn(LlmRole.User, "request"))
        val run = AgentLoopMachine(input)
        assertEquals(AgentLoopStep.CallModel(input), run.start())
        assertEquals(AgentLoopStep.CallTools(calls), run.modelReply("working", calls))
        val next = run.toolResults(listOf(ToolResult("b", "second result"), ToolResult("a", "first result")))
            as AgentLoopStep.CallModel
        assertEquals(listOf("a", "b"), next.history.last().toolResults.map { it.callId })
        assertEquals(listOf(LlmRole.User, LlmRole.Assistant, LlmRole.User), next.history.map { it.role })
        assertEquals(AgentLoopStep.Done("finished"), run.modelReply("finished"))
        assertEquals(AgentLoopPhase.Finished, run.phase)
        assertEquals(2, run.iterations)
    }

    @Test
    fun iterationBudgetCountsModelRequestsAndPreservesCompletedTools() {
        val run = AgentLoopMachine(maxIterations = 1)
        run.start()
        run.modelReply("", calls)
        assertEquals(AgentLoopStep.IterationLimitReached, run.toolResults(listOf(ToolResult("a", "1"), ToolResult("b", "2"))))
        assertEquals(1, run.iterations)
        assertEquals(2, run.history().size)
        assertEquals(listOf("1", "2"), run.history().last().toolResults.map { it.content })
    }

    @Test
    fun zeroBudgetProducesNoModelRequest() {
        val run = AgentLoopMachine(maxIterations = 0)
        assertEquals(AgentLoopStep.IterationLimitReached, run.start())
        assertEquals(0, run.iterations)
    }

    @Test
    fun modelFailureNeverSchedulesTools() {
        val run = AgentLoopMachine()
        run.start()
        assertEquals(AgentLoopStep.Failed("network error"), run.modelReply("partial", calls, "network error"))
        assertEquals(emptyList(), run.history())
        assertFailsWith<IllegalStateException> { run.toolResults(emptyList()) }
    }

    @Test
    fun refusesMissingDuplicateAndForeignToolResultsWithoutAdvancing() {
        val run = AgentLoopMachine()
        run.start()
        run.modelReply("", calls)
        for (invalid in listOf(
            listOf(ToolResult("a", "1")),
            listOf(ToolResult("a", "1"), ToolResult("a", "2")),
            listOf(ToolResult("a", "1"), ToolResult("foreign", "2")),
        )) {
            assertFailsWith<IllegalArgumentException> { run.toolResults(invalid) }
            assertEquals(AgentLoopPhase.AwaitingTools, run.phase)
            assertEquals(1, run.iterations)
        }
        assertEquals(AgentLoopPhase.AwaitingModel, run.toolResults(listOf(ToolResult("a", "1"), ToolResult("b", "2"))).let { run.phase })
    }

    @Test
    fun rejectsAmbiguousCallIdentifiersBeforeSchedulingSideEffects() {
        val run = AgentLoopMachine()
        run.start()
        assertFailsWith<IllegalArgumentException> { run.modelReply("", listOf(calls[0], calls[0])) }
        assertFailsWith<IllegalArgumentException> { run.modelReply("", listOf(calls[0].copy(id = ""))) }
        assertEquals(AgentLoopPhase.AwaitingModel, run.phase)
        assertEquals(emptyList(), run.history())
    }

    @Test
    fun cancellationRejectsLateResultsAndFurtherStart() {
        val run = AgentLoopMachine()
        run.start()
        run.modelReply("", calls)
        assertEquals(AgentLoopStep.Cancelled, run.cancel())
        assertEquals(AgentLoopStep.Cancelled, run.cancel())
        assertFailsWith<IllegalStateException> { run.modelReply("late") }
        assertFailsWith<IllegalStateException> { run.toolResults(listOf(ToolResult("a", "late"))) }
        assertFailsWith<IllegalStateException> { run.start() }
    }

    @Test
    fun returnedListsAndOriginalInputCannotMutatePendingRunState() {
        val original = mutableListOf(LlmTurn(LlmRole.User, "original"))
        val run = AgentLoopMachine(original)
        original.clear()
        val advertised = (run.start() as AgentLoopStep.CallModel).history as MutableList<LlmTurn>
        advertised.clear()
        val mutableCalls = calls.toMutableList()
        val pending = run.modelReply("working", mutableCalls) as AgentLoopStep.CallTools
        mutableCalls.clear()
        (pending.calls as MutableList<ToolCall>).clear()
        run.toolResults(listOf(ToolResult("a", "1"), ToolResult("b", "2")))
        assertEquals("original", run.history().first().text)
        assertEquals(calls, run.history()[1].toolCalls)
    }

    @Test
    fun runsHaveIndependentState() {
        val first = AgentLoopMachine()
        val second = AgentLoopMachine()
        first.start()
        second.start()
        first.modelReply("", calls)
        assertEquals(AgentLoopStep.Done("second"), second.modelReply("second"))
        assertEquals(AgentLoopPhase.AwaitingTools, first.phase)
        assertEquals(emptyList(), second.history())
    }
}
