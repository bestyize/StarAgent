package com.starringbox.searx

import com.starringbox.searx.engines.BingEngine
import com.starringbox.searx.engines.DuckDuckGoEngine
import com.starringbox.searx.engines.MojeekEngine
import com.starringbox.searx.engines.WikipediaEngine

/**
 * 四个内置源。分开暴露是为了让调用方能挑着用：
 * 只要百科就 `Searx(listOf(Engines.wikipedia()))`，不必把整个默认组合拉进来。
 *
 * 每个都是一次性的轻量对象（共用进程级 HTTP 客户端），想要几个建几个。
 */
object Engines {
    /** DuckDuckGo 的 HTML 端点，覆盖面最广的通用源。 */
    fun duckDuckGo(): SearchEngine = DuckDuckGoEngine()

    /** Bing 网页结果，第二个通用源。 */
    fun bing(): SearchEngine = BingEngine()

    /** Wikipedia OpenSearch JSON——唯一结构化、稳定的源，百科类查询优先。 */
    fun wikipedia(): SearchEngine = WikipediaEngine()

    /** Mojeek，自建索引的独立引擎，补覆盖面。 */
    fun mojeek(): SearchEngine = MojeekEngine()

    /** 默认组合：四个都用。 */
    fun all(): List<SearchEngine> = listOf(duckDuckGo(), bing(), wikipedia(), mojeek())
}
