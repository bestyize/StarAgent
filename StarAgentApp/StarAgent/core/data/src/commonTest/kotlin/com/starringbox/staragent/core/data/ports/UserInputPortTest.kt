package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.model.MessageBody
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserInputPortTest {

    @Test
    fun requestInputSuspendsUntilAnswered() = runTest {
        val state = AppState()
        val agentId = state.agents.first().id
        val port = state.userInputPort

        val deferredResult = async {
            port.requestInput(
                agentId = agentId,
                question = "确定要提交吗？",
                options = listOf("确认", "取消"),
                placeholder = null,
            )
        }

        yield()

        // 验证对话流中已经出现了挂起中的 Question 卡片
        val messages = state.messagesOf(agentId)
        val questionMsg = messages.firstOrNull { it.body is MessageBody.Question }
        assertNotNull(questionMsg)
        val body = questionMsg.body as MessageBody.Question
        assertEquals("确定要提交吗？", body.question)
        assertEquals(listOf("确认", "取消"), body.options)
        assertNull(body.selectedAnswer, "作答前 selectedAnswer 必须为 null")

        // 模拟用户点击选项作答
        val answered = state.answerQuestion(agentId, body.id, "确认")
        assertTrue(answered, "answerQuestion 应该返回成功")

        // 协程恢复并拿到用户选择
        val result = deferredResult.await()
        assertEquals("确认", result)

        // 验证会话中的卡片状态已更新为已作答
        val updatedBody = state.messagesOf(agentId).first { it.id == questionMsg.id }.body as MessageBody.Question
        assertEquals("确认", updatedBody.selectedAnswer)
    }

    @Test
    fun answerUnknownQuestionReturnsFalse() = runTest {
        val state = AppState()
        val agentId = state.agents.first().id
        assertFalse(state.answerQuestion(agentId, "q-9999", "答案"))
    }
}
