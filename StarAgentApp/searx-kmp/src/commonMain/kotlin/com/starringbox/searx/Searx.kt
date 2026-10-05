package com.starringbox.searx

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 把 [engines] 聚成一个搜索源——这就是这个库的全部。
 *
 * 一次 `search` 做四件事：并发问所有引擎、按 URL 去重、轮流取结果、截断到 [SearchRequest.limit]。
 * 没有排序打分（SearXNG 那套权重是另一回事），只是把各路结果**交错**着放，
 * 让每个引擎都有机会出现在前面。
 *
 * ## 引擎失败是常态，不往上抛
 *
 * 公开引擎会限流、会改版、会超时。一个挂了不该让整次搜索失败——挂了跳过它，
 * 用剩下的结果继续。**只有全部引擎都抛异常时**才把第一个异常抛出去，
 * 让调用方能区分"网络确实不通"和"真的没有结果"。
 */
class Searx(
    private val engines: List<SearchEngine>,
    private val engineTimeoutMillis: Long,
) {

    /** 保留原有单参数构造器，现有调用方继续使用默认预算。 */
    constructor(engines: List<SearchEngine>) : this(engines, DEFAULT_ENGINE_TIMEOUT_MILLIS)

    /** 默认配置：四个公开源，全都不要 API Key。 */
    constructor() : this(Engines.all())

    init { require(engineTimeoutMillis > 0) { "engineTimeoutMillis must be positive" } }

    suspend fun search(request: SearchRequest): List<SearchResult> {
        require(request.query.isNotBlank()) { "query must not be blank" }
        val limit = if (request.limit <= 0) 10 else request.limit

        // supervisorScope：一个引擎抛异常不会取消兄弟协程。
        val outcomes = supervisorScope {
            engines.map { engine ->
                async {
                    withTimeoutOrNull(engineTimeoutMillis) {
                        try { Result.success(engine.search(request)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { Result.failure(failure) }
                    } ?: Result.failure(SearchEngineTimeoutException(engine.name, engineTimeoutMillis))
                }
            }.map { it.await() }
        }

        // 一个都没成功：要么全挂了（抛第一个异常），要么本来就没引擎（没结果）。
        if (outcomes.all { it.isFailure }) {
            outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
            return emptyList()
        }

        return interleave(outcomes.map { it.getOrDefault(emptyList()) }, limit)
    }

    /**
     * 轮流从每个引擎的批次里取一条，边取边按 URL 去重。
     *
     * 第一轮取每家的第 1 条、第二轮取每家的第 2 条……直到凑够 [limit] 或没有更多。
     * 同一篇文章被多家收录时，第一次出现的那条（也就是更靠前的引擎的结果）胜出。
     */
    private fun interleave(batches: List<List<SearchResult>>, limit: Int): List<SearchResult> {
        val seen = HashSet<String>()
        val out = ArrayList<SearchResult>(limit)
        val cursors = IntArray(batches.size)
        var progressing = true
        while (out.size < limit && progressing) {
            progressing = false
            for (i in batches.indices) {
                if (out.size >= limit) break
                val batch = batches[i]
                val index = cursors[i]
                if (index >= batch.size) continue
                cursors[i] = index + 1
                progressing = true
                val item = batch[index]
                if (seen.add(canonicalUrl(item.url))) out += item
            }
        }
        return out
    }

    companion object {
        /** 单源预算包含请求和解析；小于 StarAgent 的 30 秒工具总时限。自定义引擎须响应取消。 */
        const val DEFAULT_ENGINE_TIMEOUT_MILLIS = 15_000L

        /**
         * 保留既有的 http/https、www、路径末尾斜杠及 fragment 合并策略。
         * 仅规范化主机大小写，路径、用户信息及原始 query（包括末尾斜杠）保持原样。
         * 此键只用于去重，返回的 SearchResult.url 不会被改写。
         */
        private fun canonicalUrl(raw: String): String {
            val value = raw.trim().substringBefore('#')
            val schemeEnd = value.indexOf("://")
            if (schemeEnd < 0) return value
            val scheme = value.substring(0, schemeEnd)
            if (!scheme.equals("https", ignoreCase = true) && !scheme.equals("http", ignoreCase = true)) return value
            val address = value.substring(schemeEnd + 3)
            val authorityEnd = address.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) address.length else it }
            val authority = address.substring(0, authorityEnd)
            if (authority.isBlank()) return value
            val userInfoEnd = authority.lastIndexOf('@')
            val userInfo = if (userInfoEnd >= 0) authority.substring(0, userInfoEnd + 1) else ""
            val host = authority.substring(userInfoEnd + 1).lowercase().removePrefix("www.")
            val tail = address.substring(authorityEnd)
            val queryStart = tail.indexOf('?')
            val path = if (queryStart >= 0) tail.substring(0, queryStart) else tail
            val query = if (queryStart >= 0) tail.substring(queryStart) else ""
            return userInfo + host + path.trimEnd('/') + query
        }
    }
}
