package com.starringbox.staragent.core.data.ports

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 重定向这道口子。
 *
 * 这个类就是"取网页"唯一的安全边界——没有权限框架，只靠实现里那三道检查。
 * 而 Ktor 的自动重定向会让 `https://attacker/r → 302 http://192.168.1.1/`
 * **一次绕过两道**，所以 `AppWebPort` 必须关掉自动重定向、逐跳自己走、
 * 每一跳重跑一遍检查。这组用例钉的就是"每一跳都检查过"。
 *
 * 用 [MockEngine] 而不是真发请求：`302` 到一个内网地址这件事需要一台
 * **真的会给这种 Location 的服务器**，公网上不存在；而假的引擎能让每一跳
 * 的响应完全可控，也不依赖外网。生产路径不受影响——缝只在构造函数上。
 */
class WebPortRedirectTest {

    /**
     * 一台按地址回话的假服务器。[routes] 里没有的地址会以 200 回 [fallbackBody]。
     *
     * 返回的 [MockEngine] 还带 `requestHistory`，可以断言"某个地址**从没被请求过**"——
     * 拦下一个跳转要说得出"我们没往那儿发过东西"，只看返回值分不清
     * "拦住了"和"发了但被远端拒了"。
     */
    private fun engine(
        routes: Map<String, MockResponse>,
        fallbackBody: String = "终点站的正文",
    ): MockEngine = MockEngine { request ->
        val url = request.url.toString()
        val route = routes[url]
        when {
            route == null -> respond(fallbackBody, HttpStatusCode.OK)
            route.location != null -> respond(
                content = "",
                status = route.status,
                headers = headersOf(HttpHeaders.Location, route.location),
            )

            else -> respond(route.body, route.status)
        }
    }

    private class MockResponse(
        val status: HttpStatusCode,
        val location: String? = null,
        val body: String = "",
    )

    private fun redirect(to: String, status: HttpStatusCode = HttpStatusCode.Found) =
        MockResponse(status, location = to)

    private fun port(engine: MockEngine) = AppWebPort(
        // 与生产同一个形状：关掉自动重定向。开着的话 HttpRedirect 会先跳过去，
        // 这组用例就变成在测 Ktor 而不是在测我们的闸门。
        HttpClient(engine) { followRedirects = false },
    )

    // ── 负向：跳出去必须被拦住 ────────────────────

    /**
     * 🔴 这条是整组用例的核心：**降级到明文 http 的跳转必须拒绝，且目标一次都不能被请求**。
     */
    @Test
    fun refusesRedirectToNonHttpsAndNeverRequestsIt() = runTest {
        val engine = engine(
            mapOf(
                "https://attacker.example/r" to redirect("http://plain.example/secret"),
            )
        )

        val result = port(engine).fetch("https://attacker.example/r")

        assertFalse(result.ok, "跳去明文 http 必须失败，实际：$result")
        assertEquals(
            listOf("https://attacker.example/r"),
            engine.requestHistory.map { it.url.toString() },
            "只该请求过最初那个地址——跳转目标一次都不能发出去",
        )
    }

    /**
     * 🔴 同一个洞的另一半：跳到**内网地址**必须拒绝。
     *
     * 这条与上一条各堵一半——只堵 https 的话，`302 Location: http://192.168.1.1/`
     * 会被"不是 https"顺带挡住，但 `302 Location: https://192.168.1.1/` 会漏过去。
     * 所以下面两条（http 与 https 各一）都要有。
     */
    @Test
    fun refusesRedirectToInternalHttpAddress() = runTest {
        val engine = engine(
            mapOf("https://attacker.example/r" to redirect("http://192.168.1.1/")),
        )

        val result = port(engine).fetch("https://attacker.example/r")

        assertFalse(result.ok)
        assertEquals(1, engine.requestHistory.size, "内网目标不该被请求")
        assertTrue(
            result.error!!.contains("不是 https"),
            "降级到明文时先撞上的是 https 那道闸门，错误文案要说清是哪一道，实际：${result.error}",
        )
    }

    /** 内网判定那道闸门自己也要挡住：跳到一个**https 的内网地址**。 */
    @Test
    fun refusesRedirectToInternalHttpsAddress() = runTest {
        val engine = engine(
            mapOf("https://attacker.example/r" to redirect("https://192.168.1.1/admin")),
        )

        val result = port(engine).fetch("https://attacker.example/r")

        assertFalse(result.ok, "https 也挡不住的话这道闸门形同虚设")
        assertTrue(
            result.error!!.contains("内网"),
            "这一跳是 https，能被拦下只可能是内网那道闸门起了作用，实际：${result.error}",
        )
        assertEquals(1, engine.requestHistory.size)
    }

    /** 中间那一跳开始转向内网，也要拦——不是只看第一跳。 */
    @Test
    fun refusesInternalTargetOnASecondHop() = runTest {
        val engine = engine(
            mapOf(
                "https://a.example/start" to redirect("https://b.example/mid"),
                "https://b.example/mid" to redirect("https://10.0.0.5/"),
            )
        )

        val result = port(engine).fetch("https://a.example/start")

        assertFalse(result.ok)
        assertEquals(
            listOf("https://a.example/start", "https://b.example/mid"),
            engine.requestHistory.map { it.url.toString() },
            "第二跳通过了，第三跳必须被拦下",
        )
    }

    /**
     * 跳数超限是**普通的失败结果**，不是死循环、也不是异常。
     *
     * 用一条自己指自己的链（等价于环）——`hops >= MAX_REDIRECTS` 必须兜住它。
     */
    @Test
    fun redirectLoopPastTheCapFailsInsteadOfSpinning() = runTest {
        val engine = engine(
            mapOf("https://loop.example/x" to redirect("https://loop.example/x")),
        )

        val result = port(engine).fetch("https://loop.example/x")

        assertFalse(result.ok, "环不能返回成功，实际：$result")
        assertTrue(
            result.error!!.contains("跳转"),
            "要有一句人能看懂的话，实际：${result.error}",
        )
        // 最初那次 + 上限次数的跟随。写成 `> MAX` 或者忘了自增的话次数会跑偏。
        assertEquals(
            AppWebPort.MAX_REDIRECTS + 1,
            engine.requestHistory.size,
            "恰好跟 MAX_REDIRECTS 次就该停——多一次是没刹住，少一次是提前放弃",
        )
    }

    // ── 正向：正常的跳转必须照常跟 ────────────────

    /**
     * 正对照。**没有这条，一个"一律拒绝重定向"的实现也能让上面全绿。**
     *
     * 跳去另一个公网 https，要真的跟过去、拿到**终点站**的正文。
     */
    @Test
    fun followsALegitimateRedirectAndReturnsTheFinalBody() = runTest {
        val engine = engine(
            routes = mapOf(
                "https://old.example/page" to redirect("https://new.example/page"),
            ),
            fallbackBody = "终点站的正文",
        )

        val result = port(engine).fetch("https://old.example/page")

        assertTrue(result.ok, "正常的跳转不该被拦，实际：$result")
        assertEquals("终点站的正文", result.text)
        assertEquals(
            listOf("https://old.example/page", "https://new.example/page"),
            engine.requestHistory.map { it.url.toString() },
            "要真的跟到终点站去取，而不是拿 302 那一次空响应交差",
        )
    }

    /**
     * `Location` 是**相对地址**时必须相对当前地址解析，而不是当成绝对地址原样发出去。
     *
     * 两条各钉一种形状：根相对（`/c`）与纯相对（`next`，相对当前路径的目录）。
     * 后者的期望值 `/a/next` 说明"当前地址的路径参与了拼接"——把 Location 原样
     * 交给客户端的话，请求历史里会是 `https://site.example/next`（丢了 `/a`），
     * 或者干脆解析失败。实现用的是 Ktor 的 `URLBuilder.takeFrom`，也是 Ktor
     * 自己的 HttpRedirect 插件用的同一套（所以 `..` 不做规范化，两边一致）。
     */
    @Test
    fun resolvesRelativeLocationAgainstTheCurrentUrl() = runTest {
        val rootRelative = engine(
            routes = mapOf("https://site.example/a/b" to redirect("/c")),
            fallbackBody = "c 的内容",
        )
        assertTrue(port(rootRelative).fetch("https://site.example/a/b").ok)
        assertEquals(
            listOf("https://site.example/a/b", "https://site.example/c"),
            rootRelative.requestHistory.map { it.url.toString() },
            "根相对路径要解析成同源的绝对地址",
        )

        val bareRelative = engine(
            routes = mapOf("https://site.example/a/b" to redirect("next")),
            fallbackBody = "next 的内容",
        )
        assertTrue(port(bareRelative).fetch("https://site.example/a/b").ok)
        assertEquals(
            listOf("https://site.example/a/b", "https://site.example/a/next"),
            bareRelative.requestHistory.map { it.url.toString() },
            "纯相对路径要相对当前路径解析，`/a` 不能丢",
        )
    }

    /**
     * 一跳**通过闸门**、随后落在正常页面上：证明闸门只拦该拦的。
     *
     * 与 [refusesInternalTargetOnASecondHop] 是同一条链的前半段——那条被内网闸门拦下，
     * 这条走到终点。两条一起才说明"拦的是地址本身，不是跳转次数"。
     */
    @Test
    fun followsTwoHopsWhenBothArePublicHttps() = runTest {
        val engine = engine(
            routes = mapOf(
                "https://a.example/start" to redirect("https://b.example/mid"),
                "https://b.example/mid" to redirect("https://c.example/final"),
            ),
            fallbackBody = "最终正文",
        )

        val result = port(engine).fetch("https://a.example/start")

        assertTrue(result.ok, "两跳公网 https，没理由拦，实际：$result")
        assertEquals("最终正文", result.text)
        assertEquals(3, engine.requestHistory.size)
    }

    /** 一开始就是内网或非 https 时，**一次请求都不该发出去**。 */
    @Test
    fun rejectsBadEntryUrlWithoutAnyRequest() = runTest {
        val engine = engine(emptyMap())

        assertFalse(port(engine).fetch("http://example.com/").ok)
        assertFalse(port(engine).fetch("https://192.168.1.1/").ok)

        assertEquals(emptyList(), engine.requestHistory.map { it.url.toString() })
    }
}
