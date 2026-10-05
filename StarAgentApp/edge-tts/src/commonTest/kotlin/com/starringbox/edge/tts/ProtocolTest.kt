package com.starringbox.edge.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun secMsGecMatchesTheWindowsTicksConstruction() {
        assertEquals(
            "7ECB79D14E3AA576D2D79E6D487A1388156D91E614B1BE11C64226A29BC8DD8C",
            EdgeTtsProtocol.secMsGec(unixSeconds = 0),
        )
    }

    @Test
    fun sha256MatchesTheStandardVector() {
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            Sha256.hexUpper("abc".encodeToByteArray()),
        )
    }

    @Test
    fun voiceNamesAreExpandedWithoutBreakingRegionalVoices() {
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (zh-CN-liaoning, XiaobeiNeural)",
            EdgeTtsProtocol.normalizeVoice("zh-CN-liaoning-XiaobeiNeural"),
        )
    }

    @Test
    fun ssmlEscapesTextAndRejectsSplittingEntities() {
        val text = "A & B < C"
        val ssml = EdgeTtsProtocol.ssml(EdgeTtsConfig(), text)
        assertTrue(ssml.contains("A &amp; B &lt; C"))
        assertTrue(!ssml.contains("A & B"))
    }

    @Test
    fun binaryFrameHeadersAndPayloadAreSeparatedByCrlf() {
        val header = "X-RequestId:abc\r\nContent-Type:audio/mpeg\r\nPath:audio"
        val payload = byteArrayOf(1, 2, 3, 4)
        val headerLength = 2 + header.length
        val frame = byteArrayOf((headerLength / 256).toByte(), (headerLength % 256).toByte()) +
            header.encodeToByteArray() +
            "\r\n".encodeToByteArray() +
            payload

        val parsed = parseBinaryAudioFrame(frame)
        assertEquals("audio", parsed.headers["Path"])
        assertEquals("audio/mpeg", parsed.headers["Content-Type"])
        assertTrue(parsed.data.contentEquals(payload))
    }

    @Test
    fun textFrameParsesPathAndPayload() {
        val parsed = parseTextResponse("Path:turn.end\r\nX-RequestId:abc\r\n\r\n{}")
        assertEquals("turn.end", parsed.path)
        assertEquals("{}", parsed.payload)
    }

    @Test
    fun chunkerKeepsUtf8TextIntact() {
        val chunks = Utf8TextChunker.split("你好世界😀", maxBytes = 4)
        assertTrue(chunks.isNotEmpty())
        assertEquals("你好世界😀", chunks.joinToString(""))
    }

    @Test
    fun configRejectsInvalidProsodyValues() {
        assertFailsWith<IllegalArgumentException> { EdgeTtsConfig(rate = "fast") }
        assertFailsWith<IllegalArgumentException> { EdgeTtsConfig(pitch = "high") }
    }
}
