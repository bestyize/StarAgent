package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.platform.voice.Voice
import com.starringbox.staragent.core.platform.voice.VoiceSource
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
    @Test fun legacyReadingKeepsFollowingTheSelectedAsrProvider() {
        SettingsStore.put("speechProvider", "openai")
        SettingsStore.put("speechKey:openai", "test-speech")
        val state = AppState()
        assertNull(state.ttsVoiceSource)
        assertNull(state.ttsProviderId)
        assertEquals("alloy", state.ttsEndpoint()?.ttsVoice)
    }
    @Test fun independentReadingSurvivesAsrChangesAndRestart() {
        val state = AppState()
        state.setSpeechKey("openai", "test-speech")
        state.selectTtsProvider("openai")
        state.selectTtsVoice("echo")
        state.selectTtsVoiceSource(VoiceSource.Model)
        state.selectSpeechProvider("zhipu")
        state.selectVoiceSource(VoiceSource.Local)
        val restored = AppState()
        assertEquals(VoiceSource.Local, restored.voiceSource)
        assertEquals(VoiceSource.Model, restored.ttsVoiceSource)
        assertEquals("openai", restored.activeTtsProvider()?.id)
        assertEquals("echo", restored.ttsEndpoint()?.ttsVoice)
    }
    @Test fun changingAnInheritedProviderResetsItsVoiceAndMiMoKeepsItsOwnProtocol() {
        val state = AppState()
        state.selectSpeechProvider("openai")
        state.selectTtsVoice("echo")
        state.selectSpeechProvider("mimo")
        state.setSpeechKey("mimo", "test-speech")
        assertNull(state.ttsVoiceId)
        assertEquals(com.starringbox.staragent.core.model.SpeechProtocol.MiMo, state.asrEndpoint()?.protocol)
        assertEquals("mimo-default", state.ttsEndpoint()?.ttsVoice)
    }
}
