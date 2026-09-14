package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.storage.LocalStore
import com.starringbox.staragent.core.storage.LocalStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 群讨论的编排：谁按什么顺序说、跳过怎么算、单个成员失败会不会拖垮整场、停止是否真的停得住。
 *
 * 靠 `AppState` 构造函数上那个 stream 缝注入假的模型调用——不用网络也能把整条链路跑一遍。
 */
class GroupTalkFlowTest {

    private class MemoryStore : LocalStore {
        private val texts = mutableMapOf<String, String>()
        private val blobs = mutableMapOf<String, ByteArray>()

        override fun readText(name: String): String? = texts[name]

        override fun writeText(name: String, text: String) {
            texts[name] = text
        }

        override fun readBytes(name: String): ByteArray? = blobs[name]

        override fun writeBytes(name: String, bytes: ByteArray) {
            blobs[name] = bytes
        }

        override fun delete(name: String) {
            texts.remove(name)
            blobs.remove(name)
        }
    }

    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    /**
     * 造一个"能连上模型"的状态：给第一家服务商配上 Key 和模型。
     *
     * 刻意直接改内存里的列表而不是调 `updateProvider`——后者会把这条测试的假 Key
     * 写进开发机的真实偏好设置里，把用户的 Key 冲掉。
     *
     * [reply] 返回这次发言的内容；返回 `null` 表示这次调用失败。
     */
    private fun readyState(
        reply: (LlmEndpoint, List<LlmTurn>) -> String?,
    ): AppState {
        val state = AppState { endpoint, history ->
            val text = reply(endpoint, history)
            flow {
                if (text == null) {
                    emit(ChatStreamEvent.Failed("测试用的失败"))
                } else {
                    emit(ChatStreamEvent.Delta(text))
                    emit(ChatStreamEvent.Completed)
                }
            }
        }
        val index = state.providers.indexOfFirst { it.id == state.providers.first().id }
        state.providers[index] = state.providers[index].copy(
            apiKey = "test-key",
            models = listOf("test-model"),
        )
        return state
    }

    /**
     * 种子里那个群有三位成员：小星、工作助手、英语老师。
     *
     * 只认开头那句 `你是「X」`——提示里还会列出全部成员名单，按名字找会认错人。
     */
    private fun speakerOf(prompt: String?): String = when {
        prompt == null -> "?"
        prompt.contains("你是「小星」") -> "小星"
        prompt.contains("你是「工作助手」") -> "工作助手"
        prompt.contains("你是「英语老师」") -> "英语老师"
        else -> "?"
    }

    @Test
    fun everyMemberSpeaksOnceInOrder() = runTest {
        val order = mutableListOf<String>()
        val state = readyState { endpoint, _ ->
            order += speakerOf(endpoint.systemPrompt)
            "我说两句。"
        }

        state.sendToGroup("g-1", "一期该不该做真机操作？")

        assertEquals(listOf("小星", "工作助手", "英语老师"), order, "按群成员的顺序依次发言")
    }

    /**
     * 这条是整个功能的命门：**后面发言的人必须看得到前面刚说的话**。
     * 看不到的话，这就不是讨论，只是三个人各答一遍。
     */
    @Test
    fun laterSpeakersSeeWhatEarlierOnesSaid() = runTest {
        val histories = mutableListOf<List<LlmTurn>>()
        val state = readyState { _, history ->
            histories += history
            "我认为应该放二期。"
        }

        state.sendToGroup("g-1", "一期该不该做真机操作？")

        assertEquals(1, histories[0].size, "第一位只看到用户的问题")
        assertTrue(
            histories[1].any { it.text.contains("【小星】我认为应该放二期。") },
            "第二位应当看到小星刚说的话，并且认得出是谁说的",
        )
    }

    @Test
    fun roundsMultiplyTheNumberOfCalls() = runTest {
        val order = mutableListOf<String>()
        val state = readyState { endpoint, _ ->
            order += speakerOf(endpoint.systemPrompt)
            "好。"
        }
        val group = state.group("g-1")!!
        state.saveGroup(group.copy(rounds = 2))

        state.sendToGroup("g-1", "问题")

        assertEquals(6, order.size, "3 位成员 × 2 轮")
        assertEquals("小星", order[3], "第二轮从小星重新开始")
    }

    @Test
    fun skippingIsRecordedAndTheDiscussionGoesOn() = runTest {
        val state = readyState { endpoint, _ ->
            if (speakerOf(endpoint.systemPrompt) == "工作助手") "```skip\n```" else "我有补充。"
        }

        state.sendToGroup("g-1", "问题")

        val messages = state.messagesOfGroup("g-1")
        val skipped = messages.filter { it.body is MessageBody.Skipped }
        assertEquals(1, skipped.size, "只有工作助手跳过")
        assertEquals("a-work", skipped.first().speakerAgentId)

        val spoken = messages.filter { it.author == Author.Agent && it.body is MessageBody.Text }
        assertEquals(2, spoken.size, "跳过不影响前后两位照常发言")
    }

    @Test
    fun skippedMemberIsInvisibleToTheOnesAfterIt() = runTest {
        val histories = mutableListOf<List<LlmTurn>>()
        val state = readyState { endpoint, history ->
            histories += history
            if (speakerOf(endpoint.systemPrompt) == "小星") "```skip\n```" else "我说两句。"
        }

        state.sendToGroup("g-1", "问题")

        // 第三位看到的历史里不该有小星这条——"跳过"就是这次没说
        val third = histories.last()
        assertTrue(
            third.none { it.text.contains("小星") },
            "跳过不该在后续成员的上下文里留下痕迹",
        )
    }

    @Test
    fun oneMembersFailureDoesNotStopTheRest() = runTest {
        val state = readyState { endpoint, _ ->
            if (speakerOf(endpoint.systemPrompt) == "工作助手") null else "我说两句。"
        }

        state.sendToGroup("g-1", "问题")

        val messages = state.messagesOfGroup("g-1")
        assertTrue(
            messages.any { (it.body as? MessageBody.Notice)?.isError == true },
            "失败的成员要留下一条可见的说明",
        )
        assertEquals(
            2,
            messages.count { it.author == Author.Agent && it.body is MessageBody.Text },
            "另外两位照常发言，一个人失败不该拖垮整场",
        )
    }

    @Test
    fun groupRepliesNeverProduceTaskCards() = runTest {
        // 群聊是纯讨论：模型就算吐了任务块，也不该变成草稿卡片
        val state = readyState { _, _ ->
            "我建议先做一期。\n```task\n{\"title\":\"先做一期\",\"recurrence\":\"daily\"}\n```"
        }

        state.sendToGroup("g-1", "问题")

        val messages = state.messagesOfGroup("g-1")
        assertTrue(messages.none { it.body is MessageBody.Draft }, "群里不该出现任务草稿")
        assertTrue(
            messages.none { (it.body as? MessageBody.Text)?.text?.contains("```") == true },
            "原始 JSON 不该露给用户",
        )
    }

    @Test
    fun stoppingTheDiscussionDoesNotCallTheRemainingSpeakers() = runTest {
        var calls = 0
        val state = AppState { _, _ ->
            calls++
            flow {
                delay(1_000)
                emit(ChatStreamEvent.Delta("说完了。"))
                emit(ChatStreamEvent.Completed)
            }
        }
        val index = state.providers.indexOfFirst { it.id == state.providers.first().id }
        state.providers[index] = state.providers[index].copy(
            apiKey = "test-key",
            models = listOf("test-model"),
        )

        val job = launch { state.sendToGroup("g-1", "问题") }
        // 第一位说完、第二位说了一半的时候按下停止
        advanceTimeBy(1_500)
        job.cancel()
        advanceUntilIdle()

        assertEquals(2, calls, "停止之后不该再叫第三位")
        assertFalse(state.isGroupBusy("g-1"), "停止之后要放开这一群，否则再也发不了消息")
        assertTrue(
            state.messagesOfGroup("g-1").any { (it.body as? MessageBody.Notice)?.text == "已停止" },
            "被中断的那位要留下一条说明",
        )
    }

    @Test
    fun mentioningOneMemberOnlyCallsThatOne() = runTest {
        val order = mutableListOf<String>()
        val state = readyState { endpoint, _ ->
            order += speakerOf(endpoint.systemPrompt)
            "我说两句。"
        }

        state.sendToGroup("g-1", "@英语老师 你怎么看")

        assertEquals(listOf("英语老师"), order, "@ 了谁就只叫谁，其余这轮不发言")
    }

    @Test
    fun unrecognisedMentionFallsBackToEveryone() = runTest {
        val order = mutableListOf<String>()
        val state = readyState { endpoint, _ ->
            order += speakerOf(endpoint.systemPrompt)
            "我说两句。"
        }

        state.sendToGroup("g-1", "@一个不在群里的人 你们说说")

        assertEquals(3, order.size, "@ 不到具体某个人时按普通消息处理，照常轮流")
    }
}
