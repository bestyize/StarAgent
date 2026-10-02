package com.starringbox.staragent.core.speech.local.tts.model

/** Mono signed PCM16 little-endian, without a WAV header. */
data class SpeechAudioChunk(val pcm16: ByteArray, val sampleRate: Int) {
    init { require(sampleRate > 0 && pcm16.size % 2 == 0) { "无效的 PCM16 音频" } }
}

fun floatSamplesToPcm16(samples: FloatArray): ByteArray = ByteArray(samples.size * 2).also { pcm ->
    samples.forEachIndexed { index, value ->
        val normalized = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        val signed = (normalized * if (normalized < 0f) 32768f else 32767f).toInt()
        pcm[index * 2] = signed.toByte(); pcm[index * 2 + 1] = (signed shr 8).toByte()
    }
}
