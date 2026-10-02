package com.starringbox.staragent.core.speech.local.asr.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Pcm16WavTest {
    @Test
    fun parsesPcm16Samples() {
        val samples = pcm16WavToFloat(wav(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)))

        assertEquals(3, samples.size)
        assertEquals(-1f, samples[0])
        assertEquals(0f, samples[1])
        assertEquals(Short.MAX_VALUE / 32768f, samples[2])
    }

    @Test
    fun rejectsUnsupportedAndTruncatedWav() {
        val audio = wav(shortArrayOf(1))
        assertFailsWith<IllegalArgumentException> { pcm16WavToFloat(audio.copyOf(audio.size - 1)) }
        assertFailsWith<IllegalArgumentException> {
            pcm16WavToFloat(wav(shortArrayOf(1), sampleRate = 8_000))
        }
        assertFailsWith<IllegalArgumentException> { pcm16WavToFloat(byteArrayOf()) }
    }

    private fun wav(samples: ShortArray, sampleRate: Int = 16_000): ByteArray {
        val dataSize = samples.size * 2
        val bytes = ByteArray(44 + dataSize)
        fun ascii(at: Int, value: String) = value.forEachIndexed { index, char -> bytes[at + index] = char.code.toByte() }
        fun u16(at: Int, value: Int) {
            bytes[at] = value.toByte()
            bytes[at + 1] = (value ushr 8).toByte()
        }
        fun u32(at: Int, value: Int) {
            u16(at, value)
            u16(at + 2, value ushr 16)
        }
        ascii(0, "RIFF")
        u32(4, bytes.size - 8)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        u32(16, 16)
        u16(20, 1)
        u16(22, 1)
        u32(24, sampleRate)
        u32(28, sampleRate * 2)
        u16(32, 2)
        u16(34, 16)
        ascii(36, "data")
        u32(40, dataSize)
        samples.forEachIndexed { index, sample -> u16(44 + index * 2, sample.toInt()) }
        return bytes
    }
}
