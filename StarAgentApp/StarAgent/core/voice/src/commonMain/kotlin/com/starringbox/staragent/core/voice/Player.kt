package com.starringbox.staragent.core.voice

/**
 * 播放一段音频。设备能力，由各端入口注入。
 *
 * 为什么需要它：**走服务商的那条合成链路只能拿到音频字节，得有人把它放出来**。
 * 桌面和 Android 都没有"给一段 wav 就能出声"的通用库（JDK 连 MP3 解码都没有，
 * 所以整条链路统一用 PCM/WAV）。
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

    /** 各端入口调用一次。桌面在 main()，Android 在 MainActivity。 */
    fun install(player: Player) {
        installed = player
    }

    val available: Boolean get() = installed.available

    suspend fun play(clip: VoiceClip): Boolean = installed.play(clip)

    fun stop() = installed.stop()
}
