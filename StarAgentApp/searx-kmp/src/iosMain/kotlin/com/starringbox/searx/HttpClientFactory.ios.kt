package com.starringbox.searx

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun defaultSearxHttpClient(): HttpClient = HttpClient(Darwin) {
    configureSearx()
}
