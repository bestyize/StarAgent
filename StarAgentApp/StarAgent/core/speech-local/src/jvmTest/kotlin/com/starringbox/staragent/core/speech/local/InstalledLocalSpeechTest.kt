package com.starringbox.staragent.core.speech.local

import com.starringbox.staragent.core.speech.Wav
import com.starringbox.staragent.core.speech.local.asr.client.SenseVoiceAsr
import com.starringbox.staragent.core.speech.local.tts.client.PiperTts
import com.starringbox.staragent.core.speech.local.tts.model.TtsResult
import com.starringbox.staragent.core.speech.local.tts.model.SpeechAudioChunk
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Manual smoke test for already-installed local speech models. Does not download either model. */
class InstalledLocalSpeechTest {
    @Test
    fun installedPiperAndSenseVoiceWorkEndToEnd() = runBlocking {
        if (System.getenv("STARAGENT_VERIFY_OFFLINE") != "1") return@runBlocking

        try {
            assertTrue(PiperTts.prepareRuntime(), "Piper native runtime is unavailable")
            assertTrue(PiperTts.installed(), "Piper model is not installed")
            assertTrue(SenseVoiceAsr.installed(), "SenseVoice model is not installed")

            val phrase = "你好，我是星环助手。"
            val synthesis = PiperTts.synthesize(phrase)
            assertTrue(synthesis is TtsResult.Success, "Piper synthesis failed: $synthesis")
            val originalWav = (synthesis as TtsResult.Success).audio
            val audio = parsePcm16MonoWav(originalWav)
            assertTrue(Wav.durationMillis(originalWav) > 0, "Piper produced an empty WAV")
            assertTrue(audio.pcm.isNotEmpty(), "Piper WAV has no PCM samples")
            assertTrue(audio.sampleRate > 0, "Piper WAV has an invalid sample rate")

            val asrWav = if (audio.sampleRate == Wav.SAMPLE_RATE) {
                Wav.wrap(audio.pcm)
            } else {
                Wav.wrap(resamplePcm16(audio.pcm, audio.sampleRate, Wav.SAMPLE_RATE))
            }
            assertTrue(parsePcm16MonoWav(asrWav).sampleRate == Wav.SAMPLE_RATE, "ASR input was not resampled to 16 kHz")
            val recognized = SenseVoiceAsr.transcribe(asrWav)
            assertTrue(recognized.any { it.isLetterOrDigit() }, "SenseVoice returned no spoken content: $recognized")

            val chunks = ArrayList<SpeechAudioChunk>()
            PiperTts.stream(phrase) { chunks += it }
            assertTrue(chunks.isNotEmpty(), "Piper stream returned no audio")
            assertTrue(chunks.all { it.pcm16.isNotEmpty() && it.pcm16.size % 2 == 0 }, "Piper stream contains invalid PCM16 chunks")
            assertTrue(chunks.all { it.sampleRate == audio.sampleRate }, "Piper stream sample rate differs from synthesized WAV")
            assertTrue(chunks.sumOf { it.pcm16.size } > 0, "Piper stream contains no PCM data")
        } finally {
            try {
                PiperTts.release()
            } finally {
                SenseVoiceAsr.release()
            }
        }
    }
}

private data class MonoPcmWav(val sampleRate: Int, val pcm: ByteArray)

/** Reads the WAV format and data chunks instead of relying on a fixed 44-byte header. */
private fun parsePcm16MonoWav(wav: ByteArray): MonoPcmWav {
    require(wav.size >= 12 && wav.ascii(0, 4) == "RIFF" && wav.ascii(8, 4) == "WAVE") { "Piper did not return RIFF/WAVE" }
    val riffEndLong = 8L + wav.u32Le(4)
    require(riffEndLong in 12L..wav.size.toLong()) { "Piper WAV has an invalid RIFF length" }
    val riffEnd = riffEndLong.toInt()
    var offset = 12
    var sampleRate: Int? = null
    var channels: Int? = null
    var bitsPerSample: Int? = null
    var encoding: Int? = null
    var pcm: ByteArray? = null

    while (offset <= riffEnd - 8) {
        val id = wav.ascii(offset, 4)
        val size = wav.u32Le(offset + 4)
        val payload = offset + 8
        val endLong = payload.toLong() + size
        require(endLong <= riffEnd && endLong <= wav.size) { "Piper WAV contains a truncated chunk" }
        val end = endLong.toInt()
        when (id) {
            "fmt " -> {
                require(size >= 16) { "Piper WAV fmt chunk is truncated" }
                encoding = wav.u16Le(payload)
                channels = wav.u16Le(payload + 2)
                sampleRate = wav.u32Le(payload + 4).toInt()
                bitsPerSample = wav.u16Le(payload + 14)
            }
            "data" -> {
                require(pcm == null) { "Piper WAV contains multiple data chunks" }
                pcm = wav.copyOfRange(payload, end)
            }
        }
        val next = endLong + (size and 1L)
        require(next <= riffEnd) { "Piper WAV chunk padding exceeds RIFF length" }
        offset = next.toInt()
    }
    require(offset == riffEnd) { "Piper WAV contains an incomplete chunk header" }
    require(encoding == 1 && channels == 1 && bitsPerSample == 16) { "Piper WAV must be mono PCM16" }
    val samples = requireNotNull(pcm) { "Piper WAV has no data chunk" }
    require(samples.isNotEmpty() && samples.size % 2 == 0) { "Piper WAV has invalid PCM16 data" }
    return MonoPcmWav(requireNotNull(sampleRate), samples)
}

/** Linear interpolation from mono PCM16 at [sourceRate] into [targetRate]. */
private fun resamplePcm16(pcm: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
    require(sourceRate > 0 && targetRate > 0 && pcm.size >= 2 && pcm.size % 2 == 0)
    val input = FloatArray(pcm.size / 2) { index -> pcm.s16Le(index * 2) / 32768f }
    val outputCount = (((input.size - 1) * targetRate.toDouble() / sourceRate).roundToInt() + 1).coerceAtLeast(1)
    val output = ByteArray(outputCount * 2)
    for (index in 0 until outputCount) {
        val position = (index.toDouble() * sourceRate / targetRate).coerceAtMost((input.size - 1).toDouble())
        val left = position.toInt()
        val right = minOf(left + 1, input.lastIndex)
        val fraction = (position - left).toFloat()
        val value = (input[left] + (input[right] - input[left]) * fraction).coerceIn(-1f, 1f)
        val sample = (value * if (value < 0f) 32768f else 32767f).toInt()
        output[index * 2] = sample.toByte()
        output[index * 2 + 1] = (sample shr 8).toByte()
    }
    return output
}

private fun ByteArray.ascii(offset: Int, length: Int) = buildString(length) {
    repeat(length) { append(this@ascii[offset + it].toInt().toChar()) }
}

private fun ByteArray.u16Le(offset: Int) = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
private fun ByteArray.s16Le(offset: Int) = u16Le(offset).toShort().toInt()
private fun ByteArray.u32Le(offset: Int): Long =
    (this[offset].toLong() and 255) or ((this[offset + 1].toLong() and 255) shl 8) or
        ((this[offset + 2].toLong() and 255) shl 16) or ((this[offset + 3].toLong() and 255) shl 24)
