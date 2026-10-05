package com.starringbox.searx.engines

import com.starringbox.searx.SEARX_USER_AGENT
import com.starringbox.searx.SearchEngine
import com.starringbox.searx.SearchRequest
import com.starringbox.searx.SearchResult
import com.starringbox.searx.attr
import com.starringbox.searx.cleanText
import com.starringbox.searx.sharedSearxClient
import com.starringbox.searx.urlEncode
import com.starringbox.searx.unwrapBingUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * Bing 网页结果的 HTML 端点。结果块是 `<li class="b_algo">`，
 * 标题在 `h2 a`，摘要在 `<p>`。
 *
 * 链接可能是 `/ck/a?…&u=a1<base64url>` 的跳转链，`unwrapBingUrl` 解。
 * Bing 对无 Cookie 的请求有时返回精简页，解析不到就返回空——交给聚合层忽略。
 */
internal class BingEngine(
    private val client: HttpClient = sharedSearxClient,
) : SearchEngine {
    override val name = "bing"

    override suspend fun search(request: SearchRequest): List<SearchResult> {
        val response = client.get("https://www.bing.com/search?q=${urlEncode(request.query)}&count=${request.limit}") {
            header(HttpHeaders.UserAgent, SEARX_USER_AGENT)
            header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
        }
        return parseBing(response.bodyAsText(), request.limit)
    }
}

private val bingBlock = Regex("""(?is)<li class="b_algo".*?</li>""")
private val bingAnchor = Regex("""(?is)<h2\b[^>]*>.*?<a\b([^>]*)>(.*?)</a>""")
private val bingParagraph = Regex("""(?is)<p\b[^>]*>(.*?)</p>""")

/** 纯函数：喂一段 Bing 的 HTML，返回解析结果。 */
internal fun parseBing(html: String, limit: Int): List<SearchResult> {
    val out = ArrayList<SearchResult>(limit)
    for (block in bingBlock.findAll(html)) {
        val chunk = block.value
        val anchor = bingAnchor.find(chunk) ?: continue
        // h2 的属性串前面没有标签名，补一个 `<a>` 外壳交给通用的 attr 解析
        val href = attr("<a${anchor.groupValues[1]}>", "href")?.let(::unwrapBingUrl) ?: continue
        if (!href.startsWith("http")) continue
        val title = cleanText(anchor.groupValues[2])
        if (title.isBlank()) continue
        val snippet = bingParagraph.find(chunk)?.let { cleanText(it.groupValues[1]) }.orEmpty()
        out += SearchResult(title, href, snippet, "bing")
        if (out.size >= limit) break
    }
    return out
}
