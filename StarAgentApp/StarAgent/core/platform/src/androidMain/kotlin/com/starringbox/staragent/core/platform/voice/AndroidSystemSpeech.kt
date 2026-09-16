package com.starringbox.staragent.core.platform.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 语音识别的**设备后端**：用 Android 自带的识别器。
 *
 * 和模型后端最大的不同是**它自己录音、自己弹界面**，所以我们不碰 [Recorder]。
 * 这也正是把 [Transcriber] 的契约定成"从开始听到出文字"整段的原因——
 * 两种后端在这一点上形状一致，上层就不用区分。
 *
 * 好处是不要 Key、不产生费用；代价是识别质量取决于机型和系统。
 */
class AndroidSystemTranscriber(
    private val context: Context,
    caller: ActivityResultCaller,
) : Transcriber {

    private val main = Handler(Looper.getMainLooper())

    private var pendingPermission: ((Boolean) -> Unit)? = null
    private val permissionLauncher =
        caller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = pendingPermission
            pendingPermission = null
            callback?.invoke(granted)
        }

    private var recognizer: SpeechRecognizer? = null

    /** 结果可能比"结束"先到（系统静音后自己收尾），所以两边都要能接住。 */
    private var waiting: ((SpeechOutcome<String>) -> Unit)? = null
    private var settled: SpeechOutcome<String>? = null

    private fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 这台机器有没有系统识别——**不看权限**。
     *
     * 权限不算能力，只是还没申请过而已，点一下就会弹窗（见 [start]）。把它算进来会绕成死环：
     * 没权限 → 这路不算可用 → 麦克风入口不渲染 → 没有入口去申请 → 永远没权限。
     * 录音器那边（[Recorders]）是同样的立场。
     */
    override val ready: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
        if (!granted() && !requestPermission()) return false

        settled = null
        main.post {
            val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
            engine.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                }
            )
        }
        return true
    }

    override suspend fun finish(): SpeechOutcome<String> {
        settled?.let {
            settled = null
            return it
        }

        return suspendCancellableCoroutine { continuation ->
            waiting = { outcome -> if (continuation.isActive) continuation.resume(outcome) }
            continuation.invokeOnCancellation { waiting = null }
            // 主动收尾：系统识别在静音一段时间后也会自己结束，两条路都通
            main.post { runCatching { recognizer?.stopListening() } }
        }
    }

    override fun cancel() {
        waiting = null
        settled = null
        main.post {
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }
    }

    private fun settle(outcome: SpeechOutcome<String>) {
        val callback = waiting
        if (callback != null) {
            waiting = null
            callback(outcome)
        } else {
            settled = outcome
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val text = results
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()

            settle(if (text.isBlank()) SpeechOutcome.Failed("没有听清，再说一次") else SpeechOutcome.Ok(text))
        }

        override fun onError(error: Int) = settle(SpeechOutcome.Failed(describeRecognitionError(error)))

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        pendingPermission = { granted -> if (continuation.isActive) continuation.resume(granted) }
        continuation.invokeOnCancellation { pendingPermission = null }
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/**
 * 文字转语音的**设备后端**：用 Android 自带的合成器。
 *
 * 它自己出声，所以不经过 [Player]——这正是把 [Synthesizer] 定成"合成并播放"
 * 而不是"给我一段音频"的原因。
 *
 * 初始化是异步的，所以 `ready` 一开始是 false、准备好之后才变 true。
 *
 * ⚠️ **`readyState` 必须是 Compose 的快照状态，不能换成普通 `Boolean`。**
 * 它被**组合路径**读到：`ChatScreen` 判断 `state.speakReplies && state.canSpeak`
 * → `AppState.canSpeak` → `Voice.canSpeak` → 这里的 `ready`。默认引擎是"自动"且优先
 * 设备能力，所以这个值什么时候翻，决定了朗读入口什么时候出现。
 * 换成普通字段不会有任何编译错误，只是界面再也不会因此重组——表现成
 * "朗读开关明明开着，入口却一直不出现"。
 * 这也是整个模块 androidMain 依赖 compose.runtime 的唯一原因。
 */
class AndroidSystemSynthesizer(context: Context) : Synthesizer {

    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null

    private var readyState by mutableStateOf(false)

    init {
        main.post {
            lateinit var created: TextToSpeech
            created = TextToSpeech(context) { status ->
                readyState = status == TextToSpeech.SUCCESS
                if (readyState) {
                    runCatching { created.language = Locale.SIMPLIFIED_CHINESE }
                }
            }
            engine = created
        }
    }

    override val ready: Boolean get() = readyState

    override fun stop() {
        // 系统合成是自己排队播放的，停它只能通过引擎本身。
        // 没初始化好时 engine 还是 null，停止是空操作，不该抛。
        runCatching { engine?.stop() }
    }

    override suspend fun speak(text: String): SpeechOutcome<Unit> {
        val trimmed = text.trim()
        // 没什么可念的算成功——空回复不是错误
        if (trimmed.isBlank()) return SpeechOutcome.Ok(Unit)

        val tts = engine ?: return SpeechOutcome.Failed("系统朗读还没准备好")
        if (!readyState) return SpeechOutcome.Failed("系统朗读还没准备好")

        val result = tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, "staragent")
        return if (result == TextToSpeech.SUCCESS) {
            SpeechOutcome.Ok(Unit)
        } else {
            SpeechOutcome.Failed("系统朗读起不来")
        }
    }
}

/** 把识别器的错误码翻成用户能照着处理的话。 */
private fun describeRecognitionError(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，再说一次"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
    SpeechRecognizer.ERROR_AUDIO -> "录音出错了"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务连不上网络"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器正忙，等一下再试"
    else -> "识别失败了（$error）"
}
