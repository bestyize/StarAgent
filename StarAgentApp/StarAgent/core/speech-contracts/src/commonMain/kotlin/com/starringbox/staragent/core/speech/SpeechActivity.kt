package com.starringbox.staragent.core.speech

import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/** Lightweight capture-side endpoint detection; recognition still uses the selected ASR. */
class SpeechActivity {
    @Volatile var level: Float = 0f
        private set
    @Volatile var ended: Boolean = false
        private set
    private var speechMillis = 0
    private var silenceMillis = 0

    fun reset() {
        level = 0f
        ended = false
        speechMillis = 0
        silenceMillis = 0
    }

    fun acceptPcm(bytes: ByteArray, length: Int, sampleRate: Int = Wav.SAMPLE_RATE) {
        val samples = length.coerceAtMost(bytes.size) / 2
        if (samples == 0) return
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
            val value = sample.toDouble() / 32768.0
            sum += value * value
        }
        acceptLevel(sqrt(sum / samples).toFloat(), samples * 1000 / sampleRate)
    }

    fun acceptLevel(rms: Float, durationMillis: Int) {
        level = (rms * 12f).coerceIn(0f, 1f)
        if (ended) return
        if (rms >= 0.012f) {
            speechMillis += durationMillis
            silenceMillis = 0
        } else if (speechMillis >= 160) {
            silenceMillis += durationMillis
            ended = silenceMillis >= 1_100
        } else {
            // A transient click is not a complete utterance.
            speechMillis = 0
        }
    }
}
