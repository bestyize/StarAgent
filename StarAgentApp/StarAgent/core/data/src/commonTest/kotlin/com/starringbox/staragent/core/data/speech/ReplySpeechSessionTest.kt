package com.starringbox.staragent.core.data.speech

import com.starringbox.staragent.core.speech.PcmAudioChunk
import com.starringbox.staragent.core.speech.SpeechOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplySpeechSessionTest {
    @Test
    fun sentencesStayOrderedAndBoundaryFlushesTailWithoutJoiningRounds() = runTest {
        val synthesized = mutableListOf<String>()
        val played = mutableListOf<String>()
        val session = ReplySpeechSession(
            scope = backgroundScope,
            synthesize = { text, emit ->
                synthesized += text
                emit(chunk(text))
                SpeechOutcome.Ok(Unit)
            },
            play = { played += it.pcm16.decodeToString().trimEnd('\u0000') },
            drain = {},
            stopPlayback = {},
            onFailure = { error("unexpected speech error: $it") },
        )

        session.append("第一句。尾一")
        session.boundary()
        session.append("第二句！尾二")
        session.finish()

        assertEquals(listOf("第一句。", "尾一", "第二句！", "尾二"), synthesized)
        assertEquals(synthesized, played)
    }

    @Test
    fun splitCodeFencesHideTaskAndMemoryPayloadsButKeepNormalTailText() = runTest {
        val spoken = mutableListOf<String>()
        val played = mutableListOf<String>()
        val session = session(scope = backgroundScope, synthesized = spoken, played = played)

        session.append("普通句。`")
        session.append("``task\n{\"title\":\"不要朗读\"}\n``")
        session.append("`普通尾句。```memory\n{\"text\":\"也不要朗读\"}\n```")
        session.append("```task\nunclosed payload")
        session.boundary() // an unmatched fence is discarded and reset at a new turn
        session.append("恢复后的普通尾句。")
        session.finish()

        assertEquals(listOf("普通句。", "普通尾句。", "恢复后的普通尾句。"), spoken)
        assertEquals(spoken, played)
    }

    @Test
    fun slowPlaybackAppliesBoundedBackpressure() = runTest {
        val playStarted = CompletableDeferred<Unit>()
        val releasePlayer = CompletableDeferred<Unit>()
        val session = ReplySpeechSession(
            scope = backgroundScope,
            synthesize = { _, emit ->
                repeat(12) { emit(PcmAudioChunk(ByteArray(2), 16_000)) }
                SpeechOutcome.Ok(Unit)
            },
            play = {
                playStarted.complete(Unit)
                releasePlayer.await()
            },
            drain = {},
            stopPlayback = {},
            onFailure = { error("unexpected speech error: $it") },
        )

        val appending = launch { session.append("一。二。三。四。") }
        playStarted.await()
        runCurrent()
        assertFalse(appending.isCompleted, "sentence/audio queue capacity should eventually backpressure appends")

        releasePlayer.complete(Unit)
        appending.join()
        session.finish()
    }

    @Test
    fun synthesisFailureUnblocksLargeTextAndOnlyReportsOnce() = runTest {
        var failures = 0
        val session = ReplySpeechSession(
            scope = backgroundScope,
            synthesize = { _, _ -> SpeechOutcome.Failed("Piper/服务商失败") },
            play = {},
            drain = {},
            stopPlayback = {},
            onFailure = { failures++ },
        )

        val appending = launch { session.append("第一句。".repeat(1000)) }
        appending.join()
        session.append("此后再追加大量文字。".repeat(1000))
        session.finish()

        assertEquals(1, failures)
    }

    @Test
    fun stopUnblocksBackpressuredAppendWithoutCancellingParentScope() = runTest {
        val playStarted = CompletableDeferred<Unit>()
        val releasePlayer = CompletableDeferred<Unit>()
        var failureCount = 0
        var stopCount = 0
        val session = ReplySpeechSession(
            scope = backgroundScope,
            synthesize = { _, emit ->
                repeat(12) { emit(PcmAudioChunk(ByteArray(2), 16_000)) }
                SpeechOutcome.Ok(Unit)
            },
            play = {
                playStarted.complete(Unit)
                releasePlayer.await()
            },
            drain = {},
            stopPlayback = { stopCount++ },
            onFailure = { failureCount++ },
        )

        val appending = launch { session.append("第一句。第二句。第三句。第四句。") }
        playStarted.await()
        runCurrent()
        assertFalse(appending.isCompleted)

        session.stop()
        appending.join()
        session.finish()

        assertTrue(currentCoroutineContext().isActive, "stopping speech must leave the owning LLM scope active")
        assertEquals(0, failureCount)
        assertTrue(stopCount >= 1)
    }

    private fun chunk(text: String): PcmAudioChunk {
        val bytes = text.encodeToByteArray()
        return PcmAudioChunk(if (bytes.size % 2 == 0) bytes else bytes + byteArrayOf(0), 16_000)
    }

    private fun session(
        scope: kotlinx.coroutines.CoroutineScope,
        synthesized: MutableList<String>,
        played: MutableList<String>,
    ) = ReplySpeechSession(
        scope = scope,
        synthesize = { text, emit ->
            synthesized += text
            emit(chunk(text))
            SpeechOutcome.Ok(Unit)
        },
        play = { played.add(it.pcm16.decodeToString().trimEnd('\u0000')) },
        drain = {},
        stopPlayback = {},
        onFailure = { error("unexpected speech error: $it") },
    )
}
