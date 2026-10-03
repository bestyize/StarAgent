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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `create_skill`：工具层只管把参数转给 `SkillPort.create` 再把结果转回文本——
 * id/描述/正文的校验逻辑在 `AppSkillPort`，这里只钉住转发不出错。
 */
class CreateSkillToolTest {

    private class RecordingSkillPort(private val result: SkillInstallResult) : SkillPort {
        var lastDraft: SkillDraft? = null
        override fun list() = emptyList<SkillSummary>()
        override fun setInstalled(id: String, installed: Boolean) = false
        override fun installFromFile(agentId: String, path: String) = SkillInstallResult(false, message = "未实现")
        override fun remove(id: String) = SkillInstallResult(false, message = "未实现")
        override fun read(id: String): SkillDetail? = null
        override fun readFile(id: String, path: String): String? = null
        override fun create(draft: SkillDraft): SkillInstallResult {
            lastDraft = draft
            return result
        }
    }

    @Test
    fun forwardsAllFieldsToTheSkillPort() = runTest {
        val port = RecordingSkillPort(SkillInstallResult(true, "weekly-report", "已创建技能「周报整理」（id=weekly-report）"))
        val host = testHost(skills = port)

        val result = CreateSkillTool.execute(
            buildJsonObject {
                put("id", "weekly-report")
                put("name", "周报整理")
                put("description", "把一周的进展整理成周报")
                put("instructions", "1. 收集进展。\n2. 按模板整理。")
            },
            host,
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("weekly-report"))
        assertEquals("weekly-report", port.lastDraft?.id)
        assertEquals("周报整理", port.lastDraft?.name)
        assertEquals("把一周的进展整理成周报", port.lastDraft?.description)
        assertEquals("1. 收集进展。\n2. 按模板整理。", port.lastDraft?.instructions)
    }

    @Test
    fun portRefusalBecomesAnErrorResult() = runTest {
        val port = RecordingSkillPort(SkillInstallResult(false, message = "「网页检索」是内置技能，智能体不能改写。换一个 id 新建。"))
        val host = testHost(skills = port)

        val result = CreateSkillTool.execute(
            buildJsonObject {
                put("id", "web")
                put("name", "网页检索")
                put("description", "d")
                put("instructions", "i")
            },
            host,
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("内置技能"))
    }
}
