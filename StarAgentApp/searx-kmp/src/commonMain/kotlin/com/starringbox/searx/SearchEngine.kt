package com.starringbox.searx

/**
 * 一个上游搜索源。
 *
 * 实现只做一件事：把 [request] 变成一个 HTTP 请求，再把响应解析成 [SearchResult] 列表。
 * **失败就抛异常**——聚合那层会逐个 catch，一个引擎挂了不该拖垮整次搜索。
 * 挂起操作须响应协程取消，并在取消或超时后释放本次请求的资源。
 */
interface SearchEngine {
    /** 引擎名，用于去重时的标注和日志。 */
    val name: String

    suspend fun search(request: SearchRequest): List<SearchResult>
}

/** 单源超时按引擎失败处理；所有源都失败时可由调用方获知具体原因。 */
class SearchEngineTimeoutException(
    val engineName: String,
    val timeoutMillis: Long,
) : IllegalStateException("Search engine '$engineName' timed out after ${timeoutMillis}ms")
