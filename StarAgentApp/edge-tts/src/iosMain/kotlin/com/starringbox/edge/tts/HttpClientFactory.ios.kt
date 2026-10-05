package com.starringbox.edge.tts

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun defaultEdgeTtsHttpClient(): HttpClient = HttpClient(Darwin) {
    configureEdgeTts()
}
