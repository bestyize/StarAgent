package com.starringbox.staragent.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * HTTP 引擎按平台区分：桌面用 CIO、Android 用 OkHttp、iOS 用 Darwin。
 * 这是整个模型接入层里唯一需要平台代码的地方。
 */
internal expect fun newPlatformHttpClient(): HttpClient

/**
 * 流式请求要能长时间挂着，所以请求级超时给得很宽，
 * 真正的看门狗是"多久没收到数据"，也就是 socketTimeout。
 *
 * WebSocket 插件也在这里装。它是**给火山方舟 Agent Plan 的语音识别用的**——
 * 那家的识别只有带 `/plan/` 的 WebSocket 二进制端点，没有 HTTP 版本。
 * 装在共用客户端上不花什么代价（只是个插件），对话那几条 HTTP 请求不受影响。
 */
internal fun HttpClientConfig<*>.applyStarAgentDefaults() {
    install(WebSockets)
    install(HttpTimeout) {
        requestTimeoutMillis = 900_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 180_000
    }
}

/** 复用同一个客户端，避免每次对话都重建连接池。 */
private val sharedClient: HttpClient by lazy { newPlatformHttpClient() }

internal fun httpClient(): HttpClient = sharedClient
