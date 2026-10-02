package com.starringbox.staragent.core.speech.local.asr.client

/** The model is downloaded on demand. Android is the first supported runtime. */
expect object SenseVoiceAsr {
    fun supported(): Boolean
    fun installed(): Boolean
    suspend fun install(onProgress: (Long, Long) -> Unit = { _, _ -> })
    suspend fun remove()
    /** Releases the cached native engine while keeping downloaded model files. */
    suspend fun release()
    suspend fun transcribe(wav: ByteArray): String
}
