package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.tools.testHost
import com.starringbox.staragent.core.tools.ports.SkillDetail
import com.starringbox.staragent.core.tools.ports.SkillDraft
import com.starringbox.staragent.core.tools.ports.SkillInstallResult
import com.starringbox.staragent.core.tools.ports.SkillPort
import com.starringbox.staragent.core.tools.ports.SkillSummary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `use_skill`：按需读取一个已启用技能的完整说明。
 *
 * 系统提示里只给目录，正文全靠这个工具取——所以"未启用的技能不给读"是硬要求：
 * 用户在设置里关掉的技能，不该再有路子从这里绕回来生效。`disable-model-invocation`
 * 同理：作者说了只给用户手动调用，模型就不能自己挑它。
 */
class UseSkillToolTest {

    private class FakeSkillPort(private val skills: List<SkillDetail>) : SkillPort {
        override fun list() = skills.map { SkillSummary(it.id, it.name, it.summary, it.installed, modelInvocable = it.modelInvocable) }
        override fun setInstalled(id: String, installed: Boolean) = false
        override fun installFromFile(agentId: String, path: String) = SkillInstallResult(false, message = "未实现")
        override fun remove(id: String) = SkillInstallResult(false, message = "未实现")
        override fun read(id: String): SkillDetail? = skills.firstOrNull { it.id == id }
        override fun readFile(id: String, path: String): String? = null
        override fun create(draft: SkillDraft) = SkillInstallResult(false, message = "未实现")
    }

    @Test
    fun unknownIdIsAnError() = runTest {
        val host = testHost(skills = FakeSkillPort(emptyList()))

        val result = UseSkillTool.execute(buildJsonObject { put("id", "nope") }, host)

        assertTrue(result.isError, "不存在的 id 应当报错")
        assertTrue(result.content.contains("nope"))
    }

    @Test
    fun notInstalledSkillIsAnError() = runTest {
        val host = testHost(
            skills = FakeSkillPort(
                listOf(SkillDetail("web", "网页检索", "联网搜索", installed = false, instructions = "正文"))
            )
        )

        val result = UseSkillTool.execute(buildJsonObject { put("id", "web") }, host)

        assertTrue(result.isError, "未启用的技能不该被读出来")
        assertFalse(result.content.contains("正文"), "未启用时正文不该泄露到结果里")
    }

    @Test
    fun installedSkillReturnsItsInstructions() = runTest {
        val host = testHost(
            skills = FakeSkillPort(
                listOf(
                    SkillDetail(
                        id = "web",
                        name = "网页检索",
                        summary = "联网搜索",
                        installed = true,
                        instructions = "1. 先搜索。\n2. 再核实来源。",
                    )
                )
            )
        )

        val result = UseSkillTool.execute(buildJsonObject { put("id", "web") }, host)

        assertFalse(result.isError)
        assertTrue(result.content.contains("网页检索"), "结果应当带上技能名")
        assertTrue(result.content.contains("先搜索"), "结果应当带上完整正文")
    }

    @Test
    fun installedSkillWithBlankInstructionsFallsBackToSummary() = runTest {
        val host = testHost(
            skills = FakeSkillPort(
                listOf(SkillDetail("web", "网页检索", "联网搜索", installed = true, instructions = ""))
            )
        )

        val result = UseSkillTool.execute(buildJsonObject { put("id", "web") }, host)

        assertFalse(result.isError)
        assertTrue(result.content.contains("联网搜索"), "正文为空时应当退到用简介兜底")
    }

    @Test
    fun modelInvocableFalseSkillIsRefusedEvenWhenInstalled() = runTest {
        // disable-model-invocation：作者的意思是"只给用户 /id 调用"，模型不能自己挑它
        val host = testHost(
            skills = FakeSkillPort(
                listOf(
                    SkillDetail(
                        id = "manual-only",
                        name = "仅手动",
                        summary = "只能用户手动调用",
                        installed = true,
                        instructions = "正文不该泄露",
                        modelInvocable = false,
                    )
                )
            )
        )

        val result = UseSkillTool.execute(buildJsonObject { put("id", "manual-only") }, host)

        assertTrue(result.isError, "modelInvocable=false 的技能不该被模型读出来")
        assertFalse(result.content.contains("正文不该泄露"))
    }

    @Test
    fun listsResourceFilesWhenPresent() = runTest {
        val host = testHost(
            skills = FakeSkillPort(
                listOf(
                    SkillDetail(
                        id = "pdf",
                        name = "PDF 处理",
                        summary = "读写 PDF",
                        installed = true,
                        instructions = "正文",
                        resources = listOf("references/style.md", "assets/template.pdf"),
                    )
                )
            )
        )

        val result = UseSkillTool.execute(buildJsonObject { put("id", "pdf") }, host)

        assertFalse(result.isError)
        assertTrue(result.content.contains("references/style.md"))
        assertTrue(result.content.contains("assets/template.pdf"))
        assertTrue(result.content.contains("read_skill_file"), "应当提示用 read_skill_file 去读附带的文件")
    }
}
