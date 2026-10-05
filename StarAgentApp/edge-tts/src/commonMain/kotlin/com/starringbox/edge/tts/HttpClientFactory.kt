package com.starringbox.edge.tts

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

internal expect fun defaultEdgeTtsHttpClient(): HttpClient

internal fun HttpClientConfig<*>.configureEdgeTts() {
    install(WebSockets)
    install(HttpTimeout) {
        requestTimeoutMillis = 120_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 120_000
    }
}
