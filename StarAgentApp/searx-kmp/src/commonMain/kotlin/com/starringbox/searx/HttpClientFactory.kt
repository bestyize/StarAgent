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
 * 默认请求预算与单源预算一致，为 Agent 的工具总时限预留返回和聚合余量。
 * Searx 还会约束自定义 HTTP 客户端及引擎，单个慢源不会占满外层工具预算。
 */
internal fun HttpClientConfig<*>.configureSearx() {
    install(HttpTimeout) {
        requestTimeoutMillis = Searx.DEFAULT_ENGINE_TIMEOUT_MILLIS
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = Searx.DEFAULT_ENGINE_TIMEOUT_MILLIS
    }
}

/**
 * 进程级共享客户端。四个引擎共用一份连接池，没必要各建各的；它随进程存活，
 * 这一版不提供 close。
 */
internal val sharedSearxClient: HttpClient by lazy { defaultSearxHttpClient() }
