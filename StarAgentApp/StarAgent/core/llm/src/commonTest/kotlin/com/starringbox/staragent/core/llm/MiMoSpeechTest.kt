package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.platform.voice.PcmAudioChunk
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MiMoSpeechTest {
    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun asrAndSynthesisUseTheMiMoRootAndApiKeyHeader() = runTest {
        var url = ""
        var key = ""
        val client = HttpClient(MockEngine { request ->
            url = request.url.toString()
            key = request.headers["api-key"].orEmpty()
            respond(
                content = ByteReadChannel("""{"choices":[{"message":{"content":"你好"}}]}""".encodeToByteArray()),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        try {
            val endpoint = SpeechEndpoint("https://api.xiaomimimo.com/v1", " secret ")
            assertEquals("你好", transcribeWithMiMo(endpoint, "mimo-v2.5-asr", byteArrayOf(1, 2), client))
            assertEquals("https://api.xiaomimimo.com/v1/chat/completions", url)
            assertEquals("secret", key)

            var audioUrl = ""
            var audioKey = ""
            val audioClient = HttpClient(MockEngine { request ->
                    audioUrl = request.url.toString()
                    audioKey = request.headers["api-key"].orEmpty()
                    respond(
                        content = ByteReadChannel("""{"audio":{"data":"AQI="}}""".encodeToByteArray()),
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                })
            try {
                assertContentEquals(byteArrayOf(1, 2), synthesizeWithMiMo(endpoint, "mimo-v2.5-tts", "你好", audioClient))
                assertEquals("https://api.xiaomimimo.com/v1/chat/completions", audioUrl)
                assertEquals("secret", audioKey)
            } finally {
                audioClient.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun streamingConsumesSseFromTheResponseChannelAndEmitsPcm() = runTest {
        var requestedUrl = ""
        var requestedKey = ""
        var doneSent = false
        var callbackBeforeDone = false
        val body = ByteChannel(autoFlush = true)
        val audioDelivered = CompletableDeferred<Unit>()
        launch {
            body.writeStringUtf8("data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"AQIDBA==\"}},\"finish_reason\":null}]}\n\n")
            audioDelivered.await()
            body.writeStringUtf8("data: [DONE]\n\n")
            doneSent = true
            body.close()
        }
        val client = HttpClient(MockEngine { request ->
            requestedUrl = request.url.toString()
            requestedKey = request.headers["api-key"].orEmpty()
            respond(
                content = body,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        })
        val chunks = mutableListOf<PcmAudioChunk>()
        try {
            // MockEngine 的 IO 跑在真实 dispatcher 上，虚拟时钟不能替它判超时。
            withContext(Dispatchers.Default) { withTimeout(5_000) {
                streamWithMiMo(SpeechEndpoint("https://api.xiaomimimo.com/v1", "mimo-key"), "你好", {
                    callbackBeforeDone = !doneSent
                    chunks += it
                    audioDelivered.complete(Unit)
                }, client)
            } }
        } finally {
            client.close()
        }

        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", requestedUrl)
        assertEquals("mimo-key", requestedKey)
        assertTrue(callbackBeforeDone, "audio should be delivered while later SSE bytes are still pending")
        assertEquals(1, chunks.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), chunks.single().pcm16)
        assertEquals(24_000, chunks.single().sampleRate)
    }

    @Test
    fun aClosedSseBodyWithoutDoneIsReportedAsAnEarlyDisconnect() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                content = ByteReadChannel("data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"AQI=\"}},\"finish_reason\":null}]}\n\n".encodeToByteArray()),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        })
        try {
            val failure = try {
                streamWithMiMo(SpeechEndpoint("https://api.xiaomimimo.com/v1", "k"), "hello", {}, client)
                null
            } catch (error: IllegalStateException) {
                error
            }
            assertEquals("MiMo 音频连接提前断开", failure?.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun ttsPayloadEscapesSpecialTextAndUsesTheConfiguredVoiceAndWavFormat() {
        val text = "line 1\n\"你好\" \\ done"
        val payload = parse(mimoTtsPayload("mimo-v2.5-tts", text, streaming = false, voice = "voice-\"a"))

        assertEquals(text, payload["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("voice-\"a", payload["audio"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertEquals("wav", payload["audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertFalse(payload["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", mimoUrl(SpeechEndpoint("https://api.xiaomimimo.com/v1", "k"), "/chat/completions"))
    }

    @Test
    fun asrPayloadCarriesWavAsStructuredDataUrl() {
        val wav = byteArrayOf(0, 1, 2, 3)
        val payload = parse(mimoAsrPayload("mimo-v2.5-asr", wav))
        val audio = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["input_audio"]!!.jsonObject

        assertEquals("data:audio/wav;base64,AAECAw==", audio["data"]!!.jsonPrimitive.content)
        assertEquals("auto", payload["asr_options"]!!.jsonObject["language"]!!.jsonPrimitive.content)
        assertEquals("mimo-v2.5-asr", payload["model"]!!.jsonPrimitive.content)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun fullSynthesisResponseDecodesTheAudioField() {
        val encoded = Base64.encode(byteArrayOf(1, 2, 3, 4))
        val response = """{"choices":[{"message":{"audio":{"data":"$encoded"}}}]}"""

        assertContentEquals(byteArrayOf(1, 2, 3, 4), parseMiMoAudio(response))
    }

    @Test
    fun sseDecoderAccumulatesDataLinesAcrossEventBoundaries() {
        val decoder = MiMoSseDecoder()
        assertEquals(null, decoder.acceptLine(": keepalive"))
        assertEquals(null, decoder.acceptLine("data: {"))
        assertEquals(null, decoder.acceptLine("data:  \"choices\": [{\"delta\": {\"audio\": {\"data\": \"AQI=\"}},"))
        assertEquals(null, decoder.acceptLine("data: \"finish_reason\": null}]}"))
        val raw = decoder.acceptLine("")

        val event = decodeMiMoTtsEvent(raw!!)
        assertContentEquals(byteArrayOf(1, 2), event.audio)
        assertFalse(event.finished, "null finish_reason is an ordinary intermediate event")
        assertEquals("[DONE]", run {
            decoder.acceptLine("data: [DONE]")
            decoder.acceptLine("")
        })
    }

    @Test
    fun pcmAlignmentCarriesHalfSamplesAndRejectsTruncatedTail() {
        val alignment = Pcm16Alignment()
        assertContentEquals(byteArrayOf(1, 2), alignment.append(byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(3, 4), alignment.append(byteArrayOf(4)))
        alignment.finish()

        val truncated = Pcm16Alignment()
        assertContentEquals(byteArrayOf(), truncated.append(byteArrayOf(9)))
        assertFailsWith<IllegalStateException> { truncated.finish() }
    }

    @Test
    fun emittedPcmChunksAreBoundedAndReassembleExactly() = runTest {
        val pcm = ByteArray(MIMO_MAX_PCM_CHUNK_BYTES * 2 + 22) { (it and 0xff).toByte() }
        val chunks = mutableListOf<PcmAudioChunk>()

        assertTrue(emitMiMoPcm16(pcm) { chunks += it })
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.pcm16.size <= 12_000 && it.pcm16.size % 2 == 0 })
        assertTrue(chunks.all { it.sampleRate == 24_000 && it.channels == 1 })
        assertContentEquals(pcm, chunks.flatMap { it.pcm16.asIterable() }.toByteArray())
    }

    @Test
    fun errorEventsAndNonStopFinishReasonsAreRejected() {
        assertFailsWith<LlmHttpException> { decodeMiMoTtsEvent("""{"error":{"message":"bad key"}}""") }
        assertFailsWith<LlmHttpException> {
            decodeMiMoTtsEvent("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        }
    }
}
