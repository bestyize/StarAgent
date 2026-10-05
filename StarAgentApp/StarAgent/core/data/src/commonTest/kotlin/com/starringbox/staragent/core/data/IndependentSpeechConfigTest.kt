package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.speech.VoiceSource
import kotlin.test.*

class IndependentSpeechConfigTest {
    private val sandbox = SettingsSandbox(listOf(
        "speechProvider", "asrModel", "voiceSource", "ttsVoiceSource", "ttsProvider", "ttsVoice",
        "speechKey:zhipu", "speechKey:openai", "speechKey:mimo", "streamReplies",
    ))
    @BeforeTest fun before() { sandbox.enter(); LocalStores.install(MemoryStore()) }
    @AfterTest fun after() {
        sandbox.leave(); Voice.installLocal(); Voice.installSpeakingPreference(null)
        Voice.installPreference(null); Voice.installModel(); LocalStores.install(MemoryStore())
        Voice.installStopHandler(null)
    }
    @Test fun defaultReadingUsesEdgeTtsAndItsDefaultVoice() {
        val state = AppState()
        assertEquals(VoiceSource.Model, state.speech.ttsVoiceSource)
        assertEquals("edge-tts", state.speech.ttsProviderId)
        assertEquals("zh-CN-XiaoxiaoNeural", state.speech.ttsVoiceId)
        assertEquals("zh-CN-XiaoxiaoNeural", state.speech.ttsEndpoint()?.ttsVoice)
    }
    @Test fun explicitlyInheritedReadingStillFollowsTheSelectedAsrProvider() {
        SettingsStore.put("speechProvider", "openai")
        SettingsStore.put("speechKey:openai", "test-speech")
        val state = AppState()
        state.speech.selectTtsProvider(null)
        assertNull(state.speech.ttsProviderId)
        assertEquals("alloy", state.speech.ttsEndpoint()?.ttsVoice)
    }
    @Test fun independentReadingSurvivesAsrChangesAndRestart() {
        val state = AppState()
        state.speech.setSpeechKey("openai", "test-speech")
        state.speech.selectTtsProvider("openai")
        state.speech.selectTtsVoice("echo")
        state.speech.selectTtsVoiceSource(VoiceSource.Model)
        state.speech.selectSpeechProvider("zhipu")
        state.speech.selectVoiceSource(VoiceSource.Local)
        val restored = AppState()
        assertEquals(VoiceSource.Local, restored.speech.voiceSource)
        assertEquals(VoiceSource.Model, restored.speech.ttsVoiceSource)
        assertEquals("openai", restored.speech.activeTtsProvider()?.id)
        assertEquals("echo", restored.speech.ttsEndpoint()?.ttsVoice)
    }
    @Test fun changingAnInheritedProviderResetsItsVoiceAndMiMoKeepsItsOwnProtocol() {
        val state = AppState()
        state.speech.selectTtsProvider(null)
        state.speech.selectSpeechProvider("openai")
        state.speech.selectTtsVoice("echo")
        state.speech.selectSpeechProvider("mimo")
        state.speech.setSpeechKey("mimo", "test-speech")
        assertNull(state.speech.ttsVoiceId)
        assertEquals(com.starringbox.staragent.core.model.SpeechProtocol.MiMo, state.speech.asrEndpoint()?.protocol)
        assertEquals("mimo_default", state.speech.ttsEndpoint()?.ttsVoice)
    }
}
