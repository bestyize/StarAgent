package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.platform.voice.SpeechOutcome
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock

/**
 * 火山方舟（豆包语音）那套语音接口。
 *
 * 和另外几家走 OpenAI 形状的放在一起会很难读，所以单独一份——它**每一处都不一样**：
 *
 * - 鉴权是一个 **`X-Api-Key` 头**，不是 `Authorization: Bearer`。而且和火山那套旧的语音接口
 *   （要 APP ID + Access Token）**不是一回事**，两边的凭据不通用；
 * - 用哪个模型不看请求体，看 **`X-Api-Resource-Id` 头**；
 * - 识别**成败看响应头 `X-Api-Status-Code`**，不看 HTTP 状态码——200 而业务失败是常态，
 *   只看状态码的症状是"没出字，但也不报错"；
 * - 合成回来的是一串**流式事件**，音频分片各在事件的 `data` 里、**各自独立 base64**，
 *   所以必须逐片解码再拼字节（见 [readVolcTtsStream]）。
 *
 * 这里的函数只管发请求与解析，**失败一律抛 [LlmHttpException]**——
 * 由 `ModelSpeech` 统一翻成 [SpeechOutcome]，两条协议分支的错误处理才走同一条路。
 */
internal object VolcSpeech {

    const val ASR_PATH: String = "/api/v3/auc/bigmodel/recognize/flash"
    const val TTS_PATH: String = "/api/v3/tts/unidirectional/sse"

    /** 合成取样率。24000 是官方列的档位里常见的一个，播放侧只读 WAV 头，不依赖它。 */
    const val SAMPLE_RATE: Int = 24_000

    const val KEY_HEADER: String = "X-Api-Key"
    const val RESOURCE_HEADER: String = "X-Api-Resource-Id"
    const val REQUEST_ID_HEADER: String = "X-Api-Request-Id"
    const val STATUS_HEADER: String = "X-Api-Status-Code"
    const val MESSAGE_HEADER: String = "X-Api-Message"

    /** 业务成功码。**和 HTTP 的状态码无关**。 */
    const val STATUS_OK: String = "20000000"

    /** 静音：音频是好的，只是没人说话。不是错误。 */
    const val STATUS_SILENCE: String = "20000003"

    /** 合成事件里的 `code`：这一段有音频。 */
    const val CODE_DATA: String = "0"

    /** 合成事件里的 `code`：正常结束。 */
    const val CODE_END: String = "20000000"
}

/** 一次识别读出来的东西。静音要单独成一档——它不是错误，但也没有字。 */
internal sealed interface VolcReading {
    data class Text(val value: String) : VolcReading

    /** 服务端判定这段音频里没人说话（`20000003`）。 */
    data object Silence : VolcReading
}

/**
 * 识别：把整段 WAV 发过去，一次拿回文字。
 *
 * 用的是**录音文件识别极速版**——它是这条路上唯一"一次 POST、音频可以直接放进请求体"
 * 的接口。另外两条（流式识别是 WebSocket 二进制长连接，标准版录音文件识别只收
 * 公网可下载的 URL）对"录音不出本机"的用法都不能用。
 *
 * 代价是它只有 1.0 的模型，而且不接流：录完再整段发，没有边说边出字。
 */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun transcribeWithVolcArk(
    baseUrl: String,
    apiKey: String,
    resourceId: String,
    wav: ByteArray,
): VolcReading {
    val payload = volcAsrPayload(wav)

    val response = httpClient().post(baseUrl.trimEnd('/') + VolcSpeech.ASR_PATH) {
        header(VolcSpeech.KEY_HEADER, apiKey)
        header(VolcSpeech.RESOURCE_HEADER, resourceId)
        // 每次唯一——出问题时靠它去火山那边查这次调用
        header(VolcSpeech.REQUEST_ID_HEADER, newRequestId())
        contentType(ContentType.Application.Json)
        setBody(payload)
    }

    val raw = response.bodyAsText()

    // HTTP 那层的失败（401、404…）先照常抛。它的说明在响应头里，不在响应体里
    if (!response.status.isSuccess()) throw LlmHttpException(volcMessage(response, raw))

    // 成败看这个头，不看 HTTP 状态码——200 而业务失败是常态
    when (response.headers[VolcSpeech.STATUS_HEADER]) {
        VolcSpeech.STATUS_SILENCE -> return VolcReading.Silence
        VolcSpeech.STATUS_OK, null -> Unit
        else -> throw LlmHttpException(volcMessage(response, raw))
    }

    return VolcReading.Text(readVolcAsrText(raw))
}

/**
 * 识别的请求体。
 *
 * 和合成那份同一个理由：抽出来才能单测字段名。三层 `user`/`audio`/`request` 是它自己的
 * 规矩，缺一层就是 400，而错误信息不会告诉你缺的是哪一层。
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun volcAsrPayload(wav: ByteArray): String =
    buildJsonObject {
        putJsonObject("user") { put("uid", UID) }
        putJsonObject("audio") {
            put("format", "wav")
            // base64 直接进请求体，所以不必先把音频传到什么地方去
            put("data", Base64.Default.encode(wav))
        }
        putJsonObject("request") {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("enable_ddc", true)
        }
    }.toString()

/**
 * 从识别的响应里取出文本。
 *
 * 解不开时给空串，让调用方走"没有听清"——**不要在这里抛**：
 * 那会被外层当成网络故障，报给用户一个完全不相干的原因。
 */
internal fun readVolcAsrText(raw: String): String =
    runCatching {
        Json.parseToJsonElement(raw).jsonObject["result"]?.jsonObject?.get("text")?.jsonPrimitive?.content
    }.getOrNull().orEmpty().trim()

/**
 * 合成：把整段文字发过去，把流式回来的音频拼成一整段 WAV。
 *
 * 文本过长要由调用方切段——这个接口单次有上限，而 `Synthesizer` 的契约是"把这段念出来"。
 */
internal suspend fun synthesizeWithVolcArk(
    baseUrl: String,
    apiKey: String,
    resourceId: String,
    voice: String?,
    text: String,
): ByteArray {
    val response = httpClient().post(baseUrl.trimEnd('/') + VolcSpeech.TTS_PATH) {
        header(VolcSpeech.KEY_HEADER, apiKey)
        header(VolcSpeech.RESOURCE_HEADER, resourceId)
        header(VolcSpeech.REQUEST_ID_HEADER, newRequestId())
        contentType(ContentType.Application.Json)
        setBody(volcTtsPayload(text, voice))
    }

    if (!response.status.isSuccess()) {
        throw LlmHttpException(httpErrorMessage(response.status.value, response.bodyAsText()))
    }

    val audio = readVolcTtsStream(response.bodyAsText())
    if (audio.isEmpty()) throw LlmHttpException("合成出来是空的")
    return audio
}

/**
 * 合成的请求体。
 *
 * 抽出来是为了能单测字段名——形状写错只有真发出去才知道，而字段名恰恰最容易手滑。
 * `speaker`（音色）**必须和资源 ID 同一代**：`seed-tts-2.0` 要配 `…_uranus_bigtts` 的音色，
 * 配错会回 `resource ID is mismatched with speaker`。
 */
internal fun volcTtsPayload(text: String, voice: String?): String =
    buildJsonObject {
        putJsonObject("user") { put("uid", UID) }
        putJsonObject("req_params") {
            put("text", text)
            voice?.takeIf { it.isNotBlank() }?.let { put("speaker", it) }
            put("sample_rate", VolcSpeech.SAMPLE_RATE)
            putJsonObject("audio_params") {
                // 要 wav：播放侧只认 WAV，PCM 要自己套 44 字节头、还得赌它是 s16le，
                // 赌错的症状是"能放，全是噪声"，比报错难查得多
                put("format", "wav")
            }
        }
    }.toString()

/**
 * 把流式响应拼成一段完整音频。
 *
 * 两种流都可能撞上——官方文档给的是 NDJSON（一行一个 JSON），实际调用样例用的是 SSE
 * （`data: {...}`）。这里**两种都认**：剥掉可有可无的 `data:` 前缀，剩下的只要以 `{`
 * 开头就当事件解析。多认一种的代价是几行，少认一种的代价是"换了个端点就没声音"。
 *
 * **每片音频是各自独立 base64 的**，所以必须逐片解码再拼字节；把 base64 字符串首尾相接
 * 再解一次是不行的——除了最后一片，前面每片都带 `=` 填充，接起来就解不动了。
 * 这个错在真机上的症状是"没声音"，光看现象推不出来，所以专门有一条测试守着。
 *
 * 拼完验一下它确实是 WAV。这一条是多花的：万一格式参数被服务端换掉、回一段 mp3，
 * 报"拿回来的不是 WAV"比"能播，但是一片噪音"清楚得多。
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun readVolcTtsStream(raw: String): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    var total = 0

    raw.lineSequence().forEach { line ->
        val json = line.trim().removePrefix("data:").trim()
        if (!json.startsWith("{")) return@forEach

        val event = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return@forEach

        val code = event["code"]?.jsonPrimitive?.content
        if (code != null && code != VolcSpeech.CODE_DATA && code != VolcSpeech.CODE_END) {
            val message = event["message"]?.jsonPrimitive?.content.orEmpty()
            // 半路出错：把已经拼好的丢掉，直说
            throw LlmHttpException("合成失败（code=$code $message）")
        }

        val encoded = event["data"]?.jsonPrimitive?.content
        if (encoded.isNullOrBlank()) return@forEach

        val bytes = Base64.Default.decode(encoded)
        if (bytes.isNotEmpty()) {
            chunks += bytes
            total += bytes.size
        }
    }

    if (total == 0) return ByteArray(0)

    val audio = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(audio, offset)
        offset += chunk.size
    }

    if (!audio.looksLikeWav()) {
        throw LlmHttpException("合成回来的不是 WAV（前 4 字节不是 RIFF）")
    }
    return audio
}

/** 火山把错误说明放在**响应头** `X-Api-Message` 里，响应体往往是空的。两处都看一遍。 */
private fun volcMessage(response: HttpResponse, body: String): String =
    response.headers[VolcSpeech.MESSAGE_HEADER]?.takeIf { it.isNotBlank() }
        ?: httpErrorMessage(response.status.value, body)

private fun ByteArray.looksLikeWav(): Boolean =
    size >= 44 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'A'.code.toByte() &&
        this[10] == 'V'.code.toByte() && this[11] == 'E'.code.toByte()

private const val UID = "staragent"

/** 请求 ID：每次唯一，出问题时靠它去火山那边查这次调用。 */
private fun newRequestId(): String =
    "staragent-${Clock.System.now().toEpochMilliseconds()}-${(0..0xffffff).random()}"
