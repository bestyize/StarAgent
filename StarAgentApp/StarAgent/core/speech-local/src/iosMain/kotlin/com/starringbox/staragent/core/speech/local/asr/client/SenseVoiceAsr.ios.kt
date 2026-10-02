package com.starringbox.staragent.core.speech.local.asr.client

actual object SenseVoiceAsr {
    actual fun supported() = false
    actual fun installed() = false
    actual suspend fun install(onProgress: (Long, Long) -> Unit): Unit = error("当前平台暂不支持 SenseVoice")
    actual suspend fun remove(): Unit = Unit
    actual suspend fun release(): Unit = Unit
    actual suspend fun transcribe(wav: ByteArray): String = error("当前平台暂不支持 SenseVoice")
}
