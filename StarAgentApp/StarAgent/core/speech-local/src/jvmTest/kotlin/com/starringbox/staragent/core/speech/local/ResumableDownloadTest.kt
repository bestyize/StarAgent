package com.starringbox.staragent.core.speech.local

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 断点续传。用本机 HTTP 服务模拟模型下载站：一次 302 跳转到另一个路径（真实环境是跳到 CDN），
 * 再按 `Range` 返回 206，或者装作不支持 Range 返回 200。
 */
class ResumableDownloadTest {

    private val content = Random(7).nextBytes(300_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val ranges = mutableListOf<String?>()
    private var honorRange = true

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("resumable").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model") { exchange ->
            exchange.responseHeaders.add("Location", "/cdn/model")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/cdn/model") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            ranges += range
            val start = range?.takeIf { honorRange }?.removePrefix("bytes=")?.substringBefore('-')?.toInt()
            val body = if (start != null) content.copyOfRange(start, content.size) else content
            if (start != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
            }
            exchange.sendResponseHeaders(if (start != null) 206 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private val url get() = "http://127.0.0.1:${server.address.port}/model"

    private fun download(target: File, onProgress: (Long) -> Unit = {}): Unit = runBlocking {
        downloadVerified(url, target, content.size.toLong(), sha, "测试模型", onProgress)
    }

    @Test
    fun aHalfDownloadedFileContinuesFromWhereItStopped() {
        val target = File(dir, "model.bin")
        partialFileOf(target).writeBytes(content.copyOfRange(0, 120_000))
        var first = -1L
        download(target) { if (first < 0) first = it }

        assertContentEquals(content, target.readBytes())
        assertFalse(partialFileOf(target).exists(), "下完之后半截文件要被改名成正式文件")
        assertEquals(listOf<String?>("bytes=120000-"), ranges.toList(), "跟过重定向之后还要带着 Range")
        assertEquals(120_000L, first, "进度从已经下好的那部分开始算，不是从 0")
    }

    @Test
    fun aServerThatIgnoresRangeStartsOver() {
        honorRange = false
        val target = File(dir, "model.bin")
        partialFileOf(target).writeBytes(content.copyOfRange(0, 120_000))

        download(target)

        assertContentEquals(content, target.readBytes(), "整份重发时要覆盖写，不能接在半截后面")
    }

    @Test
    fun aCorruptPartialIsDiscardedSoTheNextTryStartsClean() {
        val target = File(dir, "model.bin")
        val wrong = content.copyOfRange(0, 120_000).also { it[10] = (it[10] + 1).toByte() }
        partialFileOf(target).writeBytes(wrong)

        assertFailsWith<IllegalStateException> { download(target) }
        assertFalse(partialFileOf(target).exists(), "校验失败要删掉半截文件，否则下次还会接着坏的拼")
        assertFalse(target.exists())

        download(target)
        assertContentEquals(content, target.readBytes())
    }

    @Test
    fun aVerifiedFileIsNotDownloadedAgain() {
        val target = File(dir, "model.bin")
        target.writeBytes(content)

        download(target)

        assertTrue(ranges.isEmpty(), "已经下好、校验通过的文件不该再发请求")
    }
}
