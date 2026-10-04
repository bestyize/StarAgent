package com.starringbox.staragent.core.speech.cloud

import com.starringbox.staragent.core.llm.LlmHttpException
import com.starringbox.staragent.core.llm.ensureSuccess
import com.starringbox.staragent.core.llm.httpClient
import com.starringbox.staragent.core.speech.PcmAudioChunk
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MIMO_DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1"
private const val MIMO_DEFAULT_TTS_MODEL = "mimo-v2.5-tts"
private const val MIMO_DEFAULT_VOICE = "mimo_default"
private const val MIMO_SAMPLE_RATE = 24_000
internal const val MIMO_MAX_PCM_CHUNK_BYTES = 12_000 // 250 ms, mono PCM16LE.

internal suspend fun transcribeWithMiMo(
    endpoint: SpeechEndpoint,
    model: String,
    wav: ByteArray,
    client: HttpClient = httpClient(),
): String {
    val payload = mimoAsrPayload(model, wav)
    val response = client.preparePost(mimoUrl(endpoint, "/chat/completions")) {
        header("api-key", endpoint.apiKey.trim())
        header(HttpHeaders.Authorization, "Bearer ${endpoint.apiKey.trim()}")
        contentType(ContentType.Application.Json)
        setBody(payload)
    }.execute { response ->
        ensureSuccess(response)
        response.bodyAsBytes().decodeToString()
    }
    return parseMiMoTranscription(response)
}

internal suspend fun synthesizeWithMiMo(
    endpoint: SpeechEndpoint,
    model: String,
    text: String,
    client: HttpClient = httpClient(),
): ByteArray {
    val response = client.preparePost(mimoUrl(endpoint, "/chat/completions")) {
        header("api-key", endpoint.apiKey.trim())
        header(HttpHeaders.Authorization, "Bearer ${endpoint.apiKey.trim()}")
        contentType(ContentType.Application.Json)
        setBody(mimoTtsPayload(model, text, streaming = false, voice = endpoint.ttsVoice))
    }.execute { response ->
        ensureSuccess(response)
        response.bodyAsBytes().decodeToString()
    }
    return parseMiMoAudio(response)
}

/** MiMo emits 24 kHz mono PCM16LE in SSE data events. The response stays streamed end to end. */
internal suspend fun streamWithMiMo(
    endpoint: SpeechEndpoint,
    text: String,
    onAudio: suspend (PcmAudioChunk) -> Unit,
    client: HttpClient = httpClient(),
) {
    val model = endpoint.ttsModel?.takeIf { it.isNotBlank() } ?: MIMO_DEFAULT_TTS_MODEL
    client.preparePost(mimoUrl(endpoint, "/chat/completions")) {
        header("api-key", endpoint.apiKey.trim())
        header(HttpHeaders.Authorization, "Bearer ${endpoint.apiKey.trim()}")
        contentType(ContentType.Application.Json)
        setBody(mimoTtsPayload(model, text, streaming = true, voice = endpoint.ttsVoice))
    }.execute { response ->
        ensureSuccess(response)
        val channel = response.bodyAsChannel()
        val sse = MiMoSseDecoder()
        val alignment = Pcm16Alignment()
        var completed = false
        var received = false

        suspend fun consume(data: String): Boolean {
            if (data.trim() == "[DONE]") return true
            val event = decodeMiMoTtsEvent(data)
            val pcm = alignment.append(event.audio)
            if (emitMiMoPcm16(pcm, onAudio)) received = true
            return event.finished
        }

        while (!completed) {
            val line = channel.readLine() ?: break
            val data = sse.acceptLine(line) ?: continue
            completed = consume(data)
        }
        if (!completed) sse.finish()?.let { completed = consume(it) }
        check(completed) { "MiMo 音频连接提前断开" }
        alignment.finish()
        check(received) { "MiMo 没有返回有效音频" }
    }
}

internal fun mimoUrl(endpoint: SpeechEndpoint, path: String): String {
    val configured = endpoint.baseUrl.trim().trimEnd('/')
    var root = configured.ifBlank { MIMO_DEFAULT_BASE_URL }
        .removeSuffix("/chat/completions")
        .removeSuffix("/audio/speech")
        .removeSuffix("/audio/transcriptions")
    if (root.contains("xiaomimimo.com") && !root.endsWith("/v1")) {
        root = "$root/v1"
    }
    return root + path
}

internal fun mimoAsrPayload(model: String, wav: ByteArray): String = buildJsonObject {
    put("model", model)
    put("messages", buildJsonArray {
        add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "input_audio")
                    put("input_audio", buildJsonObject {
                        put("data", "data:audio/wav;base64,${wav.encodeBase64()}")
                        put("format", "wav")
                    })
                })
            })
        })
    })
    put("asr_options", buildJsonObject { put("language", "auto") })
    put("stream", false)
}.toString()

internal fun mimoTtsPayload(
    model: String,
    text: String,
    streaming: Boolean,
    voice: String? = null,
): String = buildJsonObject {
    val selectedVoice = when (voice?.trim()) {
        null, "", "mimo-default", "mimo_default" -> MIMO_DEFAULT_VOICE
        else -> voice.trim()
    }
    put("model", model)
    put("stream", streaming)
    putJsonArray("messages") {
        add(buildJsonObject {
            put("role", "user")
            put("content", "以自然清晰的语气朗读")
        })
        add(buildJsonObject {
            put("role", "assistant")
            put("content", text)
        })
    }
    putJsonObject("audio") {
        put("format", if (streaming) "pcm16" else "wav")
        put("voice", selectedVoice)
    }
}.toString()

internal fun parseMiMoTranscription(raw: String): String {
    val root = parseMiMoJson(raw)
    root.throwMiMoError()
    val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.get("message") as? JsonObject
    val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull
        ?: root["text"]?.let { (it as? JsonPrimitive)?.contentOrNull }
    return content?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw LlmHttpException("MiMo 没有返回识别文本")
}

@OptIn(ExperimentalEncodingApi::class)
internal fun parseMiMoAudio(raw: String): ByteArray {
    val root = parseMiMoJson(raw)
    root.throwMiMoError()
    val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.get("message") as? JsonObject
    val audio = (message?.get("audio") ?: root["audio"]) as? JsonObject
    val data = audio?.get("data")?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?: throw LlmHttpException("MiMo 没有返回语音数据")
    return try { Base64.decode(data) } catch (failure: IllegalArgumentException) {
        throw LlmHttpException("MiMo 返回的语音数据无法解析")
    }
}

internal data class MiMoTtsEvent(val audio: ByteArray = ByteArray(0), val finished: Boolean = false)

internal fun decodeMiMoTtsEvent(raw: String): MiMoTtsEvent {
    if (raw.isBlank()) return MiMoTtsEvent()
    if (raw.trim() == "[DONE]") return MiMoTtsEvent(finished = true)
    val root = parseMiMoJson(raw)
    root.throwMiMoError()
    val choice = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: return MiMoTtsEvent()
    val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
    if (finishReason != null && finishReason != "stop") {
        throw LlmHttpException("MiMo 音频未完整生成（$finishReason）")
    }
    val delta = choice["delta"] as? JsonObject
    val message = choice["message"] as? JsonObject
    val audio = (delta?.get("audio") ?: message?.get("audio")) as? JsonObject
    val encoded = audio?.get("data")?.let { (it as? JsonPrimitive)?.contentOrNull }
    val bytes = if (encoded.isNullOrEmpty()) ByteArray(0) else decodeMiMoBase64(encoded)
    // A null finish_reason is normal for intermediate chunks; only explicit stop completes an event stream.
    return MiMoTtsEvent(bytes, finishReason == "stop")
}

/** Incremental SSE frame reader. It buffers one event only and joins multi-line data fields per SSE rules. */
internal class MiMoSseDecoder {
    private val data = mutableListOf<String>()

    fun acceptLine(line: String): String? {
        val normalized = line.removeSuffix("\r")
        if (normalized.isEmpty()) return flush()
        if (normalized.startsWith(':')) return null
        val colon = normalized.indexOf(':')
        val field = if (colon < 0) normalized else normalized.substring(0, colon)
        if (field != "data") return null
        val value = if (colon < 0) "" else normalized.substring(colon + 1).removePrefix(" ")
        data += value
        return null
    }

    fun finish(): String? = flush()

    private fun flush(): String? {
        if (data.isEmpty()) return null
        return data.joinToString("\n").also { data.clear() }
    }
}

/** Network event boundaries need not coincide with 16-bit sample boundaries. */
internal class Pcm16Alignment {
    private var pending: Byte? = null

    fun append(bytes: ByteArray): ByteArray {
        val all = pending?.let { byteArrayOf(it) + bytes } ?: bytes
        pending = if (all.size % 2 == 1) all.last() else null
        return if (pending == null) all else all.copyOf(all.size - 1)
    }

    fun finish() {
        check(pending == null) { "MiMo 返回了不完整的 PCM16 采样" }
    }
}

internal suspend fun emitMiMoPcm16(
    pcm16: ByteArray,
    onAudio: suspend (PcmAudioChunk) -> Unit,
): Boolean {
    var emitted = false
    var offset = 0
    while (offset < pcm16.size) {
        val end = minOf(offset + MIMO_MAX_PCM_CHUNK_BYTES, pcm16.size)
        onAudio(PcmAudioChunk(pcm16.copyOfRange(offset, end), MIMO_SAMPLE_RATE, 1))
        emitted = true
        offset = end
    }
    return emitted
}

private fun parseMiMoJson(raw: String): JsonObject = try {
    Json.parseToJsonElement(raw).jsonObject
} catch (failure: Exception) {
    throw LlmHttpException("MiMo 返回格式无法解析")
}

private fun JsonObject.throwMiMoError() {
    val error = this["error"]?.takeIf { it != JsonNull } ?: return
    val message = (error as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?: (error as? JsonPrimitive)?.contentOrNull
    throw LlmHttpException(message?.takeIf { it.isNotBlank() } ?: "MiMo 语音请求失败")
}

@OptIn(ExperimentalEncodingApi::class)
private fun ByteArray.encodeBase64(): String = Base64.encode(this)

@OptIn(ExperimentalEncodingApi::class)
private fun decodeMiMoBase64(encoded: String): ByteArray = try {
    Base64.decode(encoded)
} catch (failure: IllegalArgumentException) {
    throw LlmHttpException("MiMo 返回的语音数据无法解析")
}
