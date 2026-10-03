package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.MemoryStore
import com.starringbox.staragent.core.model.SkillSource
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.tools.ports.SkillDraft
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AppSkillPort`：安装、卸载、创建技能。
 *
 * 两种来源都要能装：带 frontmatter 的（本家格式，也兼容 Claude Code 用 `description`
 * 而不是 `summary` 的 SKILL.md），和什么标注都没有的纯 markdown；目录形式的安装要能
 * 把 references/ 等同目录文件一起带上。
 */
class AppSkillPortInstallTest {

    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    @Test
    fun installsASkillWithFrontmatterAndBodyAsInstructions() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val agentId = state.agents.first().id
        val port = AppSkillPort(state)

        store.writeText(
            "agent-sandbox/$agentId/SKILL.md",
            """
            ---
            name: custom-skill
            description: 一句话说明
            metadata:
              display-name: 自定义技能
              glyph: 🧪
            ---

            第一步：做 A。
            第二步：做 B。
            """.trimIndent(),
        )

        val result = port.installFromFile(agentId, "SKILL.md")

        assertTrue(result.ok, "应当安装成功：${result.message}")
        val installed = state.skills.single { it.id == result.id }
        assertEquals("custom-skill", installed.id)
        assertEquals("自定义技能", installed.name)
        assertEquals("一句话说明", installed.summary)
        assertEquals("🧪", installed.glyph)
        assertTrue(installed.installed)
        assertEquals(SkillSource.Imported, installed.source)
        assertTrue(
            installed.instructions.contains("第一步：做 A。"),
            "正文应当作为 instructions 存下来，实际是：${installed.instructions}",
        )
    }

    @Test
    fun installsAPlainMarkdownFileWithNoFrontmatter() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val agentId = state.agents.first().id
        val port = AppSkillPort(state)

        store.writeText(
            "agent-sandbox/$agentId/plain-notes.md",
            """
            # 纯文本技能

            这整段都是正文，没有任何 frontmatter 标注。
            """.trimIndent(),
        )

        val result = port.installFromFile(agentId, "plain-notes.md")

        assertTrue(result.ok, "没有 frontmatter 的纯 markdown 也该能装：${result.message}")
        val installed = state.skills.single { it.id == result.id }
        assertEquals("plain-notes", installed.id, "id 应当取自文件名")
        assertEquals("纯文本技能", installed.summary, "summary 应当取第一行非空内容，并去掉前导的 #")
        assertTrue(
            installed.instructions.contains("这整段都是正文"),
            "整段文本都应当成为正文，实际是：${installed.instructions}",
        )
    }

    @Test
    fun installingADirectoryCopiesSiblingResourceFiles() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val agentId = state.agents.first().id
        val port = AppSkillPort(state)

        store.writeText(
            "agent-sandbox/$agentId/skills/pdf/SKILL.md",
            "---\nname: pdf\ndescription: 读写 PDF\n---\n正文",
        )
        // copyResources 走的是 readBytes/writeBytes（二进制安全的拷贝），不是 writeText
        store.writeBytes(
            "agent-sandbox/$agentId/skills/pdf/references/style.md",
            "标题用二级标题。".encodeToByteArray(),
        )

        val result = port.installFromFile(agentId, "skills/pdf")

        assertTrue(result.ok, "目录安装应当成功：${result.message}")
        assertTrue(result.message.contains("附带"), "应当提到带上了多少个文件：${result.message}")
        val detail = port.read("pdf")
        assertEquals(listOf("references/style.md"), detail?.resources)
    }

    @Test
    fun installedDirectoryResourcesAreListedAndReadableAfterInstall() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val agentId = state.agents.first().id
        val port = AppSkillPort(state)

        store.writeText(
            "agent-sandbox/$agentId/skills/pdf/SKILL.md",
            "---\nname: pdf\ndescription: 读写 PDF\n---\n正文",
        )
        store.writeText(
            "agent-sandbox/$agentId/skills/pdf/references/style.md",
            "标题用二级标题。",
        )
        port.installFromFile(agentId, "skills/pdf")

        val read = port.readFile("pdf", "references/style.md")

        assertEquals("标题用二级标题。", read)
    }

    @Test
    fun resourcesAreNotCopiedWhenSkillMdIsAtTheSandboxRoot() {
        // 在沙箱根目录的话"同目录"就是整个沙箱，不该把其他不相关文件都当成技能资源带走
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val agentId = state.agents.first().id
        val port = AppSkillPort(state)

        store.writeText("agent-sandbox/$agentId/SKILL.md", "---\nname: root-skill\ndescription: d\n---\n")
        store.writeText("agent-sandbox/$agentId/unrelated.txt", "不相关的文件")

        val result = port.installFromFile(agentId, "SKILL.md")

        assertTrue(result.ok)
        val detail = port.read("root-skill")
        assertEquals(emptyList(), detail?.resources)
    }

    @Test
    fun installingADuplicateIdIsRefused() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val agentId = state.agents.first().id
        val port = AppSkillPort(state)
        store.writeText("agent-sandbox/$agentId/SKILL.md", "---\nname: web\ndescription: d\n---\n")

        val result = port.installFromFile(agentId, "SKILL.md")

        assertFalse(result.ok, "web 是种子里已有的内置技能 id，应当拒绝重复安装")
    }

    @Test
    fun installFromFileIsRefusedWhenSuppressed() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val agentId = state.agents.first().id
        val port = AppSkillPort(state, isSuppressed = { true })
        store.writeText("agent-sandbox/$agentId/SKILL.md", "---\nname: new-one\ndescription: d\n---\n")

        val result = port.installFromFile(agentId, "SKILL.md")

        assertFalse(result.ok, "本轮读过外部内容时不能安装技能")
        assertTrue(state.skills.none { it.id == "new-one" })
    }

    @Test
    fun removeRefusesABuiltInSkill() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val port = AppSkillPort(state)

        val result = port.remove("web")

        assertFalse(result.ok, "内置技能不能删除，只能停用")
        assertTrue(state.skills.any { it.id == "web" }, "拒绝之后技能仍应留在列表里")
    }

    @Test
    fun removeDeletesAnImportedSkillAndItsResourceFiles() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val agentId = state.agents.first().id
        val port = AppSkillPort(state)
        store.writeText(
            "agent-sandbox/$agentId/skills/pdf/SKILL.md",
            "---\nname: pdf\ndescription: 读写 PDF\n---\n正文",
        )
        store.writeText(
            "agent-sandbox/$agentId/skills/pdf/references/style.md",
            "标题用二级标题。",
        )
        port.installFromFile(agentId, "skills/pdf")

        val result = port.remove("pdf")

        assertTrue(result.ok, "导入的技能应当能删除：${result.message}")
        assertTrue(state.skills.none { it.id == "pdf" })
        assertEquals(null, store.readText("skills/pdf/SKILL.md"), "SKILL.md 应当被删除")
        assertEquals(null, store.readText("skills/pdf/references/style.md"), "附带的资源文件也应当被删除")
    }

    @Test
    fun createInstallsANewAgentSkill() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val port = AppSkillPort(state)

        val result = port.create(SkillDraft("weekly-report", "周报整理", "把一周的进展整理成周报", "1. 收集进展。\n2. 整理。"))

        assertTrue(result.ok, "合法的创建请求应当成功：${result.message}")
        val skill = state.skills.single { it.id == "weekly-report" }
        assertEquals(SkillSource.Agent, skill.source)
        assertTrue(skill.installed)
        assertEquals("把一周的进展整理成周报", skill.summary)
        assertEquals("1. 收集进展。\n2. 整理。", skill.instructions)
    }

    @Test
    fun createCanUpdateItsOwnPreviouslyCreatedAgentSkill() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val port = AppSkillPort(state)
        port.create(SkillDraft("weekly-report", "周报整理", "旧说明", "旧正文"))

        val result = port.create(SkillDraft("weekly-report", "周报整理 v2", "新说明", "新正文"))

        assertTrue(result.ok, "改写自己创建的技能应当成功：${result.message}")
        assertEquals(1, state.skills.count { it.id == "weekly-report" }, "改写不该产生第二条记录")
        val skill = state.skills.single { it.id == "weekly-report" }
        assertEquals("新说明", skill.summary)
        assertEquals("新正文", skill.instructions)
    }

    @Test
    fun createRefusesToOverwriteABuiltInSkill() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val port = AppSkillPort(state)

        val result = port.create(SkillDraft("web", "网页检索", "想改写内置技能", "正文"))

        assertFalse(result.ok, "内置技能不能被智能体改写")
        val original = state.skills.single { it.id == "web" }
        assertEquals(SkillSource.BuiltIn, original.source, "拒绝之后原技能的来源不该被动过")
    }

    @Test
    fun createRefusesToOverwriteAnImportedSkill() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val agentId = state.agents.first().id
        val port = AppSkillPort(state)
        store.writeText("agent-sandbox/$agentId/SKILL.md", "---\nname: imported-one\ndescription: d\n---\n")
        port.installFromFile(agentId, "SKILL.md")

        val result = port.create(SkillDraft("imported-one", "改名", "想改写导入技能", "正文"))

        assertFalse(result.ok, "导入的技能不能被智能体改写")
    }

    @Test
    fun createRejectsAnInvalidId() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val port = AppSkillPort(state)

        val result = port.create(SkillDraft("Not Valid!", "名字", "说明", "正文"))

        assertFalse(result.ok, "大写和空格都不是合法 id")
        assertTrue(state.skills.none { it.id == "Not Valid!" })
    }

    @Test
    fun createIsRefusedWhenSuppressed() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val port = AppSkillPort(state, isSuppressed = { true })

        val result = port.create(SkillDraft("new-skill", "名字", "说明", "正文"))

        assertFalse(result.ok, "本轮读过外部内容时不能创建技能")
        assertTrue(state.skills.none { it.id == "new-skill" })
    }

    @Test
    fun readReturnsNullForAnUnknownSkill() {
        val store = MemoryStore()
        LocalStores.install(store)
        val state = AppState()
        val port = AppSkillPort(state)

        assertNull(port.read("does-not-exist"))
    }
}
