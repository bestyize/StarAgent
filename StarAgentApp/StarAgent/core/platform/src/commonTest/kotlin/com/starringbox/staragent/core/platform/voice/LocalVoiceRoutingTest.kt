package com.starringbox.staragent.core.platform.voice

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async

class LocalVoiceRoutingTest {
    @AfterTest fun reset() {
        Voice.cancelListening(); Voice.installDevice(); Voice.installModel(); Voice.installLocal()
        Voice.installPreference(null); Voice.installSpeakingPreference(null); Voice.installStopHandler(null)
    }
    private fun transcriber(text: String, ready: Boolean = true) = object : Transcriber {
        override val ready = ready
        override suspend fun start() = true
        override suspend fun finish() = SpeechOutcome.Ok(text)
        override fun cancel() = Unit
    }
    @Test fun automaticInputPrefersInstalledOfflineBeforePaidCloud() = runTest {
        Voice.installModel(transcriber("cloud"))
        Voice.installLocal(transcriber("local"))
        Voice.installPreference { null }
        assertIs<SpeechOutcome.Ok<Unit>>(Voice.startListening())
        assertEquals("local", assertIs<SpeechOutcome.Ok<String>>(Voice.stopListening()).value)
    }
    @Test fun explicitOfflineNeverFallsBackToCloud() = runTest {
        Voice.installModel(transcriber("cloud"))
        Voice.installLocal(transcriber("local", ready = false))
        Voice.installPreference { VoiceSource.Local }
        assertFalse(Voice.canListen)
        assertIs<SpeechOutcome.Failed>(Voice.startListening())
    }
    @Test fun readingCanUseSystemWhileRecognitionUsesOffline() = runTest {
        var spoken = ""
        Voice.installDevice(synthesizer = object : Synthesizer {
            override val ready = true
            override suspend fun speak(text: String): SpeechOutcome<Unit> { spoken = text; return SpeechOutcome.Ok(Unit) }
            override fun stop() = Unit
        })
        Voice.installPreference { VoiceSource.Local }
        Voice.installSpeakingPreference { VoiceSource.Device }
        assertIs<SpeechOutcome.Ok<Unit>>(Voice.speak("independent"))
        assertEquals("independent", spoken)
    }
    @Test fun lateRecognitionCannotSendTextOrClearANewerRecording() = runTest {
        val finish = CompletableDeferred<Unit>()
        Voice.installLocal(object : Transcriber {
            override val ready = true
            override suspend fun start() = true
            override suspend fun finish(): SpeechOutcome<String> { finish.await(); return SpeechOutcome.Ok("old") }
            override fun cancel() = Unit
        })
        Voice.installPreference { VoiceSource.Local }
        Voice.startListening()
        val old = async { Voice.stopListening() }
        runCurrent()
        Voice.cancelListening()
        Voice.startListening()
        finish.complete(Unit)
        assertIs<SpeechOutcome.Failed>(old.await())
        assertTrue(Voice.isListening)
    }
}
