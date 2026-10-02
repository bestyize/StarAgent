package com.starringbox.staragent.core.platform.voice

/** 一次语音操作的结果。失败一律带上可以直接展示给用户的理由。 */
sealed interface SpeechOutcome<out T> {
    data class Ok<T>(val value: T) : SpeechOutcome<T>
    data class Failed(val message: String) : SpeechOutcome<Nothing>
}

/** 语音能力从哪来。界面上的「语音引擎」就是这个。 */
enum class VoiceSource(val label: String, val hint: String) {
    Device("系统能力", "用设备自带的识别与合成，不用配 Key，也不产生费用"),

    /**
     * 叫「云端识别」而不是「大模型」：后者和对话那边的「模型接入」撞名，
     * 而两者现在已经是完全独立的两份配置——同一个词指两件事，是上一版最容易让人困惑的地方。
     */
    Model("云端识别", "走你在「我的 → 语音」里配的服务商，识别更准，按量计费"),
    Local("离线模型", "先下载 SenseVoice / Piper 模型，使用时无需联网"),
}

/**
 * 语音转文字。
 *
 * 契约是**从"开始听"到"出文字"的整段**，而不是"给我一段音频、我返回文字"——
 * 因为设备自带的识别（Android 的 `SpeechRecognizer`）自己会录音、自己弹界面，
 * 压根不接受外部喂进来的音频。把两端对齐成同一段流程，上层就不用区分。
 */
interface Transcriber {
    /**
     * **这条后端在这台机器上存不存在**——不是"现在立刻就能录"。
     *
     * 权限、授权这类"还没问过用户"的状态**不算不可用**：它由 [start] 去申请。
     * 反过来会把入口锁死——界面拿这个值决定麦克风按钮显不显示，而申请权限的唯一入口
     * 恰恰就是那个按钮，前者为 false 就等于永远申请不到。模型端同理：
     * 它答的是"这家服务商配没配语音模型"，不是"Key 现在有没有过期"。
     *
     * 刻意不是挂起函数——界面要拿它决定入口显不显示，在组合期能同步读到是必要条件。
     */
    val ready: Boolean
    val inputLevel: Float get() = 0f
    val speechEnded: Boolean get() = false

    /** 开始听。返回 false 表示起不来（权限被拒、没配服务商、设备不认）。 */
    suspend fun start(): Boolean

    /** 结束并给出文字。用户取消、没听清、网络失败都走 [SpeechOutcome.Failed]。 */
    suspend fun finish(): SpeechOutcome<String>

    /** 放弃这一次，不需要结果。 */
    fun cancel()
}

/**
 * 文字转语音。**合成与播放是同一件事**——
 * 设备自带的能力内部直接出声，走服务商的那条则合成后交给 [Players]，
 * 上层只关心"念出来了没有"。
 */
interface Synthesizer {
    /** 同上，界面要同步地问"能不能念"。 */
    val ready: Boolean

    /** 念出来。文本为空时直接算成功（没什么可念的）。 */
    suspend fun speak(text: String): SpeechOutcome<Unit>

    /**
     * 打断正在念的内容。没在念时是空操作。
     *
     * 这件事**必须由契约提供**，不能指望调用方去管：设备合成的播放由系统自己排，
     * 走服务商的那条由 [Players] 播，两条路各停各的，上层没法用一个通用的办法打断。
     */
    fun stop()
}
