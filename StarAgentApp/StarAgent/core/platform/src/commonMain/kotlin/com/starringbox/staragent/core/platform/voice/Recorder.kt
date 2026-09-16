package com.starringbox.staragent.core.platform.voice

/**
 * 录音。设备能力，由各端入口经 [DeviceVoice.install] 注入。
 *
 * 用 interface + 注册表而不是 `expect/actual`：两端的实现都持有自己的原生会话
 * （桌面的 `TargetDataLine`、Android 的 `AudioRecord`），而且要处理权限和"没有麦克风"
 * 这类只有运行期才知道的状态，凭空造不出来。这和选图、保存是同一个理由。
 */
interface Recorder {
    /**
     * 这个平台现在到底能不能录。
     *
     * 刻意做成实例属性而不是注册表的身份比较：**桌面没有麦克风是常态**，
     * 那时候 `available` 要是 true，界面就会显示一个点了没反应的按钮。
     */
    val available: Boolean

    /** 开始录。返回 false 表示起不来（没权限、没有设备、已经在录）。 */
    suspend fun start(): Boolean

    /** 停止并返回录到的音频；什么都没录到返回 null。 */
    suspend fun stop(): VoiceClip?

    /** 放弃这次录音，不产出结果。 */
    fun cancel()
}

/** 没装录音实现的平台走这里（iOS，以及没有麦克风的桌面）。 */
private object NoRecorder : Recorder {
    override val available: Boolean get() = false
    override suspend fun start(): Boolean = false
    override suspend fun stop(): VoiceClip? = null
    override fun cancel() = Unit
}

object Recorders {
    private var installed: Recorder = NoRecorder

    /** 由 [DeviceVoice.install] 调用一次——业务代码不该直接碰这里。 */
    fun install(recorder: Recorder) {
        installed = recorder
    }

    val available: Boolean get() = installed.available

    suspend fun start(): Boolean = installed.start()

    suspend fun stop(): VoiceClip? = installed.stop()

    fun cancel() = installed.cancel()
}
