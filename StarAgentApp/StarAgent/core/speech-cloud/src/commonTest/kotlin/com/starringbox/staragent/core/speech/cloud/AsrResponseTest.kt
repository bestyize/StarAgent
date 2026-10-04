package com.starringbox.staragent.core.speech.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 识别响应怎么读，以及**哪种结果不该交给用户**。
 *
 * 守的是一个具体现象：什么都不说，输入框里冒出一句 `Thank you.`。
 * Whisper 是必须解码出字来的模型，没有"什么都没听到"这个输出，
 * 静音进去它就给训练语料里最常跟在无声后面的那句字幕。
 * 服务端自己知道这事——`no_speech_prob` 就是它的自白，我们只是把它读出来。
 */
class AsrResponseTest {

    /** Groq 的 `whisper-large-v3-turbo` 对着一段静音真给出来的形状。 */
    private val silenceResponse = """
        {
          "task": "transcribe",
          "language": "english",
          "duration": 2.0,
          "text": "Thank you.",
          "segments": [
            {
              "id": 0, "start": 0.0, "end": 2.0, "text": "Thank you.",
              "avg_logprob": -0.82, "compression_ratio": 0.7, "no_speech_prob": 0.94
            }
          ]
        }
    """.trimIndent()

    @Test
    fun aThankYouOnSilenceIsNotHandedToTheUser() {
        val reading = readAsrResponse(silenceResponse)

        assertEquals("Thank you.", reading.text, "文本照样读出来——判断归判断，别把读取也一起省掉")
        assertTrue(reading.looksLikeSilence, "服务端都说了 94% 没人说话，这句 Thank you 是它编的")
    }

    @Test
    fun realSpeechIsKept() {
        val reading = readAsrResponse(
            """
            {
              "text": "帮我把这段话整理一下",
              "segments": [
                {
                  "text": "帮我把这段话整理一下",
                  "avg_logprob": -0.21, "no_speech_prob": 0.02
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("帮我把这段话整理一下", reading.text)
        assertFalse(reading.looksLikeSilence)
    }

    /**
     * 说得轻、离得远的时候，`no_speech_prob` 会偏高但字是解得出来的
     * （`avg_logprob` 不低）。这种要放过去——把说话轻的人挡在门外
     * 比放过一句 Thank you 糟得多。
     */
    @Test
    fun aQuietSpeakerIsNotMistakenForSilence() {
        val reading = readAsrResponse(
            """
            {
              "text": "好",
              "segments": [
                { "text": "好", "avg_logprob": -0.35, "no_speech_prob": 0.7 }
              ]
            }
            """.trimIndent()
        )

        assertFalse(
            reading.looksLikeSilence,
            "拿不准有没有人说话，但对解出来的字很有底——这时候该信字",
        )
    }

    /** 有一段是确定的人声，整句就该留着，哪怕前后各挂了一段静音。 */
    @Test
    fun oneConfidentSegmentSavesTheWholeSentence() {
        val reading = readAsrResponse(
            """
            {
              "text": "开始 录音 结束",
              "segments": [
                { "avg_logprob": -1.4, "no_speech_prob": 0.96 },
                { "avg_logprob": -0.19, "no_speech_prob": 0.03 },
                { "avg_logprob": -1.6, "no_speech_prob": 0.91 }
              ]
            }
            """.trimIndent()
        )

        assertFalse(reading.looksLikeSilence, "中间那段是真话，不能因为两头静音把它一起丢掉")
    }

    /**
     * 服务商没支持 `verbose_json`、退回成普通 `{"text": ...}` 的时候，
     * 文本要照样读得出来，而且**不能**因为没有概率就当成静音。
     */
    @Test
    fun aPlainJsonResponseStillReadsAndIsNeverJudged() {
        val reading = readAsrResponse("""{"text":"你好"}""")

        assertEquals("你好", reading.text)
        assertTrue(reading.segments.isEmpty())
        assertFalse(reading.looksLikeSilence, "没有概率就是没有依据，不该下判断")
    }

    /** 响应不成形时给一份空的，让上面走"没有听清"，而不是抛出去变成"网络故障"。 */
    @Test
    fun malformedShapesDoNotThrow() {
        assertEquals("", readAsrResponse("not json at all").text)
        assertEquals("", readAsrResponse("[]").text)
        // 字段类型和文档不一致：segments 是个字符串
        val odd = readAsrResponse("""{"text":"在","segments":"nope"}""")
        assertEquals("在", odd.text)
        assertTrue(odd.segments.isEmpty())
    }

    /** `no_speech_prob` 缺失的段没有判断依据，直接不计入。 */
    @Test
    fun segmentsWithoutProbabilitiesAreIgnored() {
        val reading = readAsrResponse(
            """{"text":"在","segments":[{"text":"在","avg_logprob":-0.3}]}"""
        )

        assertTrue(reading.segments.isEmpty())
        assertFalse(reading.looksLikeSilence)
    }

    // ── 什么时候才多要这份概率 ──────────────────

    /**
     * `verbose_json` 不是通用值，不能无条件带上：OpenAI 自己的
     * gpt-4o-transcribe 会直接报「not compatible with model」，
     * 智谱 GLM-ASR 文档里的请求体也没有这个参数。
     * 而 `no_speech_prob` 本来就只有 Whisper 系才给。
     */
    @Test
    fun onlyWhisperIsAskedForProbabilities() {
        assertTrue(wantsSpeechProbabilities("whisper-large-v3-turbo"))
        assertTrue(wantsSpeechProbabilities("whisper-large-v3"))
        assertTrue(wantsSpeechProbabilities("distil-whisper-large-v3-en"))

        assertFalse(wantsSpeechProbabilities("glm-asr-2512"), "智谱不认这个参数，带上就是 400")
        assertFalse(wantsSpeechProbabilities("gpt-4o-transcribe"), "OpenAI 明确拒绝这个组合")
    }
}
