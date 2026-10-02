package com.starringbox.staragent.core.speech.local.asr.vad

import kotlin.test.*

class SpeechAudioTest {
    /** PCM16 where sample i stores the value i, so trimmed output shows which samples were kept. */
    private fun ramp(samples: Int) = ByteArray(samples * 2).also { bytes ->
        for (i in 0 until samples) { bytes[2 * i] = (i and 255).toByte(); bytes[2 * i + 1] = (i shr 8).toByte() }
    }
    private fun indices(pcm: ByteArray) = List(pcm.size / 2) { i -> (pcm[2 * i].toInt() and 255) or ((pcm[2 * i + 1].toInt() and 255) shl 8) }

    @Test
    fun noiseOnlyRecordingsAreRejected() {
        val pcm = ramp(16_000)
        assertNull(SpeechAudio.keepSpeech(pcm, emptyList()))
        // 200 ms of detected speech is below the 250 ms minimum.
        assertNull(SpeechAudio.keepSpeech(pcm, listOf(4000 until 7200)))
    }

    @Test
    fun speechIsPaddedMergedAndClippedToTheRecording() {
        val pcm = ramp(16_000)
        // Two spans 1600 samples apart overlap once each is widened by 1600 samples (100 ms).
        val kept = SpeechAudio.keepSpeech(pcm, listOf(4000 until 8000, 9600 until 12_000), paddingMillis = 100)!!
        assertEquals((2400 until 13_600).toList(), indices(kept))
        val edges = SpeechAudio.keepSpeech(pcm, listOf(0 until 5000, 13_000 until 16_000), paddingMillis = 100)!!
        assertEquals((0 until 6600).toList() + (11_400 until 16_000).toList(), indices(edges))
    }

    @Test
    fun floatConversionIsSignedLittleEndian() {
        val pcm = byteArrayOf(0, 0, 0, 64, 0, -128, -1, 127)
        assertContentEquals(floatArrayOf(0f, .5f, -1f, 32767 / 32768f), SpeechAudio.pcm16ToFloat(pcm))
    }

    @Test
    fun punctuationOnlyTextIsNotAnUtterance() {
        listOf("", " ", ".", "。", "，。？！", "…").forEach { assertFalse(it.hasSpokenContent(), "'$it'") }
        listOf("嗯", "好的。", "OK", "3", "그.").forEach { assertTrue(it.hasSpokenContent(), "'$it'") }
    }
}
