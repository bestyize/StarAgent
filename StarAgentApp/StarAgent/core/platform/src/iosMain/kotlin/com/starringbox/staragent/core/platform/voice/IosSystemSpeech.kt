package com.starringbox.staragent.core.platform.voice

import com.starringbox.staragent.core.speech.SpeechOutcome
import com.starringbox.staragent.core.speech.Synthesizer
import com.starringbox.staragent.core.speech.Transcriber
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSLocale
import platform.darwin.NSObject
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.currentLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus

/**
 * iOS 的权限桥接。
 *
 * Apple 这两条权限都是**异步回调**，而契约里 [Transcriber.start] 是挂起函数，
 * 所以这里把它们桥成挂起。
 *
 * 两者都刻意只申请、不缓存：每次 [Transcriber.start] 都问一遍。
 * `requestXxx` 在已经授权时是立即回调的，代价可以忽略，
 * 而缓存一份状态就会多出一份可能过期、且没法失效的副本。
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosPermissions {

    /** 麦克风权限。 */
    suspend fun requestMicrophone(): Boolean = suspendCancellableCoroutine { continuation ->
        AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted ->
            if (continuation.isActive) continuation.resume(granted)
        }
    }

    /** 语音识别权限——这条是 Apple 单独要求的，和麦克风是两回事。 */
    suspend fun requestSpeechRecognition(): Boolean = suspendCancellableCoroutine { continuation ->
        SFSpeechRecognizer.requestAuthorization { status ->
            // 只有明确授权才算通过：被拒、受限、还没决定都按"不行"处理，
            // 上层会把这句理由展示给用户
            val granted = status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized
            if (continuation.isActive) continuation.resume(granted)
        }
    }
}

/**
 * 语音识别的**设备后端**：用 Apple 的 `SFSpeechRecognizer`。
 *
 * 和 Android 那条一样，**它自己录音**，所以不碰 [Recorders]——
 * 这也正是把 [Transcriber] 的契约定成"从开始听到出文字"整段的原因。
 *
 * 但实现方式和 Android 不同：Android 的识别器把录音、界面、超时全包了，
 * iOS 这边要自己起 `AVAudioEngine`、在输入节点上装 tap、把 buffer 喂给请求对象。
 * 所以这里比 Android 那份多一层音频采集。
 *
 * 需要**两条**权限：语音识别（`SFSpeechRecognizer`）和麦克风（录音），
 * 而且都必须先在 `Info.plist` 里写好用途说明，否则一调用就崩。
 */
@OptIn(ExperimentalForeignApi::class)
class IosSystemTranscriber : Transcriber {

    /** 构造失败（系统不支持当前语言）时为 null，[ready] 据此回答。 */
    private val speech: SFSpeechRecognizer? = SFSpeechRecognizer(locale = NSLocale.currentLocale())

    private val engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null

    /** 结果可能比"结束"先到，所以两边都要能接住。 */
    private var waiting: ((SpeechOutcome<String>) -> Unit)? = null
    private var settled: SpeechOutcome<String>? = null
    private var heard: String = ""

    /**
     * 这台设备有没有可用的识别——**不看权限**。
     *
     * 和 Android 那条同一个立场：权限不算能力，只是还没申请过，[start] 会去申请。
     * 把它算进来会让麦克风入口在首次进入时不渲染，而申请权限的唯一入口就是那个按钮，
     * 于是永远申请不到。这里只看"系统支不支持当前语言"。
     */
    override val ready: Boolean get() = speech != null

    override suspend fun start(): Boolean {
        val recognizer = speech ?: return false
        if (!IosPermissions.requestSpeechRecognition()) return false
        if (!IosPermissions.requestMicrophone()) return false

        settled = null
        heard = ""

        val audioSessionReady = runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryRecord, null)
            session.setActive(true, null)
        }.getOrDefault(false)
        if (!audioSessionReady) return false

        val recognitionRequest = SFSpeechAudioBufferRecognitionRequest().apply {
            // 只要最终结果：中间的半成品对"转完再发出去"没有用，反而会让人以为说错了
            shouldReportPartialResults = false
        }
        request = recognitionRequest

        val started = runCatching {
            val input = engine.inputNode
            val format = input.outputFormatForBus(0uL)
            input.installTapOnBus(0uL, 1024u, format) { buffer, _ ->
                buffer?.let { recognitionRequest.appendAudioPCMBuffer(it) }
            }
            engine.prepare()
            engine.startAndReturnError(null)
        }.getOrDefault(false)

        if (!started) {
            stopEngine()
            return false
        }

        task = recognizer.recognitionTaskWithRequest(recognitionRequest) { result, error ->
            result?.let { heard = it.bestTranscription.formattedString() }
            if (result?.isFinal() == true || error != null) {
                stopEngine()
                // 有文字就用文字：错误和结果可能一起回来
                settle(
                    if (heard.isNotBlank()) SpeechOutcome.Ok(heard.trim())
                    else SpeechOutcome.Failed(describeSpeechError(error))
                )
            }
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
            // 主动收尾：停止喂音频，系统随即给出最终结果。静音一段时间后它也会自己结束，
            // 两条路都通——和 Android 那条一致。
            runCatching { request?.endAudio() }
            stopEngine()
        }
    }

    override fun cancel() {
        waiting = null
        settled = null
        heard = ""
        runCatching { task?.cancel() }
        task = null
        request = null
        stopEngine()
    }

    private fun stopEngine() {
        runCatching {
            if (engine.running) engine.stop()
            engine.inputNode.removeTapOnBus(0uL)
        }
        runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
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
}

/**
 * 文字转语音的**设备后端**：用 Apple 的 `AVSpeechSynthesizer`。
 *
 * 它自己出声，所以不经过 [Player]——这正是把 [Synthesizer] 定成"合成并播放"
 * 而不是"给我一段音频"的原因。
 *
 * 合成器没有异步初始化（不像 Android 的 `TextToSpeech`），所以 `ready` 恒为 true，
 * 不需要快照状态，也就没有 Android 那边"必须依赖 Compose"的包袱。
 * 朗读不需要任何权限。
 */
@OptIn(ExperimentalForeignApi::class)
class IosSystemSynthesizer : Synthesizer {

    private val engine = AVSpeechSynthesizer()
    private data class Utterance(
        val utterance: AVSpeechUtterance,
        val completed: kotlinx.coroutines.CompletableDeferred<SpeechOutcome<Unit>>,
    )
    private var pending: Utterance? = null
    // AVFoundation 弱引用 delegate，需要在 Kotlin 对象上保留强引用。
    private val progress = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            settle(didFinishSpeechUtterance, SpeechOutcome.Ok(Unit))
        }
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            settle(didCancelSpeechUtterance, SpeechOutcome.Failed("朗读已停止"))
        }
    }

    init { engine.delegate = progress }

    override val ready: Boolean get() = true

    override suspend fun speak(text: String): SpeechOutcome<Unit> {
        val trimmed = text.trim()
        // 没什么可念的算成功——空回复不是错误
        if (trimmed.isBlank()) return SpeechOutcome.Ok(Unit)

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            stopOnMain()
            val utterance = AVSpeechUtterance.speechUtteranceWithString(trimmed)
            // 和 Android 那边设 Locale.SIMPLIFIED_CHINESE 对齐；取不到就交给系统默认音色
            AVSpeechSynthesisVoice.voiceWithLanguage("zh-CN")?.let { utterance.voice = it }
            val active = Utterance(utterance, kotlinx.coroutines.CompletableDeferred())
            pending = active
            try {
                engine.speakUtterance(utterance)
                active.completed.await()
            } finally {
                if (pending === active) {
                    pending = null
                    runCatching { engine.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }
                }
            }
        }
    }

    override fun stop() = onMain { stopOnMain() }

    private fun stopOnMain() {
        val active = pending
        pending = null
        active?.completed?.complete(SpeechOutcome.Failed("朗读已停止"))
        runCatching { engine.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }
    }

    private fun settle(utterance: AVSpeechUtterance, outcome: SpeechOutcome<Unit>) = onMain {
        pending?.takeIf { it.utterance == utterance }?.completed?.complete(outcome)
    }

    private fun onMain(action: () -> Unit) {
        if (NSThread.isMainThread) action() else NSOperationQueue.mainQueue.addOperationWithBlock(action)
    }
}

/** 把识别失败翻成用户能照着处理的话。 */
private fun describeSpeechError(error: NSError?): String = when (error?.code) {
    203L, 1110L -> "没有听清，再说一次" // 无匹配 / 没检测到语音
    1101L -> "录音出错了"
    1107L -> "语音识别权限被拒了"
    1700L, 1701L -> "识别服务连不上网络"
    else -> error?.localizedDescription ?: "识别失败了"
}
