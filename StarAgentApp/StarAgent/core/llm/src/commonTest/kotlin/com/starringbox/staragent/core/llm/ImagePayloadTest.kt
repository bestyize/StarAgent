package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.media.MediaAsset
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 两家协议的图片写法差别很细——块类型不同、base64 要不要带 `data:` 前缀也不同，
 * 而且**写错了只有真发出去才会知道**。这里把两种 payload 的形状钉住，
 * 免得改动协议时无声地把它弄坏。
 */
class ImagePayloadTest {

    private val onePixelPng = MediaAsset(
        id = "a1",
        mimeType = "image/png",
        bytes = byteArrayOf(1, 2, 3, 4),
    )

    private fun turnWithImage() = LlmTurn(
        role = LlmRole.User,
        text = "这是什么",
        images = listOf(onePixelPng),
    )

    @Test
    fun openAiUsesImageUrlWithDataUrl() {
        val payload = buildJsonObject {
            put("role", "user")
            putOpenAiContent(turnWithImage())
        }

        val blocks = payload["content"]!!.jsonArray
        assertEquals(2, blocks.size, "应当是「图片块 + 文字块」两块")

        val image = blocks[0].jsonObject
        assertEquals("image_url", image["type"]!!.jsonPrimitive.content)

        val url = image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(
            url.startsWith("data:image/png;base64,"),
            "OpenAI 兼容系要求 data URL，实际是 ${url.take(24)}",
        )

        assertEquals("text", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicUsesRawBase64WithoutDataPrefix() {
        val payload = buildJsonObject {
            put("role", "user")
            putAnthropicContent(turnWithImage())
        }

        val blocks = payload["content"]!!.jsonArray
        val image = blocks[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)

        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)

        val data = source["data"]!!.jsonPrimitive.content
        // 这条是最容易踩的：Anthropic 拿到带 data: 前缀的串会判为非法 Base64 直接 400
        assertFalse(
            data.startsWith("data:"),
            "Anthropic 必须收裸 base64，实际是 ${data.take(24)}",
        )
        assertTrue(data.isNotBlank())
    }

    @Test
    fun textOnlyTurnStaysAPlainString() {
        val plain = LlmTurn(LlmRole.User, "你好")

        // 没有图片时两家都退化成纯字符串，不做无谓的格式变化
        assertEquals("你好", buildJsonObject { putOpenAiContent(plain) }["content"]!!.jsonPrimitive.content)
        assertEquals("你好", buildJsonObject { putAnthropicContent(plain) }["content"]!!.jsonPrimitive.content)
    }
}
