package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.MessageBody
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 这里测的是**系统提示的条件注入**，不是循环本身（循环在 AgentLoopTest 里测）。
 *
 * 两件事必须只有一套指令：既发工具、又在提示里要求吐 ```task 块的话，
 * 模型会两套都用——既建了任务，又附一个文本块。
 */
class SendWithToolsTest {

    @Test
    fun systemPromptOmitsTextBlockInstructionsWhenToolsAreSent() {
        val state = AppState()
        val agent = state.agents.first()

        val prompt = state.systemPromptForTest(agent)

        assertTrue(
            !prompt.contains("```task"),
            "发工具时提示里不该再出现 ```task 的格式要求",
        )
        assertTrue(
            !prompt.contains("```memory"),
            "发工具时提示里不该再出现 ```memory 的格式要求",
        )
    }

    @Test
    fun systemPromptKeepsMemoryContextEvenWithTools() {
        // 记忆回灌跟工具是两回事：即便有 recall_memory，最近的事还是要直接给它
        val state = AppState()
        val agent = state.agents.first()
        state.addMemory(agent.id, com.starringbox.staragent.core.model.MemoryCategory.Preference, "爱吃辣")

        val prompt = state.systemPromptForTest(agent)

        assertTrue(prompt.contains("爱吃辣"))
    }

    @Test
    fun systemPromptStillCarriesPersonaAndResponseMode() {
        val state = AppState()
        val agent = state.agents.first()
        val prompt = state.systemPromptForTest(agent)
        assertTrue(prompt.contains(agent.name))
    }
}
