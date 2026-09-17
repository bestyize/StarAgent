package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.Channel
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.Skill
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 目录树存档的回归测试。
 *
 * 这一层要守的失败模式是**用户内容无声消失**：解码失败 → 读不出来 → 退回种子数据。
 * 所以每个用例都绕着"数据一条不少"和"坏一个只坏一个"来写。
 *
 * 迁移那几条尤其要紧——开发机、Android 真机、模拟器上都有真实数据，
 * 迁错一次就是数据没了。
 */
class StorageTreeTest {

    private val store = MemoryStore()
    private val persister = StatePersister(store)

    // ── 迁移 ────────────────────────────────────

    @Test
    fun aV1SaveMigratesEveryEntityIntoTheTree() {
        store.writeText(StateTree.LEGACY_STATE, V1_SAVE)

        val loaded = persister.load()
        assertNotNull(loaded, "旧存档必须读得出来")
        assertTrue(loaded.fromLegacyFile, "这是从 state.json 读出来的，还没建树")

        // 读出来只是第一步，树要等这次落盘才建起来——那正是 open 里补的那次 persistState
        persister.save(loaded.state)

        val paths = store.paths()
        listOf(
            StateTree.LAYOUT,
            StateTree.CONFIG,
            "agents/a-xing/agent.json",
            "agents/a-xing/SOUL.md",
            "agents/a-xing/MEMORY.md",
            "agents/a-work/agent.json",
            "skills/web/SKILL.md",
            "skills/digest/SKILL.md",
            "groups/g-1.json",
            "sessions/agents/a-xing.jsonl",
            "sessions/groups/g-1.jsonl",
            "tasks/tasks.json",
            StateTree.LEGACY_BACKUP,
        ).forEach { assertTrue(it in paths, "迁移后应当有 $it，实际有：$paths") }

        assertFalse(StateTree.LEGACY_STATE in paths, "树建好了，旧存档就该退休")
    }

    @Test
    fun migrationKeepsTheOldSaveByteForByte() {
        store.writeText(StateTree.LEGACY_STATE, V1_SAVE)

        val loaded = persister.load()!!
        persister.save(loaded.state)

        assertEquals(
            V1_SAVE,
            store.readText(StateTree.LEGACY_BACKUP),
            "旧存档要原样留档——它是万一新树有问题时唯一的退路",
        )
    }

    @Test
    fun migratedContentIsIdenticalToTheOldSave() {
        store.writeText(StateTree.LEGACY_STATE, V1_SAVE)

        val before = persister.load()!!
        persister.save(before.state)

        val after = StatePersister(store).load()!!
        assertFalse(after.fromLegacyFile, "第二次读到的应当是树，不是旧存档")

        assertEquals(before.state.agents, after.state.agents, "智能体（含人设）必须一字不差")
        assertEquals(before.state.tasks, after.state.tasks)
        assertEquals(before.state.skills, after.state.skills)
        assertEquals(before.state.channels, after.state.channels)
        assertEquals(before.state.groups, after.state.groups)

        // 记忆的 id 是按位置生成的，所以只比"内容与归属"，不比 id
        fun brief(items: List<MemoryItem>) = items.map { Triple(it.agentId, it.category, it.text) }
        assertEquals(brief(before.state.memories), brief(after.state.memories), "记忆一条都不能少")

        assertEquals(
            before.state.conversations.keys,
            after.state.conversations.keys,
            "会话的键要原样回来（含 group:g-1 这种带冒号的）",
        )
        before.state.conversations.forEach { (key, messages) ->
            // 比编出来的 JSONL，而不是比 List<ChatMessage>：`MediaAsset` 刻意没有 equals
            // （内部是 ByteArray），带图的消息用结构比较永远不等。
            assertEquals(
                StateTree.encodeMessages(messages),
                StateTree.encodeMessages(after.state.conversations[key].orEmpty()),
                "会话 $key 的内容必须一字不差",
            )
        }
    }

    @Test
    fun anExistingTreeIsNotMigratedAgain() {
        store.writeText(StateTree.LEGACY_STATE, V1_SAVE)
        persister.save(persister.load()!!.state)

        val second = StatePersister(store).load()!!

        assertFalse(second.fromLegacyFile, "有 layout.json 就该直接读树")
        assertTrue(second.state.agents.isNotEmpty())
        assertNull(store.readText(StateTree.LEGACY_STATE), "旧存档不该复活")
    }

    /**
     * 两个标记同时在，说明上一次迁移写完了树却没来得及删旧存档（写到一半断电）。
     * 这时应当读树，并且把旧存档顺手清掉——而不是再迁一遍。
     */
    @Test
    fun aTreeWithALeftoverV1FileStillReadsTheTreeAndCleansUp() {
        store.writeText(StateTree.LEGACY_STATE, V1_SAVE)
        persister.save(persister.load()!!.state)
        store.writeText(StateTree.LEGACY_STATE, V1_SAVE) // 模拟"没删成"

        val reloaded = StatePersister(store)
        val loaded = reloaded.load()!!
        assertFalse(loaded.fromLegacyFile)

        reloaded.save(loaded.state)
        assertNull(store.readText(StateTree.LEGACY_STATE), "树是完整的，旧存档就该清掉")
    }

    @Test
    fun aFreshInstallHasNothingToLoad() {
        assertNull(persister.load(), "没有 layout.json 也没有 state.json = 全新安装")
    }

    @Test
    fun anUnreadableV1SaveFallsBackButKeepsTheOriginalBytes() {
        store.writeText(StateTree.LEGACY_STATE, "{ 这不是 JSON")

        assertNull(persister.load(), "解不出来就退化为种子数据，不能崩在启动路径上")
        assertEquals(
            "{ 这不是 JSON",
            store.readText(StateTree.CORRUPT_FILE),
            "退化的代价是内容看起来消失了，所以原始内容必须另存一份",
        )
    }

    // ── 坏一个只坏一个 ──────────────────────────

    @Test
    fun oneBrokenAgentFileOnlyCostsThatAgent() {
        writeTree()
        // 把一个 agent.json 弄坏——目录树最大的收益就是把伤害限制在单个文件
        store.writeText("agents/a-work/agent.json", "{ 坏了")

        val loaded = StatePersister(store).load()!!

        assertEquals(1, loaded.state.agents.size, "坏掉一个，其余照常加载")
        assertEquals("a-xing", loaded.state.agents.single().id)
        assertTrue(loaded.issues >= 1, "坏过文件要计数——设置页要把它显示出来")

        assertEquals(
            "{ 坏了",
            store.readText("${StateTree.BROKEN_DIR}/agents_a-work_agent.json"),
            "读不出来的文件要挪到 backups/broken/，不是就地丢掉",
        )
        assertNull(store.readText("agents/a-work/agent.json"), "坏文件已经挪走")
    }

    @Test
    fun oneBrokenLineOnlyCostsThatMessage() {
        writeTree()
        val path = "sessions/agents/a-xing.jsonl"
        val good = store.readText(path)!!
        store.writeText(path, good + "{ 半行坏消息\n")

        val loaded = StatePersister(store).load()!!

        assertEquals(2, loaded.state.conversations["a-xing"]?.size, "坏行不该带走整段对话")
        assertEquals(1, loaded.issues)
    }

    @Test
    fun aBrokenLayoutFallsBackToScanOrderInsteadOfLosingTheTree() {
        writeTree()
        store.writeText(StateTree.LAYOUT, "{ 坏了")

        val loaded = StatePersister(store).load()!!

        assertEquals(2, loaded.state.agents.size, "顺序坏了只是顺序坏了，一条数据都不能少")
        assertTrue(loaded.issues >= 1)
    }

    // ── 顺序 ────────────────────────────────────

    /**
     * 列表顺序在界面上是可见的：`MeScreen` 直接按顺序铺，技能还 `take(4)` 上首页。
     * 而扫描目录只能得到字母序，所以顺序必须单独存。
     */
    @Test
    fun listOrderSurvivesARoundTripEvenWhenItIsNotAlphabetical() {
        val agents = listOf(
            agent("z-1"),
            agent("a-2"),
            agent("m-3"),
        )
        StatePersister(store).save(PersistedState(agents = agents))

        val loaded = StatePersister(store).load()!!

        assertEquals(
            listOf("z-1", "a-2", "m-3"),
            loaded.state.agents.map { it.id },
            "顺序不能被扫描出来的字母序顶掉",
        )
    }

    @Test
    fun skillsComeBackInTheirOriginalOrder() {
        val skills = listOf(skill("zz"), skill("aa"))
        StatePersister(store).save(PersistedState(skills = skills))

        val loaded = StatePersister(store).load()!!

        assertEquals(listOf("zz", "aa"), loaded.state.skills.map { it.id })
    }

    // ── 写：只写变了的 ──────────────────────────

    @Test
    fun savingTheSameStateTwiceWritesNothingTheSecondTime() {
        val state = PersistedState(agents = listOf(agent("a-xing")), skills = listOf(skill("web")))
        StatePersister(store).save(state)

        val again = StatePersister(store)
        again.load()
        store.resetWriteCounts()
        again.save(state)

        assertEquals(0, store.textWrites, "一个字都没改就不该重写任何文件")
        assertEquals(0, store.byteWrites)
    }

    @Test
    fun changingOneAgentDoesNotRewriteTheWholeTree() {
        StatePersister(store).save(
            PersistedState(
                agents = listOf(agent("a-xing"), agent("a-work"), agent("a-english")),
                skills = List(7) { skill("s$it") },
                groups = listOf(AgentGroup("g-1", "群", listOf("a-xing"))),
            )
        )

        val p = StatePersister(store)
        val state = p.load()!!.state
        store.resetWriteCounts()
        // 只改一个智能体的置顶
        p.save(state.copy(agents = state.agents.map { if (it.id == "a-work") it.copy(pinned = true) else it }))

        assertTrue(
            store.textWrites <= 3,
            "改一个智能体只该写它自己那几个文件，实际写了 ${store.textWrites} 个",
        )
    }

    // ── 删除 ────────────────────────────────────

    @Test
    fun deletingAnAgentTakesItsFilesWithIt() {
        val p = StatePersister(store)
        p.save(
            PersistedState(
                agents = listOf(agent("a-xing"), agent("a-work")),
                memories = listOf(MemoryItem("mem-1", "a-work", MemoryCategory.Preference, "只写风险")),
            )
        )
        assertTrue("agents/a-work/MEMORY.md" in store.paths())

        val state = p.load()!!.state
        p.save(state.copy(agents = state.agents.filter { it.id != "a-work" }))

        val leftovers = store.paths().filter { it.startsWith("agents/a-work/") }
        assertTrue(leftovers.isEmpty(), "删掉的智能体不该在树上留下任何东西，实际留下：$leftovers")
    }

    /**
     * 删智能体时不顺手删它的记忆（记忆是用户的内容），所以这些记忆得有个家。
     *
     * 家里住的是 `memories/<agentId>.md`，格式和 `MEMORY.md` 一样——这样 `agentId`
     * 从文件名就能读回来，迁移对它们是**无损**的。这一点是真数据上发现的：
     * 这台机器的存档里就有一批"工作助手"被删掉之后留下的记忆。
     */
    @Test
    fun memoriesOutliveTheAgentTheyBelongedTo() {
        val p = StatePersister(store)
        p.save(
            PersistedState(
                agents = listOf(agent("a-xing"), agent("a-work")),
                memories = listOf(
                    MemoryItem("mem-1", "a-xing", MemoryCategory.Preference, "喜欢简洁"),
                    MemoryItem("mem-2", "a-work", MemoryCategory.Decision, "一期不做真机操作"),
                ),
            )
        )

        val state = p.load()!!.state
        p.save(state.copy(agents = state.agents.filter { it.id != "a-work" }))

        val reloaded = StatePersister(store).load()!!.state
        val orphan = reloaded.memories.single { it.text == "一期不做真机操作" }
        assertEquals(2, reloaded.memories.size, "删人不能顺手删掉他的记忆")
        assertEquals(
            "a-work",
            orphan.agentId,
            "孤儿记忆的归属也得原样留着——抹成空串就是一次静默的信息丢失",
        )
        assertEquals(MemoryCategory.Decision, orphan.category)
    }

    // ── 图片 ────────────────────────────────────

    @Test
    fun imagesMoveToTheirRealExtensionAndKeepTheirBytes() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        store.writeText(StateTree.LEGACY_STATE, V1_SAVE)
        store.writeBytes("media/img-1.bin", bytes)

        val loaded = persister.load()!!
        assertEquals(bytes.size, loaded.state.mediaBytes().single().size, "迁移时图片要真的读出来")

        persister.save(loaded.state)

        assertContentEquals(bytes, store.readBytes("media/img-1.png"), "内容一个字节都不能变")
        assertNull(store.readBytes("media/img-1.bin"), "旧的 .bin 该收掉——树是要给人用文件管理器打开的")

        // 再读一次，图还在
        val reloaded = StatePersister(store).load()!!
        assertContentEquals(bytes, reloaded.state.mediaBytes().single())
    }

    @Test
    fun anUnchangedImageIsNotRewritten() {
        val bytes = ByteArray(870_000)
        val p = StatePersister(store)
        p.save(PersistedState(conversations = mapOf("a-xing" to listOf(imageMessage(bytes)))))

        val state = p.load()!!.state
        store.resetWriteCounts()
        p.save(state)

        assertEquals(0, store.byteWrites, "870 KB 的图一个字都没变，不该重写一遍")
    }

    private fun PersistedState.mediaBytes(): List<ByteArray> =
        conversations.values.flatten()
            .mapNotNull { (it.body as? MessageBody.Image)?.assets }
            .flatten()
            .map { it.bytes }

    // ── 素材 ────────────────────────────────────

    private fun writeTree() {
        persister.save(
            PersistedState(
                agents = listOf(agent("a-xing"), agent("a-work")),
                tasks = listOf(
                    AgentTask("task-1", "a-xing", "汇总", Recurrence.Daily, "每天 09:00", "明天 09:00"),
                ),
                memories = listOf(MemoryItem("mem-1", "a-xing", MemoryCategory.Preference, "喜欢简洁")),
                skills = listOf(skill("web")),
                channels = listOf(Channel("wechat", "微信", "💬", connected = true)),
                groups = listOf(AgentGroup("g-1", "三人行", listOf("a-xing"))),
                conversations = mapOf("a-xing" to plainMessages()),
            )
        )
    }

    private fun plainMessages() = listOf(
        ChatMessage("m-1", Author.User, MessageBody.Text("你好"), "刚刚"),
        ChatMessage("m-2", Author.Agent, MessageBody.Text("在"), "刚刚"),
    )

    private fun imageMessage(bytes: ByteArray) = ChatMessage(
        id = "m-img",
        author = Author.User,
        body = MessageBody.Image(listOf(MediaAsset("img-1", "image/png", bytes)), caption = ""),
        timeLabel = "刚刚",
    )

    private fun agent(id: String) = Agent(
        id = id,
        name = id,
        glyph = "星",
        persona = "$id 的人设",
        modelId = "deepseek:0",
    )

    private fun skill(id: String) = Skill(id, "技能 $id", "说明 $id", "🔧", installed = true)

    /**
     * 一份照着**真实存档的形状**写的 v1：顶层七个键，消息体用带全限定名的多态 `type`，
     * 会话键里有 `group:g-1` 这种带冒号的，图片走 `MediaAsset`（字节不进 JSON）。
     */
    private companion object {
        const val V1_SAVE = """
        {
          "agents": [
            {"id":"a-xing","name":"小星","glyph":"星","persona":"通用助手，回答直接",
             "modelId":"deepseek:0","skillIds":["web","digest"],"canScheduleTasks":true},
            {"id":"a-work","name":"工作助手","glyph":"工","persona":"面向工作场景",
             "modelId":"deepseek:0","skillIds":["digest"]}
          ],
          "tasks": [
            {"id":"task-1","agentId":"a-xing","title":"AI 新闻汇总","recurrence":"Daily",
             "scheduleLabel":"每天 09:00","nextRunLabel":"明天 09:00","status":"Active"}
          ],
          "memories": [
            {"id":"mem-1","agentId":"a-xing","category":"Preference","text":"喜欢简洁"},
            {"id":"mem-2","agentId":"a-xing","category":"Habit","text":"早上看资讯"},
            {"id":"mem-3","agentId":"a-work","category":"Decision","text":"一期不做真机操作"}
          ],
          "skills": [
            {"id":"web","name":"网页检索","summary":"联网搜索","glyph":"🔍","installed":true},
            {"id":"digest","name":"信息汇总","summary":"整理成摘要","glyph":"📋","installed":false}
          ],
          "channels": [
            {"id":"wechat","name":"微信","glyph":"💬","connected":true},
            {"id":"feishu","name":"飞书","glyph":"🔷"}
          ],
          "groups": [
            {"id":"g-1","name":"三人行","memberIds":["a-xing","a-work"]}
          ],
          "conversations": {
            "a-xing": [
              {"id":"m-1","author":"User",
               "body":{"type":"com.starringbox.staragent.core.model.MessageBody.Text","text":"你好"},
               "timeLabel":"昨天 21:04"},
              {"id":"m-2","author":"User",
               "body":{"type":"com.starringbox.staragent.core.model.MessageBody.Image",
                       "assets":[{"id":"img-1","mimeType":"image/png"}],"caption":"看这张"},
               "timeLabel":"昨天 21:05"}
            ],
            "group:g-1": [
              {"id":"m-3","author":"Agent","speakerAgentId":"a-xing","roundIndex":1,
               "body":{"type":"com.starringbox.staragent.core.model.MessageBody.Text","text":"我先说"},
               "timeLabel":"刚刚"}
            ]
          }
        }
        """
    }
}
