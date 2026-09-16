package com.starringbox.staragent.core.platform.voice

/**
 * 桌面（JVM）：只装录音和播放。
 *
 * **刻意不装识别与合成**——JDK 里没有 `SpeechRecognizer` / `TextToSpeech` 的对应物，
 * 这两个能力在桌面上只能走大模型服务商那一路，由 `core:data` 调 [Voice.installModel] 装。
 * 所以桌面的语音是"说得出、听得见，但认不出、念不了"，
 * 除非用户在设置里配了支持语音的服务商（智谱或 OpenAI）。
 *
 * 这个函数没有参数，是因为 `javax.sound.sampled` 既不需要 `Context`，
 * 也不需要注册什么结果回调——凡是需要的平台（Android）签名里都会体现出来。
 *
 * 没有麦克风、没有声卡都是**常态而非异常**：两个实现会如实把 `available` 报成 false，
 * 界面据此把语音入口藏起来。所以这里不做任何检查，也永远不会因此失败。
 */
fun DeviceVoice.install() {
    Recorders.install(DesktopVoiceRecorder())
    Players.install(DesktopVoicePlayer())
}
