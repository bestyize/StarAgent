package com.starringbox.searx.engines

import com.starringbox.searx.SEARX_USER_AGENT
import com.starringbox.searx.SearchEngine
import com.starringbox.searx.SearchRequest
import com.starringbox.searx.SearchResult
import com.starringbox.searx.sharedSearxClient
import com.starringbox.searx.urlEncode
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wikipedia 的 OpenSearch JSON 接口——**唯一一个结构化、稳定的源**，
 * 所以它给的摘要最干净（就是条目简介）。
 *
 * 语言取自 [SearchRequest.language] 的前缀（`zh-CN` → `zh`），拼进子域名；
 * 没有语言偏好时用英文站。
 */
internal class WikipediaEngine(
    private val client: HttpClient = sharedSearxClient,
) : SearchEngine {
    override val name = "wikipedia"

    override suspend fun search(request: SearchRequest): List<SearchResult> {
        val lang = request.language.takeWhile { it.isLetter() }.lowercase().ifBlank { "en" }
        val url = "https://$lang.wikipedia.org/w/api.php" +
            "?action=opensearch&format=json&limit=${request.limit}&search=${urlEncode(request.query)}"
        val response = client.get(url) { header(HttpHeaders.UserAgent, SEARX_USER_AGENT) }
        return parseWikipedia(response.bodyAsText(), request.limit)
    }
}

private val wikiJson = Json { ignoreUnknownKeys = true }

/** 纯函数：喂一段 OpenSearch 的 JSON（形状 `["query",[标题…],[简介…],[链接…]]`）。 */
internal fun parseWikipedia(body: String, limit: Int): List<SearchResult> {
    val root = runCatching { wikiJson.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
    val titles = root.getOrNull(1)?.jsonArray?.map { it.jsonPrimitive.content } ?: return emptyList()
    val snippets = root.getOrNull(2)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    val links = root.getOrNull(3)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    return titles.mapIndexedNotNull { index, title ->
        val url = links.getOrNull(index) ?: return@mapIndexedNotNull null
        if (title.isBlank() || !url.startsWith("http")) return@mapIndexedNotNull null
        SearchResult(title, url, snippets.getOrNull(index).orEmpty(), "wikipedia")
    }.take(limit)
}
