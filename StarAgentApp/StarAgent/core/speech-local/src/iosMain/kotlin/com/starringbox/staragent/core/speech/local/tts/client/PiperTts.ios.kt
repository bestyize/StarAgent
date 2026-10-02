package com.starringbox.staragent.core.speech.local.tts.client

import com.starringbox.staragent.core.speech.local.tts.model.TtsResult
import com.starringbox.staragent.core.speech.local.tts.model.SpeechAudioChunk

actual object PiperTts {
    actual suspend fun stream(text: String, onAudio: suspend (SpeechAudioChunk) -> Unit) {
        error("Piper 本地语音目前仅支持 Android 与桌面端")
    }
    actual fun supported(): Boolean = false
    actual fun prepareRuntime(): Boolean = false
    actual fun installed(): Boolean = false
    actual suspend fun install(onProgress: (Long, Long) -> Unit) { error("Piper 本地语音目前仅支持 Android 与桌面端") }
    actual suspend fun remove() = Unit
    actual suspend fun release() = Unit
    actual suspend fun synthesize(text: String): TtsResult = TtsResult.Failure("Piper 本地语音目前仅支持 Android 与桌面端")
}
