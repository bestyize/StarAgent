package com.starringbox.staragent.core.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.starringbox.staragent.core.model.BuiltInCompanion
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.platform.voice.SpeechOutcome
import com.starringbox.staragent.core.platform.voice.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CompanionPhase { Idle, Listening, Transcribing, Thinking, Speaking, Paused }

/** One half-duplex voice session. Microphone capture never overlaps reply playback. */
class CompanionVoiceSession internal constructor(private val state: AppState) {
    var active by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var phase by mutableStateOf(CompanionPhase.Idle)
        private set
    var inputLevel by mutableStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null
    private var submitRequested = false
    private var generation = 0L

    fun start(scope: CoroutineScope) {
        if (active) return
        error = when {
            state.endpointFor(state.companion) == null -> "先配置一个对话模型，就可以和她交流了。"
            !state.canListen -> "请先在语音设置中配置语音识别。"
            !state.canSpeak -> "请先在语音设置中配置回复朗读。"
            else -> null
        }
        if (error != null) return
        active = true
        muted = false
        val current = ++generation
        job = scope.launch {
            try {
                while (isActive) {
                    if (muted) {
                        phase = CompanionPhase.Paused
                        delay(100)
                        continue
                    }
                    if (state.isBusy(BuiltInCompanion.ID)) {
                        phase = CompanionPhase.Thinking
                        delay(100)
                        continue
                    }
                    submitRequested = false
                    when (val started = state.startListening()) {
                        is SpeechOutcome.Failed -> { pauseWith(started.message); continue }
                        is SpeechOutcome.Ok -> Unit
                    }
                    currentCoroutineContext().ensureActive()
                    if (muted) { state.cancelListening(); continue }
                    phase = CompanionPhase.Listening
                    // Endpoint detection is provided by the selected transcriber. Manual send
                    // remains available, and a time cap prevents unbounded recording buffers.
                    var elapsed = 0
                    while (!muted && !submitRequested && !Voice.speechEnded && elapsed < 45_000) {
                        inputLevel = Voice.inputLevel
                        delay(100)
                        elapsed += 100
                    }
                    inputLevel = 0f
                    if (muted) { state.cancelListening(); continue }
                    phase = CompanionPhase.Transcribing
                    when (val heard = state.stopListening()) {
                        is SpeechOutcome.Failed -> if (!muted) pauseWith(heard.message)
                        is SpeechOutcome.Ok -> {
                            currentCoroutineContext().ensureActive()
                            if (muted || heard.value.isBlank()) continue
                            phase = CompanionPhase.Thinking
                            state.sendCompanionVoice(heard.value)
                            currentCoroutineContext().ensureActive()
                            val last = state.messagesOf(BuiltInCompanion.ID).lastOrNull()?.body
                            val failure = state.speechError ?: (last as? MessageBody.Notice)
                                ?.takeIf { it.isError }?.text
                            if (failure != null) pauseWith(failure)
                            // Playback has drained before another recording is allowed.
                            delay(250)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current == generation) {
                    error = failure.message ?: "语音对话暂时中断，请重新开始。"
                    active = false
                }
            } finally {
                if (current == generation) {
                    state.cancelListening()
                    state.stopSpeaking()
                    active = false
                    phase = CompanionPhase.Idle
                    inputLevel = 0f
                }
            }
        }
    }

    fun setMicrophoneMuted(value: Boolean) {
        muted = value
        if (!value) error = null
        if (value && (phase == CompanionPhase.Listening || phase == CompanionPhase.Transcribing)) {
            state.cancelListening()
            inputLevel = 0f
        }
    }

    fun submitUtterance() {
        if (phase == CompanionPhase.Listening && !muted) submitRequested = true
    }

    fun stop() {
        generation++
        active = false
        job?.cancel()
        job = null
        state.cancelListening()
        state.stopSpeaking()
        phase = CompanionPhase.Idle
        inputLevel = 0f
    }

    private fun pauseWith(message: String) {
        error = message
        muted = true
        phase = CompanionPhase.Paused
        state.cancelListening()
    }
}
