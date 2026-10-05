package com.starringbox.staragent.core.speech.local

import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 下载一个大小和 SHA-256 已知的文件，支持断点续传。
 *
 * 下到一半的内容留在 `<目标>.part` 里：断网、退出应用、进程被杀都不会丢，
 * 下次调用会带上 `Range` 从断点接着下。只有校验失败时才删掉 `.part` 从头来，
 * 否则下次还会接着一份坏文件往下拼。
 *
 * [onProgress] 报告的是这个文件已经在磁盘上的字节数（含之前下好的部分）。
 */
internal suspend fun downloadVerified(
    url: String,
    target: File,
    size: Long,
    sha256: String,
    describe: String,
    onProgress: (Long) -> Unit,
) {
    if (verifiedFile(target, size, sha256)) { onProgress(size); return }
    val partial = partialFileOf(target)
    if (partial.length() > size) partial.delete()
    if (partial.length() < size) fetchInto(url, partial, size, describe, onProgress)
    onProgress(partial.length())
    if (!verifiedFile(partial, size, sha256)) {
        partial.delete()
        error("$describe SHA-256 校验失败，请重新下载")
    }
    if (target.exists()) check(target.delete()) { "无法替换旧的$describe" }
    check(partial.renameTo(target)) { "无法保存$describe" }
}

/** 下到一半的那份文件。删除模型时要一起删掉，否则会留下一份没人管的半截文件。 */
internal fun partialFileOf(target: File): File = File(target.parentFile, "${target.name}.part")

internal fun verifiedFile(file: File, size: Long, sha256: String): Boolean {
    if (!file.isFile || file.length() != size) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } == sha256
}

private suspend fun fetchInto(url: String, partial: File, size: Long, describe: String, onProgress: (Long) -> Unit) {
    val start = partial.length()
    var active: HttpURLConnection? = null
    // 阻塞读不响应协程取消，靠断开连接把它打断。
    val disconnectWatcher = CoroutineScope(currentCoroutineContext()).launch(
        context = Dispatchers.IO,
        start = CoroutineStart.UNDISPATCHED,
    ) {
        try { awaitCancellation() } finally { active?.disconnect() }
    }
    try {
        fetch(url, partial, start, size, describe, onProgress) { active = it }
    } catch (failure: IOException) {
        // 取消时是靠断开连接打断阻塞读的，这会冒出一个 "Socket closed"。
        // 那不是网络错误，按取消处理，界面上才不会显示一条莫名其妙的报错。
        currentCoroutineContext().ensureActive()
        throw failure
    } finally {
        disconnectWatcher.cancel()
        active?.disconnect()
    }
}

private suspend fun fetch(
    url: String,
    partial: File,
    start: Long,
    size: Long,
    describe: String,
    onProgress: (Long) -> Unit,
    onConnection: (HttpURLConnection) -> Unit,
) {
    val connection = open(url, start, onConnection)
    val code = connection.responseCode
    val append = when {
        code == HttpURLConnection.HTTP_PARTIAL && contentRangeStart(connection) == start -> true
        // 服务端不认 Range、整份重发：只能从头写
        code == HttpURLConnection.HTTP_OK -> false
        code == 416 -> { partial.delete(); error("${describe}下载失败（HTTP 416），请重新下载") }
        else -> error("${describe}下载失败（HTTP $code）")
    }
    var received = if (append) start else 0L
    onProgress(received)
    connection.inputStream.use { input ->
        FileOutputStream(partial, append).buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                received += count
                check(received <= size) { "${describe}大小不符" }
                output.write(buffer, 0, count)
                onProgress(received)
            }
        }
    }
}

/**
 * 自己跟重定向，不交给 `instanceFollowRedirects`：模型地址会 302 到另一个域名的 CDN，
 * 要确保跳过去的那次请求还带着 `Range`。
 */
private fun open(url: String, start: Long, onConnection: (HttpURLConnection) -> Unit): HttpURLConnection {
    var current = URL(url)
    repeat(MAX_REDIRECTS) {
        val connection = current.openConnection() as HttpURLConnection
        onConnection(connection)
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        // 不让网络层透明压缩：Range 按压缩后的字节算，续上去的内容会对不上。
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
        val code = connection.responseCode
        if (code !in 300..399) return connection
        val location = connection.getHeaderField("Location") ?: error("下载地址重定向缺少目标（HTTP $code）")
        connection.disconnect()
        current = URL(current, location)
    }
    error("下载地址重定向次数过多")
}

private fun contentRangeStart(connection: HttpURLConnection): Long? =
    connection.getHeaderField("Content-Range")
        ?.substringAfter("bytes ", "")
        ?.substringBefore('-')
        ?.trim()
        ?.toLongOrNull()

private const val MAX_REDIRECTS = 5
