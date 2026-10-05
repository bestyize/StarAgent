package com.starringbox.searx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Test fun keepsCaseSensitivePathsDistinct() = runTest {
        val results = Searx(listOf(FakeEngine("a", listOf(
            result("upper path", "https://example.com/Guide"),
            result("lower path", "https://example.com/guide"),
        )))).search(SearchRequest("q"))
        assertEquals(listOf("upper path", "lower path"), results.map { it.title })
    }

    @Test fun keepsQueryNamesAndValuesCaseSensitive() = runTest {
        val urls = listOf(
            "https://example.com/search?Key=AbC",
            "https://example.com/search?key=AbC",
            "https://example.com/search?key=abc",
        )
        val results = Searx(listOf(FakeEngine("a", urls.map { result(it, it) }))).search(SearchRequest("q"))
        assertEquals(urls, results.map { it.url })
    }

    @Test fun normalizesHostCaseWithoutChangingTheReturnedUrl() = runTest {
        val original = "HTTPS://WWW.EXAMPLE.COM/Guide/?key=AbC#intro"
        val results = Searx(listOf(
            FakeEngine("a", listOf(result("first", original))),
            FakeEngine("b", listOf(result("duplicate", "http://example.com/Guide?key=AbC#details"))),
        )).search(SearchRequest("q"))
        assertEquals(listOf(original), results.map { it.url })
    }

    @Test fun preservesTrailingSlashesInsideQueryValues() = runTest {
        val withSlash = "https://example.com/Guide?next=Path/"
        val withoutSlash = "https://example.com/Guide?next=Path"
        val results = Searx(listOf(FakeEngine("a", listOf(
            result("slash", withSlash), result("no slash", withoutSlash),
        )))).search(SearchRequest("q"))
        assertEquals(listOf(withSlash, withoutSlash), results.map { it.url })
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun timedOutSourceReleasesResourcesAndKeepsSuccessfulResults() = runTest {
        var released = false
        val slow = object : SearchEngine {
            override val name = "slow"
            override suspend fun search(request: SearchRequest): List<SearchResult> {
                try { awaitCancellation() } finally { released = true }
            }
        }
        val fast = object : SearchEngine {
            override val name = "fast"
            override suspend fun search(request: SearchRequest): List<SearchResult> {
                delay(20)
                return listOf(result("ready", "https://example.com/Guide?Key=AbC"))
            }
        }
        val results = Searx(listOf(slow, fast), engineTimeoutMillis = 100).search(SearchRequest("q"))
        assertEquals(listOf("ready"), results.map { it.title })
        assertTrue(released)
        assertEquals(100L, testScheduler.currentTime)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun allTimedOutSourcesFailAtTheirSharedDeadline() = runTest {
        val sources = listOf("first", "second").map { name -> object : SearchEngine {
            override val name = name
            override suspend fun search(request: SearchRequest): List<SearchResult> = awaitCancellation()
        } }
        val failure = assertFailsWith<SearchEngineTimeoutException> {
            Searx(sources, engineTimeoutMillis = 100).search(SearchRequest("q"))
        }
        assertEquals("first", failure.engineName)
        assertEquals(100L, failure.timeoutMillis)
        assertEquals(100L, testScheduler.currentTime, "Sources must run in parallel, not consume sequential timeout budgets")
    }

    @Test fun callerCancellationPropagatesAndReleasesTheSource() = runTest {
        val entered = CompletableDeferred<Unit>()
        var released = false
        var cancelled = false
        val source = object : SearchEngine {
            override val name = "pending"
            override suspend fun search(request: SearchRequest): List<SearchResult> {
                entered.complete(Unit)
                try { awaitCancellation() } finally { released = true }
            }
        }
        val job = launch {
            try { Searx(listOf(source)).search(SearchRequest("q")) }
            catch (failure: CancellationException) { cancelled = true; throw failure }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(released)
    }

    @Test fun nonPositiveSourceBudgetsAreRejected() {
        assertFailsWith<IllegalArgumentException> { Searx(emptyList(), engineTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { Searx(emptyList(), engineTimeoutMillis = -1) }
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

    private fun engine(dispatcher: CoroutineDispatcher): MockEngine = MockEngine(MockEngineConfig().apply {
        // HTTP 模拟响应与超时使用同一虚拟时钟，避免真实 IO 线程尚未响应时测试时钟先跳到超时。
        this.dispatcher = dispatcher
        addHandler { request ->
            val body = when {
                "duckduckgo" in request.url.host -> ddgHtml
                "bing.com" in request.url.host -> bingHtml
                "wikipedia.org" in request.url.host -> wikiJson
                else -> mojeekHtml
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        }
    })

    @Test fun fullSearchRunsEveryEngineThroughMockHttp() = runTest {
        // 四个引擎共用同一个 MockEngine，各自按 host 拿响应
        val client = HttpClient(engine(StandardTestDispatcher(testScheduler)))
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
