package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.tools.ports.FetchResult
import com.starringbox.staragent.core.tools.ports.WebPort
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import com.starringbox.staragent.core.llm.httpClient

/**
 * 取网页。
 *
 * 三道自制边界（没有权限框架，所以收在实现里）：
 * 1. **只允许 https**——明文 http 会把用户看的地址暴露在链路上
 * 2. **拒绝内网地址**——否则模型可以把这台设备当跳板去探测局域网
 * 3. **长度截断**——网页正文可以极长，不截会把上下文撑爆
 *
 * 失败返回 [FetchResult.ok] = false 而**不抛异常**：抓不到是给模型看的信息，
 * 它看到"这个地址取不到"会换个地址重试。
 */
class AppWebPort : WebPort {

    override suspend fun fetch(url: String): FetchResult {
        val normalized = url.trim()
        if (!normalized.startsWith("https://")) {
            return FetchResult(false, "", "只支持 https 地址")
        }
        if (looksInternal(normalized)) {
            return FetchResult(false, "", "这个地址指向内网，不能访问")
        }

        return runCatching {
            val response = httpClient().get(normalized)
            if (!response.status.isSuccess()) {
                return@runCatching FetchResult(false, "", "取不到（HTTP ${response.status.value}）")
            }
            val body = response.bodyAsText()
            FetchResult(true, extractText(body))
        }.getOrElse { error ->
            FetchResult(false, "", "取不到：${error.message ?: error::class.simpleName}")
        }
    }

    /** 主机名是 IP 字面量且在私有段里，或者直接写 localhost。 */
    private fun looksInternal(url: String): Boolean {
        val host = url.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
        if (host == "localhost" || host.endsWith(".local")) return true

        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 127 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 169 && parts[1] == 254 -> true
            else -> false
        }
    }

    /**
     * 最朴素的正文提取：丢掉 script / style 的**内容**，再去掉所有标签，
     * 折叠空白，最后截断。
     *
     * 不引 HTML 解析库的理由：一期只看"能不能拿到大致可读的文字"，
     * 正则够用。真正做差了再换，换的时候只动这一个函数。
     */
    private fun extractText(html: String): String {
        val withoutBlocks = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        val withoutTags = withoutBlocks.replace(Regex("(?s)<[^>]+>"), " ")
        val decoded = withoutTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return decoded
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_CHARS)
    }

    private companion object {
        /** 单次网页正文的上限。够模型读懂，又不会把上下文撑爆。 */
        const val MAX_CHARS = 8000
    }
}
