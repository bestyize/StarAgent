package com.starringbox.staragent.core.platform.voice

/**
 * iOS：一次装齐四样设备能力——录音、播放、系统识别、系统合成。
 *
 * 签名和桌面那份一样没有参数，因为 Apple 这边既不需要 `Context`，
 * 也不需要注册结果回调（权限申请走 `SFSpeechRecognizer.requestAuthorization` 与
 * `AVAudioApplication.requestRecordPermission`，[start] 里直接 await）。
 *
 * ## ⚠️ 两条 Info.plist 用途说明必须先加
 *
 * `NSSpeechRecognitionUsageDescription` 与 `NSMicrophoneUsageDescription`。
 * **少了它们不是功能失效，是一调用就崩**——这是 Apple 的硬性要求，
 * 而且没有任何办法从 Kotlin 侧加。
 *
 * ## ⚠️ 这份实现没有在真机上跑过
 *
 * 开发机是 Windows：Kotlin/Native 的 Apple 目标在这里**能编 klib（也就是完整的类型检查），
 * 但链接成 framework 和跑测试都需要 macOS**。所以：
 *
 * - 已经验证的：能通过编译，接口对得上。
 * - 没有验证的：权限桥接的实际时序、`SFSpeechRecognitionTask` 回调的线程与时机、
 *   `AVAudioSession` category 的实际影响、以及真机上的识别质量。
 *
 * 第一次上 Mac 时，重点看这几处。
 *
 * ## 还没接上的最后一步
 *
 * `shared/src/iosMain/.../MainViewController.kt` 里目前**没有**调用这个函数
 * （给 `shared` 加一条 `:core:platform` 依赖即可）。所以现在 iOS 上跑起来仍然是
 * "两路都没有"：模型那一路也活不了，因为 `ModelTranscriber` / `ModelSynthesizer`
 * 要求 `Recorders.available` / `Players.available`——那两个在没装之前恒为 false。
 */
fun DeviceVoice.install() {
    Recorders.install(IosVoiceRecorder())
    Players.install(IosVoicePlayer())
    Voice.installDevice(
        transcriber = IosSystemTranscriber(),
        synthesizer = IosSystemSynthesizer(),
    )
}
