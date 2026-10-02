package com.starringbox.staragent.core.speech.local.tts.model

sealed interface TtsResult {
    data class Success(val audio: ByteArray, val contentType: String = "audio/mpeg") : TtsResult
    data class Failure(val message: String, val httpCode: Int? = null) : TtsResult
}
