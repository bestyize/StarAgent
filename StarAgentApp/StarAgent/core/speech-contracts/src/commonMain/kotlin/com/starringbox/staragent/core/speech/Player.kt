package com.starringbox.staragent.core.speech

/**
 * 播放一段音频。设备能力，由各端入口经 core:platform 的 `DeviceVoice.install` 注入。
 *
 * 为什么需要它：**走服务商的那条合成链路只能拿到音频字节，得有人把它放出来**。
 * 常规链路统一用 PCM/WAV；Edge TTS 的公开端点固定返回 MP3，播放实现按 MIME 分派。
 */
interface Player {
    /** 这个平台现在能不能放。桌面没有音频输出设备时应当是 false。 */
    val available: Boolean

    /** 播一段音频。成功开始播放返回 true。 */
    suspend fun play(clip: VoiceClip): Boolean

    /** 打断当前播放。没有在播时什么都不做。 */
    fun stop()
}

/** 没装播放实现的平台走这里（iOS，以及没有声卡的桌面）。 */
private object NoPlayer : Player {
    override val available: Boolean get() = false
    override suspend fun play(clip: VoiceClip): Boolean = false
    override fun stop() = Unit
}

object Players {
    private var installed: Player = NoPlayer

    /** 由 `DeviceVoice.install` 调用一次——业务代码不该直接碰这里。 */
    fun install(player: Player) {
        installed = player
    }

    val available: Boolean get() = installed.available

    suspend fun play(clip: VoiceClip): Boolean = installed.play(clip)

    fun stop() = installed.stop()
}
