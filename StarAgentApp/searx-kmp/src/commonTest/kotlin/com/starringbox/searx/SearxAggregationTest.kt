package com.starringbox.searx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 一个可控的假引擎：返回固定结果，或抛异常。 */
private class FakeEngine(
    override val name: String,
    private val results: List<SearchResult> = emptyList(),
    private val error: Throwable? = null,
) : SearchEngine {
    var calls = 0
        private set

    override suspend fun search(request: SearchRequest): List<SearchResult> {
        calls++
        error?.let { throw it }
        return results
    }
}

private fun result(title: String, url: String, snippet: String = "") =
    SearchResult(title, url, snippet, "fake")

class SearxAggregationTest {

    @Test fun interleavesResultsFromEveryEngine() = runTest {
        val searx = Searx(
            listOf(
                FakeEngine("a", listOf(result("a1", "https://a.example/1"), result("a2", "https://a.example/2"))),
                FakeEngine("b", listOf(result("b1", "https://b.example/1"), result("b2", "https://b.example/2"))),
            ),
        )

        val results = searx.search(SearchRequest("q", limit = 4))

        // 轮流取：a1, b1, a2, b2
        assertEquals(listOf("a1", "b1", "a2", "b2"), results.map { it.title })
    }

    @Test fun deduplicatesByCanonicalUrl() = runTest {
        val searx = Searx(
            listOf(
                FakeEngine("a", listOf(result("first", "https://www.example.com/page/"))),
                FakeEngine("b", listOf(result("dup", "http://example.com/page"), result("other", "https://example.com/x"))),
            ),
        )

        val results = searx.search(SearchRequest("q", limit = 10))

        // www/http/结尾斜杠的差异被归一化，第一条胜出
        assertEquals(listOf("first", "other"), results.map { it.title })
    }

    @Test fun respectsLimit() = runTest {
        val many = (1..50).map { result("r$it", "https://example.com/$it") }
        val searx = Searx(listOf(FakeEngine("a", many)))

        assertEquals(7, searx.search(SearchRequest("q", limit = 7)).size)
    }

    @Test fun oneEngineFailingDoesNotBreakTheOthers() = runTest {
        val searx = Searx(
            listOf(
                FakeEngine("bad", error = RuntimeException("boom")),
                FakeEngine("good", listOf(result("ok", "https://ok.example/"))),
            ),
        )

        // 坏引擎抛异常，好的照样出结果——不往上抛
        val results = searx.search(SearchRequest("q", limit = 5))
        assertEquals(listOf("ok"), results.map { it.title })
    }

    @Test fun allEnginesFailingThrows() = runTest {
        val searx = Searx(
            listOf(
                FakeEngine("bad1", error = RuntimeException("boom1")),
                FakeEngine("bad2", error = RuntimeException("boom2")),
            ),
        )

        assertFailsWith<RuntimeException> { searx.search(SearchRequest("q", limit = 5)) }
    }

    @Test fun enginesWithNoResultsIsNotAnError() = runTest {
        val searx = Searx(listOf(FakeEngine("a"), FakeEngine("b")))
        assertTrue(searx.search(SearchRequest("q", limit = 5)).isEmpty())
    }

    @Test fun blankQueryIsRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { Searx(listOf(FakeEngine("a"))).search(SearchRequest("  ")) }
    }
}

/** 端到端：用 MockEngine 同时喂四个引擎的响应，走完整条 search()。 */
class SearxWithMockEngineTest {

    private val ddgHtml = """
        <div class="result"><a class="result__a" href="https://kotlinlang.org/">Kotlin</a>
        <a class="result__snippet">The Kotlin language.</a></div>
    """.trimIndent()

    private val bingHtml = """
        <li class="b_algo"><h2><a href="https://kotlinlang.org/docs">Kotlin docs</a></h2>
        <p>Official documentation.</p></li>
    """.trimIndent()

    private val wikiJson = """
        ["kotlin",["Kotlin"],["Kotlin is a language."],["https://en.wikipedia.org/wiki/Kotlin"]]
    """.trimIndent()

    private val mojeekHtml = """
        <ul class="results-standard"><li><h2><a class="title" href="https://mojeek.example/k">K</a></h2>
        <p class="s">Snippet.</p></li></ul>
    """.trimIndent()

    private fun engine(): MockEngine = MockEngine { request ->
        val body = when {
            "duckduckgo" in request.url.host -> ddgHtml
            "bing.com" in request.url.host -> bingHtml
            "wikipedia.org" in request.url.host -> wikiJson
            else -> mojeekHtml
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
    }

    @Test fun fullSearchRunsEveryEngineThroughMockHttp() = runTest {
        // 四个引擎共用同一个 MockEngine，各自按 host 拿响应
        val client = HttpClient(engine())
        val searx = Searx(
            listOf(
                com.starringbox.searx.engines.DuckDuckGoEngine(client),
                com.starringbox.searx.engines.BingEngine(client),
                com.starringbox.searx.engines.WikipediaEngine(client),
                com.starringbox.searx.engines.MojeekEngine(client),
            ),
        )

        val results = searx.search(SearchRequest("kotlin", limit = 10))

        // 四家都返回了东西
        assertEquals(4, results.size)
        assertTrue(results.any { it.engine == "duckduckgo" })
        assertTrue(results.any { it.engine == "wikipedia" })
    }
}
