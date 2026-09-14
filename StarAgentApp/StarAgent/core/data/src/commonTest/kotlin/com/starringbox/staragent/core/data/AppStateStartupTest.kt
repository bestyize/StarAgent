package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.storage.LocalStore
import com.starringbox.staragent.core.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 启动路径的回归测试。
 *
 * 内置服务商曾经和演示内容挤在同一个 `Seed.populate` 里。改成"有存档就还原"之后，
 * 走还原路径时 `providers` 是空的，而 AppState 初始化里有一句 `providers.first()`——
 * 结果是：**用户一旦有了存档，App 就再也起不来**。
 *
 * 所以每个用例都构造两次 AppState：第一次落盘，第二次走还原路径。
 */
class AppStateStartupTest {

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

    /** LocalStores 是全局单例，跑完换回一个干净的，免得影响同模块的其它测试。 */
    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    @Test
    fun restorePathStillHasTheBuiltInProviders() {
        LocalStores.install(MemoryStore())

        AppState() // 首次：灌种子并落盘
        val restarted = AppState() // 再次：走的是还原路径

        assertTrue(
            restarted.providers.isNotEmpty(),
            "还原路径下内置服务商不能为空——否则初始化会直接崩在 providers.first() 上",
        )
        assertTrue(restarted.providers.any { it.id == "deepseek" }, "内置服务商应当完整")
    }

    @Test
    fun deletedAgentDoesNotComeBackAfterRestart() {
        LocalStores.install(MemoryStore())

        val first = AppState()
        val victim = first.agents.first().id
        first.deleteAgent(victim)

        val restarted = AppState()

        assertTrue(
            restarted.agents.none { it.id == victim },
            "删掉的智能体不该在冷启后复活——那正是持久化要解决的问题",
        )
    }

    @Test
    fun groupsSurviveARestart() {
        LocalStores.install(MemoryStore())

        val first = AppState()
        first.saveGroup(
            AgentGroup(
                id = "g-9",
                name = "临时群",
                memberIds = listOf("a-xing", "a-work"),
                rounds = 2,
            )
        )

        val restarted = AppState()
        val group = restarted.group("g-9")

        assertNotNull(group, "群组应当跟着存档一起回来")
        assertEquals("临时群", group.name)
        assertEquals(2, group.rounds)
        assertEquals(listOf("a-xing", "a-work"), group.memberIds)
    }

    @Test
    fun deletingAMemberAlsoTakesItOutOfTheGroups() {
        LocalStores.install(MemoryStore())

        val state = AppState()
        state.saveGroup(
            AgentGroup(id = "g-9", name = "临时群", memberIds = listOf("a-xing", "a-work"))
        )

        state.deleteAgent("a-work")

        assertEquals(
            listOf("a-xing"),
            state.group("g-9")?.memberIds,
            "人删了群成员也得摘掉，否则成员计数对不上",
        )
    }

    /**
     * 加群组之前写下的存档，必须还能读出来。
     *
     * 这条守的是这次改动里最贵的那个失败：JSON 解码失败 → `load()` 返回 null →
     * AppState 静默退回种子数据 → 用户所有智能体、任务、记忆、对话无声消失。
     *
     * 所以固定用的是"真实旧格式"：顶层没有 `groups`，消息里没有 `speakerAgentId` / `roundIndex`，
     * `MessageBody` 用的是带全限定名的多态 `type` 字段（照磁盘上那份存档抄的）。
     */
    @Test
    fun legacySaveWithoutGroupsOrSpeakerFieldsStillLoads() {
        val store = MemoryStore()
        store.writeText(
            "state.json",
            """
            {
              "agents": [
                {"id":"a-legacy","name":"老智能体","glyph":"老","persona":"以前的人设","modelId":"deepseek:0"}
              ],
              "tasks": [],
              "memories": [],
              "skills": [],
              "channels": [],
              "conversations": {
                "a-legacy": [
                  {"id":"m-1","author":"User",
                   "body":{"type":"com.starringbox.staragent.core.model.MessageBody.Text","text":"你好"},
                   "timeLabel":"刚刚"}
                ]
              }
            }
            """.trimIndent(),
        )
        LocalStores.install(store)

        val state = AppState()

        assertEquals(1, state.agents.size, "旧存档必须原样恢复，不能被种子数据盖掉")
        assertEquals("老智能体", state.agents.first().name)
        assertTrue(state.groups.isEmpty(), "旧存档里没有群组，读出来就该是空的")

        val restored = state.messagesOf("a-legacy").first()
        assertEquals("你好", (restored.body as MessageBody.Text).text)
        assertNull(restored.speakerAgentId, "旧消息没有发言人字段，应当补成 null 而不是解码失败")
        assertNull(restored.roundIndex)
    }

    /**
     * 消息 id 的计数器不落盘，冷启后从 0 开始——新消息会拿到跟存档里一样的 id。
     * 界面用 id 当列表 key，重复 key 会让 LazyColumn 直接抛异常。
     *
     * 所以还原时要修两件事：计数器顶到已有 id 之上；已经撞上的重新编号（内容不能丢）。
     */
    @Test
    fun collidingMessageIdsAreRepairedOnLoad() {
        val store = MemoryStore()
        store.writeText(
            "state.json",
            """
            {
              "agents": [
                {"id":"a-1","name":"老智能体","glyph":"老","persona":"","modelId":"deepseek:0"}
              ],
              "tasks": [], "memories": [], "skills": [], "channels": [],
              "conversations": {
                "a-1": [
                  {"id":"m-1","author":"User",
                   "body":{"type":"com.starringbox.staragent.core.model.MessageBody.Text","text":"一"},
                   "timeLabel":"刚刚"},
                  {"id":"m-1","author":"Agent",
                   "body":{"type":"com.starringbox.staragent.core.model.MessageBody.Text","text":"二"},
                   "timeLabel":"刚刚"},
                  {"id":"m-9","author":"User",
                   "body":{"type":"com.starringbox.staragent.core.model.MessageBody.Text","text":"三"},
                   "timeLabel":"刚刚"}
                ]
              }
            }
            """.trimIndent(),
        )
        LocalStores.install(store)

        val state = AppState()
        val messages = state.messagesOf("a-1")
        val ids = messages.map { it.id }

        assertEquals(3, messages.size, "重编号只改身份，一条消息都不能丢")
        assertEquals(ids.size, ids.toSet().size, "id 必须唯一，否则列表 key 会重复")
        assertEquals(listOf("一", "二", "三"), messages.map { (it.body as MessageBody.Text).text })

        val fresh = state.nextId("m")
        assertTrue(fresh !in ids, "接着发的新消息不能再撞上已有的 id，实际拿到 $fresh")
    }
}
