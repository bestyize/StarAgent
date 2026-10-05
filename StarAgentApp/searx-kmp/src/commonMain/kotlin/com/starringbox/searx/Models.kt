package com.starringbox.searx

/**
 * 一条搜索结果。字段就这三个——标题、链接、摘要，够 Agent 判断要不要点进去看正文。
 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String = "",
    /** 这条结果来自哪个引擎，去重时用来标注和排查。 */
    val engine: String = "",
)

/**
 * 搜索请求。只是把参数收在一起，没有别的作用。
 *
 * [limit] 是"每个引擎最多取几条"，不是最终条数——聚合去重之后可能少一些。
 */
data class SearchRequest(
    val query: String,
    val limit: Int = 10,
    /** 语言偏好，传给支持的引擎（如 Wikipedia 的 wiki 子域、DDG 的 `kl` 参数）。空串表示引擎默认。 */
    val language: String = "",
)
