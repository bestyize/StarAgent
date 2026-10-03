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
 * `read_skill_file`：渐进披露的第三层，读技能目录里附带的参考文件。
 */
class ReadSkillFileToolTest {

    private class FakeSkillPort(private val files: Map<Pair<String, String>, String>) : SkillPort {
        override fun list() = emptyList<SkillSummary>()
        override fun setInstalled(id: String, installed: Boolean) = false
        override fun installFromFile(agentId: String, path: String) = SkillInstallResult(false, message = "未实现")
        override fun remove(id: String) = SkillInstallResult(false, message = "未实现")
        override fun read(id: String): SkillDetail? = null
        override fun readFile(id: String, path: String): String? = files[id to path]
        override fun create(draft: SkillDraft) = SkillInstallResult(false, message = "未实现")
    }

    @Test
    fun missingIdArgumentIsAnError() = runTest {
        val host = testHost(skills = FakeSkillPort(emptyMap()))

        val result = ReadSkillFileTool.execute(buildJsonObject { put("path", "references/style.md") }, host)

        assertTrue(result.isError)
    }

    @Test
    fun unsafePathIsRejectedBeforeReachingThePort() = runTest {
        val host = testHost(skills = FakeSkillPort(mapOf("pdf" to "../secret" to "leaked")))

        val result = ReadSkillFileTool.execute(
            buildJsonObject { put("id", "pdf"); put("path", "../secret") },
            host,
        )

        assertTrue(result.isError, "路径穿越应当在工具层就被拦下")
    }

    @Test
    fun missingFileReturnsAHelpfulError() = runTest {
        val host = testHost(skills = FakeSkillPort(emptyMap()))

        val result = ReadSkillFileTool.execute(
            buildJsonObject { put("id", "pdf"); put("path", "references/missing.md") },
            host,
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("pdf"))
        assertTrue(result.content.contains("references/missing.md"))
    }

    @Test
    fun existingResourceFileIsReturned() = runTest {
        val host = testHost(
            skills = FakeSkillPort(mapOf("pdf" to "references/style.md" to "标题用二级标题。"))
        )

        val result = ReadSkillFileTool.execute(
            buildJsonObject { put("id", "pdf"); put("path", "references/style.md") },
            host,
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("标题用二级标题。"))
    }
}
