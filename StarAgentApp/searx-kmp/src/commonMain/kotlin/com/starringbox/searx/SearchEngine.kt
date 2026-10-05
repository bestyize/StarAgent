package com.starringbox.searx

/**
 * 一个上游搜索源。
 *
 * 实现只做一件事：把 [request] 变成一个 HTTP 请求，再把响应解析成 [SearchResult] 列表。
 * **失败就抛异常**——聚合那层会逐个 catch，一个引擎挂了不该拖垮整次搜索。
 */
interface SearchEngine {
    /** 引擎名，用于去重时的标注和日志。 */
    val name: String

    suspend fun search(request: SearchRequest): List<SearchResult>
}
