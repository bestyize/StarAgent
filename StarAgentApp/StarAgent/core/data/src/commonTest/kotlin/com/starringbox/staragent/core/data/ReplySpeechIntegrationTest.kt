package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.speech.PcmAudioChunk
import com.starringbox.staragent.core.speech.PcmPlayer
import com.starringbox.staragent.core.speech.PcmPlayers
import com.starringbox.staragent.core.speech.SpeechOutcome
import com.starringbox.staragent.core.speech.StreamingSynthesizer
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.speech.VoiceSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReplySpeechIntegrationTest {
    private val sandbox = SettingsSandbox(
        listOf(
            "voiceSource", "ttsVoiceSource", "ttsProvider", "ttsVoice", "speechProvider", "asrModel",
            "speakReplies", "streamReplies", "speechKey:mimo",
            "apikey:deepseek", "models:deepseek",
        )
    )

    @BeforeTest
    fun setUp() {
        sandbox.enter()
        LocalStores.install(MemoryStore())
        Voice.installStopHandler(null)
        Voice.installDevice(null, null)
        Voice.installModel(null, null)
        Voice.installLocal()
        Voice.installPreference(null)
        Voice.installSpeakingPreference(null)
        PcmPlayers.install(null)
    }

    @AfterTest
    fun tearDown() {
        Voice.stopSpeaking()
        Voice.installStopHandler(null)
        Voice.installDevice(null, null)
        Voice.installModel(null, null)
        Voice.installLocal()
        Voice.installPreference(null)
        Voice.installSpeakingPreference(null)
        PcmPlayers.install(null)
        LocalStores.install(MemoryStore())
        sandbox.leave()
    }

    @Test
    fun firstPcmArrivesBeforeLlmFinishesAndFullSendDoesNotReplayTheReply() = runTest {
        val finishGateReached = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        val stream: (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent> = { _, _ ->
            flow {
                emit(ChatStreamEvent.Delta("首句已经说出。"))
                finishGateReached.complete(Unit)
                releaseFinish.await()
                emit(ChatStreamEvent.Delta("这是最后一句。"))
                emit(ChatStreamEvent.Completed)
            }
        }
        val state = readyState(stream)
        val synth = RecordingStreamingSynthesizer()
        val player = RecordingPcmPlayer()
        installSpeechBackends(synth, player)
        val agent = state.agents.first()

        val sending = async { state.send(agent.id, "回答这个问题", readAloud = true) }
        finishGateReached.await()
        player.firstPcm.await()
        assertFalse(sending.isCompleted, "the LLM stream is still blocked at its finish gate")
        assertEquals(listOf("首句已经说出。"), synth.streamedText)

        releaseFinish.complete(Unit)
        sending.await()

        assertEquals(listOf("首句已经说出。", "这是最后一句。"), synth.streamedText)
        assertEquals(synth.streamedText, player.spokenChunks)
        assertEquals(0, synth.oneShotCalls, "a streamed reply must not be spoken a second time at send completion")
        assertEquals("首句已经说出。这是最后一句。", lastAgentText(state.messagesOf(agent.id)))
        assertEquals(1, player.drainCalls)
    }

    @Test
    fun groupRepliesStreamInMemberRoundOrder() = runTest {
        val requestOrder = mutableListOf<String>()
        val repliesByTurn = mapOf(
            "小星-1" to "星星第一轮。",
            "工作助手-1" to "工作第一轮。",
            "小星-2" to "星星第二轮。",
            "工作助手-2" to "工作第二轮。",
        )
        val stream: (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent> = { endpoint, _ ->
            val speaker = if (endpoint.systemPrompt.orEmpty().contains("你是「小星」")) "小星" else "工作助手"
            val round = if (endpoint.systemPrompt.orEmpty().contains("现在是第 1 / 2 轮")) 1 else 2
            val key = "$speaker-$round"
            requestOrder += key
            val reply = repliesByTurn.getValue(key)
            flow {
                emit(ChatStreamEvent.Delta(reply))
                emit(ChatStreamEvent.Completed)
            }
        }
        val state = readyState(stream)
        val group = assertNotNull(state.group("g-1")).copy(memberIds = listOf("a-xing", "a-work"), rounds = 2)
        state.saveGroup(group)
        val synth = RecordingStreamingSynthesizer()
        val player = RecordingPcmPlayer()
        installSpeechBackends(synth, player)

        state.sendToGroup(group.id, "请轮流回答", readAloud = true)

        val replies = listOf("星星第一轮。", "工作第一轮。", "星星第二轮。", "工作第二轮。")
        assertEquals(listOf("小星-1", "工作助手-1", "小星-2", "工作助手-2"), requestOrder)
        assertEquals(replies, synth.streamedText, "speech must follow member order, then advance to the next round")
        assertEquals(replies, player.spokenChunks)
        val fullText = state.messagesOfGroup(group.id)
            .filter { it.author == Author.Agent && it.body is MessageBody.Text }
            .map { (it.body as MessageBody.Text).text }
        assertEquals(replies, fullText)
    }

    @Test
    fun groupSpeechFailureDoesNotLoseFullLlmText() = runTest {
        // The separate scenario has its own restored-conversation sandbox.
        LocalStores.install(MemoryStore())
        val failedReplies = listOf("小星这段仍要完整保存。", "工作助手也要完整保存。")
        val failedState = readyState { endpoint, _ ->
            val reply = if (endpoint.systemPrompt.orEmpty().contains("你是「小星」")) failedReplies[0] else failedReplies[1]
            flow {
                emit(ChatStreamEvent.Delta(reply))
                emit(ChatStreamEvent.Completed)
            }
        }
        val failedGroup = assertNotNull(failedState.group("g-1"))
            .copy(memberIds = listOf("a-xing", "a-work"), rounds = 1)
        failedState.saveGroup(failedGroup)
        val failedSynth = RecordingStreamingSynthesizer(failOnFirstSentence = true)
        val failedPlayer = RecordingPcmPlayer()
        installSpeechBackends(failedSynth, failedPlayer)

        failedState.sendToGroup(failedGroup.id, "即使语音失败也要完成", readAloud = true)

        val completedDespiteSpeechError = failedState.messagesOfGroup(failedGroup.id)
            .filter { it.author == Author.Agent && it.body is MessageBody.Text }
            .map { (it.body as MessageBody.Text).text }
        assertEquals(failedReplies, completedDespiteSpeechError)
        assertEquals(0, failedPlayer.frameCount)
        assertTrue(failedState.speechError?.contains("测试合成失败") == true)
    }

    @Test
    fun stopSpeakingStopsOnlySpeechWhileLlmCompletesWithoutReplay() = runTest {
        val finishGateReached = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        val stream: (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent> = { _, _ ->
            flow {
                emit(ChatStreamEvent.Delta("停止前的句子。"))
                finishGateReached.complete(Unit)
                releaseFinish.await()
                emit(ChatStreamEvent.Delta("停止后的句子仍进文本。"))
                emit(ChatStreamEvent.Completed)
            }
        }
        val state = readyState(stream)
        val synth = RecordingStreamingSynthesizer()
        val player = RecordingPcmPlayer()
        installSpeechBackends(synth, player)
        val agent = state.agents.first()
        val sending = async { state.send(agent.id, "继续完成这个回答", readAloud = true) }

        finishGateReached.await()
        player.firstPcm.await()
        val framesAtStop = player.frameCount
        state.stopSpeaking()
        releaseFinish.complete(Unit)
        sending.await()

        assertEquals("停止前的句子。停止后的句子仍进文本。", lastAgentText(state.messagesOf(agent.id)))
        assertEquals(listOf("停止前的句子。"), synth.streamedText)
        assertEquals(framesAtStop, player.frameCount, "no PCM may arrive after stop")
        assertEquals(0, synth.oneShotCalls, "stopping a stream must not trigger whole-reply fallback playback")
    }

    private fun readyState(stream: (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent>): AppState {
        val state = AppState(stream = stream)
        val provider = assertNotNull(state.providerById("deepseek"))
        state.updateProvider(provider.copy(apiKey = "test-chat-key", models = listOf("integration-model")))
        state.setSpeechKey("mimo", "test-speech-key")
        state.selectSpeechProvider("mimo")
        state.selectAsrModel("mimo-v2.5-asr")
        state.selectTtsProvider("mimo")
        state.selectTtsVoice("mimo-default")
        state.selectVoiceSource(VoiceSource.Model)
        state.selectTtsVoiceSource(VoiceSource.Local)
        state.setReadingReplies(true)
        state.setStreamingReplies(true)
        return state
    }

    private fun installSpeechBackends(synth: RecordingStreamingSynthesizer, player: RecordingPcmPlayer) {
        // AppState construction installs the production local adapter; replace it after construction.
        Voice.installLocal(synthesizer = synth)
        PcmPlayers.install(player)
    }

    private fun lastAgentText(messages: List<com.starringbox.staragent.core.model.ChatMessage>): String =
        messages.last { it.author == Author.Agent && it.body is MessageBody.Text }
            .let { (it.body as MessageBody.Text).text }

    private class RecordingStreamingSynthesizer(
        private val failOnFirstSentence: Boolean = false,
    ) : StreamingSynthesizer {
        val streamedText = mutableListOf<String>()
        var oneShotCalls = 0
        override val ready = true
        override val streamReady = true

        override suspend fun speak(text: String): SpeechOutcome<Unit> {
            oneShotCalls++
            return SpeechOutcome.Ok(Unit)
        }

        override suspend fun stream(text: String, onAudio: suspend (PcmAudioChunk) -> Unit): SpeechOutcome<Unit> {
            streamedText += text
            if (failOnFirstSentence && streamedText.size == 1) return SpeechOutcome.Failed("测试合成失败")
            val bytes = text.encodeToByteArray()
            val pcm = if (bytes.size % 2 == 0) bytes else bytes + byteArrayOf(0)
            onAudio(PcmAudioChunk(pcm, sampleRate = 24_000))
            return SpeechOutcome.Ok(Unit)
        }

        override fun stop() = Unit
    }

    private class RecordingPcmPlayer : PcmPlayer {
        override val available = true
        val firstPcm = CompletableDeferred<Unit>()
        val spokenChunks = mutableListOf<String>()
        var frameCount = 0
        var drainCalls = 0

        override suspend fun enqueue(chunk: PcmAudioChunk) {
            frameCount += chunk.pcm16.size / (2 * chunk.channels)
            spokenChunks += chunk.pcm16.decodeToString().trimEnd('\u0000')
            firstPcm.complete(Unit)
        }

        override suspend fun drain() { drainCalls++ }
        override fun stop() = Unit
    }
}
