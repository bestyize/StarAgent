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
 * 火山方舟那套语音接口的形状。
 *
 * 这两份 JSON 和这一串流**只有一个地方能验证**——真发出去。所以字段名在这里钉住：
 * 名字写错不会编译报错，只会在用户点录音或朗读的时候换回一个看不懂的失败。
 *
 * 尤其是流式那段拼装：**每片音频是各自独立 base64 的**，把 base64 字符串首尾相接
 * 再解一次是错的（前面每片都带 `=` 填充）。这个错在真机上的症状是"没声音"，
 * 光看现象推不出来。
 *
 * ⚠️ 这一层测不到的是**请求头与路径**（`X-Api-Key`、`X-Api-Resource-Id`、
 * `/api/v3/auc/bigmodel/recognize/flash` 这些）——那要起一个本地服务器把请求接住，
 * 而 `core:llm` 没有 jvmTest 源集。那部分目前只能靠代码审阅。
 */
class VolcSpeechTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private fun wav(): ByteArray = Wav.wrap(ByteArray(64) { it.toByte() })

    // ── 请求体 ──────────────────────────────────

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun theAsrBodyIsThreeLayersWithTheAudioInBase64() {
        val body = parse(volcAsrPayload(wav()))

        // 三层缺一层就是 400，而错误信息不会告诉你缺的是哪一层
        val audio = body["audio"]!!.jsonObject
        assertEquals("wav", audio["format"]!!.jsonPrimitive.content)
        val decoded = Base64.Default.decode(audio["data"]!!.jsonPrimitive.content)
        assertTrue(
            decoded.size >= 44 && String(decoded.copyOfRange(0, 4)) == "RIFF",
            "音频要作为 base64 WAV 放在 audio.data 里——这家收字节，不必先传对象存储",
        )

        assertEquals("bigmodel", body["request"]!!.jsonObject["model_name"]!!.jsonPrimitive.content)
        assertTrue(body["user"]!!.jsonObject.isNotEmpty(), "user 那一层不能省")
    }

    @Test
    fun theTtsBodyNestsEverythingUnderReqParams() {
        val body = parse(volcTtsPayload("你好", "zh_female_vv_uranus_bigtts"))
        val params = body["req_params"]!!.jsonObject

        assertEquals("你好", params["text"]!!.jsonPrimitive.content)
        assertEquals("zh_female_vv_uranus_bigtts", params["speaker"]!!.jsonPrimitive.content)
        assertEquals(24_000, params["sample_rate"]!!.jsonPrimitive.content.toInt())
        // 要 wav：播放侧只认 WAV。PCM 要自己套 44 字节头，还得赌它是 s16le
        assertEquals("wav", params["audio_params"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun aMissingVoiceIsLeftOutRatherThanSentBlank() {
        val params = parse(volcTtsPayload("你好", null))["req_params"]!!.jsonObject
        assertNull(params["speaker"], "空音色不该当成一个空字符串发出去")

        val blank = parse(volcTtsPayload("你好", "   "))["req_params"]!!.jsonObject
        assertNull(blank["speaker"])
    }

    @Test
    fun textIsPassedThroughUntouched() {
        val tricky = "第一行\n第二行 \"带引号\""
        val payload = volcTtsPayload(tricky, "zh_female_vv_uranus_bigtts")

        assertEquals(tricky, parse(payload)["req_params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        // 直接断言"没有裸换行"比断言"含有 \n"结实——后者写成 Kotlin 字面量时容易被转义层数绕进去
        assertTrue(!payload.contains('\n'), "换行没转义，这样的 body 不是合法 JSON")
    }

    // ── 流式拼装 ────────────────────────────────

    /**
     * **每片各自 base64，逐片解完再拼字节。**
     *
     * 头一片带 WAV 头，第二片是接着的裸字节——这就是真实流的样子。
     */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun everyChunkIsDecodedOnItsOwnAndKeptInOrder() {
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
    fun aPlainNdjsonStreamIsAcceptedToo() {
        val stream = """{"code":0,"data":"${Base64.Default.encode(wav())}"}"""

        assertEquals(wav().size, readVolcTtsStream(stream).size)
    }

    /**
     * 事件里的 `code` 才是成败——**别的都是错**，而这时 HTTP 状态码照样是 200。
     *
     * 漏了这条的现象是"没声音，但也没报错"。
     */
    @Test
    fun anErrorEventIsReportedWithItsCodeAndMessage() {
        val error = assertFailsWith<LlmHttpException> {
            readVolcTtsStream("""data: {"code":45000002,"message":"speaker not found"}""")
        }

        assertTrue(error.userMessage.contains("45000002"), "要带上业务码：${error.userMessage}")
        assertTrue(error.userMessage.contains("speaker not found"), "要带上它自己的说明：${error.userMessage}")
    }

    /** 拿回来的东西不是 WAV 就明说：格式参数万一被换掉，这比"能播，但是一片噪音"清楚得多。 */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun aPayloadThatIsNotWavIsRejected() {
        val mp3ish = Base64.Default.encode(ByteArray(64) { 0x55 })

        val error = assertFailsWith<LlmHttpException> { readVolcTtsStream("""data: {"code":0,"data":"$mp3ish"}""") }

        assertTrue(error.userMessage.contains("不是 WAV"), "要直说拿回来的不是 WAV：${error.userMessage}")
    }

    /** 没有任何音频时返回空数组而不是抛：调用方那里才说得清"合成出来是空的"。 */
    @Test
    fun aStreamWithNoAudioComesBackEmpty() {
        assertTrue(readVolcTtsStream("""data: {"code":20000000}""").isEmpty())
        assertTrue(readVolcTtsStream("<html>502</html>").isEmpty())
    }

    // ── 响应解析 ────────────────────────────────

    @Test
    fun theAsrTextComesFromResultText() {
        assertEquals("你好", readVolcAsrText("""{"result":{"text":"你好"}}"""))
        assertEquals("你好", readVolcAsrText("""{"result":{"text":"  你好  "}}"""), "首尾空白要去掉")
    }

    /**
     * 读不懂时给空串，**不要抛**——那会被外层当成网络故障，
     * 报给用户一个完全不相干的原因。空串会走"没有听清"。
     */
    @Test
    fun anUnreadableAsrReplyGivesBlankInsteadOfThrowing() {
        assertEquals("", readVolcAsrText("<html>502</html>"))
        assertEquals("", readVolcAsrText("""{"result":{}}"""))
        // result 不是对象时是"读不到"，不是崩
        assertEquals("", readVolcAsrText("""{"result":"nope"}"""))
    }

    /** 路径与响应头名是照官方文档核过的，写错就整条不通——钉在这里防止手滑。 */
    @Test
    fun theWireNamesAreTheOnesThatWereVerified() {
        assertEquals("/api/v3/auc/bigmodel/recognize/flash", VolcSpeech.ASR_PATH)
        assertEquals("/api/v3/tts/unidirectional/sse", VolcSpeech.TTS_PATH)
        assertEquals("X-Api-Key", VolcSpeech.KEY_HEADER)
        assertEquals("X-Api-Resource-Id", VolcSpeech.RESOURCE_HEADER)
        assertEquals("X-Api-Status-Code", VolcSpeech.STATUS_HEADER)
        // 成败是业务码，和 HTTP 状态码无关；20000003 是静音不是错误
        assertEquals("20000000", VolcSpeech.STATUS_OK)
        assertEquals("20000003", VolcSpeech.STATUS_SILENCE)
    }
}
