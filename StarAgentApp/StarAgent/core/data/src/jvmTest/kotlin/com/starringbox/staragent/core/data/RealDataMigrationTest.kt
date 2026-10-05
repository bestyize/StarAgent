package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.platform.storage.DesktopLocalStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 拿**这台机器上那份真实存档**跑一遍迁移。
 *
 * 上面那些用例用的都是手写的 fixture——形状是照真存档抄的，但抄的形状不会自己变。
 * 这条不同：它读的是用户真正在用的那份 `~/.staragent/`。迁移是这次改动里唯一
 * **不可逆**的一步，值得有一条拿真数据压过的测试。
 *
 * 三条纪律：
 *
 * 1. **只读真目录**——先整份拷进临时目录，全程只动副本；
 * 2. 换台机器没有这份数据就**跳过**（返回而不是失败），别人跑测试不该被它绊倒；
 * 3. 照样断言，不做"跑通就算"——真数据里每个实体都要在迁移后原样回来。
 */
class RealDataMigrationTest {

    private val realRoot = File(System.getProperty("user.home"), ".staragent")
    private val work = Files.createTempDirectory("staragent-realmigration").toFile()

    @AfterTest
    fun cleanUp() {
        work.deleteRecursively()
    }

    @Test
    fun theRealSaveOnThisMachineSurvivesMigration() {
        if (!File(realRoot, StateTree.LEGACY_STATE).isFile) return // 这台机器上没有旧存档，跳过

        realRoot.copyRecursively(work, overwrite = true)
        val store = DesktopLocalStore(work)
        val rawBefore = store.readText(StateTree.LEGACY_STATE)
        val legacyMedia = store.list("media").filter { it.endsWith(".bin") }

        // ① 迁移前先读一遍：这是"数据应该长什么样"的基准
        //
        // 读和写用**同一个** persister，和 AppState 一致：它记着"上次写出去的是什么"，
        // 增量写和"旧存档什么时候退休"都靠这份记忆。换一个实例保存，等于把记忆丢了。
        val persister = StatePersister(store)
        val before = persister.load()
        assertNotNull(before, "这台机器上的真实存档必须读得出来")
        assertTrue(before.fromLegacyFile, "它现在还是旧格式——这条测试的前提")
        val imagesBefore = before.state.imageBytes()

        // ② 迁移（就是 AppState 启动时会补的那次落盘）
        persister.save(before.state)

        // ③ 再从树里读一遍，逐项对照
        val after = StatePersister(store).load()
        assertNotNull(after, "迁移完必须能从树里读回来")
        assertFalse(after.fromLegacyFile)

        assertEquals(before.state.agents, after.state.agents, "智能体（含人设）必须一字不差")
        assertEquals(before.state.tasks, after.state.tasks)
        assertEquals(before.state.groups, after.state.groups)
        assertEquals(
            before.state.skills.map { it.copy(installed = false) },
            after.state.skills.map { it.copy(installed = false) },
            "技能内容要一致（安装状态另存，单独比）",
        )
        assertEquals(
            before.state.skills.filter { it.installed }.map { it.id }.toSet(),
            after.state.skills.filter { it.installed }.map { it.id }.toSet(),
            "已安装的技能不能变",
        )

        fun memoryKeys(state: PersistedState) =
            state.memories.map { Triple(it.agentId, it.category, it.text) }.sortedBy { it.third }
        assertEquals(memoryKeys(before.state), memoryKeys(after.state), "记忆一条都不能少")

        assertEquals(
            before.state.conversations.keys,
            after.state.conversations.keys,
            "会话的键要原样回来（含 group:g-1 这种带冒号的）",
        )
        before.state.conversations.forEach { (key, messages) ->
            // 比编出来的 JSONL，而不是比 List<ChatMessage>：MediaAsset 没有 equals
            assertEquals(
                StateTree.encodeMessages(messages),
                StateTree.encodeMessages(after.state.conversations[key].orEmpty()),
                "会话 $key 一个字都不能变",
            )
        }

        // ④ 图片要有名有姓地搬过去，字节一个不少
        val imagesAfter = after.state.imageBytes()
        assertEquals(imagesBefore.size, imagesAfter.size, "图片张数不能变")
        imagesAfter.zip(imagesBefore).forEachIndexed { index, (beforeBytes, afterBytes) ->
            assertTrue(
                beforeBytes.contentEquals(afterBytes),
                "第 $index 张图的字节要一模一样",
            )
        }
        assertTrue(
            store.list("media").none { it.endsWith(".bin") },
            "旧的 .bin 该收掉——树是要给人用文件管理器打开的，实际还有：" +
                "${legacyMedia.filter { it in store.list("media") }}",
        )

        // ⑤ 老存档退休，但备份还在，而且是逐字节一致的那一份
        assertNull(store.readText(StateTree.LEGACY_STATE), "树建好之后旧存档就该删掉")
        assertEquals(rawBefore, store.readText(StateTree.LEGACY_BACKUP), "备份必须和原件一字不差")

        // ⑥ 树是完整的：layout.json 在，且每个智能体都有自己的文件
        val paths = store.list("")
        assertTrue(StateTree.LAYOUT in paths)
        after.state.agents.forEach { agent ->
            assertTrue(StateTree.agentFile(agent.id) in paths, "迁移后应当有 ${agent.id} 的 agent.json")
            assertTrue(StateTree.soulFile(agent.id) in paths, "迁移后应当有 ${agent.id} 的 SOUL.md")
        }

        println(
            "[迁移实测] 智能体 ${after.state.agents.size} / 会话 ${after.state.conversations.size} " +
                "/ 记忆 ${after.state.memories.size} / 任务 ${after.state.tasks.size} " +
                "/ 技能 ${after.state.skills.size} / 图片 ${imagesAfter.size} 张 " +
                "/ 文件 ${paths.size} 个 / 读取问题 ${after.issues} 处"
        )
    }
}

private fun PersistedState.imageBytes(): List<ByteArray> =
    conversations.values.flatten()
        .mapNotNull { (it.body as? MessageBody.Image)?.assets }
        .flatten()
        .map { it.bytes }
        .filter { it.isNotEmpty() }
