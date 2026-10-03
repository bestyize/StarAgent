package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 系统提示里的【已启用的技能】小节。
 *
 * 发得出工具和发不出工具是两条不同的路：前者只给目录、让模型自己调 `use_skill`；
 * 后者没有取的途径，只能把正文直接带上。这两条路不能混——带了正文又提 use_skill，
 * 模型会去调一个在这条路上根本用不上的工具。
 */
class SkillPromptTest {

    // 每条用例一份空存储：AppState 构造时读存储，不重置的话上一条用例写进去的技能会串到下一条
    @BeforeTest
    fun freshStore() {
        LocalStores.install(MemoryStore())
    }

    @Test
    fun installedSkillIsListedUninstalledIsNot() {
        val state = AppState()
        val agent = state.agents.first()
        state.addSkill(Skill("on", "已启用技能", "开着的", "🧩", installed = true))
        state.addSkill(Skill("off", "未启用技能", "关着的", "🧩", installed = false))

        val prompt = state.systemPromptForTest(agent)

        assertTrue(prompt.contains("已启用技能"), "装了且启用的技能要出现在提示里")
        assertFalse(prompt.contains("未启用技能"), "没启用的技能不该出现在提示里")
    }

    @Test
    fun toolsAvailableAddsTheUseSkillHintButNotTheInstructions() {
        val state = AppState()
        val agent = state.agents.first()
        state.addSkill(
            Skill("on", "已启用技能", "开着的", "🧩", installed = true, instructions = "完整说明正文")
        )

        val prompt = state.systemPromptForTest(agent, toolsAvailable = true)

        assertTrue(prompt.contains("use_skill"), "发得出工具时要告诉模型调 use_skill 去读正文")
        assertFalse(prompt.contains("完整说明正文"), "发得出工具时不该把正文直接带上，否则 use_skill 就没意义了")
    }

    @Test
    fun toolsUnavailableInlinesInstructionsButNotTheHint() {
        val state = AppState()
        val agent = state.agents.first()
        state.addSkill(
            Skill("on", "已启用技能", "开着的", "🧩", installed = true, instructions = "完整说明正文")
        )

        val prompt = state.systemPromptForTest(agent, toolsAvailable = false)

        assertTrue(prompt.contains("完整说明正文"), "发不出工具时没有取的途径，只能把正文直接带上")
        assertFalse(prompt.contains("use_skill"), "发不出工具时模型调不了 use_skill，不该提它")
    }

    @Test
    fun modelInvocableFalseSkillIsExcludedFromTheDirectoryEvenWhenInstalled() {
        // disable-model-invocation：作者的意思是"只在用户点名时用"，模型自己的目录里不该看到它
        val state = AppState()
        val agent = state.agents.first()
        state.addSkill(
            Skill("manual-only", "仅手动技能", "只能用户手动调用", "🧩", installed = true, modelInvocable = false)
        )

        val prompt = state.systemPromptForTest(agent)

        assertFalse(prompt.contains("仅手动技能"), "modelInvocable=false 的技能不该出现在模型可见的目录里")
    }

    @Test
    fun longDescriptionIsTruncatedInTheDirectoryListing() {
        // 目录每轮都发，用的是简介的上限（200），不是正文那条 1500 的上限
        val limit = 200
        val state = AppState()
        val agent = state.agents.first()
        val longSummary = "摘".repeat(limit + 100)
        state.addSkill(Skill("long-desc", "长简介技能", longSummary, "🧩", installed = true))

        val prompt = state.systemPromptForTest(agent)

        assertTrue(prompt.contains("长简介技能"))
        assertFalse(
            prompt.contains("摘".repeat(limit + 1)),
            "目录里的简介不能超过 200 字上限",
        )
    }

    @Test
    fun noSectionAtAllWhenNothingIsInstalled() {
        val state = AppState()
        val agent = state.agents.first()
        // 清掉种子技能，确保一个已启用的都没有
        state.skills.toList().forEach { state.removeSkill(it.id) }

        val prompt = state.systemPromptForTest(agent)

        assertFalse(prompt.contains("已启用的技能"), "没有技能装着时，提示里不该出现这个小节")
    }

    @Test
    fun inlinedInstructionsAreTruncatedToTheCharLimit() {
        // MAX_INLINE_SKILL_CHARS 是 AppState 里 private 的常量（1500），这里直接写死镜像它——
        // 测的是"确实有个上限"，不是具体数字本身。
        val limit = 1_500
        val state = AppState()
        val agent = state.agents.first()
        val longBody = "正".repeat(limit + 500)
        state.addSkill(Skill("on", "长技能", "开着的", "🧩", installed = true, instructions = longBody))

        val prompt = state.systemPromptForTest(agent, toolsAvailable = false)

        assertFalse(
            prompt.contains("正".repeat(limit + 1)),
            "内联正文不能超过上限，否则一个超长技能能把对话挤没",
        )
    }
}
