# edge-tts Kotlin Multiplatform

`edge-tts` 是一个独立的 Kotlin Multiplatform 客户端，用来调用 Microsoft Edge
“大声朗读”使用的在线语音合成服务。它输出音频流、词边界和句边界。

公开服务当前固定返回 `audio-24khz-48kbitrate-mono-mp3`。库保留输出格式参数，
方便服务端将来扩展；当前可验证的格式只有这一种。

这个库不是微软官方 SDK，也不包含微软的语音模型。服务端接口、客户端标识和
鉴权参数可能随时变化，生产环境应保留正式的 Azure Speech 后端作为兜底。

## 支持目标

- JVM / Desktop
- Android
- iOS Arm64 与 iOS Simulator Arm64

## 最小用法

```kotlin
val client = EdgeTtsClient(
    config = EdgeTtsConfig(voice = "zh-CN-XiaoxiaoNeural"),
)

val audio = client.synthesizeToBytes("你好，欢迎使用语音合成。")
```

流式收集音频和文本边界：

```kotlin
client.synthesize("你好。").collect { event ->
    when (event) {
        is EdgeTtsEvent.Audio -> play(event.bytes)
        is EdgeTtsEvent.Boundary -> showSubtitle(event.text)
        EdgeTtsEvent.Completed -> Unit
    }
}
```

## 与 StarAgent 的关系

StarAgent 的 `core:speech-cloud` 只通过公共 API 接入这个库，不依赖它的协议内部实现。
应用侧负责把音频交给 `Player` 或 `PcmPlayer`，因此库本身不绑定任何 UI 或播放器。
