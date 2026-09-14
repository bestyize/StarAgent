package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.model.LlmProtocol
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 一次流式对话。两种协议对外暴露同一个事件流，
 * 上层（core:data / 界面）不需要知道用的是哪一家。
 */
object ChatCompletion {

    fun stream(endpoint: LlmEndpoint, history: List<LlmTurn>): Flow<ChatStreamEvent> = flow {
        try {
            when (endpoint.protocol) {
                LlmProtocol.OpenAi -> streamOpenAi(endpoint, history, this)
                LlmProtocol.Anthropic -> streamAnthropic(endpoint, history, this)
            }
            emit(ChatStreamEvent.Completed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (http: LlmHttpException) {
            emit(ChatStreamEvent.Failed(http.userMessage))
        } catch (t: Throwable) {
            emit(ChatStreamEvent.Failed(describeConnectionFailure(t)))
        }
    }

    // ── OpenAI 兼容协议 ─────────────────────────────
    // DeepSeek / 通义 / 智谱 / Kimi / 多数自建端点都走这套：
    // POST {base}/chat/completions，Authorization: Bearer，SSE 形如 `data: {...}`，以 `data: [DONE]` 收尾。

    private suspend fun streamOpenAi(
        endpoint: LlmEndpoint,
        history: List<LlmTurn>,
        collector: FlowCollector<ChatStreamEvent>,
    ) {
        val url = endpoint.baseUrl.trimEnd('/') + "/chat/completions"
        val payload = buildJsonObject {
            put("model", endpoint.model)
            put("stream", true)
            putJsonArray("messages") {
                endpoint.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                    addJsonObject {
                        put("role", "system")
                        put("content", prompt)
                    }
                }
                history.forEach { turn ->
                    addJsonObject {
                        put("role", turn.role.wire())
                        putOpenAiContent(turn)
                    }
                }
            }
        }.toString()

        httpClient().preparePost(url) {
            header(HttpHeaders.Authorization, "Bearer ${endpoint.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.execute { response ->
            ensureSuccess(response)
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val chunk = line.removePrefix("data:").trim()
                if (chunk.isEmpty()) continue
                if (chunk == "[DONE]") break

                val text = runCatching {
                    Json.parseToJsonElement(chunk)
                        .jsonObject["choices"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("delta")
                        ?.jsonObject
                        ?.get("content")
                        .asTextOrNull()
                }.getOrNull()

                if (!text.isNullOrEmpty()) collector.emit(ChatStreamEvent.Delta(text))
            }
        }
    }

    // ── Anthropic 原生协议 ──────────────────────────
    // POST {base}/messages，x-api-key 鉴权，system 是顶层字段，
    // SSE 按 event 类型区分，正文在 content_block_delta.delta.text。

    private suspend fun streamAnthropic(
        endpoint: LlmEndpoint,
        history: List<LlmTurn>,
        collector: FlowCollector<ChatStreamEvent>,
    ) {
        val url = endpoint.baseUrl.trimEnd('/') + "/messages"
        val payload = buildJsonObject {
            put("model", endpoint.model)
            put("max_tokens", 4096)
            put("stream", true)
            endpoint.systemPrompt?.takeIf { it.isNotBlank() }?.let { put("system", it) }
            putJsonArray("messages") {
                history.forEach { turn ->
                    addJsonObject {
                        put("role", turn.role.wire())
                        putAnthropicContent(turn)
                    }
                }
            }
        }.toString()

        httpClient().preparePost(url) {
            header("x-api-key", endpoint.apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.execute { response ->
            ensureSuccess(response)
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val chunk = line.removePrefix("data:").trim()
                if (chunk.isEmpty()) continue

                val event = runCatching { Json.parseToJsonElement(chunk).jsonObject }.getOrNull() ?: continue
                when (event["type"].asTextOrNull()) {
                    "content_block_delta" -> {
                        val text = event["delta"]?.jsonObject?.get("text").asTextOrNull()
                        if (!text.isNullOrEmpty()) collector.emit(ChatStreamEvent.Delta(text))
                    }

                    "message_stop" -> break

                    "error" -> {
                        val detail = event["error"]?.jsonObject?.get("message").asTextOrNull()
                        throw LlmHttpException(detail ?: "模型返回了错误")
                    }
                }
            }
        }
    }

    // ── 公共部分 ────────────────────────────────────

}

/** 带用户可读文案的协议层错误，直接展示给用户。 */
class LlmHttpException(val userMessage: String) : Exception(userMessage)

/** 非 2xx 一律翻成用户能看懂的话；语音那边也走这里，两处文案才一致。 */
internal suspend fun ensureSuccess(response: HttpResponse) {
    if (response.status.isSuccess()) return
    val raw = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
    throw LlmHttpException(httpErrorMessage(response.status.value, raw))
}

private fun LlmRole.wire(): String = if (this == LlmRole.User) "user" else "assistant"

/**
 * OpenAI 兼容系的图片写法：content 从字符串变成块数组，图片是 `image_url` + **data URL**。
 *
 * DeepSeek、通义千问、智谱、Kimi、MiniMax、小米 MiMo、火山、OpenRouter 都认这一套，
 * 所以只需要这一份实现。没有图片时仍发纯字符串，减少无谓的格式变化。
 */
internal fun JsonObjectBuilder.putOpenAiContent(turn: LlmTurn) {
    if (turn.images.isEmpty()) {
        put("content", turn.text)
        return
    }
    putJsonArray("content") {
        turn.images.forEach { asset ->
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", asset.dataUrl()) }
            }
        }
        if (turn.text.isNotBlank()) {
            addJsonObject {
                put("type", "text")
                put("text", turn.text)
            }
        }
    }
}

/**
 * Anthropic 的图片写法与上面**完全不同**，这是接多模态时最容易踩的坑：
 * - 块类型是 `image`（不是 `image_url`）
 * - base64 必须是**裸串**，带上 `data:` 前缀会被判定为非法 Base64
 * - MIME 类型因此要单独放在 `media_type` 字段里
 */
internal fun JsonObjectBuilder.putAnthropicContent(turn: LlmTurn) {
    if (turn.images.isEmpty()) {
        put("content", turn.text)
        return
    }
    putJsonArray("content") {
        turn.images.forEach { asset ->
            addJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", asset.mimeType)
                    put("data", asset.base64())
                }
            }
        }
        if (turn.text.isNotBlank()) {
            addJsonObject {
                put("type", "text")
                put("text", turn.text)
            }
        }
    }
}

private fun JsonElement?.asTextOrNull(): String? {
    if (this == null || this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content
}

internal fun httpErrorMessage(status: Int, raw: String): String {
    val detail = runCatching {
        Json.parseToJsonElement(raw)
            .jsonObject["error"]
            ?.jsonObject
            ?.get("message")
            .asTextOrNull()
    }.getOrNull()

    val base = when (status) {
        401, 403 -> "API Key 无效或没有权限"
        404 -> "接口地址或模型名不对"
        429 -> "触发限流，稍后再试"
        in 500..599 -> "服务端异常（HTTP $status）"
        else -> "请求失败（HTTP $status）"
    }
    return if (detail.isNullOrBlank()) base else "$base：$detail"
}

internal fun describeConnectionFailure(t: Throwable): String {
    val name = t::class.simpleName ?: "未知错误"
    val detail = t.message?.takeIf { it.isNotBlank() }
    return if (detail == null) "连接失败（$name）" else "连接失败：$detail"
}
