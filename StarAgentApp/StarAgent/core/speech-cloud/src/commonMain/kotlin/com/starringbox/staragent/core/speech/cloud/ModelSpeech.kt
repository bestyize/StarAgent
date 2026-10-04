package com.starringbox.staragent.core.speech.cloud

import com.starringbox.staragent.core.llm.LlmHttpException
import com.starringbox.staragent.core.llm.describeConnectionFailure
import com.starringbox.staragent.core.llm.ensureSuccess
import com.starringbox.staragent.core.llm.httpClient
import com.starringbox.staragent.core.model.SpeechProtocol
import com.starringbox.staragent.core.speech.Players
import com.starringbox.staragent.core.speech.PcmAudioChunk
import com.starringbox.staragent.core.speech.PcmPlayers
import com.starringbox.staragent.core.speech.Recorders
import com.starringbox.staragent.core.speech.SpeechOutcome
import com.starringbox.staragent.core.speech.StreamingSynthesizer
import com.starringbox.staragent.core.speech.Synthesizer
import com.starringbox.staragent.core.speech.Transcriber
import com.starringbox.staragent.core.speech.VoiceClip
import com.starringbox.staragent.core.speech.VoiceRules
import com.starringbox.staragent.core.speech.Wav
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 一次语音请求所需的全部信息。
 *
 * 比 [com.starringbox.staragent.core.llm.LlmEndpoint] 简单：协议只有两种，而且**由服务商定死、不需要用户选**——
 * 用户看到的永远是"哪一家"，形状是那一家自己的事。见 [SpeechProtocol]。
 */
data class SpeechEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val protocol: SpeechProtocol = SpeechProtocol.OpenAi,
    val asrModel: String? = null,
    val ttsModel: String? = null,
    val ttsVoice: String? = null,
)

/**
 * 走服务商的语音识别。
 *
 * 和设备自带识别最大的不同：**录音是我们的，识别在服务商那边**。
 * 所以这里会用到 [Recorders]——而设备后端自己录音，不碰它。
 */
class ModelTranscriber(
    private val filter: suspend (VoiceClip) -> VoiceClip? = { it },
    private val transcribeRequest: (suspend (SpeechEndpoint, String, VoiceClip) -> SpeechOutcome<String>)? = null,
    private val endpoint: () -> SpeechEndpoint?,
) : Transcriber {

    private val requestGate = Mutex()
    private var requestJob: Job? = null
    private var startedWith: SpeechEndpoint? = null
    override val inputLevel: Float get() = Recorders.inputLevel
    override val speechEnded: Boolean get() = Recorders.speechEnded

    override val ready: Boolean
        get() = endpoint()?.asrModel != null && Recorders.available

    override suspend fun start(): Boolean {
        val speech = endpoint()?.takeIf { it.asrModel != null } ?: return false
        startedWith = null
        return try {
            if (!Recorders.start()) false
            else {
                currentCoroutineContext().ensureActive()
                startedWith = speech
                true
            }
        } catch (cancellation: CancellationException) {
            Recorders.cancel()
            throw cancellation
        }
    }

    override suspend fun finish(): SpeechOutcome<String> = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        requestGate.withLock {
            requestJob?.cancel()
            requestJob = job
        }
        try {
            val speech = startedWith.also { startedWith = null } ?: endpoint()
            val clip = Recorders.stop() ?: return@coroutineScope SpeechOutcome.Failed("没有录到声音")
            VoiceRules.rejection(clip)?.let { return@coroutineScope SpeechOutcome.Failed(it.message) }

            val filtered = filter(clip) ?: return@coroutineScope SpeechOutcome.Failed("没听到说话，再说一次")
            VoiceRules.rejection(filtered)?.let { return@coroutineScope SpeechOutcome.Failed(it.message) }

            val model = speech?.asrModel
                ?: return@coroutineScope SpeechOutcome.Failed("没有配置支持语音识别的服务商")
            currentCoroutineContext().ensureActive()
            val result = transcribeRequest?.invoke(speech, model, filtered) ?: transcribe(speech, model, filtered)
            if (result is SpeechOutcome.Ok && !result.value.hasSpokenContent()) {
                SpeechOutcome.Failed("没有听清，再说一次")
            } else result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (http: LlmHttpException) {
            SpeechOutcome.Failed(http.userMessage)
        } catch (t: Throwable) {
            SpeechOutcome.Failed(describeConnectionFailure(t))
        } finally {
            withContext(NonCancellable) {
                requestGate.withLock { if (requestJob === job) requestJob = null }
            }
        }
    }

    override fun cancel() {
        startedWith = null
        requestJob?.cancel()
        Recorders.cancel()
    }

    /**
     * 发出去。**两条协议在这里分岔**，但错误处理留在外面这一层——
     * 失败要变成什么，两种形状没有区别。
     */
    private suspend fun transcribe(
        speech: SpeechEndpoint,
        model: String,
        clip: VoiceClip,
    ): SpeechOutcome<String> = try {
        when (speech.protocol) {
            SpeechProtocol.OpenAi -> transcribeOpenAi(speech, model, clip)
            SpeechProtocol.MiMo -> SpeechOutcome.Ok(
                transcribeWithMiMo(speech, model, clip.bytes).trim().also {
                    if (!it.hasSpokenContent()) throw LlmHttpException("没听到说话，再说一次")
                }
            )
            SpeechProtocol.VolcArk -> transcribeVolcArk(speech, model, clip)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (http: LlmHttpException) {
        SpeechOutcome.Failed(http.userMessage)
    } catch (t: Throwable) {
        SpeechOutcome.Failed(describeConnectionFailure(t))
    }

    /** OpenAI 那套：`POST /audio/transcriptions`，multipart 带 `file` 与 `model`。 */
    private suspend fun transcribeOpenAi(
        speech: SpeechEndpoint,
        model: String,
        clip: VoiceClip,
    ): SpeechOutcome<String> {
        val verbose = wantsSpeechProbabilities(model)
        val response = httpClient().post(speech.baseUrl.trimEnd('/') + "/audio/transcriptions") {
            header(HttpHeaders.Authorization, "Bearer ${speech.apiKey}")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", model)
                        if (verbose) {
                            // 要 segments，里面才有 no_speech_prob
                            append("response_format", "verbose_json")
                            // 贪心解码。温度一高，静音上的胡话更多
                            append("temperature", "0")
                        }
                        append(
                            "file",
                            clip.bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, clip.mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"speech.wav\"")
                            },
                        )
                    }
                )
            )
        }
        ensureSuccess(response)

        val reading = readAsrResponse(response.bodyAsText())

        return when {
            reading.text.isBlank() -> SpeechOutcome.Failed("没有听清，再说一次")
            reading.looksLikeSilence -> SpeechOutcome.Failed("没听到说话，再说一次")
            else -> SpeechOutcome.Ok(reading.text)
        }
    }

    /**
     * 火山方舟 Agent Plan：一段 WebSocket 二进制会话，见 [VolcSpeech]。
     *
     * 语言传 `"auto"`——[Transcriber] 的契约里没有语言这一项（界面也没得选），
     * 而协议里留空就是"由服务端自己判"，正是想要的那个意思。
     *
     * 这条路拿不到 `no_speech_prob`，所以本地那道静音闸对它不起作用。
     * 替代品是服务端自己的判定：一个字都没解出来就当静音。
     */
    private suspend fun transcribeVolcArk(
        speech: SpeechEndpoint,
        model: String,
        clip: VoiceClip,
    ): SpeechOutcome<String> = when (
        val reading = transcribeWithVolcArk(
            baseUrl = speech.baseUrl,
            apiKey = speech.apiKey,
            resourceId = model,
            wav = clip.bytes,
            language = "auto",
        )
    ) {
        VolcReading.Silence -> SpeechOutcome.Failed("没听到说话，再说一次")
        is VolcReading.Text -> SpeechOutcome.Ok(reading.value)
    }
}

/**
 * 该不该向这个模型多要一份 `verbose_json`。
 *
 * 只对 Whisper 系要。两个理由，都很硬：
 *
 * 一是**只有它需要**。静音幻觉是 Whisper 的结构性毛病——它是必须解码出字来的
 * seq2seq，没有"什么都没听到"这个输出。`no_speech_prob` 也是 Whisper 才有的字段，
 * GLM-ASR、gpt-4o-transcribe 都不给。
 *
 * 二是**乱要会炸**。`verbose_json` 不是通用值：OpenAI 自己的 gpt-4o-transcribe
 * 直接报「response_format 'verbose_json' is not compatible with model」，
 * 智谱 GLM-ASR 文档里的请求体也没有这个参数。无条件带上，等于拿一个 400
 * 换一道本来只有 Whisper 用得上的闸。
 */
internal fun wantsSpeechProbabilities(model: String): Boolean =
    model.contains("whisper", ignoreCase = true)

/**
 * 识别结果里的一段，以及服务端对它的两个置信度。
 *
 * @param noSpeechProb 这一段"根本没人说话"的概率。
 * @param avgLogprob 这一段解出来的字的平均对数概率，越低表示模型自己越没底。
 */
internal data class AsrSegment(val noSpeechProb: Double, val avgLogprob: Double)

/** 一次识别读出来的东西。`segments` 为空表示服务端没给概率，不是"没有段"。 */
internal data class AsrReading(val text: String, val segments: List<AsrSegment>) {

    /**
     * 这段结果是不是静音幻觉。
     *
     * 判据要**每一段**都成立：只要有一段是有把握的人声，那这句话就该留着，
     * 哪怕前后各挂了一段静音。
     *
     * 两条线取或：
     * - `no_speech_prob` 高到 [NO_SPEECH_CERTAIN]，单凭这条就够——服务端已经
     *   很确定这里没人说话，它给出的字只能是编的。
     * - `no_speech_prob` 过 [NO_SPEECH_LIKELY] **并且** `avg_logprob` 低于
     *   [AVG_LOGPROB_FLOOR]：拿不准有没有人说话，同时对解出来的字也没底。
     *   这两个数是 faster-whisper 的默认阈值，不是我随手定的。
     *
     * 没有 segments 时返回 false：**判断不了就不下判断**，和本地那道闸一个原则。
     */
    val looksLikeSilence: Boolean
        get() = segments.isNotEmpty() && segments.all {
            it.noSpeechProb >= NO_SPEECH_CERTAIN ||
                (it.noSpeechProb >= NO_SPEECH_LIKELY && it.avgLogprob <= AVG_LOGPROB_FLOOR)
        }

    companion object {
        const val NO_SPEECH_CERTAIN = 0.85
        const val NO_SPEECH_LIKELY = 0.6
        const val AVG_LOGPROB_FLOOR = -1.0
    }
}

/**
 * 从识别的响应里读出文本和概率。
 *
 * 对形状很宽容：`verbose_json` 没被支持、退回成普通 `{"text": ...}` 的时候，
 * 文本照样读得出来，只是没有概率可用——那时候就只剩本地那道闸。
 * 整个响应解不开时给一份空的，让调用方走"没有听清"。
 */
internal fun readAsrResponse(raw: String): AsrReading {
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return AsrReading("", emptyList())

    // 一路用安全转换：字段类型和文档不一致时该是"读不到"，不该是抛异常——
    // 那会被外层当成网络故障，报给用户一个完全不相干的原因
    val text = (root["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    val segments = (root["segments"] as? JsonArray).orEmpty().mapNotNull { element ->
        val segment = element as? JsonObject ?: return@mapNotNull null
        val noSpeech = (segment["no_speech_prob"] as? JsonPrimitive)?.doubleOrNull
            ?: return@mapNotNull null
        // avg_logprob 缺了按 0 算：0 是最高的对数概率，也就是"最不像静音"，
        // 缺字段不该帮着把结果推向拦截
        val logprob = (segment["avg_logprob"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        AsrSegment(noSpeech, logprob)
    }
    return AsrReading(text, segments)
}

/**
 * 合成的请求体。
 *
 * 抽出来是为了能单测字段名——接口形状写错只有真发出去才知道，
 * 而字段名恰恰是最容易手滑的地方。
 */
internal fun speechPayload(model: String, text: String, voice: String?): String =
    buildJsonObject {
        put("model", model)
        put("input", text)
        // 音色是必填。没配就退回各家自己的默认值，让服务端决定
        voice?.takeIf { it.isNotBlank() }?.let { put("voice", it) }
        // 明确要 wav：整条链路只有 PCM/WAV，播放侧就不用引 MP3 解码器
        put("response_format", "wav")
    }.toString()

/**
 * 走服务商的语音合成。
 *
 * 合成出来是一段字节，**播放要交给 [Players]**——服务商只给音频文件，
 * 出声是设备的事。
 */
class ModelSynthesizer(
    private val synthesizeRequest: (suspend (SpeechEndpoint, String, String) -> ByteArray)? = null,
    private val streamRequest: (suspend (SpeechEndpoint, String, suspend (PcmAudioChunk) -> Unit) -> Unit)? = null,
    private val endpoint: () -> SpeechEndpoint?,
) : StreamingSynthesizer {

    private val requestGate = Mutex()
    private var requestJob: Job? = null

    override val ready: Boolean
        get() = endpoint()?.ttsModel != null && Players.available

    override val streamReady: Boolean
        get() = endpoint()?.let { it.protocol == SpeechProtocol.MiMo && it.ttsModel == MIMO_STREAM_TTS_MODEL } == true

    override suspend fun speak(text: String): SpeechOutcome<Unit> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return SpeechOutcome.Ok(Unit)
        val speech = endpoint()
        val model = speech?.ttsModel ?: return SpeechOutcome.Failed("没有配置支持语音合成的服务商")

        return exclusiveRequest {
            try {
                val bytes = synthesizeRequest?.let { it(speech, model, trimmed) }
                    ?: synthesize(speech, model, trimmed)
                currentCoroutineContext().ensureActive()
                if (bytes.isEmpty()) return@exclusiveRequest SpeechOutcome.Failed("合成出来是空的")

                val clip = VoiceClip(bytes, Wav.MIME_TYPE, Wav.durationMillis(bytes))
                val played = Players.play(clip)
                currentCoroutineContext().ensureActive()
                if (played) SpeechOutcome.Ok(Unit) else SpeechOutcome.Failed("音频没能播放出来")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (http: LlmHttpException) {
                SpeechOutcome.Failed(http.userMessage)
            } catch (t: Throwable) {
                SpeechOutcome.Failed(describeConnectionFailure(t))
            }
        }
    }

    override suspend fun stream(text: String, onAudio: suspend (PcmAudioChunk) -> Unit): SpeechOutcome<Unit> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return SpeechOutcome.Ok(Unit)
        val speech = endpoint()
        if (speech?.protocol != SpeechProtocol.MiMo || speech.ttsModel != MIMO_STREAM_TTS_MODEL) {
            return SpeechOutcome.Failed("当前语音服务商不支持流式合成")
        }

        return exclusiveRequest {
            try {
                var audioBytes = 0L
                val consume: suspend (PcmAudioChunk) -> Unit = { chunk ->
                    currentCoroutineContext().ensureActive()
                    audioBytes += chunk.pcm16.size
                    onAudio(chunk)
                }
                val injected = streamRequest
                if (injected != null) injected(speech, trimmed, consume)
                else streamWithMiMo(speech, trimmed, consume)
                currentCoroutineContext().ensureActive()
                if (audioBytes == 0L) SpeechOutcome.Failed("合成出来是空的") else SpeechOutcome.Ok(Unit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (http: LlmHttpException) {
                SpeechOutcome.Failed(http.userMessage)
            } catch (t: Throwable) {
                SpeechOutcome.Failed(describeConnectionFailure(t))
            }
        }
    }

    override fun stop() {
        requestJob?.cancel()
        Players.stop()
        PcmPlayers.stop()
    }

    private suspend fun <T> exclusiveRequest(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        requestGate.withLock {
            requestJob?.cancel()
            requestJob = job
        }
        try {
            job.ensureActive()
            block()
        } finally {
            withContext(NonCancellable) {
                requestGate.withLock { if (requestJob === job) requestJob = null }
            }
        }
    }

    /** OpenAI and VolcArk retain their existing one-shot request paths. */
    private suspend fun synthesize(speech: SpeechEndpoint, model: String, text: String): ByteArray =
        when (speech.protocol) {
            SpeechProtocol.OpenAi -> {
                val response = httpClient().post(speech.baseUrl.trimEnd('/') + "/audio/speech") {
                    header(HttpHeaders.Authorization, "Bearer ${speech.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(speechPayload(model, text, speech.ttsVoice))
                }
                ensureSuccess(response)
                response.bodyAsBytes()
            }
            SpeechProtocol.MiMo -> synthesizeWithMiMo(speech, model, text)
            SpeechProtocol.VolcArk -> synthesizeWithVolcArk(
                baseUrl = speech.baseUrl,
                apiKey = speech.apiKey,
                resourceId = model,
                voice = speech.ttsVoice,
                text = text,
            )
        }

    private companion object {
        const val MIMO_STREAM_TTS_MODEL = "mimo-v2.5-tts"
    }
}

private fun String.hasSpokenContent(): Boolean = any { it.isLetterOrDigit() }
