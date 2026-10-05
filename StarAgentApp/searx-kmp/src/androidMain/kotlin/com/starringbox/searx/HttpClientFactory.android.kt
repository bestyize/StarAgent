package com.starringbox.searx

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun defaultSearxHttpClient(): HttpClient = HttpClient(OkHttp) {
    configureSearx()
}
