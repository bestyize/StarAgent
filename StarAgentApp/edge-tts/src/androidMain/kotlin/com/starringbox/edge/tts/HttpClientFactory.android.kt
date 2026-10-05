package com.starringbox.edge.tts

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun defaultEdgeTtsHttpClient(): HttpClient = HttpClient(OkHttp) {
    configureEdgeTts()
}
