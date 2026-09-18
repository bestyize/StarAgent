package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.platform.voice.Players
import com.starringbox.staragent.core.platform.voice.Recorders
import com.starringbox.staragent.core.platform.voice.SpeechOutcome
import com.starringbox.staragent.core.platform.voice.Synthesizer
import com.starringbox.staragent.core.platform.voice.Transcriber
import com.starringbox.staragent.core.platform.voice.VoiceClip
import com.starringbox.staragent.core.platform.voice.VoiceRules
import com.starringbox.staragent.core.platform.voice.Wav
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
 * 比 [LlmEndpoint] 简单：**语音只有一种协议形状**。
 * 十家里提供语音的智谱和 OpenAI 走的是同一套（`/audio/transcriptions` 是
 * multipart 上传、`/audio/speech` 是 JSON），所以这里不需要协议字段。
 */
data class SpeechEndpoint(
    val baseUrl: String,
    val apiKey: String,
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
class ModelTranscriber(private val endpoint: () -> SpeechEndpoint?) : Transcriber {

    override val ready: Boolean
        get() = endpoint()?.asrModel != null && Recorders.available

    override suspend fun start(): Boolean = Recorders.start()

    override suspend fun finish(): SpeechOutcome<String> {
        val clip = Recorders.stop() ?: return SpeechOutcome.Failed("没有录到声音")

        // 本地先拦一道：超时长、太小、格式不对，在这里说清楚比发出去换一个 400 强
        VoiceRules.rejection(clip)?.let { return SpeechOutcome.Failed(it.message) }

        val speech = endpoint()
        val model = speech?.asrModel
            ?: return SpeechOutcome.Failed("没有配置支持语音识别的服务商")

        return transcribe(speech, model, clip)
    }

    override fun cancel() = Recorders.cancel()

    private suspend fun transcribe(
        speech: SpeechEndpoint,
        model: String,
        clip: VoiceClip,
    ): SpeechOutcome<String> = try {
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

        when {
            reading.text.isBlank() -> SpeechOutcome.Failed("没有听清，再说一次")
            reading.looksLikeSilence -> SpeechOutcome.Failed("没听到说话，再说一次")
            else -> SpeechOutcome.Ok(reading.text)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (http: LlmHttpException) {
        SpeechOutcome.Failed(http.userMessage)
    } catch (t: Throwable) {
        SpeechOutcome.Failed(describeConnectionFailure(t))
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
class ModelSynthesizer(private val endpoint: () -> SpeechEndpoint?) : Synthesizer {

    override val ready: Boolean
        get() = endpoint()?.ttsModel != null && Players.available

    override suspend fun speak(text: String): SpeechOutcome<Unit> {
        val trimmed = text.trim()
        // 没什么可念的算成功，不要报错——空回复不是错误
        if (trimmed.isBlank()) return SpeechOutcome.Ok(Unit)

        val speech = endpoint()
        val model = speech?.ttsModel
            ?: return SpeechOutcome.Failed("没有配置支持语音合成的服务商")

        return try {
            val bytes = synthesize(speech, model, trimmed)
            if (bytes.isEmpty()) {
                SpeechOutcome.Failed("合成出来是空的")
            } else {
                val clip = VoiceClip(bytes, Wav.MIME_TYPE, Wav.durationMillis(bytes))
                if (Players.play(clip)) {
                    SpeechOutcome.Ok(Unit)
                } else {
                    SpeechOutcome.Failed("音频没能播放出来")
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (http: LlmHttpException) {
            SpeechOutcome.Failed(http.userMessage)
        } catch (t: Throwable) {
            SpeechOutcome.Failed(describeConnectionFailure(t))
        }
    }

    override fun stop() = Players.stop()

    private suspend fun synthesize(speech: SpeechEndpoint, model: String, text: String): ByteArray {
        val response = httpClient().post(speech.baseUrl.trimEnd('/') + "/audio/speech") {
            header(HttpHeaders.Authorization, "Bearer ${speech.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(speechPayload(model, text, speech.ttsVoice))
        }
        ensureSuccess(response)
        return response.bodyAsBytes()
    }
}
