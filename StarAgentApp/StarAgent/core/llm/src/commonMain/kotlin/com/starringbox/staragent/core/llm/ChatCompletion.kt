package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.ToolResult
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
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
        val openAiCalls = OpenAiToolCallAccumulator()
        val payload = buildJsonObject {
            put("model", endpoint.model)
            put("stream", true)
            if (endpoint.tools.isNotEmpty()) {
                put("tools", ToolWire.renderOpenAi(endpoint.tools))
            }
            put("messages", buildOpenAiMessages(endpoint, history))
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

                // 工具调用是跨帧拼的，这里只负责喂给累积器，流结束才 finish()。
                // 按约定传**一条裸的 tool_calls 条目**，自己迭代 choices[0].delta.tool_calls。
                runCatching {
                    Json.parseToJsonElement(chunk)
                        .jsonObject["choices"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("delta")
                        ?.jsonObject
                        ?.get("tool_calls")
                        ?.jsonArray
                }.getOrNull()?.forEach { entry ->
                    if (entry is JsonObject) openAiCalls.accept(entry)
                }
            }
            val ready = openAiCalls.finish()
            if (ready.isNotEmpty()) collector.emit(ChatStreamEvent.ToolCallsReady(ready))
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
        val anthropicCalls = AnthropicToolCallAccumulator()
        val payload = buildJsonObject {
            put("model", endpoint.model)
            put("max_tokens", 4096)
            put("stream", true)
            if (endpoint.tools.isNotEmpty()) {
                put("tools", ToolWire.renderAnthropic(endpoint.tools))
            }
            endpoint.systemPrompt?.takeIf { it.isNotBlank() }?.let { put("system", it) }
            put("messages", buildAnthropicMessages(history))
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
                if (handleAnthropicEvent(event, anthropicCalls, collector)) break
            }
        }
    }

    // ── 公共部分 ────────────────────────────────────

}

/**
 * 处理一条 Anthropic SSE 事件。返回 true 表示流该结束了（`message_stop`）。
 *
 * 从 [ChatCompletion.streamAnthropic] 的读流循环里抽出来，是为了让它**能被直接驱动**：
 * 工具的解析（开块 → 拼参数 → 收尾成 `ToolCallsReady`）是这一层最容易写错的地方，
 * 而它原本埋在 `execute { }` 里、非有真 HTTP 响应够不着，测不了。
 * 抽出来后测试喂一串手写事件就能覆盖，行为一字未改。
 *
 * 只负责"解析工具/转出发言"，不负责拼请求体——那个留在 [ChatCompletion.streamAnthropic]。
 */
internal suspend fun handleAnthropicEvent(
    event: JsonObject,
    calls: AnthropicToolCallAccumulator,
    collector: FlowCollector<ChatStreamEvent>,
): Boolean {
    when (event["type"].asTextOrNull()) {
        // 开一个 tool_use 块。input 在这里通常是 {}，
        // 真正的参数在后面的 input_json_delta 里分片到达。
        "content_block_start" -> {
            val contentBlock = event["content_block"]?.jsonObject
            if (contentBlock != null && contentBlock["type"].asTextOrNull() == "tool_use") {
                val index = event["index"].asIntOrNull() ?: 0
                calls.start(
                    index = index,
                    id = contentBlock["id"].asTextOrNull().orEmpty(),
                    name = contentBlock["name"].asTextOrNull().orEmpty(),
                )
            }
        }

        "content_block_delta" -> {
            val delta = event["delta"]?.jsonObject
            // 两种 delta：正文（text）和工具参数（input_json_delta）
            when (delta?.get("type").asTextOrNull()) {
                "input_json_delta" -> {
                    val index = event["index"].asIntOrNull() ?: 0
                    delta?.get("partial_json").asTextOrNull()?.let {
                        calls.acceptDelta(index, it)
                    }
                }

                else -> {
                    val text = delta?.get("text").asTextOrNull()
                    if (!text.isNullOrEmpty()) collector.emit(ChatStreamEvent.Delta(text))
                }
            }
        }

        "message_stop" -> {
            val ready = calls.finish()
            if (ready.isNotEmpty()) collector.emit(ChatStreamEvent.ToolCallsReady(ready))
            return true
        }

        "error" -> {
            val detail = event["error"]?.jsonObject?.get("message").asTextOrNull()
            throw LlmHttpException(detail ?: "模型返回了错误")
        }
    }
    return false
}

/**
 * 拼 OpenAI 的 `messages` 数组。从 [ChatCompletion.streamOpenAi] 的请求体里抽出来，
 * 理由同 [handleAnthropicEvent]：藏在 Ktor 的 `execute { }` 之前、又要发真请求才能验证，
 * 而这里恰恰是**两家回喂位置差异**的所在（结果走独立 `role:"tool"` 消息），最该被钉住。
 *
 * `system` 取 [LlmEndpoint.systemPrompt]；其余按 [history] 顺序渲染。
 */
internal fun buildOpenAiMessages(endpoint: LlmEndpoint, history: List<LlmTurn>): JsonArray =
    buildJsonArray {
        endpoint.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            addJsonObject {
                put("role", "system")
                put("content", prompt)
            }
        }
        history.forEach { turn ->
            // 工具结果是**独立消息**：role 是 "tool"，不是把结果塞进 user 轮。
            // 这是 OpenAI 跟 Anthropic 的第二处结构差异。
            if (turn.toolResults.isNotEmpty()) {
                turn.toolResults.forEach { result ->
                    addJsonObject { putOpenAiToolResult(result) }
                }
                // continue：这条 turn 到此为止，**不再**顺带渲染成普通 user 消息。
                // 少这个 return，同一批结果会既发 role:"tool" 又发 user，模型会看到两份。
                return@forEach
            }
            addJsonObject {
                put("role", turn.role.wire())
                putOpenAiContent(turn)
                putOpenAiToolTurns(turn)
            }
        }
    }

/**
 * 拼 Anthropic 的 `messages` 数组。与 OpenAI 版最大的差别：工具结果不在这里单独成消息，
 * 而是装进一条 **user 消息**的 `tool_result` 块里（见 [putAnthropicToolBlocks]）。
 *
 * Anthropic 的 `system` 是**顶层字段**，不在 messages 里，所以这里只看 [history]。
 */
internal fun buildAnthropicMessages(history: List<LlmTurn>): JsonArray =
    buildJsonArray {
        history.forEach { turn ->
            if (turn.toolResults.isNotEmpty()) {
                // Anthropic 的工具结果装在 **user 消息**的 tool_result 块里，
                // 跟 OpenAI 的独立 role:"tool" 消息不是一回事。
                // 这里同样要 return：结果轮不再顺带渲染成普通消息。
                addJsonObject {
                    put("role", "user")
                    putAnthropicToolBlocks(turn)
                }
                return@forEach
            }
            addJsonObject {
                put("role", turn.role.wire())
                putAnthropicContent(turn)
                putAnthropicToolBlocks(turn)
            }
        }
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

/**
 * OpenAI 兼容系的**工具调用**部分：assistant 轮把调用挂在消息上的 `tool_calls` 数组里，
 * `arguments` 回成**字符串**。这跟 Anthropic 正好相反（那边是对象），是两家最容易写反的一处。
 *
 * 这里**只**渲染 assistant 侧带出的调用。工具结果不是这个数组的一部分——
 * 它在 OpenAI 那侧是**独立消息**，由 [putOpenAiToolResult] 生成。
 */
internal fun JsonObjectBuilder.putOpenAiToolTurns(turn: LlmTurn) {
    if (turn.toolCalls.isEmpty()) return
    putJsonArray("tool_calls") {
        turn.toolCalls.forEach { call ->
            addJsonObject {
                put("id", call.id)
                put("type", "function")
                putJsonObject("function") {
                    put("name", call.name)
                    put("arguments", call.arguments)
                }
            }
        }
    }
}

/**
 * OpenAI 兼容系的**工具结果**形状：一条 `role:"tool"` 的**独立消息**，
 * 用 `tool_call_id` 跟发起它的那次调用对上。
 *
 * 这是 OpenAI 与 Anthropic 的第二处结构差异：那边结果装在 **user 消息**的
 * `tool_result` 块里（见 [putAnthropicToolBlocks]），不是独立消息。写反了模型会
 * 莫名其妙不调工具甚至直接 400，很难从现象反推原因，所以单独拎出来钉住。
 */
internal fun JsonObjectBuilder.putOpenAiToolResult(result: ToolResult) {
    put("role", "tool")
    put("tool_call_id", result.callId)
    put("content", result.content)
}

/**
 * Anthropic 的工具部分。两处跟 OpenAI 不同：
 *
 * 1. assistant 轮的工具调用是 content 里的 `tool_use` **块**，`input` 是**对象**
 * 2. 工具结果的块类型是 `tool_result`，带 `tool_use_id`（不是 `id`）
 */
internal fun JsonObjectBuilder.putAnthropicToolBlocks(turn: LlmTurn) {
    if (turn.toolCalls.isEmpty() && turn.toolResults.isEmpty()) return

    // JsonObjectBuilder 只有 put 没有读，借 put 会返回旧值这一点把已有 content 取出来
    // （取完立刻会被下面两个分支重新覆盖，不会真的留下 JsonNull）。
    val existing = put("content", JsonNull)

    // 已经因为图片变成块数组了就不再重复从零拼 content，改为把工具块**追加**进去——
    // 图片和工具块要能共存于一条消息，整块盖掉会丢图片。
    if (existing !is JsonArray) {
        putJsonArray("content") {
            if (turn.text.isNotBlank()) {
                addJsonObject {
                    put("type", "text")
                    put("text", turn.text)
                }
            }
            addAnthropicToolBlocks(turn)
        }
        return
    }

    // 已经有块了（图片路径）：把工具块追加进去。**调用与结果都要追加**——
    // 同一轮里带图片的 assistant 消息完全可以同时带着工具调用，只追加结果会把它悄悄丢掉。
    val merged = buildJsonArray {
        existing.forEach { add(it) }
        addAnthropicToolBlocks(turn)
    }
    put("content", merged)
}

/**
 * 把一轮里的工具调用/工具结果渲染成 Anthropic 的块。
 *
 * 抽出来是因为它前面有两条入口（新建块数组、往已有块数组里追加），
 * 两边必须产出**一模一样**的块，复制一份迟早会走样。
 */
private fun JsonArrayBuilder.addAnthropicToolBlocks(turn: LlmTurn) {
    turn.toolCalls.forEach { call ->
        addJsonObject {
            put("type", "tool_use")
            put("id", call.id)
            put("name", call.name)
            put("input", parseToolArguments(call.arguments))
        }
    }
    turn.toolResults.forEach { result ->
        addJsonObject {
            put("type", "tool_result")
            put("tool_use_id", result.callId)
            put("content", result.content)
            if (result.isError) put("is_error", true)
        }
    }
}

/**
 * 把模型给的参数串解析成对象，给 Anthropic 的 `input` 用。
 *
 * 解析不了就退回空对象——**这一层不报错**。真要报错也得让工具执行那一步去报，
 * 那里能把错误变成一条给模型看的结果；协议层报错只会让整轮请求失败。
 */
private fun parseToolArguments(raw: String): JsonElement =
    runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }) }
        .getOrElse { buildJsonObject { } }

internal fun JsonElement?.asTextOrNull(): String? {
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
