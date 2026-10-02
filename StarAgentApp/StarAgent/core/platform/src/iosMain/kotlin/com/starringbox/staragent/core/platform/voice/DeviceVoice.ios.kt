package com.starringbox.staragent.core.platform.voice

/** iOS 的系统语音能力与 PCM 播放器。 */
fun DeviceVoice.install() {
    Recorders.install(IosVoiceRecorder())
    Players.install(IosVoicePlayer())
    PcmPlayers.install(IosPcmPlayer())
    Voice.installDevice(
        transcriber = IosSystemTranscriber(),
        synthesizer = IosSystemSynthesizer(),
    )
}
