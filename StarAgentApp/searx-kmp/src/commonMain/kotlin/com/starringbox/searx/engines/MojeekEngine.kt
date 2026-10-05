package com.starringbox.searx.engines

import com.starringbox.searx.SEARX_USER_AGENT
import com.starringbox.searx.SearchEngine
import com.starringbox.searx.SearchRequest
import com.starringbox.searx.SearchResult
import com.starringbox.searx.attr
import com.starringbox.searx.classTags
import com.starringbox.searx.cleanText
import com.starringbox.searx.sharedSearxClient
import com.starringbox.searx.urlEncode
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * Mojeek——**唯一一个自己建索引、且公开允许抓取的独立搜索引擎**。
 * 结果都在 `ul.results-standard > li` 里，标题是 `a.title`（老版是 `h2 > a`），
 * 摘要是 `p.s`。
 *
 * 它家对自动请求比较宽容，作为第四个源用来补覆盖面；解析不到就返回空。
 */
internal class MojeekEngine(
    private val client: HttpClient = sharedSearxClient,
) : SearchEngine {
    override val name = "mojeek"

    override suspend fun search(request: SearchRequest): List<SearchResult> {
        val response = client.get("https://www.mojeek.com/search?q=${urlEncode(request.query)}") {
            header(HttpHeaders.UserAgent, SEARX_USER_AGENT)
        }
        return parseMojeek(response.bodyAsText(), request.limit)
    }
}

private val mojeekItem = Regex("""(?is)<li\b.*?</li>""")
private val mojeekTitleAnchor = Regex("""(?is)<a\b([^>]*\bclass="[^"]*\btitle\b[^"]*"[^>]*)>(.*?)</a>""")
private val mojeekH2Anchor = Regex("""(?is)<h2\b[^>]*>\s*<a\b([^>]*)>(.*?)</a>""")
private val mojeekSnippet = Regex("""(?is)<p\b[^>]*\bclass="[^"]*\bs\b[^"]*"[^>]*>(.*?)</p>""")

/** 纯函数：喂一段 Mojeek 的 HTML，返回解析结果。 */
internal fun parseMojeek(html: String, limit: Int): List<SearchResult> {
    val container = classTags(html, "results-standard").firstOrNull()
        ?: classTags(html, "results").firstOrNull()
        ?: return emptyList()
    val out = ArrayList<SearchResult>(limit)
    for (item in mojeekItem.findAll(container.inner)) {
        val chunk = item.value
        val anchor = mojeekTitleAnchor.find(chunk) ?: mojeekH2Anchor.find(chunk) ?: continue
        val href = attr("<a${anchor.groupValues[1]}>", "href")
            ?.takeIf { it.startsWith("http") } ?: continue
        val title = cleanText(anchor.groupValues[2])
        if (title.isBlank()) continue
        val snippet = mojeekSnippet.find(chunk)?.let { cleanText(it.groupValues[1]) }.orEmpty()
        out += SearchResult(title, href, snippet, "mojeek")
        if (out.size >= limit) break
    }
    return out
}
