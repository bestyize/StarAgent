package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.model.SpeechProtocol
import com.starringbox.staragent.core.platform.voice.PcmAudioChunk
import com.starringbox.staragent.core.platform.voice.PcmPlayers
import com.starringbox.staragent.core.platform.voice.Player
import com.starringbox.staragent.core.platform.voice.Players
import com.starringbox.staragent.core.platform.voice.Recorder
import com.starringbox.staragent.core.platform.voice.Recorders
import com.starringbox.staragent.core.platform.voice.SpeechOutcome
import com.starringbox.staragent.core.platform.voice.VoiceClip
import com.starringbox.staragent.core.platform.voice.Wav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelSpeechLifecycleTest {
    @Test
    fun stopCancelsLateSynthesisBeforeItCanStartPlayback() = runTest {
        val started = CompletableDeferred<Unit>()
        val finishRequest = CompletableDeferred<Unit>()
        var playCalls = 0
        val player = object : Player {
            override val available = true
            override suspend fun play(clip: VoiceClip): Boolean { playCalls++; return true }
            override fun stop() = Unit
        }
        Players.install(player)
        PcmPlayers.install(null)
        val synthesizer = ModelSynthesizer(
            synthesizeRequest = { _, _, _ ->
                started.complete(Unit)
                try {
                    finishRequest.await()
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { finishRequest.await() }
                }
                Wav.wrap(ByteArray(16_000))
            },
            endpoint = { endpoint(SpeechProtocol.OpenAi, ttsModel = "tts-model") },
        )

        try {
            val speaking = launch { synthesizer.speak("你好") }
            started.await()
            synthesizer.stop()
            finishRequest.complete(Unit)
            speaking.join()

            assertTrue(speaking.isCancelled)
            assertEquals(0, playCalls, "late response must not start playback after stop")
        } finally {
            synthesizer.stop()
            Players.install(UnavailablePlayer)
            PcmPlayers.install(null)
        }
    }

    @Test
    fun transcriberCancellationReachesTheInFlightRequest() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        var cancelCalls = 0
        Recorders.install(object : Recorder {
            override val available = true
            override suspend fun start() = true
            override suspend fun stop(): VoiceClip = voicedClip()
            override fun cancel() { cancelCalls++ }
        })
        val transcriber = ModelTranscriber(
            filter = { it },
            transcribeRequest = { _, _, _ ->
                requestStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            },
            endpoint = { endpoint(SpeechProtocol.OpenAi, asrModel = "whisper-test") },
        )

        try {
            assertTrue(transcriber.start())
            val finishing = launch { transcriber.finish() }
            requestStarted.await()
            transcriber.cancel()
            finishing.join()

            assertTrue(finishing.isCancelled, "finish must propagate request cancellation")
            assertEquals(1, cancelCalls, "cancel must also stop the recorder")
        } finally {
            transcriber.cancel()
            Recorders.install(UnavailableRecorder)
        }
    }

    @Test
    fun finishUsesTheEndpointCapturedAtStartAndRejectsPunctuationOnlyText() = runTest {
        var current = endpoint(SpeechProtocol.OpenAi, asrModel = "first-model", baseUrl = "https://first.invalid")
        var sentTo: String? = null
        Recorders.install(object : Recorder {
            override val available = true
            override suspend fun start() = true
            override suspend fun stop(): VoiceClip = voicedClip()
            override fun cancel() = Unit
        })
        val transcriber = ModelTranscriber(
            filter = { it },
            transcribeRequest = { speech, _, _ ->
                sentTo = speech.baseUrl
                SpeechOutcome.Ok("。？！")
            },
            endpoint = { current },
        )

        try {
            assertTrue(transcriber.start())
            current = endpoint(SpeechProtocol.OpenAi, asrModel = "second-model", baseUrl = "https://second.invalid")
            assertIs<SpeechOutcome.Failed>(transcriber.finish())
            assertEquals("https://first.invalid", sentTo)
        } finally {
            Recorders.install(UnavailableRecorder)
        }
    }

    @Test
    fun mimoStreamingForwardsPcmWithoutRequiringAPlayer() = runTest {
        val received = ArrayList<PcmAudioChunk>()
        Players.install(UnavailablePlayer)
        PcmPlayers.install(null)
        val synthesizer = ModelSynthesizer(
            streamRequest = { _, _, onAudio -> onAudio(PcmAudioChunk(ByteArray(640), 16_000)) },
            endpoint = { endpoint(SpeechProtocol.MiMo, ttsModel = "mimo-v2.5-tts") },
        )

        try {
            assertTrue(synthesizer.streamReady)
            assertIs<SpeechOutcome.Ok<Unit>>(synthesizer.stream("你好") { received += it })
            assertEquals(1, received.size)
            assertEquals(16_000, received.single().sampleRate)
            assertFalse(Players.available)
        } finally {
            synthesizer.stop()
            Players.install(UnavailablePlayer)
            PcmPlayers.install(null)
        }
    }
}

private fun endpoint(
    protocol: SpeechProtocol,
    asrModel: String? = null,
    ttsModel: String? = null,
    baseUrl: String = "https://speech.invalid",
) = SpeechEndpoint(baseUrl = baseUrl, apiKey = "test-key", protocol = protocol, asrModel = asrModel, ttsModel = ttsModel)

private fun voicedClip(): VoiceClip {
    val pcm = ByteArray(32_000)
    for (i in pcm.indices step 2) {
        pcm[i] = 0
        pcm[i + 1] = 0x20
    }
    val wav = Wav.wrap(pcm)
    return VoiceClip(wav, Wav.MIME_TYPE, Wav.durationMillis(wav))
}

private object UnavailablePlayer : Player {
    override val available = false
    override suspend fun play(clip: VoiceClip) = false
    override fun stop() = Unit
}

private object UnavailableRecorder : Recorder {
    override val available = false
    override suspend fun start() = false
    override suspend fun stop(): VoiceClip? = null
    override fun cancel() = Unit
}
