package com.starringbox.staragent.core.data.speech

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.starringbox.staragent.core.data.Seed
import com.starringbox.staragent.core.data.SettingsStore
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.SpeechProvider
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.speech.PcmPlayers
import com.starringbox.staragent.core.speech.SpeechOutcome
import com.starringbox.staragent.core.speech.VoiceClip
import com.starringbox.staragent.core.speech.VoiceSource
import com.starringbox.staragent.core.speech.Wav
import com.starringbox.staragent.core.speech.cloud.ModelSynthesizer
import com.starringbox.staragent.core.speech.cloud.ModelTranscriber
import com.starringbox.staragent.core.speech.cloud.SpeechEndpoint
import com.starringbox.staragent.core.speech.local.LocalSynthesizer
import com.starringbox.staragent.core.speech.local.LocalTranscriber
import com.starringbox.staragent.core.speech.local.asr.client.SenseVoiceAsr
import com.starringbox.staragent.core.speech.local.asr.vad.filterSpeechWav
import com.starringbox.staragent.core.speech.local.tts.client.PiperTts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * 语音的全部状态与操作：引擎偏好、语音服务商与 Key、离线模型、听写，以及回复朗读。
 *
 * 构造时把三路后端装进 [Voice] 并从 [SettingsStore] 还原偏好；设备那一路由各端入口装。
 */
class SpeechController {

    /** 语音引擎偏好。null = 自动：优先设备能力（不要 Key、不花钱）。 */
    var voiceSource by mutableStateOf<VoiceSource?>(null)
        private set

    /**
     * 用哪家语音服务商。null = 还没选（[activeSpeechProvider] 会退回第一家）。
     *
     * 识别使用此配置；朗读没有单独选服务商时才继承它。
     * 凭据按服务商存储，识别和朗读可引用不同服务商的凭据。
     */
    var speechProviderId by mutableStateOf<String?>(null)
        private set

    /** 识别用哪个模型。null = 用那家的预设。存的是模型名本身，不是下标。 */
    var asrModelId by mutableStateOf<String?>(null)
        private set

    var ttsVoiceSource by mutableStateOf<VoiceSource?>(null)
        private set
    var ttsProviderId by mutableStateOf<String?>(null)
        private set
    var ttsVoiceId by mutableStateOf<String?>(null)
        private set
    val localAsrSupported: Boolean get() = SenseVoiceAsr.supported()
    val localTtsSupported: Boolean get() = PiperTts.supported()
    var localAsrInstalled by mutableStateOf(SenseVoiceAsr.installed())
        private set
    var localTtsInstalled by mutableStateOf(false)
        private set
    var localModelBusy by mutableStateOf(false)
        private set
    var localModelProgress by mutableStateOf("")
        private set
    var localModelError by mutableStateOf<String?>(null)
        private set
    private var localModelJob: Job? = null
    private var replySpeechSession: ReplySpeechSession? = null
    private var speechGeneration = 0L
    var speakingAgentId by mutableStateOf<String?>(null)
        private set
    var speechError by mutableStateOf<String?>(null)
        private set
    var streamReplies by mutableStateOf(true)
        private set

    /**
     * 语音侧的全部候选。常量——每次启动由 [Seed.speechProviders] 重建，之后不变。
     *
     * ⚠️ **声明位置必须在 [init] 之前**：属性初始化器和 init 块按声明顺序交错执行，
     * 而 init 里那句 [restoreSettings] 就要往 [speechKeys] 里写。
     */
    val speechProviders: List<SpeechProvider> = Seed.speechProviders()

    /** 语音侧的 Key，按服务商分开存。理由见 [setSpeechKey]。snapshot map，界面跟着它刷新。 */
    private val speechKeys = mutableStateMapOf<String, String>()

    /** 回复要不要念出来。默认关——朗读会真的产生费用，不能默默开始花钱。 */
    var speakReplies by mutableStateOf(false)
        private set

    init {
        // 语音的模型后端在这里装：它要用"用户当前配置的服务商"，
        // 所以只有数据层装得了。设备能力那一路由各端入口装（Android 有，桌面没有）。
        Voice.installModel(
            transcriber = ModelTranscriber(filter = { clip ->
                withContext(Dispatchers.Default) {
                    filterSpeechWav(clip.bytes)?.let { VoiceClip(it, clip.mimeType, Wav.durationMillis(it)) }
                }
            }) { asrEndpoint() },
            synthesizer = ModelSynthesizer { ttsEndpoint() },
        )

        // 「自动 / 系统能力 / 云端识别」这一档也要接过来。
        // [Voice] 刻意不自己存它——上一版两边各存一份、而这边忘了同步，
        // 结果这个选择在真机上一直是失效的。现在这边是唯一的真相。
        Voice.installPreference { voiceSource }
        PiperTts.prepareRuntime()
        localTtsInstalled = PiperTts.installed()
        Voice.installLocal(LocalTranscriber(), LocalSynthesizer())
        Voice.installSpeakingPreference { ttsVoiceSource }
        Voice.installStopHandler {
            speechGeneration++
            replySpeechSession?.stop()
            speakingAgentId = null
        }

        restoreSettings()
    }

    fun setStreamingReplies(enabled: Boolean) {
        stopSpeaking()
        streamReplies = enabled
        SettingsStore.put(KEY_STREAM_REPLIES, enabled.toString())
    }

    /**
     * 当前生效的那家语音服务商。
     *
     * [speechProviderId] 为 null（没选过）或指向一家已经没有的（上一版存下来的某个
     * 自定义服务商 id、或者预置里删掉过一家）时，**退回第一家**——
     * 设置项失效不该让语音直接哑掉。
     */
    fun activeSpeechProvider(): SpeechProvider? =
        speechProviderId?.let { id -> speechProviders.firstOrNull { it.id == id } }
            ?: speechProviders.firstOrNull()

    fun activeTtsProvider(): SpeechProvider? =
        ttsProviderId?.let { id -> speechProviders.firstOrNull { it.id == id } }
            ?: activeSpeechProvider()

    fun selectTtsVoiceSource(source: VoiceSource?) {
        stopSpeaking()
        ttsVoiceSource = source
        SettingsStore.put(KEY_TTS_VOICE_SOURCE, source?.name.orEmpty())
    }

    fun selectTtsProvider(id: String?) {
        stopSpeaking()
        ttsProviderId = id
        ttsVoiceId = null
        SettingsStore.put(KEY_TTS_PROVIDER, id.orEmpty())
        SettingsStore.put(KEY_TTS_VOICE, "")
    }

    fun selectTtsVoice(voice: String?) {
        stopSpeaking()
        ttsVoiceId = voice?.trim()?.takeIf { it.isNotBlank() }
        SettingsStore.put(KEY_TTS_VOICE, ttsVoiceId.orEmpty())
    }

    suspend fun installLocalAsr() = manageLocalModel { progress -> SenseVoiceAsr.install(progress) }
    suspend fun installLocalTts() = manageLocalModel { progress -> PiperTts.install(progress) }
    suspend fun removeLocalAsr() = manageLocalModel { _ -> cancelListening(); SenseVoiceAsr.remove() }
    suspend fun removeLocalTts() = manageLocalModel { _ -> stopSpeaking(); PiperTts.remove() }
    fun cancelLocalModelDownload() { localModelJob?.cancel() }

    private suspend fun manageLocalModel(operation: suspend ((Long, Long) -> Unit) -> Unit) = coroutineScope {
        if (localModelBusy) return@coroutineScope
        localModelBusy = true
        localModelError = null
        localModelProgress = "准备中"
        localModelJob = coroutineContext.job
        try {
            operation { done, total ->
                localModelProgress = if (total > 0) "${(done * 100 / total).coerceIn(0, 100)}%" else "准备中"
            }
            localModelProgress = "完成"
        } catch (cancelled: CancellationException) {
            localModelProgress = "已取消"
            throw cancelled
        } catch (failure: Exception) {
            localModelError = failure.message ?: "模型操作失败"
        } catch (failure: Throwable) {
            localModelError = "离线运行库加载失败，请检查安装包"
        } finally {
            localAsrInstalled = SenseVoiceAsr.installed()
            localTtsInstalled = PiperTts.installed()
            localModelJob = null
            localModelBusy = false
        }
    }

    /** 这家配了 Key 没有。 */
    fun speechKeyOf(providerId: String): String = speechKeys[providerId].orEmpty()

    fun speechConfigured(providerId: String): Boolean = speechKeyOf(providerId).isNotBlank()

    /**
     * 写这家的 Key。传空串等于清掉配置。
     *
     * **按家分开存**，不是"一把 Key"：用户从 Groq 切到智谱时，Groq 的 Key 拿到智谱去
     * 必然 401，而把它留在输入框里等着被误发更糟。按家分开是唯一不会把 A 家的钥匙
     * 插到 B 家门上的存法；界面上一次只显示一家的输入框，读起来仍然是"就一条配置"。
     *
     * 落盘键 `speechKey:<id>` 与对话那边的 `apikey:<id>` **是两回事**——
     * 拆开的意义就在于两边互不影响。
     */
    fun setSpeechKey(providerId: String, key: String) {
        val trimmed = key.trim()
        speechKeys[providerId] = trimmed
        SettingsStore.put(speechKeyName(providerId), trimmed)
    }

    /**
     * 这家现在实际用的识别模型：选过且在候选里就用选的，否则退回预设。
     *
     * 界面和 [asrEndpoint] 都走这一个出口——分头写两遍迟早会不一致，
     * 而"界面显示 A、实际请求 B"是最难查的那种毛病。
     */
    fun activeAsrModel(provider: SpeechProvider): String =
        asrModelId?.takeIf { it in provider.asrChoices } ?: provider.asrModel

    /**
     * 识别用的接口信息。没选服务商、或这家没填 Key，都返回 null。
     *
     * 返回 null 时 `ModelTranscriber.ready` 为 false（见 `core:speech-cloud` 的 ModelSpeech），
     * 界面据此把「云端识别」那一档禁用，并把原因说清楚。
     */
    fun asrEndpoint(): SpeechEndpoint? {
        val provider = activeSpeechProvider() ?: return null
        val key = speechKeyOf(provider.id)
        if (key.isBlank()) return null
        return SpeechEndpoint(
            baseUrl = provider.baseUrl.trimEnd('/'),
            apiKey = key,
            // 形状跟着服务商走。少了这一句，火山方舟那条会拿着 OpenAI 的路径发出去
            protocol = provider.protocol,
            asrModel = activeAsrModel(provider),
        )
    }

    /**
     * 合成用的接口信息。
     *
     * **跟着用户选的那家走**，不再"自动挑一家能做合成的"——这是拆分配置的直接结果：
     * 语音侧只有一条配置，用户选了谁就是谁。这家不能合成（Groq）时返回 null，
     * `ModelSynthesizer.ready` 于是为 false，[Voice] 会把朗读退回设备自带。
     */
    fun ttsEndpoint(): SpeechEndpoint? {
        val provider = activeTtsProvider() ?: return null
        val model = provider.ttsModel ?: return null
        val key = speechKeyOf(provider.id)
        if (key.isBlank()) return null
        return SpeechEndpoint(
            baseUrl = provider.baseUrl.trimEnd('/'),
            apiKey = key,
            protocol = provider.protocol,
            ttsModel = model,
            ttsVoice = ttsVoiceId ?: provider.ttsVoice,
        )
    }

    /** 现在能不能语音输入。界面据此决定麦克风入口显不显示。 */
    val canListen: Boolean get() = Voice.canListen

    /** 现在能不能朗读。 */
    val canSpeak: Boolean get() = Voice.canSpeak

    /** 正在听。界面据此把输入区换成"正在听…"。 */
    val isListening: Boolean get() = Voice.isListening

    suspend fun startListening(): SpeechOutcome<Unit> = Voice.startListening()

    suspend fun stopListening(): SpeechOutcome<String> = Voice.stopListening()

    fun cancelListening() = Voice.cancelListening()

    /** 念一段文字。回复里只有纯文本会被念——卡片不是"话"。 */
    suspend fun speak(text: String): SpeechOutcome<Unit> = Voice.speak(text)

    fun stopSpeaking() = Voice.stopSpeaking()

    /** 注意：不能叫 setVoiceSource——那会跟属性自带的 setter 撞 JVM 签名。 */
    fun selectVoiceSource(source: VoiceSource?) {
        voiceSource = source
        SettingsStore.put(KEY_VOICE_SOURCE, source?.name.orEmpty())
    }

    /**
     * 选语音服务商。传 null 回到"还没选"（[activeSpeechProvider] 会退回第一家）。
     *
     * 换家的同时把模型选择清掉：模型名是**跟着服务商**的，
     * `whisper-large-v3-turbo` 拿到智谱去就是个不存在的模型。
     *
     * Key **不清**——它是按家分开存的，各留各的，切回去不用重填。
     */
    fun selectSpeechProvider(id: String?) {
        if (ttsProviderId == null) selectTtsVoice(null)
        speechProviderId = id
        SettingsStore.put(KEY_SPEECH_PROVIDER, id.orEmpty())
        selectAsrModel(null)
    }

    /** 选识别模型。传 null 回到这家的预设。 */
    fun selectAsrModel(model: String?) {
        asrModelId = model
        SettingsStore.put(KEY_ASR_MODEL, model.orEmpty())
    }

    /**
     * 语音现在实际会走哪条路，一句话说清。「我的」页那行副标题用它。
     *
     * 顺序照 [Voice] 的选取逻辑来，否则副标题会跟真实行为不一致——那比不显示更糟。
     */
    val asrSummary: String
        get() {
            val device = Voice.deviceListenInstalled
            val provider = activeSpeechProvider()
            val configured = provider != null && speechConfigured(provider.id)
            return when {
                voiceSource == VoiceSource.Local && localAsrInstalled -> "离线 · SenseVoice"
                voiceSource == VoiceSource.Local -> "离线 · 请先下载识别模型"
                voiceSource == VoiceSource.Device && device -> "系统能力"
                voiceSource == VoiceSource.Device -> "系统能力 · 这台设备不支持"
                // 「云端识别」是用户点名要的，没配好就得直说是哪一步没做
                voiceSource == VoiceSource.Model && configured -> provider!!.name
                voiceSource == VoiceSource.Model && provider == null -> "云端识别 · 还没选服务商"
                voiceSource == VoiceSource.Model -> "云端识别 · 还没填 Key"
                device -> "自动 · 优先系统能力"
                localAsrInstalled -> "自动 · 离线 SenseVoice"
                configured -> "自动 · ${provider!!.name}"
                else -> "还没有可用的识别"
            }
        }

    /** 同理，不能叫 setSpeakReplies。 */
    fun setReadingReplies(enabled: Boolean) {
        speakReplies = enabled
        SettingsStore.put(KEY_SPEAK_REPLIES, enabled.toString())
    }

    /**
     * 跑一轮回复，按需把它念出来。
     *
     * [messages] 读的是 [key] 那条会话：流式朗读走 [ReplySpeechSession] 边收边念，
     * 否则等 [work] 结束后念这一轮里最后一条智能体的纯文本回复。
     */
    internal suspend fun withReplySpeech(
        key: String,
        readAloud: Boolean,
        forceSpeech: Boolean = false,
        messages: () -> List<ChatMessage>,
        work: suspend (ReplySpeechSession?) -> Unit,
    ) = coroutineScope {
        if (readAloud) speechError = null
        if (!readAloud || (!speakReplies && !forceSpeech) || !canSpeak) { work(null); return@coroutineScope }
        stopSpeaking()
        val generation = speechGeneration
        val before = messages().size
        val session = if (streamReplies && Voice.canStreamSpeak) ReplySpeechSession(
            scope = this,
            synthesize = { sentence, emit -> Voice.streamSpeak(sentence, emit) },
            play = {
                if (generation == speechGeneration) speakingAgentId = key
                PcmPlayers.enqueue(it)
            },
            drain = { PcmPlayers.drain() },
            stopPlayback = { PcmPlayers.stop() },
            onFailure = { if (generation == speechGeneration) speechError = it },
        ) else null
        replySpeechSession = session
        try {
            work(session)
            if (session != null) session.finish()
            else if (generation == speechGeneration && !isListening) {
                val reply = messages().drop(before).lastOrNull { it.author == Author.Agent && it.body is MessageBody.Text }
                val text = (reply?.body as? MessageBody.Text)?.text.orEmpty()
                if (text.isNotBlank()) {
                    speakingAgentId = key
                    val result = speak(text)
                    if (result is SpeechOutcome.Failed) speechError = result.message
                }
            }
        } finally {
            if (generation == speechGeneration) speakingAgentId = null
            if (replySpeechSession === session) {
                session?.stop()
                replySpeechSession = null
            }
        }
    }

    /**
     * 语音侧的 Key 落盘键。
     *
     * **故意和对话服务商的 `apikey:<id>` 不同名**——同名就等于又回到"两边共用一把"，
     * 那正是这次要拆掉的东西。旧键一律不读、不删：它们是用户的对话配置，留着无害。
     */
    private fun speechKeyName(providerId: String) = "speechKey:$providerId"

    private fun restoreSettings() {
        SettingsStore.get(KEY_VOICE_SOURCE)
            ?.takeIf { it.isNotBlank() }
            ?.let { saved -> VoiceSource.entries.firstOrNull { it.name == saved } }
            ?.let { selectVoiceSource(it) }
        speakReplies = SettingsStore.get(KEY_SPEAK_REPLIES) == "true"
        streamReplies = SettingsStore.get(KEY_STREAM_REPLIES) != "false"

        // 语音服务商与模型。这两个只是"偏好"，指向的东西不在了也不校验——
        // activeSpeechProvider() / asrEndpoint() 每次都会重新核对一遍，那里退回第一家。
        // 在这儿校验反而不对：还原时语音的 Key 才刚灌完，此刻的可用性不代表用起来那一刻的。
        //
        // 上一版存下来的 speechProvider 可能是某个**对话**服务商的 id，
        // 读出来落到 activeSpeechProvider() 上会自然退回第一家——不需要为它写迁移。
        speechProviderId = SettingsStore.get(KEY_SPEECH_PROVIDER)?.takeIf { it.isNotBlank() }
        asrModelId = SettingsStore.get(KEY_ASR_MODEL)?.takeIf { it.isNotBlank() }
        ttsVoiceSource = SettingsStore.get(KEY_TTS_VOICE_SOURCE)?.let { saved -> VoiceSource.entries.firstOrNull { it.name == saved } }
        ttsProviderId = SettingsStore.get(KEY_TTS_PROVIDER)?.takeIf { it.isNotBlank() }
        ttsVoiceId = SettingsStore.get(KEY_TTS_VOICE)?.takeIf { it.isNotBlank() }

        // 每一家的 Key 都要读回来。漏读一家的后果是"每次冷启都要重填"，
        // 而这件事不写测试根本发现不了——id 和模型名都还原得好好的，看着一切正常。
        speechProviders.forEach { provider ->
            SettingsStore.get(speechKeyName(provider.id))
                ?.takeIf { it.isNotBlank() }
                ?.let { speechKeys[provider.id] = it }
        }
    }

    companion object {
        const val KEY_VOICE_SOURCE = "voiceSource"
        const val KEY_SPEECH_PROVIDER = "speechProvider"
        const val KEY_ASR_MODEL = "asrModel"
        const val KEY_SPEAK_REPLIES = "speakReplies"
        const val KEY_STREAM_REPLIES = "streamReplies"
        const val KEY_TTS_VOICE_SOURCE = "ttsVoiceSource"
        const val KEY_TTS_PROVIDER = "ttsProvider"
        const val KEY_TTS_VOICE = "ttsVoice"
    }
}
