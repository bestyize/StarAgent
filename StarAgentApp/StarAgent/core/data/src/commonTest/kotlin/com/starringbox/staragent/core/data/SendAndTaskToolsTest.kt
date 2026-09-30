package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工具接进 `send()` 之后，**两条曾经真的坏过的线**各钉一颗钉子。
 *
 * 它们都是复审翻出来的、而不是写的时候想到的——所以留着，别让它们再悄悄滑回去。
 *
 * 为什么单独一个文件而不是塞进 [SendWithToolsTest]：那里测的是系统提示拼出来的字符串，
 * 这里测的是**跑一次之后对话里落了什么**。两类断言混在一起，看名字分不清哪个在守什么。
 */
class SendAndTaskToolsTest {

    /**
     * 借用即还——JVM 上这些键就是桌面端在用的那一份。见 [SettingsSandbox]。
     *
     * `enter()` 在 `AppState` 构造之前清空这两个键：否则会把开发机上真实的 Key 读回来，
     * 这几条测试就会真的发一次网络请求（下面 [readyState] 显式给假 Key 就够了）。
     */
    private val sandbox = SettingsSandbox(listOf("apikey:deepseek", "models:deepseek"))

    @BeforeTest
    fun setUp() {
        sandbox.enter()
        // 每个用例一份全新的内存存储：`AppState()` 走"没有存档 → 灌种子"那条路，
        // 种子内容才是确定的。共用一份的话第二个用例会读到上一个用例写下的存档。
        LocalStores.install(MemoryStore())
    }

    @AfterTest
    fun tearDown() {
        sandbox.leave()
        LocalStores.install(MemoryStore())
    }

    /**
     * 造一个"能连上模型"的状态：给 deepseek 配上假 Key。
     *
     * 智能体（含小星）的 modelId 是 `deepseek:0`，所以配上这家就够了，
     * 模型名不需要是真实存在的——[stream] 是假的，根本不会发出去。
     */
    private fun readyState(
        stream: (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent>,
    ): AppState {
        val state = AppState(stream = stream)
        state.updateProvider(
            state.providerById("deepseek")!!.copy(apiKey = "test-key", models = listOf("test-model")),
        )
        return state
    }

    /**
     * 🔴 曾经坏过：`Stopped` 且正文为空时，用户看不到停下的原因。
     *
     * `Stopped` 那一臂没有收尾，控制流继续往下走到 `raw.isBlank()`，把刚写下的
     * "这个任务需要的步骤有点多…"覆盖成了「模型没有返回内容」。
     *
     * 触发条件不是臆想的：8 轮全是工具调用、一句正文都没吐，`maxIterations` 正好被撞上。
     */
    @Test
    fun stoppedWithNoProseStillShowsTheReasonInsteadOfSayingNothingCameBack() = runTest {
        val state = readyState { _, _ ->
            flow {
                // 每一轮都只要工具、一个字都不说；12 轮足够越过 8 次上限
                emit(
                    ChatStreamEvent.ToolCallsReady(
                        listOf(ToolCall("c", "recall_memory", "{\"query\":\"x\"}")),
                    )
                )
                emit(ChatStreamEvent.Completed)
            }
        }
        val agent = state.agent("a-xing") ?: error("种子里应当有小星")

        state.send(agent.id, "翻一下我记过的东西")

        val bodies = state.messagesOf(agent.id).map { it.body }
        assertTrue(
            bodies.any { it is MessageBody.Notice && it.text.contains("步骤有点多") },
            "上限停下时用户必须看到原因，实际消息：$bodies",
        )
        assertTrue(
            bodies.none { it is MessageBody.Notice && it.text == "模型没有返回内容" },
            "原因不该被『模型没有返回内容』盖掉，实际消息：$bodies",
        )
    }

    /**
     * 🔴 曾经坏过：`runTask` 借着 `endpointFor(agent)` 顺带继承了工具。
     *
     * 定时执行还停在老的单次请求上（`ToolCallsReady` 是丢掉的），带上工具就会让模型
     * 改用工具调用作答，而这条路不会执行，用户最终只拿到「模型没有返回内容」。
     * 它必须跟自己接工具之前一模一样：请求里不带工具、提示里带文本块指令。
     */
    @Test
    fun runTaskSendsNoToolsAndKeepsTheTextBlockPrompt() = runTest {
        val sent = mutableListOf<LlmEndpoint>()
        val state = readyState { endpoint, _ ->
            sent += endpoint
            flow {
                emit(ChatStreamEvent.Delta("汇总好了"))
                emit(ChatStreamEvent.Completed)
            }
        }
        val agent = state.agent("a-xing") ?: error("种子里应当有小星")

        // 把种子里的任务全部暂停，只留下面这一条到点——这样这一场恰好发一次请求，
        // 断言才有确定的落点。（暂停的任务既不满足 Active，也不会被 scheduleUnplanned 补期。）
        state.tasks.indices.forEach { index ->
            state.tasks[index] = state.tasks[index].copy(status = TaskStatus.Paused)
        }
        val mine = state.newTask(agent.id)
        state.saveTask(mine.copy(title = "跑一次就好", recurrence = Recurrence.Once, scheduleLabel = "今天 10:00"))
        val index = state.tasks.indexOfFirst { it.id == mine.id }
        state.tasks[index] = state.tasks[index].copy(status = TaskStatus.Active, nextRunAt = 1L)

        state.runDueTasks()

        assertEquals(1, sent.size, "只该跑这一条到点的任务")
        val endpoint = sent.single()
        assertTrue(
            endpoint.tools.isEmpty(),
            "定时执行不该带工具，实际带了 ${endpoint.tools.map { it.name }}",
        )
        assertTrue(
            endpoint.systemPrompt.orEmpty().contains("```memory"),
            "定时执行的提示该保留文本块指令：它仍是靠文本块工作的那条路",
        )

        // 反证：同一个智能体走对话那条路时**是**带工具的。
        // 没有这一句，上面"不带工具"可能是 endpointFor 压根不装工具造成的，而不是 toolsAvailable 在起作用。
        assertTrue(
            state.endpointFor(agent)!!.tools.isNotEmpty(),
            "单聊的 endpoint 应当带工具——否则上面那条断言测的不是这次改动",
        )
    }
}
