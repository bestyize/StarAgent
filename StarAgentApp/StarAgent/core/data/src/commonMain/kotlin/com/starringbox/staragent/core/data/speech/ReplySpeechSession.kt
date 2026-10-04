package com.starringbox.staragent.core.data.speech

import com.starringbox.staragent.core.speech.PcmAudioChunk
import com.starringbox.staragent.core.speech.SpeechOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope

/** Pipelines LLM text into sequential sentence synthesis and bounded PCM playback. */
internal class ReplySpeechSession(
    scope: CoroutineScope,
    private val synthesize: suspend (String, suspend (PcmAudioChunk) -> Unit) -> SpeechOutcome<Unit>,
    private val play: suspend (PcmAudioChunk) -> Unit,
    private val drain: suspend () -> Unit,
    private val stopPlayback: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val parentJob = scope.coroutineContext[Job]
    private val sessionJob = SupervisorJob(parentJob)
    private val sessionScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + sessionJob)
    private val inputMutex = Mutex()
    private val sentenceBuffer = SpeechSentenceBuffer()
    private val fenceFilter = SpeechFenceFilter()
    private val sentences = Channel<String>(SENTENCE_QUEUE_CAPACITY)
    private val audio = Channel<PcmAudioChunk>(AUDIO_QUEUE_CAPACITY)

    private var inputClosed = false
    private var stopped = false
    private var failed = false
    private var failureReported = false
    private var playbackStopped = false
    private var completedNormally = false

    private val worker = sessionScope.launch {
        try {
            process()
        } catch (cancelled: CancellationException) {
            // Explicit stop and parent cancellation are control flow, not a speech error.
        } catch (failure: Throwable) {
            fail(failure.message ?: "语音播放失败")
        } finally {
            inputClosed = true
            sentences.cancel()
            audio.cancel()
            if (!completedNormally) stopPlaybackOnce()
            sessionJob.cancel()
        }
    }

    /** Adds raw LLM output; fenced task/memory payloads are suppressed incrementally. */
    suspend fun append(delta: String) = inputMutex.withLock {
        if (inputClosed || stopped || failed || delta.isEmpty()) return@withLock
        val visible = fenceFilter.append(delta)
        enqueueAll(sentenceBuffer.append(visible))
    }

    /** Flushes this turn's trailing text and resets both sentence and fence state. */
    suspend fun boundary() = inputMutex.withLock {
        if (inputClosed || stopped || failed) return@withLock
        val visible = fenceFilter.boundary()
        enqueueAll(sentenceBuffer.append(visible))
        enqueueSentence(sentenceBuffer.boundary())
    }

    /** Closes input, speaks the final fragment, waits for synthesis and playback, then drains the player. */
    suspend fun finish() {
        inputMutex.withLock {
            if (!inputClosed && !stopped && !failed) {
                inputClosed = true
                val visible = fenceFilter.boundary()
                enqueueAll(sentenceBuffer.append(visible), allowClosedInput = true)
                enqueueSentence(sentenceBuffer.finish(), allowClosedInput = true)
                sentences.close()
            }
        }
        worker.join()
    }

    /** Stops this speech child without cancelling the scope that owns LLM generation. */
    fun stop() {
        if (stopped) return
        stopped = true
        inputClosed = true
        sentences.cancel()
        audio.cancel()
        sessionJob.cancel()
        stopPlaybackOnce()
    }

    private suspend fun process() = coroutineScope {
        val playback = launch {
            for (chunk in audio) play(chunk)
        }
        var success = false
        try {
            for (sentence in sentences) {
                when (val result = synthesize(sentence) { chunk -> audio.send(chunk) }) {
                    is SpeechOutcome.Ok -> Unit
                    is SpeechOutcome.Failed -> {
                        fail(result.message)
                        return@coroutineScope
                    }
                }
            }
            audio.close()
            playback.join()
            drain()
            success = true
            completedNormally = true
        } finally {
            if (!success) {
                sentences.cancel()
                audio.cancel()
                stopPlaybackOnce()
                playback.cancel()
            }
        }
    }

    private suspend fun enqueueAll(values: List<String>, allowClosedInput: Boolean = false) {
        for (value in values) enqueueSentence(value, allowClosedInput)
    }

    private suspend fun enqueueSentence(value: String?, allowClosedInput: Boolean = false) {
        if (value.isNullOrBlank() || stopped || failed || (inputClosed && !allowClosedInput)) return
        try {
            sentences.send(value)
        } catch (cancelled: CancellationException) {
            // A canceled channel means stop/failure. Preserve cancellation from the parent LLM job.
            currentCoroutineContext().ensureActive()
            if (!stopped && !failed && !inputClosed) throw cancelled
        } catch (closed: ClosedSendChannelException) {
            if (!stopped && !failed && !inputClosed) throw closed
        }
    }

    private fun fail(message: String) {
        if (stopped || failed) return
        failed = true
        inputClosed = true
        sentences.cancel()
        audio.cancel()
        stopPlaybackOnce()
        if (!failureReported) {
            failureReported = true
            try {
                onFailure(message)
            } catch (_: Throwable) {
                // A UI callback must not strand the queues or keep LLM generation blocked.
            }
        }
    }

    private fun stopPlaybackOnce() {
        if (playbackStopped) return
        playbackStopped = true
        try {
            stopPlayback()
        } catch (_: Throwable) {
            // Still release both bounded channels if a platform stop callback fails.
        }
    }

    private companion object {
        const val SENTENCE_QUEUE_CAPACITY = 2
        const val AUDIO_QUEUE_CAPACITY = 8
    }
}

/** Incremental fence state machine; delimiters may be split across arbitrary LLM chunks. */
private class SpeechFenceFilter {
    private var insideFence = false
    private var pendingBackticks = 0

    fun append(delta: String): String = buildString {
        for (char in delta) {
            if (char == '`') {
                pendingBackticks++
                if (pendingBackticks == 3) {
                    pendingBackticks = 0
                    insideFence = !insideFence
                }
            } else {
                if (!insideFence && pendingBackticks > 0) repeat(pendingBackticks) { append('`') }
                pendingBackticks = 0
                if (!insideFence) append(char)
            }
        }
    }

    /** Emits incomplete ordinary backticks but discards code and resets between turns. */
    fun boundary(): String {
        val tail = if (!insideFence) "`".repeat(pendingBackticks) else ""
        pendingBackticks = 0
        insideFence = false
        return tail
    }
}
