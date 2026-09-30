package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 记忆检索。
 *
 * [AppState.recallMemories] 是 recall_memory 工具的落点：模型问"我记得什么关于咖啡的"，
 * 从这里拿到的是**按相关度排过序的**那几条，而不是全部记忆的前几条。所以这里的用例
 * 盯的是排序有没有真的发生，而不是"能不能返回"。
 *
 * 一期是关键词匹配，不是向量。这条用例同时把那个粗糙的算法钉住——换实现时它要么跟着改，
 * 要么就是真换了语义。
 */
class MemoryRecallTest {

    /** 和同模块其它测试一样：LocalStores 是全局单例，跑完清干净。 */
    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    private fun state(): AppState {
        LocalStores.install(MemoryStore())
        return AppState()
    }

    /**
     * 命中整串的排在只是零散命中的前面。
     *
     * 两条都含着.query 的字，所以**只有排序能解释结果**——插入顺序故意是反的
     * （后加的排在前面），要是没排序，返回的就该是插入顺序。
     */
    @Test
    fun theStrongerMatchRanksFirst() {
        val state = state()
        val agentId = state.agents.first().id
        state.addMemory(agentId, MemoryCategory.Preference, "喜欢喝咖啡")
        // 查询串被拆开了：整串不命中，只按逐字命中计数，分数远低于上一条
        state.addMemory(agentId, MemoryCategory.Habit, "咖 啡 都分开写")

        val found = state.recallMemories(agentId, "咖啡", limit = 10)

        assertEquals(2, found.size, "两条都该被召回")
        assertEquals("喜欢喝咖啡", found.first().text, "整串命中的那条必须排在前面")
    }

    @Test
    fun memoriesWithNoHitAreDroppedRatherThanRankedLast() {
        val state = state()
        val agentId = state.agents.first().id
        // 先加无关的那条：它在池子里排在前面，不筛掉的话会第一个返回
        state.addMemory(agentId, MemoryCategory.Person, "常一起吃饭的同事是小林")
        state.addMemory(agentId, MemoryCategory.Preference, "喜欢喝咖啡")

        val found = state.recallMemories(agentId, "咖啡", limit = 10)

        assertEquals(listOf("喜欢喝咖啡"), found.map { it.text })
    }

    /** 没给关键词就是"把我知道的都给我"，不该返回空。 */
    @Test
    fun aBlankQueryReturnsTheWholePool() {
        val state = state()
        val agentId = state.agents.first().id
        state.addMemory(agentId, MemoryCategory.Preference, "喜欢喝咖啡")
        state.addMemory(agentId, MemoryCategory.Habit, "早上看资讯")

        val before = state.memoriesOf(agentId).size
        val found = state.recallMemories(agentId, "   ", limit = 10)

        assertEquals(before, found.size, "空查询应当返回整个池子，而不是空列表")
        assertTrue(found.isNotEmpty())
    }

    @Test
    fun theLimitIsRespected() {
        val state = state()
        val agentId = state.agents.first().id
        repeat(3) { index -> state.addMemory(agentId, MemoryCategory.Habit, "咖啡 $index") }

        assertEquals(1, state.recallMemories(agentId, "咖啡", limit = 1).size)
        assertEquals(2, state.recallMemories(agentId, "咖啡", limit = 2).size)
    }

    /** 检索只在**这个智能体**的记忆里找——端口那层是分智能体的。 */
    @Test
    fun otherAgentsMemoriesAreNotSearched() {
        val state = state()
        val (first, second) = state.agents.take(2).map { it.id }
        state.addMemory(second, MemoryCategory.Preference, "喜欢喝咖啡")

        assertTrue(
            state.recallMemories(first, "咖啡", limit = 10).isEmpty(),
            "不该把别的智能体的记忆翻出来",
        )
    }
}
