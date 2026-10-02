package com.starringbox.staragent.core.speech.local.asr.vad

import com.starringbox.staragent.core.platform.voice.Wav

/** Streaming voice activity detection on 16 kHz mono samples in [-1, 1]. Not thread-safe. */
interface SpeechDetector : AutoCloseable {
    /** Feeds samples of any length; returns whether speech is currently detected. */
    fun accept(samples: FloatArray): Boolean

    /** Flushes buffered audio and returns every speech span so far, in samples from the first accepted sample. */
    fun finish(): List<IntRange>
}

/** Silero VAD v5 bundled with the app. Null where the native runtime is unavailable (iOS) or fails to load. */
expect fun createSpeechDetector(): SpeechDetector?

/** Silero VAD settings shared by every platform. */
internal object SileroSettings {
    const val RESOURCE = "vad/silero_vad_v5.onnx"
    const val FILE_NAME = "silero_vad_v5.onnx"
    const val SIZE = 2_313_101L
    const val SHA256 = "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396"
    const val WINDOW = 512
    const val THRESHOLD = .5f
    const val MIN_SILENCE_SECONDS = .25f
    const val MIN_SPEECH_SECONDS = .25f
    const val MAX_SPEECH_SECONDS = 20f
}

/** Feeds the native detector whole 512-sample windows and collects the spans it reports. */
internal abstract class WindowedSpeechDetector : SpeechDetector {
    private val pending = FloatArray(SileroSettings.WINDOW)
    private var filled = 0
    private val spans = ArrayList<IntRange>()

    protected abstract fun acceptWindow(window: FloatArray)
    protected abstract fun speechDetected(): Boolean
    /** Start sample and length of each completed segment, removing them from the native queue. */
    protected abstract fun drainSegments(): List<Pair<Int, Int>>
    protected abstract fun flushNative()

    final override fun accept(samples: FloatArray): Boolean {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(SileroSettings.WINDOW - filled, samples.size - offset)
            samples.copyInto(pending, filled, offset, offset + count)
            filled += count
            offset += count
            if (filled == SileroSettings.WINDOW) {
                acceptWindow(pending.copyOf())
                filled = 0
                collect()
            }
        }
        return speechDetected()
    }

    final override fun finish(): List<IntRange> {
        if (filled > 0) {
            acceptWindow(pending.copyOf().also { it.fill(0f, filled) })
            filled = 0
        }
        flushNative()
        collect()
        return spans.toList()
    }

    private fun collect() {
        drainSegments().forEach { (start, length) -> if (length > 0) spans += start until start + length }
    }
}

object SpeechAudio {
    const val SAMPLE_RATE = 16_000

    fun pcm16ToFloat(pcm: ByteArray, count: Int = pcm.size): FloatArray {
        require(count in 0..pcm.size)
        return FloatArray(count / 2) { i -> ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
    }

    /** Keeps padded, merged 16 kHz mono PCM16 speech spans; null when speech is shorter than [minSpeechMillis]. */
    fun keepSpeech(pcm: ByteArray, spans: List<IntRange>, paddingMillis: Int = 200, minSpeechMillis: Int = 250): ByteArray? {
        val samples = pcm.size / 2
        val speech = spans.sumOf { (minOf(it.last, samples - 1) - maxOf(it.first, 0) + 1).coerceAtLeast(0) }
        if (speech < minSpeechMillis * SAMPLE_RATE / 1000) return null
        val padding = paddingMillis * SAMPLE_RATE / 1000
        val merged = ArrayList<IntRange>()
        for (span in spans.sortedBy { it.first }) {
            val start = (span.first - padding).coerceAtLeast(0)
            val end = (span.last + padding).coerceAtMost(samples - 1)
            if (start > end) continue
            val last = merged.lastOrNull()
            if (last != null && start <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, end)
            else merged += start..end
        }
        val output = ByteArray(merged.sumOf { (it.last - it.first + 1) * 2 })
        var at = 0
        for (range in merged) {
            val bytes = (range.last - range.first + 1) * 2
            pcm.copyInto(output, at, range.first * 2, range.first * 2 + bytes)
            at += bytes
        }
        return output
    }
}

/** Recognizers may produce punctuation from noise; spoken content contains a letter or digit. */
fun String.hasSpokenContent(): Boolean = any { it.isLetterOrDigit() }

/**
 * Filters a PCM16 mono 16 kHz WAV through Silero. Unsupported or malformed WAV data, unavailable native
 * runtime, and VAD failures pass through unchanged; valid recordings with no speech return null.
 */
suspend fun filterSpeechWav(wav: ByteArray): ByteArray? {
    val parsed = parsePcm16Mono16kWav(wav) ?: return wav
    if (parsed.pcm.isEmpty()) return wav

    val detector = try {
        createSpeechDetector() ?: return wav
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Throwable) {
        return wav
    }

    return try {
        detector.use {
            it.accept(SpeechAudio.pcm16ToFloat(parsed.pcm))
            val spans = it.finish()
            val kept = SpeechAudio.keepSpeech(parsed.pcm, spans) ?: return null
            Wav.wrap(kept)
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Throwable) {
        wav
    }
}

private data class PcmWav(val pcm: ByteArray)

/** Walk RIFF chunks instead of assuming the data starts at byte 44. */
private fun parsePcm16Mono16kWav(bytes: ByteArray): PcmWav? {
    if (bytes.size < 12 || !bytes.matchesAscii(0, "RIFF") || !bytes.matchesAscii(8, "WAVE")) return null
    val riffSize = bytes.readU32Le(4) ?: return null
    val riffEndLong = 8L + riffSize
    if (riffEndLong > bytes.size || riffEndLong < 12) return null
    val riffEnd = riffEndLong.toInt()

    var offset = 12
    var format: ByteArray? = null
    var data: ByteArray? = null
    while (offset <= riffEnd - 8) {
        val id = bytes.ascii(offset, 4)
        val sizeLong = bytes.readU32Le(offset + 4) ?: return null
        val payload = offset + 8
        val endLong = payload.toLong() + sizeLong
        if (endLong > riffEnd || endLong > bytes.size) return null
        val end = endLong.toInt()
        when (id) {
            "fmt " -> {
                if (format != null || sizeLong < 16) return null
                format = bytes.copyOfRange(payload, end)
            }
            "data" -> {
                if (data != null) return null
                data = bytes.copyOfRange(payload, end)
            }
        }
        val nextLong = endLong + (sizeLong and 1L)
        if (nextLong > riffEnd) return null
        offset = nextLong.toInt()
    }
    if (offset != riffEnd) return null
    val fmt = format ?: return null
    if (fmt.readU16Le(0) != 1 || fmt.readU16Le(2) != 1 || fmt.readU32Le(4) != SpeechAudio.SAMPLE_RATE.toLong() ||
        fmt.readU32Le(8) != (SpeechAudio.SAMPLE_RATE * 2).toLong() || fmt.readU16Le(12) != 2 || fmt.readU16Le(14) != 16) return null
    val pcm = data ?: return null
    if (pcm.isEmpty() || pcm.size % 2 != 0) return null
    return PcmWav(pcm)
}

private fun ByteArray.matchesAscii(offset: Int, value: String) = ascii(offset, value.length) == value
private fun ByteArray.ascii(offset: Int, length: Int) = (offset until offset + length).map { this[it].toInt().toChar() }.joinToString("")
private fun ByteArray.readU16Le(offset: Int) = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
private fun ByteArray.readU32Le(offset: Int): Long? {
    if (offset < 0 || offset > size - 4) return null
    return (this[offset].toLong() and 255) or ((this[offset + 1].toLong() and 255) shl 8) or
        ((this[offset + 2].toLong() and 255) shl 16) or ((this[offset + 3].toLong() and 255) shl 24)
}
