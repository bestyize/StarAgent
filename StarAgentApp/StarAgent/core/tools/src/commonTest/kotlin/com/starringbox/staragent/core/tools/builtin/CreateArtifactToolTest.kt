package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.tools.testHost
import com.starringbox.staragent.core.tools.ports.FileEntry
import com.starringbox.staragent.core.tools.ports.FileOperationResult
import com.starringbox.staragent.core.tools.ports.FilePort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CreateArtifactToolTest {
    private class RecordingFiles : FilePort {
        val text = mutableMapOf<String, String>()
        val binary = mutableMapOf<String, ByteArray>()
        override fun list(agentId: String, path: String) = emptyList<FileEntry>()
        override fun read(agentId: String, path: String): String? = text[path]
        override fun write(agentId: String, path: String, content: String): FileOperationResult {
            text[path] = content
            return FileOperationResult(true, "written")
        }
        override fun readBytes(agentId: String, path: String): ByteArray? = binary[path]
        override fun writeBytes(agentId: String, path: String, content: ByteArray): FileOperationResult {
            binary[path] = content
            return FileOperationResult(true, "written")
        }
        override fun delete(agentId: String, path: String) = FileOperationResult(true, "deleted")
    }

    @Test
    fun savesHtmlAndReturnsStructuredArtifact() = runTest {
        val files = RecordingFiles()
        val html = "<h1>日报</h1>"
        val result = CreateArtifactTool.execute(
            buildJsonObject {
                put("title", "日报")
                put("summary", "今日摘要")
                put("kind", "report")
                put("format", "html")
                put("content", html)
            },
            testHost(agentId = "agent-1", files = files),
        )

        val artifact = assertNotNull(result.artifact)
        assertFalse(result.isError)
        assertEquals("text/html", artifact.mimeType)
        assertEquals(html, files.text["artifacts/${artifact.id}/output.html"])
        assertTrue(result.content.contains("artifact://${artifact.id}"))
    }

    @Test
    fun savesRasterImageBytesFromBase64() = runTest {
        val files = RecordingFiles()
        val result = CreateArtifactTool.execute(
            buildJsonObject {
                put("title", "图片")
                put("format", "png")
                put("content", "data:image/png;base64,aGVsbG8=")
            },
            testHost(agentId = "agent-1", files = files),
        )

        val artifact = assertNotNull(result.artifact)
        assertFalse(result.isError)
        assertEquals("image/png", artifact.mimeType)
        assertTrue(files.binary["artifacts/${artifact.id}/output.png"]?.contentEquals("hello".encodeToByteArray()) == true)
    }

    @Test
    fun rejectsUnsafeFileName() = runTest {
        val files = RecordingFiles()
        val result = CreateArtifactTool.execute(
            buildJsonObject {
                put("title", "日报")
                put("format", "html")
                put("file_name", "../other.html")
                put("content", "<p>ok</p>")
            },
            testHost(files = files),
        )

        assertTrue(result.isError)
        assertTrue(files.text.isEmpty())
    }

    @Test
    fun rejectsNonHttpsImageSource() = runTest {
        val result = CreateArtifactTool.execute(
            buildJsonObject {
                put("title", "图片")
                put("format", "image_url")
                put("source_url", "file:///etc/passwd")
            },
            testHost(),
        )

        assertTrue(result.isError)
        assertEquals(null, result.artifact)
    }
}
