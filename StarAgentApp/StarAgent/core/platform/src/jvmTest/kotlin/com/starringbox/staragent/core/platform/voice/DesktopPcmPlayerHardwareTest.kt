package com.starringbox.staragent.core.platform.voice

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopPcmPlayerHardwareTest {
    @Test
    fun outputLifecycleWorksWhenExplicitlyEnabled() = runBlocking {
        if (System.getenv("STARAGENT_VERIFY_PCM") != "1") return@runBlocking

        val player = DesktopPcmPlayer()
        try {
            assertTrue(player.available, "桌面系统应提供可用 PCM 输出设备")

            player.enqueue(silence(24_000, 200))
            player.drain()
            player.stop()

            player.enqueue(silence(22_050, 200))
            player.drain()
            player.stop()

            val writer = async(Dispatchers.IO) {
                player.enqueue(PcmAudioChunk(ByteArray(24_000 * 2 * 60), 24_000))
            }
            delay(100)
            writer.cancel()
            player.stop()
            withTimeout(2_000) { writer.join() }

            player.enqueue(silence(24_000, 200))
            player.drain()
            player.stop()
        } finally {
            player.close()
        }
    }

    /** Produces digital silence, so enabling the hardware check never emits audible test tones. */
    private fun silence(sampleRate: Int, durationMillis: Int): PcmAudioChunk =
        PcmAudioChunk(ByteArray(sampleRate * 2 * durationMillis / 1_000), sampleRate)
}
