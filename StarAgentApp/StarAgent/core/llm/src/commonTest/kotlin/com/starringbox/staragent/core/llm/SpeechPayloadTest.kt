package com.starringbox.staragent.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 合成的请求体形状。
 *
 * 这份 JSON 只有一个地方能验证——真发出去。所以字段名在这里钉住：
 * 名字写错不会编译报错，只会在用户点"朗读"的时候换回一个 400。
 */
class SpeechPayloadTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun theBodyCarriesTheFourFieldsTheApiExpects() {
        val body = parse(speechPayload("glm-tts", "你好", "tongtong"))

        assertEquals("glm-tts", body["model"]?.jsonPrimitive?.content)
        assertEquals("你好", body["input"]?.jsonPrimitive?.content)
        assertEquals("tongtong", body["voice"]?.jsonPrimitive?.content)
        // 要 wav 不要默认的 pcm：播放侧两个平台都只能直接放 PCM/WAV
        assertEquals("wav", body["response_format"]?.jsonPrimitive?.content)
    }

    @Test
    fun aMissingVoiceIsLeftOutRatherThanSentBlank() {
        val body = parse(speechPayload("glm-tts", "你好", null))
        assertNull(body["voice"], "空音色不该当成一个空字符串发出去")

        val blank = parse(speechPayload("glm-tts", "你好", "   "))
        assertNull(blank["voice"])
    }

    @Test
    fun textIsPassedThroughUntouched() {
        // 换行和引号都要原样过去，别在拼 JSON 的路上被吃掉
        val tricky = "第一行\n第二行 \"带引号\""
        val payload = speechPayload("glm-tts", tricky, "tongtong")

        assertEquals(tricky, parse(payload)["input"]?.jsonPrimitive?.content)
        // 换行必须转义。直接断言"没有裸换行"比断言"含有 \n"结实——
        // 后者写成 Kotlin 字面量时很容易被转义层数绕进去。
        assertFalse(payload.contains('\n'), "换行没转义，这样的 body 不是合法 JSON")
    }
}
