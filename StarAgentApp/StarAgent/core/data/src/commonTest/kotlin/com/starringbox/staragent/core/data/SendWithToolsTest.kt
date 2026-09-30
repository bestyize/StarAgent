package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.MessageBody
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
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

    /**
     * 反方向：不发工具时，文本块指令**必须**在提示里。
     *
     * 没有这条，[systemPromptOmitsTextBlockInstructionsWhenToolsAreSent] 会假绿——
     * 即便注入变成无条件（两套指令永远同时给），那条测试也照样过。
     * 这条钉住的是"条件"另一半，保证降级路径不会在将来被悄悄删掉。
     */
    @Test
    fun systemPromptCarriesTextBlockInstructionsWhenToolsAreNotSent() {
        val state = AppState()
        // 种子里第一位是小星，canScheduleTasks = true，任务块指令该出现
        val agent = state.agents.first()

        val prompt = state.systemPromptForTest(agent, toolsAvailable = false)

        assertTrue(
            prompt.contains("```task"),
            "不发工具时，任务块的格式要求必须还在，否则降级路径形同虚设",
        )
        assertTrue(
            prompt.contains("```memory"),
            "不发工具时，记忆块的格式要求必须还在",
        )
    }

    /**
     * 智能体的 [Agent.canScheduleTasks] 门控：
     * 为 false 的智能体（如陪练英语老师）绝不能注册 `create_task`，
     * 彻底避免模型主动为用户创建定时/托管任务；
     * 但保留被动查询与管理类工具（list/update/cancel）。
     */
    @Test
    fun canScheduleTasksGatesCreateTaskToolRegistration() {
        val state = AppState()
        // 种子里小星 canScheduleTasks = true，英语老师 canScheduleTasks = false
        val canSchedule = state.agents.first { it.canScheduleTasks }
        val cannotSchedule = state.agents.first { !it.canScheduleTasks }

        val canScheduleToolNames = state.registryFor(canSchedule).definitions().map { it.name }
        val cannotScheduleToolNames = state.registryFor(cannotSchedule).definitions().map { it.name }

        // 正对照：允许托管任务的智能体包含 create_task
        assertTrue(canScheduleToolNames.contains("create_task"), "canScheduleTasks 为 true 的智能体必须提供 create_task")
        assertTrue(canScheduleToolNames.contains("list_tasks"))

        // 负对照：不允许托管任务的智能体绝对不能提供 create_task
        assertFalse(cannotScheduleToolNames.contains("create_task"), "canScheduleTasks 为 false 的智能体绝不能注册 create_task")
        // 但保留被动查询与调整能力（如已有任务的管理）以及其他常规工具
        assertTrue(cannotScheduleToolNames.contains("list_tasks"), "被动查询任务能力保留")
        assertTrue(cannotScheduleToolNames.contains("recall_memory"), "基础记忆工具保留")
        assertTrue(cannotScheduleToolNames.contains("web_fetch"), "网络工具保留")
    }
}
