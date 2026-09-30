package com.starringbox.staragent.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * HTTP 引擎按平台区分：桌面用 CIO、Android 用 OkHttp、iOS 用 Darwin。
 * 这是整个模型接入层里唯一需要平台代码的地方。
 *
 * [configure] 是留给调用方补的**那一点差异**（比如关掉自动重定向）。
 * 引擎本身、以及 [applyStarAgentDefaults] 那套超时，`actual` 里统一装。
 */
internal expect fun newPlatformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * 流式请求要能长时间挂着，所以请求级超时给得很宽，
 * 真正的看门狗是"多久没收到数据"，也就是 socketTimeout。
 *
 * WebSocket 插件也在这里装。它是**给火山方舟 Agent Plan 的语音识别用的**——
 * 那家的识别只有带 `/plan/` 的 WebSocket 二进制端点，没有 HTTP 版本。
 * 装在共用客户端上不花什么代价（只是个插件），对话那几条 HTTP 请求不受影响。
 *
 * ⚠️ **超时值不要拿去当工具超时**：请求级 900 秒是给"模型一次答很久"留的。
 * 工具那条路自己带 30 秒的看门狗（见 `AgentLoop`），不指望这里兜底。
 */
internal fun HttpClientConfig<*>.applyStarAgentDefaults() {
    install(WebSockets)
    install(HttpTimeout) {
        requestTimeoutMillis = 900_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 180_000
    }
}

/**
 * 建一个平台对的 HTTP 客户端。
 *
 * 本来是 internal，现在公开给 core:data 用（WebPort 的实现需要它）。
 * 引擎按平台分（CIO / OkHttp / Darwin），三端已经配好，没理由再造一个。
 *
 * [followRedirects] 默认 true，跟 Ktor 的默认值一致——对话那条路要它。
 * **取网页那条路必须传 false**：自动重定向会在**重定向前后都不再检查**的前提下
 * 跟着 `Location` 走，而 `AppWebPort` 的两道闸门（只允许 https、挡内网字面量地址）
 * 只在它自己拿到的那个地址上跑过。跟着一个 302 跳到 `http://192.168.1.1/`，
 * 两道闸门一次绕过——那是这条路唯一的边界。所以要关掉自动重定向，
 * 由 `AppWebPort` 逐跳自己走、每跳重跑一遍闸门（见 `WebPortImpl`）。
 */
fun httpClient(followRedirects: Boolean = true): HttpClient =
    if (followRedirects) sharedClient else noRedirectClient

/** 复用同一个客户端，避免每次对话都重建连接池。 */
private val sharedClient: HttpClient by lazy { newPlatformHttpClient { } }

/**
 * 关掉自动重定向的那一个。单独一份而不是每次现建：它同样是个连接池，
 * 现建会把"取网页"变成每次都重新握手。
 */
private val noRedirectClient: HttpClient by lazy {
    newPlatformHttpClient { followRedirects = false }
}
