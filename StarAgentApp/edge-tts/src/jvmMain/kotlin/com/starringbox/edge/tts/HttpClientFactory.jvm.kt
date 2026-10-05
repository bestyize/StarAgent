package com.starringbox.edge.tts

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

internal actual fun defaultEdgeTtsHttpClient(): HttpClient = HttpClient(CIO) {
    configureEdgeTts()
}
