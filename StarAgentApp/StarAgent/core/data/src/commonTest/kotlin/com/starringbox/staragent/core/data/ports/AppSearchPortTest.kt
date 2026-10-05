package com.starringbox.staragent.core.data.ports

import com.starringbox.searx.SearchEngine
import com.starringbox.searx.SearchRequest
import com.starringbox.searx.SearchResult
import com.starringbox.searx.Searx
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `AppSearchPort` 只做一件事：把 `searx-kmp` 的 `SearchResult` 映射成工具层的 `SearchItem`，
 * 并把"所有引擎都失败"收成一条失败结果。真正的元搜索逻辑在那边的库里测，
 * 这里钉的是这条**边界**——字段映射对得上、异常变成给模型看的错误信息。
 */
class AppSearchPortTest {

    private class FakeEngine(
        override val name: String,
        private val results: List<SearchResult> = emptyList(),
        private val error: Throwable? = null,
    ) : SearchEngine {
        override suspend fun search(request: SearchRequest): List<SearchResult> {
            error?.let { throw it }
            return results
        }
    }

    @Test fun mapsLibraryResultsToSearchItems() = runTest {
        val searx = Searx(
            listOf(FakeEngine("fake", listOf(SearchResult("Title", "https://example.com/", "Snippet", "fake")))),
        )
        val port = AppSearchPort(searx)

        val result = port.search("q", 5)

        assertTrue(result.ok)
        assertEquals(1, result.items.size)
        assertEquals("Title", result.items[0].title)
        assertEquals("https://example.com/", result.items[0].url)
        assertEquals("Snippet", result.items[0].snippet)
    }

    @Test fun allEnginesFailingBecomesAFailedResult() = runTest {
        val port = AppSearchPort(Searx(listOf(FakeEngine("bad", error = RuntimeException("boom")))))

        val result = port.search("q", 5)

        assertFalse(result.ok)
        assertTrue(result.items.isEmpty())
        assertTrue(result.error!!.isNotBlank())
    }

    @Test fun noResultsIsStillSuccess() = runTest {
        val port = AppSearchPort(Searx(listOf(FakeEngine("none"))))

        val result = port.search("q", 5)

        assertTrue(result.ok)
        assertTrue(result.items.isEmpty())
    }
}
