package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.model.ExecutionStep
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult

/**
 * 把工具调用计算为用户可读的执行步骤。
 */
fun executionStepsFor(
    calls: List<ToolCall>,
    results: List<ToolResult>,
    labelFor: (ToolCall) -> String = { it.name },
): List<ExecutionStep> {
    val doneIds = results.map { it.callId }.toSet()
    return calls.map { call ->
        ExecutionStep(
            label = labelFor(call),
            done = call.id in doneIds,
        )
    }
}
