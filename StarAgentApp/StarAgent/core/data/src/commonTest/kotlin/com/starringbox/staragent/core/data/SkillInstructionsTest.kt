package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 技能正文（[Skill.instructions]）的落盘与老存档补齐。
 *
 * 补齐这步最怕两件事：补了用户自己写过的正文（覆盖掉用户内容），或者该补的没补
 * （老用户的技能永远是空壳）。所以每条用例都把"补了谁、没补谁"钉死。
 */
class SkillInstructionsTest {

    /** LocalStores 是全局单例，跑完换回一个干净的，免得影响同模块的其它测试。 */
    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    @Test
    fun aSkillWithInstructionsSurvivesSaveAndReload() {
        val store = MemoryStore()
        LocalStores.install(store)

        val first = AppState()
        val ok = first.addSkill(
            Skill(
                id = "custom",
                name = "自定义技能",
                summary = "一句话说明",
                glyph = "🧩",
                installed = true,
                instructions = "第一步：先做 A。\n第二步：再做 B。",
            )
        )
        assertTrue(ok, "新技能应当能装上")

        val reloaded = AppState()
        val skill = reloaded.skills.single { it.id == "custom" }

        assertEquals("第一步：先做 A。\n第二步：再做 B。", skill.instructions, "正文要原样跟着存档回来")
        assertEquals("自定义技能", skill.name)
        assertTrue(skill.installed)
    }

    @Test
    fun builtInSkillWithoutABodyIsBackfilledFromSeedOnLoad() {
        val store = MemoryStore()
        // 模拟"加正文之前"装过的存档：web 的 SKILL.md 只有 frontmatter，没有正文
        store.writeText(StateTree.LAYOUT, """{"version":1,"agents":[],"skills":["web"],"groups":[]}""")
        store.writeText(
            StateTree.skillFile("web"),
            "---\nname: 网页检索\nglyph: 🔍\nsummary: 联网搜索并读取网页正文\n---\n",
        )
        store.writeText(StateTree.CONFIG, """{"installedSkills":["web"]}""")
        LocalStores.install(store)

        val state = AppState()
        val web = state.skills.single { it.id == "web" }

        assertEquals(
            Seed.builtInSkillInstructions["web"],
            web.instructions,
            "没有正文的内置技能应当用 Seed 里的内容补齐",
        )
        assertNotEquals("", web.instructions, "补齐之后不该还是空壳")

        // 补齐要落盘，否则这份存档每次启动都要再补一遍
        val rewritten = store.readText(StateTree.skillFile("web"))
        assertTrue(
            rewritten?.contains(Seed.builtInSkillInstructions["web"]!!.lines().first()) == true,
            "补齐之后磁盘上的 SKILL.md 也该带正文，实际内容：$rewritten",
        )
    }

    @Test
    fun builtInSkillWithACustomBodyKeepsItInsteadOfBeingOverwritten() {
        val store = MemoryStore()
        val customBody = "这是用户自己改写过的说明，和内置默认文案不一样。"
        store.writeText(StateTree.LAYOUT, """{"version":1,"agents":[],"skills":["web"],"groups":[]}""")
        store.writeText(
            StateTree.skillFile("web"),
            "---\nname: 网页检索\nglyph: 🔍\nsummary: 联网搜索并读取网页正文\n---\n\n$customBody\n",
        )
        store.writeText(StateTree.CONFIG, """{"installedSkills":["web"]}""")
        LocalStores.install(store)

        val state = AppState()
        val web = state.skills.single { it.id == "web" }

        assertEquals(customBody, web.instructions, "用户自己写过的正文不该被 Seed 的默认文案覆盖")

        val rewritten = store.readText(StateTree.skillFile("web"))
        assertTrue(rewritten?.contains(customBody) == true, "磁盘上也该保留用户的正文")
    }
}
