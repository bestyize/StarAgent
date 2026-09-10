package com.starringbox.staragent.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

internal actual fun newPlatformHttpClient(): HttpClient = HttpClient(CIO) {
    applyStarAgentDefaults()
}
