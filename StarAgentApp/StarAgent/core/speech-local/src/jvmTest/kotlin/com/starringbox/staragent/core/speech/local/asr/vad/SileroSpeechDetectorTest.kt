package com.starringbox.staragent.core.speech.local.asr.vad

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.*

/**
 * Runs the bundled Silero model on synthetic household noise. Speech itself is covered by the explicit
 * `verifySpeechGate` check, since the repository does not ship voice recordings.
 */
class SileroSpeechDetectorTest {
    private val rate = SpeechAudio.SAMPLE_RATE
    private val random = Random(7)
    private fun white(rms: Float) = (random.nextFloat() * 2 - 1) * rms * 1.732f
    private fun signal(seconds: Double, sample: (Int) -> Float) = FloatArray((seconds * rate).toInt(), sample)
    private fun scaled(samples: FloatArray, rms: Float): FloatArray {
        val current = sqrt(samples.sumOf { it.toDouble() * it } / samples.size).toFloat()
        return FloatArray(samples.size) { samples[it] * rms / current }
    }

    private fun speechSamples(samples: FloatArray, chunk: Int = 320): Int {
        val detector = assertNotNull(createSpeechDetector(), "the bundled model must load on desktop")
        return detector.use {
            samples.toList().chunked(chunk).forEach { part -> it.accept(part.toFloatArray()) }
            it.finish().sumOf { span -> span.last - span.first + 1 }
        }
    }

    @Test
    fun householdNoiseIsNotSpeech() {
        var brown = 0f
        val noises = mapOf(
            "white noise" to signal(3.0) { white(.03f) },
            "fan" to scaled(signal(3.0) { i -> brown = (brown + (random.nextFloat() * 2 - 1) * .02f) * .995f; brown + .3f * sin(2 * PI * 100 * i / rate).toFloat() }, .03f),
            "knocks" to signal(3.0) { i -> val t = i % 4000; if (t < 80) .6f * exp(-t / 15f) * (if (t % 2 == 0) 1 else -1) else white(.002f) },
            "keyboard" to signal(3.0) { i -> val t = i % 1600; if (t < 160) white(.25f) * exp(-t / 60f) else white(.002f) },
            "door" to signal(3.0) { i -> val t = i - rate; if (t in 0..2400) .9f * exp(-t / 500f) * sin(2 * PI * 60 * t / rate).toFloat() else white(.002f) },
            "music" to scaled(signal(3.0) { i -> listOf(261.6, 329.6, 392.0, 523.3).sumOf { sin(2 * PI * it * i / rate) }.toFloat() }, .1f),
        )
        noises.forEach { (name, samples) -> assertEquals(0, speechSamples(samples), name) }
    }

    @Test
    fun spansAreTheSameWhateverTheChunkSize() {
        val samples = signal(2.0) { white(.03f) } + FloatArray(700) { .4f * sin(2 * PI * 220 * it / rate).toFloat() }
        assertEquals(speechSamples(samples, 320), speechSamples(samples, 4096))
    }
}
