package com.starringbox.searx

import com.starringbox.searx.engines.parseBing
import com.starringbox.searx.engines.parseDuckDuckGo
import com.starringbox.searx.engines.parseMojeek
import com.starringbox.searx.engines.parseWikipedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 每个引擎的解析都喂一段固定的响应片段，不外网。 */
class EngineParseTest {

    @Test fun parsesDuckDuckGoResultsAndUnwrapsRedirect() {
        val html = """
            <div class="result results_links results_links_deep web-result">
              <div class="links_main links_deep result__body">
                <h2 class="result__title"><a class="result__a"
                   href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&amp;rut=abc">Kotlin Programming</a></h2>
                <a class="result__snippet">A modern language that makes developers happier.</a>
              </div>
            </div>
            <div class="result results_links">
              <a class="result__a" href="https://example.com/b">Second</a>
              <a class="result__snippet">Second snippet</a>
            </div>
        """.trimIndent()

        val results = parseDuckDuckGo(html, 10)

        assertEquals(2, results.size)
        assertEquals("Kotlin Programming", results[0].title)
        // uddg 解出了真实地址
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("A modern language that makes developers happier.", results[0].snippet)
        assertEquals("https://example.com/b", results[1].url)
        assertEquals("duckduckgo", results[0].engine)
    }

    @Test fun parsesWikipediaOpenSearchJson() {
        val body = """
            ["kotlin",["Kotlin","Kotlin (programming language)"],
             ["Kotlin is a cross-platform language.","Kotlin is a language by JetBrains."],
             ["https://en.wikipedia.org/wiki/Kotlin","https://en.wikipedia.org/wiki/Kotlin_(programming_language)"]]
        """.trimIndent()

        val results = parseWikipedia(body, 10)

        assertEquals(2, results.size)
        assertEquals("Kotlin (programming language)", results[1].title)
        assertEquals("https://en.wikipedia.org/wiki/Kotlin_(programming_language)", results[1].url)
        assertEquals("Kotlin is a language by JetBrains.", results[1].snippet)
        assertEquals("wikipedia", results[0].engine)
    }

    @Test fun parsesBingResults() {
        val html = """
            <ol id="b_results">
              <li class="b_algo">
                <h2><a href="https://kotlinlang.org/" h="ID=SERP">Kotlin</a></h2>
                <div class="b_caption"><p>Kotlin is a programming language.</p></div>
              </li>
            </ol>
        """.trimIndent()

        val results = parseBing(html, 10)

        assertEquals(1, results.size)
        assertEquals("Kotlin", results[0].title)
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("Kotlin is a programming language.", results[0].snippet)
    }

    @Test fun parsesMojeekResults() {
        val html = """
            <ul class="results-standard">
              <li>
                <h2><a class="title" href="https://kotlinlang.org/">Kotlin</a></h2>
                <p class="s">JetBrains' language.</p>
              </li>
            </ul>
        """.trimIndent()

        val results = parseMojeek(html, 10)

        assertEquals(1, results.size)
        assertEquals("Kotlin", results[0].title)
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("JetBrains' language.", results[0].snippet)
    }

    @Test fun decodeHelpersWork() {
        assertEquals("hello world", percentDecode("hello%20world"))
        assertEquals("a b", percentDecode("a+b"))
        assertEquals("a%2Fb%20c%3F", urlEncode("a/b c?"))
        assertEquals("https://example.com/x", unwrapBingUrl("https://www.bing.com/ck/a?!&&u=a1aHR0cHM6Ly9leGFtcGxlLmNvbS94"))
        // 非跳转链原样返回
        assertEquals("https://example.com/y", unwrapBingUrl("https://example.com/y"))
    }

    @Test fun emptyHtmlYieldsNoResults() {
        assertTrue(parseDuckDuckGo("<html></html>", 10).isEmpty())
        assertTrue(parseBing("<html></html>", 10).isEmpty())
        assertTrue(parseMojeek("<html></html>", 10).isEmpty())
        assertTrue(parseWikipedia("not json", 10).isEmpty())
    }
}
