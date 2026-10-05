package com.starringbox.edge.tts

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live service smoke test. It stays inert in normal CI and is enabled with
 * EDGE_TTS_LIVE_TEST=1 because the underlying service is unofficial and external.
 */
class EdgeTtsLiveSmokeTest {
    @Test
    fun listsVoicesAndSynthesizesAudio() {
        if (System.getenv("EDGE_TTS_LIVE_TEST") != "1") return

        runBlocking {
            val client = EdgeTtsClient(EdgeTtsConfig(boundary = EdgeTtsBoundaryType.WordBoundary))
            try {
                assertTrue(client.voices().any { it.shortName == "zh-CN-XiaoxiaoNeural" })

                val mp3 = client.synthesizeToBytes("你好，这是 edge-tts Kotlin Multiplatform 的联调测试。")
                assertTrue(mp3.isNotEmpty())

                var streamedBytes = 0
                var boundaries = 0
                var completed = false
                client.synthesize(
                    "流式字节测试。",
                ).collect { event ->
                    if (event is EdgeTtsEvent.Audio) streamedBytes += event.bytes.size
                    if (event is EdgeTtsEvent.Boundary) boundaries++
                    if (event == EdgeTtsEvent.Completed) completed = true
                }
                assertTrue(streamedBytes > 0)
                assertTrue(boundaries > 0)
                assertTrue(completed)
            } finally {
                client.close()
            }
        }
    }
}
