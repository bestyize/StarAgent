package com.starringbox.staragent.core.platform.voice

import com.starringbox.staragent.core.speech.PcmPlayers
import com.starringbox.staragent.core.speech.Players
import com.starringbox.staragent.core.speech.Recorders
import android.content.Context
import androidx.activity.result.ActivityResultCaller

/**
 * Android：一次装齐四样设备能力——录音、播放、系统识别、系统合成。
 *
 * 装完就落在 [Recorders] / [Players] / [Voice] 三个既有的注册表上，
 * 所以数据层和界面读到的还是同一批入口，只是构造它们的责任从壳 app 挪到了这里。
 *
 * ## 必须在 `Activity.onCreate` 里调
 *
 * 麦克风是危险权限，必须运行时申请，而申请要走 [ActivityResultCaller.registerForActivityResult]——
 * 它**只在 Activity 初始化阶段合法**，过了这个点再调会抛
 * `IllegalStateException: LifecycleOwner is attempting to register while current state is STARTED`。
 * 录音器和识别器都要这个回调，所以它们的构造也只能发生在 [install] 里面，不能延迟到首次使用。
 *
 * ## `caller` 必须是 Activity 本身
 *
 * 注册进去的回调活得比这个对象长，所以不能拿一个"临时的"或"包装过的"对象冒充——
 * 那要么注册不上，要么把 Activity 拖成泄漏。传 `this`（ComponentActivity 实现了
 * [ActivityResultCaller]）即可。
 *
 * ## `context` 传 `applicationContext`
 *
 * 三条实现只拿它做三件事：`checkSelfPermission`、开 `AudioRecord`、取 `cacheDir` 落临时音频。
 * 都不需要 UI 上下文，传 Activity 反而会让识别器与播放器各持一个 Activity 引用。
 *
 * ## 为什么这里能用 Compose
 *
 * [AndroidSystemSynthesizer] 的 `ready` 是异步翻的，而它被组合路径读到，
 * 必须用快照状态驱动重组（原因写在那个类的注释里）。这是整个模块
 * **androidMain 依赖 compose.runtime 的唯一原因**，不是随手加的。
 */
fun DeviceVoice.install(context: Context, caller: ActivityResultCaller) {
    installProcess(context)
    Recorders.install(AndroidVoiceRecorder(context, caller))
    // 识别器要注册权限回调，只能跟着 Activity 换；换之前先放掉旧的 SpeechRecognizer，
    // 否则每次重建都留下一个绑着系统识别服务的实例。
    deviceTranscriber?.release()
    deviceTranscriber = AndroidSystemTranscriber(context, caller)
    Voice.installDevice(transcriber = deviceTranscriber, synthesizer = deviceSynthesizer)
}

/**
 * 只要 Context 的那几样，一个进程装一次（`Application.onCreate`），重复调用是空操作。
 *
 * 它们原先也跟着 Activity 每次重建：`TextToSpeech` 每次 new 一个、从不 shutdown，
 * 旧引擎和它对系统 TTS 服务的绑定就一直泄漏；`PcmPlayers.install` 会关掉旧播放器，
 * 旋转屏幕时正在流式朗读的那段会被直接掐断。
 *
 * 必须在主线程调用。
 */
fun DeviceVoice.installProcess(context: Context) {
    if (deviceSynthesizer != null) return
    val app = context.applicationContext
    Players.install(AndroidVoicePlayer(app))
    PcmPlayers.install(AndroidPcmPlayer())
    deviceSynthesizer = AndroidSystemSynthesizer(app)
    Voice.installDevice(transcriber = deviceTranscriber, synthesizer = deviceSynthesizer)
}

private var deviceSynthesizer: AndroidSystemSynthesizer? = null
private var deviceTranscriber: AndroidSystemTranscriber? = null
