package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.tools.ports.UserInputPort
import kotlinx.coroutines.CompletableDeferred

/**
 * 交互确认端口实现。
 *
 * 基于内存中的 [CompletableDeferred] 注册表实现原地挂起与按 ID 精确唤醒。
 */
class AppUserInputPort(
    private val state: AppState,
) : UserInputPort {

    private val pendingInputs = mutableMapOf<String, CompletableDeferred<String>>()

    override suspend fun requestInput(
        agentId: String,
        question: String,
        options: List<String>,
        placeholder: String?,
    ): String {
        val questionId = state.nextId("q")
        val deferred = CompletableDeferred<String>()
        pendingInputs[questionId] = deferred

        try {
            state.postQuestion(
                agentId = agentId,
                questionId = questionId,
                question = question,
                options = options,
                placeholder = placeholder,
            )
            return deferred.await()
        } finally {
            pendingInputs.remove(questionId)
        }
    }

    fun submitAnswer(questionId: String, answer: String): Boolean {
        val deferred = pendingInputs.remove(questionId) ?: return false
        return deferred.complete(answer)
    }

    fun cancelQuestion(questionId: String) {
        pendingInputs.remove(questionId)?.cancel()
    }
}
