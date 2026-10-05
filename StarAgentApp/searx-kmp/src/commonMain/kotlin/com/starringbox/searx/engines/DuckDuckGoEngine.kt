package com.starringbox.searx.engines

import com.starringbox.searx.SEARX_USER_AGENT
import com.starringbox.searx.SearchEngine
import com.starringbox.searx.SearchRequest
import com.starringbox.searx.SearchResult
import com.starringbox.searx.attr
import com.starringbox.searx.classTags
import com.starringbox.searx.cleanText
import com.starringbox.searx.sharedSearxClient
import com.starringbox.searx.unwrapRedirect
import com.starringbox.searx.urlEncode
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * DuckDuckGo 的 HTML 端点（`html.duckduckgo.com/html/`，不是 JS 版）。
 * 结果块是 `<div class="result results_links …">`，标题在 `a.result__a`，
 * 摘要在 `a.result__snippet`。
 *
 * 链接是 `//duckduckgo.com/l/?uddg=<encoded>` 的跳转链，`unwrapRedirect` 会解出真实地址。
 */
internal class DuckDuckGoEngine(
    private val client: HttpClient = sharedSearxClient,
) : SearchEngine {
    override val name = "duckduckgo"

    override suspend fun search(request: SearchRequest): List<SearchResult> {
        val response = client.get("https://html.duckduckgo.com/html/?q=${urlEncode(request.query)}") {
            header(HttpHeaders.UserAgent, SEARX_USER_AGENT)
        }
        return parseDuckDuckGo(response.bodyAsText(), request.limit)
    }
}

/** 纯函数：喂一段 DDG 的 HTML，返回解析结果。测试直接调它，不碰网络。 */
internal fun parseDuckDuckGo(html: String, limit: Int): List<SearchResult> {
    val out = ArrayList<SearchResult>(limit)
    for (block in classTags(html, "result")) {
        if (block.tag != "div") continue
        val titleTag = classTags(block.inner, "result__a").firstOrNull() ?: continue
        val url = attr(titleTag.open, "href")?.let(::unwrapRedirect) ?: continue
        val title = cleanText(titleTag.inner)
        if (title.isBlank()) continue
        val snippet = classTags(block.inner, "result__snippet").firstOrNull()
            ?.let { cleanText(it.inner) }.orEmpty()
        out += SearchResult(title, url, snippet, "duckduckgo")
        if (out.size >= limit) break
    }
    return out
}
