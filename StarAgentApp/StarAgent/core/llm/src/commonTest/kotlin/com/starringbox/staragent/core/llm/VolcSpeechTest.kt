package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.platform.voice.Wav
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 火山方舟 Agent Plan 那套语音接口的形状。
 *
 * 识别走的是 WebSocket **二进制帧**，所以这里验的多半是字节：帧头、大端序号、
 * payload 长度、最后一帧的负数序号。这些写错了都不会有编译错误，只会表现成
 * "连上了但永远没有结果"或"识别出来是乱码"——从现象反推非常费劲，值得钉住。
 *
 * 合成那一半仍是普通 HTTP，验的是 JSON 字段名与流式分片的拼装。
 *
 * ⚠️ 这一层测不到的是**请求头与 URL**（`X-Api-Key`、`X-Api-Resource-Id`、
 * `wss://…/api/v3/plan/sauc/bigmodel_nostream` 这些）——那要真连一次服务，
 * 而本机没有可用的 Agent Plan Key。那部分目前只能靠代码审阅。
 */
class VolcSpeechTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private fun wav(): ByteArray = Wav.wrap(ByteArray(64) { it.toByte() })

    // ── 帧编码 ──────────────────────────────────

    @Test
    fun theFrameHeaderCarriesTypeFlagsAndSerialization() {
        val frame = encodeFrame(
            messageType = VolcSpeech.MSG_FULL_REQUEST,
            flags = VolcSpeech.FLAG_HAS_SEQUENCE,
            sequence = 1,
            payload = "{}".encodeToByteArray(),
            serialization = VolcSpeech.SERIALIZATION_JSON,
        )

        assertEquals(0x11, frame[0].toInt() and 0xff, "协议版本 1 + 头长 1×4 字节")
        assertEquals(VolcSpeech.MSG_FULL_REQUEST, (frame[1].toInt() ushr 4) and 0x0f, "消息类型在高四位")
        assertEquals(VolcSpeech.FLAG_HAS_SEQUENCE, frame[1].toInt() and 0x0f, "flags 在低四位")
        assertEquals(VolcSpeech.SERIALIZATION_JSON, (frame[2].toInt() ushr 4) and 0x0f, "序列化方式在高四位")
        assertEquals(0, frame[2].toInt() and 0x0f, "不压缩：低四位必须是 0")
    }

    @Test
    fun theSequenceAndLengthAreBigEndian() {
        val payload = byteArrayOf(1, 2, 3)
        val frame = encodeFrame(VolcSpeech.MSG_AUDIO_REQUEST, VolcSpeech.FLAG_HAS_SEQUENCE, 258, payload, 0)

        // 258 = 0x00000102，大端就是 00 00 01 02
        assertEquals(0, frame[4].toInt())
        assertEquals(0, frame[5].toInt())
        assertEquals(1, frame[6].toInt())
        assertEquals(2, frame[7].toInt())

        assertEquals(0, frame[8].toInt())
        assertEquals(0, frame[9].toInt())
        assertEquals(0, frame[10].toInt())
        assertEquals(3, frame[11].toInt(), "长度是 payload 的字节数")
        assertContentEquals(payload, frame.copyOfRange(12, 15))
    }

    @Test
    fun theFullRequestDescribesTheAudioItIsAboutToSend() {
        val payload = parse(encodeFullRequest(1, "auto").copyOfRange(12, encodeFullRequest(1, "auto").size).decodeToString())

        val audio = payload["audio"]!!.jsonObject
        assertEquals("wav", audio["format"]!!.jsonPrimitive.content)
        assertEquals("raw", audio["codec"]!!.jsonPrimitive.content)
        assertEquals(16_000, audio["rate"]!!.jsonPrimitive.content.toInt())
        assertEquals(16, audio["bits"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, audio["channel"]!!.jsonPrimitive.content.toInt())
        assertEquals("bigmodel", payload["request"]!!.jsonObject["model_name"]!!.jsonPrimitive.content)
    }

    /**
     * `"auto"` 是**不发**的。
     *
     * 协议里留空才是"由服务端自己判"；把 `"auto"` 原样发过去会被当成一个语言名，
     * 服务端不认识就直接报错。
     */
    @Test
    fun anUnspecifiedLanguageIsOmittedRatherThanSentAsAuto() {
        assertNull(languageOf(encodeFullRequest(1, "auto")))
        assertNull(languageOf(encodeFullRequest(1, "")))
        assertEquals("zh-CN", languageOf(encodeFullRequest(1, "zh")))
        assertEquals("en-US", languageOf(encodeFullRequest(1, "en")))
    }

    /**
     * **最后一帧的 sequence 取负**。
     *
     * 这是协议约定的收尾标记。漏掉它服务端会一直等着下一片音频，
     * 表现是"WebSocket 连上了、音频也发了，但永远收不到结果"——最难查的那种。
     */
    @Test
    fun theLastAudioFrameIsFlaggedAndUsesANegativeSequence() {
        val chunk = ByteArray(8)

        val middle = encodeAudioRequest(sequence = 3, audio = chunk, last = false)
        assertEquals(VolcSpeech.FLAG_HAS_SEQUENCE, middle[1].toInt() and 0x0f, "中间帧只带序号，不带最后标记")
        assertEquals(3, middle.readBigEndianInt(4))

        val last = encodeAudioRequest(sequence = 4, audio = chunk, last = true)
        assertEquals(
            VolcSpeech.FLAG_HAS_SEQUENCE or VolcSpeech.FLAG_LAST,
            last[1].toInt() and 0x0f,
            "最后一帧要同时带序号位和最后位",
        )
        assertEquals(-4, last.readBigEndianInt(4), "最后一帧的序号取负")
    }

    /** 音频片是裸字节，不该按 JSON 标；标错了服务端会尝试解析它。 */
    @Test
    fun audioFramesAreMarkedAsRawBytesNotJson() {
        val frame = encodeAudioRequest(1, ByteArray(4), last = false)
        assertEquals(0, (frame[2].toInt() ushr 4) and 0x0f, "音频帧的序列化方式是 0")
    }

    /** 一段音频要按 200ms 切成多片，且**每一片都不能丢**。 */
    @Test
    fun audioIsCutIntoTwoHundredMillisecondChunks() {
        assertEquals(6_400, VolcSpeech.AUDIO_CHUNK_BYTES, "16kHz × 16 位 × 单声道 × 0.2 秒")

        val audio = ByteArray(VolcSpeech.AUDIO_CHUNK_BYTES * 2 + 100)
        val chunks = audio.chunksOf(VolcSpeech.AUDIO_CHUNK_BYTES)

        assertEquals(3, chunks.size)
        assertEquals(VolcSpeech.AUDIO_CHUNK_BYTES, chunks[0].size)
        assertEquals(VolcSpeech.AUDIO_CHUNK_BYTES, chunks[1].size)
        assertEquals(100, chunks[2].size, "最后一片是余数")
        assertContentEquals(audio, chunks.reduce { a, b -> a + b }, "拼回去要和原文一个字节不差")
    }

    // ── 帧解码 ──────────────────────────────────

    @Test
    fun aFullResponseCarriesTheRecognisedText() {
        val frame = serverFrame(
            messageType = VolcSpeech.MSG_FULL_RESPONSE,
            flags = VolcSpeech.FLAG_HAS_SEQUENCE or VolcSpeech.FLAG_LAST,
            sequence = 5,
            payload = """{"result":{"text":"你好"}}""".encodeToByteArray(),
        )

        val response = decodeResponse(frame)

        assertEquals("你好", response.resultText)
        assertEquals(5, response.sequence)
        assertTrue(response.isLast)
        assertNull(response.errorCode)
    }

    /**
     * 错误帧是**另一种消息类型**，错误码在 payload 前面单独占 4 字节。
     *
     * 当成普通响应解的话，会把错误码读成长度，然后解出一段不存在的东西。
     */
    @Test
    fun anErrorFrameIsReadAsAnErrorNotAsAResponse() {
        val frame = serverFrame(
            messageType = VolcSpeech.MSG_ERROR_RESPONSE,
            flags = VolcSpeech.FLAG_HAS_SEQUENCE,
            sequence = 1,
            errorCode = 45000001,
            payload = "quota exceeded".encodeToByteArray(),
        )

        val response = decodeResponse(frame)

        assertEquals(45000001, response.errorCode)
        assertEquals("quota exceeded", response.payloadText)
        assertNull(response.resultText, "错误帧里不该再去解 text")
    }

    /**
     * 服务端要是压了，**明确报出来**。
     *
     * 硬解的症状是"识别出来是一堆乱码"，而那时候早就看不到真正的原因了。
     */
    @Test
    fun aCompressedResponseIsRejectedInsteadOfDecodedAsGarbage() {
        val frame = serverFrame(
            messageType = VolcSpeech.MSG_FULL_RESPONSE,
            flags = 0,
            payload = ByteArray(8),
        )
        frame[2] = (VolcSpeech.SERIALIZATION_JSON shl 4 or 1).toByte() // 低四位 = gzip

        val error = assertFailsWith<IllegalArgumentException> { decodeResponse(frame) }

        assertTrue(error.message.orEmpty().contains("压缩"), "要直说是压缩格式没请求：${error.message}")
    }

    @Test
    fun anUnknownMessageTypeIsRejected() {
        val frame = serverFrame(messageType = 7, flags = 0, payload = ByteArray(4))

        assertFailsWith<IllegalArgumentException> { decodeResponse(frame) }
    }

    // ── 合成的请求体 ────────────────────────────

    @Test
    fun theTtsBodyNestsEverythingUnderReqParams() {
        val params = parse(volcTtsPayload("你好", "zh_female_vv_uranus_bigtts"))["req_params"]!!.jsonObject

        assertEquals("你好", params["text"]!!.jsonPrimitive.content)
        assertEquals("zh_female_vv_uranus_bigtts", params["speaker"]!!.jsonPrimitive.content)
        assertEquals(24_000, params["sample_rate"]!!.jsonPrimitive.content.toInt())
        // 要 wav：播放侧只认 WAV。PCM 要自己套 44 字节头，还得赌它是 s16le
        assertEquals("wav", params["audio_params"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun aMissingVoiceIsLeftOutRatherThanSentBlank() {
        assertNull(parse(volcTtsPayload("你好", null))["req_params"]!!.jsonObject["speaker"])
        assertNull(parse(volcTtsPayload("你好", "   "))["req_params"]!!.jsonObject["speaker"])
    }

    @Test
    fun textIsPassedThroughUntouched() {
        val tricky = "第一行\n第二行 \"带引号\""
        val payload = volcTtsPayload(tricky, "zh_female_vv_uranus_bigtts")

        assertEquals(tricky, parse(payload)["req_params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        // 直接断言"没有裸换行"比断言"含有 \n"结实——后者写成 Kotlin 字面量时容易被转义层数绕进去
        assertTrue(!payload.contains('\n'), "换行没转义，这样的 body 不是合法 JSON")
    }

    // ── 合成的流式响应 ──────────────────────────

    /**
     * **每片各自 base64，逐片解完再拼字节。**
     *
     * 头一片带 WAV 头，第二片是接着的裸字节——这就是真实流的样子。
     */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun everyTtsChunkIsDecodedOnItsOwnAndKeptInOrder() {
        val head = wav()
        val rest = byteArrayOf(4, 5, 6)
        val stream = """
            data: {"code":0,"data":"${Base64.Default.encode(head)}"}
            data: {"code":0,"data":"${Base64.Default.encode(rest)}"}
            data: {"code":20000000}

        """.trimIndent()

        assertContentEquals(head + rest, readVolcTtsStream(stream), "两片要按顺序接上，一个字节都不多不少")
    }

    /** 官方文档给的是 NDJSON（一行一个 JSON，没有 `data:` 前缀），这里也得认。 */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun aPlainNdjsonTtsStreamIsAcceptedToo() {
        val stream = """{"code":0,"data":"${Base64.Default.encode(wav())}"}"""

        assertEquals(wav().size, readVolcTtsStream(stream).size)
    }

    @Test
    fun aTtsErrorEventIsReportedWithItsCodeAndMessage() {
        val error = assertFailsWith<LlmHttpException> {
            readVolcTtsStream("""data: {"code":45000002,"message":"speaker not found"}""")
        }

        assertTrue(error.userMessage.contains("45000002"), "要带上业务码：${error.userMessage}")
        assertTrue(error.userMessage.contains("speaker not found"), "要带上它自己的说明：${error.userMessage}")
    }

    /** 拿回来的东西不是 WAV 就明说：格式参数万一被换掉，这比"能播，但是一片噪音"清楚得多。 */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun aTtsPayloadThatIsNotWavIsRejected() {
        val mp3ish = Base64.Default.encode(ByteArray(64) { 0x55 })

        val error = assertFailsWith<LlmHttpException> { readVolcTtsStream("""data: {"code":0,"data":"$mp3ish"}""") }

        assertTrue(error.userMessage.contains("不是 WAV"), "要直说拿回来的不是 WAV：${error.userMessage}")
    }

    @Test
    fun aTtsStreamWithNoAudioComesBackEmpty() {
        assertTrue(readVolcTtsStream("""data: {"code":20000000}""").isEmpty())
        assertTrue(readVolcTtsStream("<html>502</html>").isEmpty())
    }

    // ── 线上的名字 ──────────────────────────────

    /**
     * 路径与请求头名是照官方文档核过的，写错就整条不通——钉在这里防止手滑。
     *
     * 尤其是 `/plan/` 那一段：Agent Plan 的 Key **不能**去调普通语音控制台的
     * `/api/v3/auc/…` 与 `/api/v3/tts/…`，少了这一段就会安静地用错凭据。
     */
    @Test
    fun theWireNamesAreTheOnesThatWereVerified() {
        assertEquals("/api/v3/plan/sauc/bigmodel_nostream", VolcSpeech.ASR_PATH)
        assertEquals("/api/v3/plan/tts/unidirectional", VolcSpeech.TTS_PATH)
        assertTrue(VolcSpeech.ASR_PATH.contains("/plan/"), "识别是 Agent Plan 的专属端点")
        assertTrue(VolcSpeech.TTS_PATH.contains("/plan/"), "合成也是")

        assertEquals("X-Api-Key", VolcSpeech.KEY_HEADER)
        assertEquals("X-Api-Resource-Id", VolcSpeech.RESOURCE_HEADER)
        assertEquals("X-Api-Request-Id", VolcSpeech.REQUEST_ID_HEADER)
        assertEquals("X-Api-Connect-Id", VolcSpeech.CONNECT_ID_HEADER)
        assertEquals("X-Api-Sequence", VolcSpeech.SEQUENCE_HEADER)
    }

    // ── 辅助 ────────────────────────────────────

    private fun languageOf(frame: ByteArray): String? =
        parse(frame.copyOfRange(12, frame.size).decodeToString())["audio"]
            ?.jsonObject?.get("language")?.jsonPrimitive?.content

    /** 照服务端的样子拼一帧回来，用来验解码那一半。 */
    private fun serverFrame(
        messageType: Int,
        flags: Int,
        payload: ByteArray,
        sequence: Int? = null,
        errorCode: Int? = null,
    ): ByteArray {
        val hasSequence = sequence != null
        val effectiveFlags = if (hasSequence) flags or VolcSpeech.FLAG_HAS_SEQUENCE else flags
        val head = 4 + (if (hasSequence) 4 else 0) + (if (errorCode != null) 4 else 0) + 4
        val frame = ByteArray(head + payload.size)

        frame[0] = 0x11
        frame[1] = ((messageType shl 4) or effectiveFlags).toByte()
        frame[2] = (VolcSpeech.SERIALIZATION_JSON shl 4).toByte()
        frame[3] = 0

        var offset = 4
        sequence?.let { frame.writeBigEndianInt(offset, it); offset += 4 }
        errorCode?.let { frame.writeBigEndianInt(offset, it); offset += 4 }
        frame.writeBigEndianInt(offset, payload.size)
        offset += 4
        payload.copyInto(frame, offset)

        return frame
    }
}
