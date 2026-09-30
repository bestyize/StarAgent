package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.tools.ports.FetchResult
import com.starringbox.staragent.core.tools.ports.WebPort
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
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
 *
 * ## 重定向必须逐跳自己走
 *
 * 前两道闸门只在"我们实际请求的那个地址"上运行过。Ktor 的 `followRedirects`
 * 默认是 true，跟着一个 `302 Location: http://192.168.1.1/` 跳过去，
 * 两道闸门**一次全绕过**——而这个类就是这条路唯一的边界。
 *
 * 所以这里用的客户端**关掉了自动重定向**（[httpClient] 的 `followRedirects = false`
 * 那一个），由本类逐跳自己走：每一跳都解析 `Location`、**重跑前两道闸门**，
 * 再发下一跳，最多 [MAX_REDIRECTS] 跳。超限是普通失败，不是异常。
 *
 * [client] 是给测试留的缝：不注入的话就是关掉重定向的那一个共享客户端。
 * 留这道缝是为了"重定向前重跑闸门"这条规则能被单测钉住——
 * 它可以塞一个 MockEngine 造的客户端，不发任何真实请求。
 */
class AppWebPort(
    private val client: HttpClient = httpClient(followRedirects = false),
) : WebPort {

    override suspend fun fetch(url: String): FetchResult {
        val entry = url.trim()
        if (!entry.startsWith("https://")) {
            return FetchResult(false, "", "只支持 https 地址")
        }
        if (looksInternal(entry)) {
            return FetchResult(false, "", "这个地址指向内网，不能访问")
        }

        return runCatching { follow(entry) }.getOrElse { error ->
            FetchResult(false, "", "取不到：${error.message ?: error::class.simpleName}")
        }
    }

    /**
     * 从 [start] 开始走，逐跳自己跟重定向。**每一跳都重跑前两道闸门。**
     *
     * 只有网络层面的失败会抛（交给 [fetch] 的 `runCatching` 收成一条失败结果）；
     * 闸门拦下、跳数超限这些都**返回**失败结果，不抛。
     */
    private suspend fun follow(start: String): FetchResult {
        var current = start
        var hops = 0

        while (true) {
            val response = client.get(current)

            if (isRedirect(response.status.value)) {
                if (hops >= MAX_REDIRECTS) {
                    // 环、或一条没完没了的跳转链。普通失败，不抛——
                    // 模型看到"跳转太多"会换个地址，抛出去只会让用户看到崩溃。
                    return FetchResult(
                        false, "",
                        "这个地址连续跳转了 $MAX_REDIRECTS 次还没到，先停在这里。",
                    )
                }

                val location = response.headers[HttpHeaders.Location]
                    // 3xx 却不给 Location，这一跳走不下去；按普通失败处理
                    ?: return FetchResult(false, "", "取不到（HTTP ${response.status.value}）")

                // Location 可以是相对路径，必须**相对当前地址**解析。
                // 手写字符串拼接会在 `/a/b` + `../c`、缺 scheme、带 query 这些形状上出错，
                // 所以交给 Ktor 自己的 URLBuilder。
                val next = resolveAgainst(current, location)
                    ?: return FetchResult(false, "", "重定向的目标地址看不懂，先停在这里。")

                // 🔴 重定向后的地址必须**重新**过一遍前两道闸门。
                // 少了这两句，`https://attacker/r → 302 http://192.168.1.1/`
                // 会在一次跳转里同时绕过"只允许 https"和"挡内网"。
                if (!next.startsWith("https://")) {
                    return FetchResult(false, "", "重定向的目标不是 https，不能访问")
                }
                if (looksInternal(next)) {
                    return FetchResult(false, "", "重定向的目标指向内网，不能访问")
                }

                current = next
                hops++
                continue
            }

            if (!response.status.isSuccess()) {
                return FetchResult(false, "", "取不到（HTTP ${response.status.value}）")
            }
            return FetchResult(true, extractText(response.bodyAsText()))
        }
    }

    /**
     * 把 `Location` 按当前地址解析成绝对地址。解析不了返回 null（调用方变成一条普通失败）。
     *
     * 用 Ktor 的 `URLBuilder.takeFrom` 而不是自己拼字符串：相对路径、缺 scheme、
     * 相对 `..` 这几类都由库处理，也是 Ktor 自己的 HttpRedirect 插件用的同一套。
     */
    private fun resolveAgainst(current: String, location: String): String? =
        runCatching { URLBuilder(current).takeFrom(location).buildString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** 3xx 里会让客户端跟着走的那几个。与 Ktor HttpRedirect 认的是同一组。 */
    private fun isRedirect(status: Int): Boolean = status in REDIRECT_CODES

    /**
     * 字面量地址或明显的本地名字就挡掉。
     *
     * 覆盖的是这几段字面量：IPv4 回环 `127/8`、私有段 `10/8` 与 `172.16-31` 与
     * `192.168/16`、IPv4 链路本地 `169.254/16`、运营商级 NAT `100.64/10`、
     * 未指定地址 `0.0.0.0`、IPv6 回环 `::1`，以及 `localhost` / `*.local`。
     *
     * **不解析域名**——所以"指向内网"的域名在这里看不出来，这是这一层的已知边界，
     * 不是遗漏。要真正封死得先解析、拿到 IP 再比一次，这一期明确不做。
     *
     * 只认 `https://` 开头：调用方两次都在它之前先确认过 scheme。
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

    /**
     * `internal` 而不是 `private`：跳数上限本身要被测试钉住
     * （见 WebPortRedirectTest 里"恰好跟 MAX_REDIRECTS 次"那条断言）。
     */
    internal companion object {
        /** 单次网页正文的上限。够模型读懂，又不会把上下文撑爆。 */
        const val MAX_CHARS = 8000

        /**
         * 最多跟几跳。5 是折中：www 与 http→https 这类正常跳转够用，
         * 又不会被一条刻意拉长的链拖住。
         */
        const val MAX_REDIRECTS = 5

        /** MovedPermanently / Found / SeeOther / TemporaryRedirect / PermanentRedirect。 */
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
