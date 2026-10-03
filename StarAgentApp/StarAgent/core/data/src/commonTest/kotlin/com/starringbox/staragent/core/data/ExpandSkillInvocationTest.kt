package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AppState.expandSkillInvocation`：用户在消息开头写 `/id`（Claude 写法）或 `$id`
 * （Codex 写法）时，把那个技能的说明直接带进这一轮发给模型的那份文本。
 *
 * 对话里显示的是用户原文——这里只测"展开后的文本"，不碰显示层。
 */
class ExpandSkillInvocationTest {

    // 每条用例一份空存储：AppState 构造时读存储，不重置的话上一条用例写进去的技能会串到下一条
    @BeforeTest
    fun freshStore() {
        LocalStores.install(MemoryStore())
    }

    private fun stateWithSkill(skill: Skill): AppState {
        val state = AppState()
        state.addSkill(skill)
        return state
    }

    @Test
    fun slashIdWithTrailingTextExpandsAndKeepsTheRequest() {
        val state = stateWithSkill(
            Skill("weekly", "周报", "整理周报", "📊", installed = true, instructions = "1. 收集进展。")
        )

        val expanded = state.expandSkillInvocation("/weekly 帮我整理这周的进展")

        assertTrue(expanded != null)
        assertTrue(expanded.contains("周报"))
        assertTrue(expanded.contains("1. 收集进展。"))
        assertTrue(expanded.contains("帮我整理这周的进展"), "用户补充的请求要带进展开后的文本里")
    }

    @Test
    fun dollarIdAlsoExpands() {
        val state = stateWithSkill(
            Skill("weekly", "周报", "整理周报", "📊", installed = true, instructions = "正文")
        )

        val expanded = state.expandSkillInvocation("\$weekly 帮我整理")

        assertTrue(expanded != null)
        assertTrue(expanded.contains("周报"))
    }

    @Test
    fun slashIdAloneWithNoFollowingTextStillExpands() {
        val state = stateWithSkill(
            Skill("weekly", "周报", "整理周报", "📊", installed = true, instructions = "正文说明")
        )

        val expanded = state.expandSkillInvocation("/weekly")

        assertTrue(expanded != null)
        assertTrue(expanded.contains("正文说明"))
        assertTrue(expanded.contains("没有补充说明"), "没有补充请求时应当有一句兜底文案")
    }

    @Test
    fun unknownIdReturnsNull() {
        val state = AppState()

        assertNull(state.expandSkillInvocation("/does-not-exist 做点什么"))
    }

    @Test
    fun uninstalledSkillReturnsNull() {
        val state = stateWithSkill(
            Skill("weekly", "周报", "整理周报", "📊", installed = false, instructions = "正文")
        )

        assertNull(state.expandSkillInvocation("/weekly 整理一下"))
    }

    @Test
    fun userInvocableFalseReturnsNull() {
        // user-invocable: false：作者的意思是只给模型用，用户 /id 不该生效
        val state = stateWithSkill(
            Skill("weekly", "周报", "整理周报", "📊", installed = true, instructions = "正文", userInvocable = false)
        )

        assertNull(state.expandSkillInvocation("/weekly 整理一下"))
    }

    @Test
    fun plainTextStartingWithSlashButNotMatchingAnyIdReturnsNull() {
        // 不是所有 "/" 开头的句子都是技能调用——比如用户只是打了个路径或分数
        val state = AppState()

        assertNull(state.expandSkillInvocation("/usr/local/bin 这是什么路径"))
    }

    @Test
    fun blankInstructionsFallsBackToSummary() {
        val state = stateWithSkill(
            Skill("weekly", "周报", "整理周报的简介", "📊", installed = true, instructions = "")
        )

        val expanded = state.expandSkillInvocation("/weekly 整理一下")

        assertTrue(expanded != null)
        assertTrue(expanded.contains("整理周报的简介"), "正文为空时应当退到用简介兜底")
    }
}
