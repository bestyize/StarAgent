package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.speech.SpeechOutcome
import com.starringbox.staragent.core.speech.Synthesizer
import com.starringbox.staragent.core.speech.Transcriber
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.speech.VoiceSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionVoiceSessionTest {
    private val sandbox = SettingsSandbox(listOf(
        "apikey:deepseek", "models:deepseek", "speakReplies", "streamReplies", "voiceSource", "ttsVoiceSource",
    ))

    @BeforeTest
    fun setup() {
        sandbox.enter()
        LocalStores.install(MemoryStore())
        Voice.installDevice(null, null)
    }

    @AfterTest
    fun cleanup() {
        Voice.cancelListening()
        Voice.stopSpeaking()
        Voice.installStopHandler(null)
        Voice.installDevice(null, null)
        Voice.installModel(null, null)
        Voice.installLocal()
        Voice.installPreference(null)
        Voice.installSpeakingPreference(null)
        LocalStores.install(MemoryStore())
        sandbox.leave()
    }

    @Test
    fun voiceOptInSpeaksWithGlobalReadingOffAndWaitsForPlaybackBeforeListeningAgain() = runTest {
        val state = readyState()
        val transcriber = FakeTranscriber()
        val releasePlayback = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        Voice.installDevice(transcriber, object : Synthesizer {
            override val ready = true
            override suspend fun speak(text: String): SpeechOutcome<Unit> {
                spoken += text
                releasePlayback.await()
                return SpeechOutcome.Ok(Unit)
            }
            override fun stop() = Unit
        })

        state.companionVoice.start(backgroundScope)
        runCurrent()
        assertEquals(CompanionPhase.Listening, state.companionVoice.phase)
        state.companionVoice.submitUtterance()
        advanceTimeBy(110)
        runCurrent()
        assertEquals(listOf("我在这里。"), spoken)
        assertEquals(1, transcriber.starts, "capture must not overlap reply playback")
        assertEquals(state.companion.id, state.speech.speakingAgentId)
        assertFalse(state.speech.speakReplies, "entering voice must not change the global setting")

        releasePlayback.complete(Unit)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(2, transcriber.starts)
        assertEquals(CompanionPhase.Listening, state.companionVoice.phase)
        state.companionVoice.stop()
        runCurrent()
        assertFalse(Voice.isListening)
        assertFalse(state.companionVoice.active)
    }

    @Test
    fun mutingDiscardsPartialCaptureAndLeavingDoesNotRestartTheMicrophone() = runTest {
        val state = readyState()
        val transcriber = FakeTranscriber()
        Voice.installDevice(transcriber, ImmediateSynthesizer)
        state.companionVoice.start(backgroundScope)
        runCurrent()
        state.companionVoice.setMicrophoneMuted(true)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(CompanionPhase.Paused, state.companionVoice.phase)
        assertTrue(state.messagesOf(state.companion.id).isEmpty())
        assertEquals(0, transcriber.finishes)
        assertFalse(Voice.isListening)

        state.companionVoice.setMicrophoneMuted(false)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, transcriber.starts)
        state.companionVoice.stop()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, transcriber.starts)
        assertFalse(Voice.isListening)
    }

    @Test
    fun leavingDuringRecognitionSuppressesTheLateTranscript() = runTest {
        val state = readyState()
        val transcriber = FakeTranscriber(CompletableDeferred())
        Voice.installDevice(transcriber, ImmediateSynthesizer)
        state.companionVoice.start(backgroundScope)
        runCurrent()
        state.companionVoice.submitUtterance()
        advanceTimeBy(110)
        runCurrent()
        assertEquals(CompanionPhase.Transcribing, state.companionVoice.phase)
        state.companionVoice.stop()
        transcriber.gate?.complete(Unit)
        runCurrent()
        assertTrue(state.messagesOf(state.companion.id).isEmpty())
        assertFalse(Voice.isListening)
    }

    @Test
    fun unavailableReplySpeechDoesNotOpenTheMicrophone() = runTest {
        val state = readyState()
        val transcriber = FakeTranscriber()
        Voice.installDevice(transcriber, null)
        state.companionVoice.start(backgroundScope)
        runCurrent()
        assertEquals(0, transcriber.starts)
        assertFalse(state.companionVoice.active)
        assertNotNull(state.companionVoice.error)
    }

    private fun readyState(): AppState {
        val state = AppState(stream = { _, _ -> flow {
            emit(ChatStreamEvent.Delta("我在这里。"))
            emit(ChatStreamEvent.Completed)
        } })
        val provider = assertNotNull(state.providerById("deepseek"))
        state.updateProvider(provider.copy(apiKey = "test-key", models = listOf("test-model")))
        state.setAgentModel(state.companion.id, "deepseek:0")
        state.speech.selectVoiceSource(VoiceSource.Device)
        state.speech.selectTtsVoiceSource(VoiceSource.Device)
        state.speech.setReadingReplies(false)
        state.speech.setStreamingReplies(false)
        return state
    }

    private class FakeTranscriber(val gate: CompletableDeferred<Unit>? = null) : Transcriber {
        override val ready = true
        var starts = 0
        var finishes = 0
        override suspend fun start(): Boolean { starts++; return true }
        override suspend fun finish(): SpeechOutcome<String> {
            finishes++
            gate?.await()
            return SpeechOutcome.Ok("你好")
        }
        override fun cancel() = Unit
    }

    private object ImmediateSynthesizer : Synthesizer {
        override val ready = true
        override suspend fun speak(text: String) = SpeechOutcome.Ok(Unit)
        override fun stop() = Unit
    }
}
