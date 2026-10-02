package com.starringbox.staragent.core.speech.local.tts.client

import com.starringbox.staragent.core.speech.local.tts.model.TtsResult
import com.starringbox.staragent.core.speech.local.tts.model.SpeechAudioChunk

/** Optional, on-device Chinese Piper TTS. Android and desktop support the local runtime; iOS is not yet available. */
expect object PiperTts {
    fun supported(): Boolean
    /** Prepare native inference runtime when this desktop platform requires it. */
    fun prepareRuntime(): Boolean
    fun installed(): Boolean
    suspend fun install(onProgress: (Long, Long) -> Unit = { _, _ -> })
    suspend fun remove()
    /** Release native inference resources without removing the downloaded model. */
    suspend fun release()
    suspend fun synthesize(text: String): TtsResult
    suspend fun stream(text: String, onAudio: suspend (SpeechAudioChunk) -> Unit)
}
