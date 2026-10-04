package com.starringbox.staragent.core.speech.cloud

import com.starringbox.staragent.core.llm.LlmHttpException
import com.starringbox.staragent.core.llm.httpClient
import com.starringbox.staragent.core.llm.httpErrorMessage
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
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
 * 火山方舟 **Agent Plan** 那套语音接口。
 *
 * 先说清楚它和另外两套的关系，因为凭据不通用、混填只会得到鉴权失败：
 *
 * | | 凭据 | 识别 | 合成 |
 * | --- | --- | --- | --- |
 * | **本类：Agent Plan** | 方舟发的 Agent Plan Key | `/api/v3/plan/sauc/…`，WebSocket | `/api/v3/plan/tts/…` |
 * | 豆包语音新版控制台 | 语音控制台单独建的 Key | `/api/v3/auc/…`，一次 POST | `/api/v3/tts/unidirectional` |
 * | 豆包语音旧控制台 | APP ID + Access Token | 未接 | `/api/v1/tts` |
 *
 * Agent Plan 的 Key **不能**去调普通语音控制台那两条——所以这里全都是 `/plan/` 路径。
 *
 * 识别只有 WebSocket 一条路，没有 HTTP 版本。帧格式是：4 字节协议头、
 * 可选 sequence、4 字节大端 payload 长度、payload。这里用协议允许的**不压缩**模式，
 * 省掉在 Kotlin/Native 上再带一套 gzip。合成倒是个普通 HTTP，只是路径带 `/plan/`。
 *
 * ⚠️ 形状按官方文档与字节生态里的调用样例写，**没有拿真 Key 跑通过**。
 * 协议里能离线验的那部分（帧的编解码）有测试兜底，见 `VolcSpeechTest`。
 */
internal object VolcSpeech {

    /** 识别端点。`baseUrl` 是 `https://…`，用之前要换成 `wss://`。 */
    const val ASR_PATH: String = "/api/v3/plan/sauc/bigmodel_nostream"

    /** 合成端点。同样是普通 HTTP，只是路径带 `/plan/`。 */
    const val TTS_PATH: String = "/api/v3/plan/tts/unidirectional"

    /** 合成的默认取样率。播放侧只读 WAV 头，不依赖它。 */
    const val SAMPLE_RATE: Int = 16_000

    /** 合成请求里的取样率。 */
    const val TTS_SAMPLE_RATE: Int = 24_000

    const val KEY_HEADER: String = "X-Api-Key"
    const val RESOURCE_HEADER: String = "X-Api-Resource-Id"
    const val REQUEST_ID_HEADER: String = "X-Api-Request-Id"
    const val CONNECT_ID_HEADER: String = "X-Api-Connect-Id"
    const val SEQUENCE_HEADER: String = "X-Api-Sequence"

    /** 音频按 200ms 一片发。PCM16 单声道：16000 × 2 ÷ 5。 */
    const val AUDIO_CHUNK_BYTES: Int = SAMPLE_RATE * 2 / 5

    // ── 帧协议 ──────────────────────────────────
    // 0x11 = 协议版本 1 + 头长 1×4 字节。这两个是二进制字面量，不是可调参数。
    const val PROTOCOL_HEADER: Int = 0x11

    /** 消息类型：客户端发的完整请求（带 JSON 参数）。 */
    const val MSG_FULL_REQUEST: Int = 1

    /** 消息类型：客户端发的纯音频片。 */
    const val MSG_AUDIO_REQUEST: Int = 2

    /** 消息类型：服务端回的完整响应。 */
    const val MSG_FULL_RESPONSE: Int = 9

    /** 消息类型：服务端回的错误。 */
    const val MSG_ERROR_RESPONSE: Int = 15

    /** flags 位：这一帧带 sequence。 */
    const val FLAG_HAS_SEQUENCE: Int = 1

    /** flags 位：这是最后一帧。 */
    const val FLAG_LAST: Int = 2

    /** 序列化方式：JSON。 */
    const val SERIALIZATION_JSON: Int = 1
}

/** 一次识别读出来的东西。静音要单独成一档——它不是错误，但也没有字。 */
internal sealed interface VolcReading {
    data class Text(val value: String) : VolcReading

    /** 服务端读完了，但一个字也没解出来。 */
    data object Silence : VolcReading
}

// ── 识别 ────────────────────────────────────────

/**
 * 识别：把整段 WAV 经 WebSocket 发过去，收完整段结果。
 *
 * 选 `bigmodel_nostream`（流式输入）而不是双向流式：调用方是**录完再发**，
 * 不需要边说边出字，这个模式正好对上 [VoiceStt] 一次请求一次结果的契约。
 */
internal suspend fun transcribeWithVolcArk(
    baseUrl: String,
    apiKey: String,
    resourceId: String,
    wav: ByteArray,
    language: String,
): VolcReading {
    val requestId = newRequestId()
    var resultText: String? = null

    try {
        httpClient().webSocket(
            urlString = baseUrl.trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://") + VolcSpeech.ASR_PATH,
            request = {
                header(VolcSpeech.KEY_HEADER, apiKey)
                header(VolcSpeech.RESOURCE_HEADER, resourceId)
                header(VolcSpeech.REQUEST_ID_HEADER, requestId)
                // 出问题时靠这两个 ID 去火山那边查这次连接
                header(VolcSpeech.CONNECT_ID_HEADER, requestId)
                header(VolcSpeech.SEQUENCE_HEADER, "-1")
            },
        ) {
            var sequence = 1
            send(Frame.Binary(true, encodeFullRequest(sequence++, language)))

            val chunks = wav.chunksOf(VolcSpeech.AUDIO_CHUNK_BYTES)
            chunks.forEachIndexed { index, chunk ->
                val last = index == chunks.lastIndex
                send(Frame.Binary(true, encodeAudioRequest(sequence++, chunk, last)))
            }

            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        val response = decodeResponse(frame.readBytes())
                        if (response.errorCode != null) {
                            throw LlmHttpException(
                                "识别失败（${response.errorCode}）：${response.payloadText}",
                            )
                        }
                        response.resultText?.takeIf { it.isNotBlank() }?.let { resultText = it }
                        if (response.isLast) break
                    }

                    is Frame.Close -> break

                    else -> Unit
                }
            }
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (http: LlmHttpException) {
        throw http
    } catch (t: Throwable) {
        // 握手失败（Key 不对、没开通）也走这里。消息里带上原文，别只说"连接失败"
        throw LlmHttpException("识别连接失败：${t.message ?: t::class.simpleName}")
    }

    val text = resultText?.trim().orEmpty()
    return if (text.isEmpty()) VolcReading.Silence else VolcReading.Text(text)
}

/**
 * 客户端发过去的完整请求：JSON 参数，说明音频是什么格式。
 *
 * `language` 留空就是不指定、由服务端自己判——传 `"auto"` 会被当成一个语言名发出去。
 */
internal fun encodeFullRequest(sequence: Int, language: String): ByteArray {
    val payload = buildJsonObject {
        putJsonObject("user") { put("uid", UID) }
        putJsonObject("audio") {
            // 原样发 WAV，所以格式、位深、声道都写死成我们录出来的那种
            put("format", "wav")
            put("codec", "raw")
            put("rate", VolcSpeech.SAMPLE_RATE)
            put("bits", 16)
            put("channel", 1)
            normalizeLanguage(language)?.let { put("language", it) }
        }
        putJsonObject("request") {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("enable_ddc", true)
            put("show_utterances", true)
        }
    }.toString().encodeToByteArray()

    return encodeFrame(
        messageType = VolcSpeech.MSG_FULL_REQUEST,
        flags = VolcSpeech.FLAG_HAS_SEQUENCE,
        sequence = sequence,
        payload = payload,
        serialization = VolcSpeech.SERIALIZATION_JSON,
    )
}

/**
 * 一片音频。
 *
 * 最后一帧有两处不同：flags 加上"最后"位，**sequence 取负数**——
 * 这是协议约定的收尾标记，漏掉它服务端会一直等下去，表现为"连上了但永远没有结果"。
 */
internal fun encodeAudioRequest(sequence: Int, audio: ByteArray, last: Boolean): ByteArray =
    encodeFrame(
        messageType = VolcSpeech.MSG_AUDIO_REQUEST,
        flags = if (last) VolcSpeech.FLAG_HAS_SEQUENCE or VolcSpeech.FLAG_LAST else VolcSpeech.FLAG_HAS_SEQUENCE,
        sequence = if (last) -sequence else sequence,
        payload = audio,
        // 音频是裸字节，不是 JSON
        serialization = 0,
    )

internal fun encodeFrame(
    messageType: Int,
    flags: Int,
    sequence: Int,
    payload: ByteArray,
    serialization: Int,
): ByteArray {
    val frame = ByteArray(12 + payload.size)
    frame[0] = VolcSpeech.PROTOCOL_HEADER.toByte()
    frame[1] = ((messageType shl 4) or flags).toByte()
    frame[2] = (serialization shl 4).toByte() // 低四位是压缩方式：0 = 不压缩
    frame[3] = 0
    frame.writeBigEndianInt(4, sequence)
    frame.writeBigEndianInt(8, payload.size)
    payload.copyInto(frame, 12)
    return frame
}

internal data class VolcAsrResponse(
    val sequence: Int?,
    val isLast: Boolean,
    val errorCode: Int?,
    val payloadText: String,
    val resultText: String?,
)

/**
 * 解一帧服务端的响应。
 *
 * 这里只认**不压缩**（`compression == 0`）：客户端没请求压缩，服务端就会用同样的方式回。
 * 万一它还是压了，**明确地报出来**比解出一堆乱码强——那种错会一路装成"识别结果很奇怪"。
 */
internal fun decodeResponse(frame: ByteArray): VolcAsrResponse {
    require(frame.size >= 8) { "返回了过短的数据帧（${frame.size} 字节）" }

    val headerSize = (frame[0].toInt() and 0x0f) * 4
    require(headerSize >= 4 && frame.size >= headerSize + 4) { "返回了无效的协议头" }

    val messageType = (frame[1].toInt() ushr 4) and 0x0f
    val flags = frame[1].toInt() and 0x0f
    val compression = frame[2].toInt() and 0x0f
    require(compression == 0) { "服务端回了当前客户端没有请求的压缩格式：$compression" }

    var offset = headerSize
    val sequence = if ((flags and VolcSpeech.FLAG_HAS_SEQUENCE) != 0) {
        frame.readBigEndianInt(offset).also { offset += 4 }
    } else {
        null
    }
    val isLast = (flags and VolcSpeech.FLAG_LAST) != 0

    val errorCode: Int?
    val payloadSize: Int
    when (messageType) {
        VolcSpeech.MSG_FULL_RESPONSE -> {
            errorCode = null
            payloadSize = frame.readBigEndianInt(offset)
            offset += 4
        }

        VolcSpeech.MSG_ERROR_RESPONSE -> {
            errorCode = frame.readBigEndianInt(offset)
            offset += 4
            payloadSize = frame.readBigEndianInt(offset)
            offset += 4
        }

        else -> throw IllegalArgumentException("返回了未知的消息类型：$messageType")
    }

    require(payloadSize >= 0 && offset + payloadSize <= frame.size) { "响应的 payload 长度无效" }
    val payloadText = frame.copyOfRange(offset, offset + payloadSize).decodeToString()

    val resultText = if (errorCode == null && payloadText.isNotBlank()) {
        runCatching {
            Json.parseToJsonElement(payloadText).jsonObject["result"]
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
        }.getOrNull()
    } else {
        null
    }

    return VolcAsrResponse(sequence, isLast, errorCode, payloadText, resultText)
}

/** `"auto"` 和空串都是"不指定"。传过去会被当成一个语言名，服务端不认识就报错。 */
private fun normalizeLanguage(language: String): String? = when (language.lowercase()) {
    "", "auto" -> null
    "zh", "zh-cn" -> "zh-CN"
    "en", "en-us" -> "en-US"
    "ja", "ja-jp" -> "ja-JP"
    else -> language
}

// ── 合成 ────────────────────────────────────────

/**
 * 合成：把整段文字发过去，把流式回来的音频拼成一整段 WAV。
 *
 * 和识别不同，这条是普通 HTTP——只不过路径也带 `/plan/`。
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
            put("sample_rate", VolcSpeech.TTS_SAMPLE_RATE)
            putJsonObject("audio_params") {
                // 要 wav：播放侧只认 WAV。PCM 要自己套 44 字节头、还得赌它是 s16le，
                // 赌错的症状是"能放，全是噪声"，比报错难查得多
                put("format", "wav")
            }
        }
    }.toString()

/**
 * 把流式响应拼成一段完整音频。
 *
 * 两种流都可能撞上——官方文档给的是 NDJSON（一行一个 JSON），有的端点回 SSE
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
        if (code != null && code != CODE_DATA && code != CODE_END) {
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

/** 事件里的 `code`：这一段有音频。 */
private const val CODE_DATA = "0"

/** 事件里的 `code`：正常结束。 */
private const val CODE_END = "20000000"

// ── 杂项 ────────────────────────────────────────

private const val UID = "staragent"

private fun ByteArray.looksLikeWav(): Boolean =
    size >= 44 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'A'.code.toByte() &&
        this[10] == 'V'.code.toByte() && this[11] == 'E'.code.toByte()

internal fun ByteArray.chunksOf(size: Int): List<ByteArray> {
    require(size > 0) { "分片大小必须为正" }
    return indices.step(size).map { start -> copyOfRange(start, minOf(start + size, this.size)) }
}

internal fun ByteArray.writeBigEndianInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.readBigEndianInt(offset: Int): Int {
    require(offset >= 0 && offset + 4 <= size) { "缺少 4 字节整数" }
    return ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)
}

/** 请求 ID：每次唯一，出问题时靠它去火山那边查这次调用。 */
private fun newRequestId(): String =
    "staragent-${Clock.System.now().toEpochMilliseconds()}-${(0..0xffffff).random()}"
