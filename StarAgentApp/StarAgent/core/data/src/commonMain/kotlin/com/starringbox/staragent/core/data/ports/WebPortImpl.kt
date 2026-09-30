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
 * 2. **挡掉本地与局域网的字面量地址**——本机、私有段、IPv4 链路本地、运营商级 NAT、
 *    IPv6 回环，以及 localhost / `*.local` 这类明显的本地名字。
 *    这是**尽力而为**的一层：它只看地址的字面量，**不解析域名**，所以一个
 *    A 记录指向 192.168.x.x 的域名在这里挡不住——那要先解析、拿到 IP 再判一次，
 *    是另一套更重的机制，这一期不做。
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

    /**
     * 字面量地址或明显的本地名字就挡掉。
     *
     * 覆盖的是这几段字面量：IPv4 回环 `127/8`、私有段 `10/8` 与 `172.16-31` 与
     * `192.168/16`、IPv4 链路本地 `169.254/16`、运营商级 NAT `100.64/10`、
     * 未指定地址 `0.0.0.0`、IPv6 回环 `::1`，以及 `localhost` / `*.local`。
     *
     * **不解析域名**——所以"指向内网"的域名在这里看不出来，这是这一层的已知边界，
     * 不是遗漏。要真正封死得先解析、拿到 IP 再比一次，这一期明确不做。
     */
    private fun looksInternal(url: String): Boolean {
        val authority = url.removePrefix("https://").substringBefore('/').lowercase()
        // 带端口的要把端口去掉。IPv6 写成 [::1]:8080，**不能按第一个冒号切**——
        // 那个冒号在方括号里面；裸写 ::1 的也一并认出来。
        val host = when {
            authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
            authority == "::1" || authority.startsWith("::1:") -> "::1"
            else -> authority.substringBefore(':')
        }

        if (host == "localhost" || host.endsWith(".local")) return true
        if (host == "::1") return true

        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 127 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 169 && parts[1] == 254 -> true
            // 运营商级 NAT：既不是私有段，也不该被当成公网目标
            parts[0] == 100 && parts[1] in 64..127 -> true
            // 未指定地址，连出去会落到本机
            parts.all { it == 0 } -> true
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
