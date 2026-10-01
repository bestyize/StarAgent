package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuestionEndToEndFlowTest {

    @AfterTest
    fun tearDown() {
        LocalStores.install(MemoryStore())
    }

    @Test
    fun agentAsksQuestionAndContinuesExecutionAfterUserAnswers() = runTest {
        var callCount = 0
        val fakeStream: (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent> = { _, history ->
            flow {
                callCount++
                if (callCount == 1) {
                    // 第 1 轮：模型请求调用 request_user_input
                    emit(
                        ChatStreamEvent.ToolCallsReady(
                            listOf(
                                ToolCall(
                                    id = "call_q1",
                                    name = "request_user_input",
                                    arguments = """{"question":"确定要执行吗？","options":["确认","放弃"]}""",
                                )
                            )
                        )
                    )
                } else {
                    // 第 2 轮：模型收到了用户的回答，给出最终回复
                    val lastTurn = history.lastOrNull()
                    val resultText = lastTurn?.toolResults?.firstOrNull()?.content ?: ""
                    emit(ChatStreamEvent.Delta("根据您的指令（$resultText），已成功处理。"))
                }
                emit(ChatStreamEvent.Completed)
            }
        }

        val state = AppState(stream = fakeStream)
        state.updateProvider(
            state.providerById("deepseek")!!.copy(apiKey = "test-key", models = listOf("test-model")),
        )
        val agent = state.agents.first()

        // 异步发送消息
        val sendJob = async {
            state.send(agent.id, "帮我执行一个危险操作")
        }

        // 让协程跑起来，直到挂起在 requestInput
        yield()

        // 验证第一轮产生了 Question 卡片
        val messages = state.messagesOf(agent.id)
        val questionMsg = messages.firstOrNull { it.body is MessageBody.Question }
        assertNotNull(questionMsg, "对话流中应出现 Question 卡片")
        val qBody = questionMsg.body as MessageBody.Question
        assertEquals("确定要执行吗？", qBody.question)

        // 模拟用户点击「确认」作答
        val answered = state.answerQuestion(agent.id, qBody.id, "确认")
        assertTrue(answered, "answerQuestion 应成功唤醒")

        // 等待整个流程执行完毕
        sendJob.await()

        // 验证第二轮模型给出的最终回复
        val replyMsg = messages.firstOrNull {
            it.author == Author.Agent && it.body is MessageBody.Text && (it.body as MessageBody.Text).text.isNotBlank()
        }
        assertNotNull(replyMsg, "对话流中应出现模型的最终文本回复")
        val textBody = replyMsg.body as MessageBody.Text
        assertTrue(textBody.text.contains("确认"), "最终回复应包含用户的回答")

        // 验证执行步骤卡片中的可读步骤名
        val execMsg = messages.firstOrNull { it.body is MessageBody.Execution }
        assertNotNull(execMsg)
        val execBody = execMsg.body as MessageBody.Execution
        assertEquals(listOf("向你提问"), execBody.steps.map { it.label })
        assertTrue(execBody.steps.all { it.done })
    }
}
