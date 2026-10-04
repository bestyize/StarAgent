package com.starringbox.staragent.core.speech.local.asr.vad

import com.starringbox.staragent.core.speech.Wav
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpeechWavFilterTest {
    @Test
    fun unsupportedSampleRatePassesThroughWithoutLoadingVad() = runBlocking {
        val wav = Wav.wrap(ByteArray(16_000 * 2))
        val unsupported = wav.copyOf().also { bytes ->
            // The sample rate field in the fmt chunk is at byte 24 in this canonical WAV.
            bytes.putIntLe(24, 8_000)
            bytes.putIntLe(28, 16_000)
        }

        assertContentEquals(unsupported, filterSpeechWav(unsupported))
    }

    @Test
    fun parsesWavChunksBeforeDataAndRejectsSilence() = runBlocking {
        assertNotNull(createSpeechDetector(), "the bundled VAD should be available for this smoke test").close()

        val canonical = Wav.wrap(ByteArray(16_000 * 2))
        val junkChunk = byteArrayOf('J'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte(), 4, 0, 0, 0, 1, 2, 3, 4)
        val withJunk = canonical.copyOfRange(0, 12) + junkChunk + canonical.copyOfRange(12, canonical.size)
        withJunk.putIntLe(4, withJunk.size - 8)

        assertNull(filterSpeechWav(withJunk))
    }
}

private fun ByteArray.putIntLe(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}
