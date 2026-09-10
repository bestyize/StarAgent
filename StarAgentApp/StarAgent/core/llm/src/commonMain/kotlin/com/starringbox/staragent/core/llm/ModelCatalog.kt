package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.model.LlmProtocol
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 拉取某个服务商当前可用的模型列表。
 *
 * 两家的列表接口形状一致（GET {base}/models 返回 data[].id），
 * 差别只在鉴权头，所以一份实现就够。
 * 用户不必手打模型名——填好 Key 点一下就能拿到真实清单再挑。
 */
object ModelCatalog {

    suspend fun fetch(
        protocol: LlmProtocol,
        baseUrl: String,
        apiKey: String,
    ): Result<List<String>> = try {
        if (baseUrl.isBlank()) throw LlmHttpException("请先填写接口地址")
        if (apiKey.isBlank()) throw LlmHttpException("请先填写 API Key")

        val url = baseUrl.trimEnd('/') + "/models"
        val response = httpClient().get(url) {
            when (protocol) {
                LlmProtocol.OpenAi -> header(HttpHeaders.Authorization, "Bearer $apiKey")
                LlmProtocol.Anthropic -> {
                    header("x-api-key", apiKey)
                    header("anthropic-version", "2023-06-01")
                }
            }
        }

        if (!response.status.isSuccess()) {
            val raw = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            throw LlmHttpException(modelListErrorMessage(response.status.value, raw))
        }

        val raw = response.bodyAsText()
        val ids = runCatching {
            Json.parseToJsonElement(raw)
                .jsonObject["data"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject["id"].asText() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.sorted()
        }.getOrNull().orEmpty()

        if (ids.isEmpty()) throw LlmHttpException("接口没有返回任何模型，请确认地址是否正确")
        Result.success(ids)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (http: LlmHttpException) {
        Result.failure(http)
    } catch (t: Throwable) {
        Result.failure(LlmHttpException(connectionFailure(t)))
    }
}

private fun modelListErrorMessage(status: Int, raw: String): String {
    val detail = runCatching {
        Json.parseToJsonElement(raw)
            .jsonObject["error"]
            ?.jsonObject
            ?.get("message")
            .asText()
    }.getOrNull()

    val base = when (status) {
        401, 403 -> "API Key 无效或没有权限"
        404 -> "这个地址没有 models 接口，请检查接口地址"
        429 -> "触发限流，稍后再试"
        in 500..599 -> "服务端异常（HTTP $status）"
        else -> "获取失败（HTTP $status）"
    }
    return if (detail.isNullOrBlank()) base else "$base：$detail"
}

private fun connectionFailure(t: Throwable): String {
    val detail = t.message?.takeIf { it.isNotBlank() } ?: (t::class.simpleName ?: "未知错误")
    return "连接失败：$detail"
}

private fun JsonElement?.asText(): String? {
    if (this == null || this is JsonNull) return null
    return (this as? JsonPrimitive)?.content
}
