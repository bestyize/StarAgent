package com.starringbox.searx

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/**
 * HTTP 引擎按平台区分：桌面用 CIO、Android 用 OkHttp、iOS 用 Darwin。
 * 和 edge-tts 一个路子——全库只有这一处需要平台代码。
 */
internal expect fun defaultSearxHttpClient(): HttpClient

/**
 * 一次搜索给每个引擎 30 秒。搜索引擎偶尔会很慢，但这个库是给 Agent 当工具用的，
 * 卡太久不如早点放弃、把已有的结果还回去。
 */
internal fun HttpClientConfig<*>.configureSearx() {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}

/**
 * 进程级共享客户端。四个引擎共用一份连接池，没必要各建各的；它随进程存活，
 * 这一版不提供 close。
 */
internal val sharedSearxClient: HttpClient by lazy { defaultSearxHttpClient() }
