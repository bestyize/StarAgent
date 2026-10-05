package com.starringbox.searx

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

internal actual fun defaultSearxHttpClient(): HttpClient = HttpClient(CIO) {
    configureSearx()
}
