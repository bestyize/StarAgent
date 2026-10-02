package com.starringbox.staragent.core.platform.voice

/** 带明确格式的 PCM16LE，禁止将 24k 音频按录音的 16k 播放。 */
data class PcmAudioChunk(val pcm16: ByteArray, val sampleRate: Int, val channels: Int = 1) {
    init {
        require(sampleRate in 8_000..192_000 && channels == 1)
        require(pcm16.size % (2 * channels) == 0) { "PCM16 采样被截断" }
    }
}

interface StreamingSynthesizer : Synthesizer {
    val streamReady: Boolean
    suspend fun stream(text: String, onAudio: suspend (PcmAudioChunk) -> Unit): SpeechOutcome<Unit>
}

/** 一个连续播放会话：正常 drain 等待播完；stop 立即丢弃队列。 */
interface PcmPlayer {
    val available: Boolean
    suspend fun enqueue(chunk: PcmAudioChunk)
    suspend fun drain()
    fun stop()
    fun close() = stop()
}

object PcmPlayers {
    private var installed: PcmPlayer? = null
    val available: Boolean get() = installed?.available == true
    fun install(player: PcmPlayer?) { installed?.close(); installed = player }
    suspend fun enqueue(chunk: PcmAudioChunk) = checkNotNull(installed) { "此平台不支持流式播放" }.enqueue(chunk)
    suspend fun drain() = checkNotNull(installed) { "此平台不支持流式播放" }.drain()
    fun stop() { installed?.stop() }
}
