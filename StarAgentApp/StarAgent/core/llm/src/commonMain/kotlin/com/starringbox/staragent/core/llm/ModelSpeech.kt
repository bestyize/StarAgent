package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.voice.Players
import com.starringbox.staragent.core.voice.Recorders
import com.starringbox.staragent.core.voice.SpeechOutcome
import com.starringbox.staragent.core.voice.Synthesizer
import com.starringbox.staragent.core.voice.Transcriber
import com.starringbox.staragent.core.voice.VoiceClip
import com.starringbox.staragent.core.voice.VoiceRules
import com.starringbox.staragent.core.voice.Wav
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val response = httpClient().post(speech.baseUrl.trimEnd('/') + "/audio/transcriptions") {
            header(HttpHeaders.Authorization, "Bearer ${speech.apiKey}")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", model)
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

        val raw = response.bodyAsText()
        val text = runCatching {
            Json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty().trim()

        if (text.isBlank()) SpeechOutcome.Failed("没有听清，再说一次") else SpeechOutcome.Ok(text)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (http: LlmHttpException) {
        SpeechOutcome.Failed(http.userMessage)
    } catch (t: Throwable) {
        SpeechOutcome.Failed(describeConnectionFailure(t))
    }
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
