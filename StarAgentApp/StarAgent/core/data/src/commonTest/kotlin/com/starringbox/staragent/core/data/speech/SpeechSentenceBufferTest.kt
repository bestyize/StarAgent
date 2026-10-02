package com.starringbox.staragent.core.data.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeechSentenceBufferTest {
    @Test
    fun splitPunctuationAndTrailingFragmentsSurviveChunkBoundaries() {
        val buffer = SpeechSentenceBuffer()

        assertEquals(listOf("第一句。"), buffer.append("第一句。第"))
        assertEquals(listOf("第二句！"), buffer.append("二句！尾"))
        assertEquals("尾", buffer.finish())
        assertNull(buffer.finish())
    }

    @Test
    fun boundaryFlushesAndClearsThePreviousRound() {
        val buffer = SpeechSentenceBuffer()

        assertEquals(emptyList(), buffer.append("工具前文本"))
        assertEquals("工具前文本", buffer.boundary())
        assertEquals(listOf("工具后文本？"), buffer.append("工具后文本？"))
        assertNull(buffer.boundary())
    }

    @Test
    fun closingQuotesStayWithTheSentenceAndDecimalsDoNotSplit() {
        val buffer = SpeechSentenceBuffer()

        assertEquals(listOf("值是 3.14。", "他说：‘好！’"), buffer.append("值是 3.14。他说：‘好！’"))
        assertNull(buffer.finish())
    }
}
