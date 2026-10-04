package com.starringbox.staragent.core.platform.voice

import com.starringbox.staragent.core.speech.PcmPlayers
import com.starringbox.staragent.core.speech.SpeechOutcome
import com.starringbox.staragent.core.speech.Synthesizer
import com.starringbox.staragent.core.speech.Transcriber
import com.starringbox.staragent.core.speech.VoiceSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 两路语音后端怎么挑。
 *
 * 这是"统一接口层"的全部价值所在，而两路后端在真机上没法同时验证（这台机器没有 Android）。
 * WAV 封装与准入规则的测试在 core:speech-contracts。
 */
class VoiceTest {

    @AfterTest
    fun reset() {
        // Voice 是全局单例，跑完清干净，免得影响同模块别的测试
        Voice.installDevice(null, null)
        Voice.installModel(null, null)
        Voice.installPreference(null)
        Voice.installSpeakingPreference(null)
        Voice.installLocal()
        Voice.installStopHandler(null)
        PcmPlayers.install(null)
    }

    // ── 后端怎么挑 ──────────────────────────────

    @Test
    fun nothingInstalledMeansNoVoice() {
        assertFalse(Voice.canListen)
        assertFalse(Voice.canSpeak)
    }

    @Test
    fun nothingInstalledExplainsWhatToDo() = runTest {
        val outcome = Voice.startListening()
        assertIs<SpeechOutcome.Failed>(outcome)
        assertTrue(outcome.message.isNotBlank(), "不能只说'语音不可用'，要说清楚下一步做什么")
    }

    @Test
    fun withBothInstalledAutomaticPrefersTheDevice() = runTest {
        Voice.installDevice(FakeTranscriber(text = "设备听出来的"))
        Voice.installModel(FakeTranscriber(text = "模型听出来的"))

        assertTrue(Voice.startListening() is SpeechOutcome.Ok)
        assertEquals("设备听出来的", (Voice.stopListening() as SpeechOutcome.Ok).value)
    }

    @Test
    fun forcingTheModelSkipsTheDevice() = runTest {
        Voice.installDevice(FakeTranscriber(text = "设备听出来的"))
        Voice.installModel(FakeTranscriber(text = "模型听出来的"))
        Voice.installPreference { VoiceSource.Model }

        Voice.startListening()
        assertEquals("模型听出来的", (Voice.stopListening() as SpeechOutcome.Ok).value)
    }

    /** 设备那路没权限时不该直接失败——还有模型那路可以顶上来。 */
    @Test
    fun anUnreadyDeviceFallsBackToTheModel() = runTest {
        Voice.installDevice(FakeTranscriber(text = "设备听出来的", ready = false))
        Voice.installModel(FakeTranscriber(text = "模型听出来的"))

        assertTrue(Voice.canListen, "设备不可用不代表没有语音能力")
        Voice.startListening()
        assertEquals("模型听出来的", (Voice.stopListening() as SpeechOutcome.Ok).value)
    }

    /**
     * 只装设备那一路就够用——**一个服务商都不配也要能语音输入**。
     *
     * 这是 Android 的真实处境：系统识别不要 Key、不花钱，功能不该反过来被
     * "配没配服务商"卡住。权限更不能算进来：不这么写，没权限 → 入口不显示 →
     * 申请权限的唯一入口就是那个入口 → 死环。
     */
    @Test
    fun aDeviceBackendAloneIsEnoughWithNoProviderConfigured() = runTest {
        Voice.installDevice(FakeTranscriber(text = "设备听出来的"))

        assertTrue(Voice.canListen, "有系统识别就该能语音输入，与配没配服务商无关")
        Voice.startListening()
        assertEquals("设备听出来的", (Voice.stopListening() as SpeechOutcome.Ok).value)
    }

    @Test
    fun forcingAnUnreadyBackendDoesNotSilentlyUseTheOther() {
        Voice.installDevice(FakeTranscriber(text = "设备听出来的"))
        Voice.installModel(FakeTranscriber(text = "模型听出来的", ready = false))
        Voice.installPreference { VoiceSource.Model }

        assertFalse(Voice.canListen, "用户明确指定了模型那一路，就不该偷偷换回设备")
    }

    /**
     * 识别那条独占，合成那条**会退回设备**——这个不对称是有意的（见 `pickSynthesizer`）。
     *
     * 「云端识别」这个档管的是识别，用户没要求把朗读也一起换掉；而朗读本来就没有
     * 独立的档位，退回去不会让他以为"我选中了别的"。
     *
     * 具体的坑：Groq 只做识别，选了它之后模型合成没有 endpoint。不放宽这一条的话，
     * Android 上明明有系统朗读却会整个哑掉，而界面上什么都不会说。
     */
    @Test
    fun anUnreadyModelSynthesizerFallsBackToTheDevice() = runTest {
        val device = FakeSynthesizer()
        Voice.installDevice(synthesizer = device)
        Voice.installModel(synthesizer = FakeSynthesizer(ready = false))
        Voice.installPreference { VoiceSource.Model }

        assertTrue(Voice.canSpeak, "识别选了云端，不该把朗读也一起关掉")
        Voice.speak("念一句")
        assertEquals(1, device.calls, "模型合成不可用时就该退回设备那一路")
    }

    @Test
    fun speakingBlankTextSucceedsWithoutCallingAnything() = runTest {
        val synthesizer = FakeSynthesizer()
        Voice.installModel(synthesizer = synthesizer)

        assertTrue(Voice.speak("   ") is SpeechOutcome.Ok)
        assertEquals(0, synthesizer.calls, "没内容可念不该真的去调合成")
    }

    /**
     * 停止要打到**每一条**后端上。
     *
     * 这条补的是真机上的一个漏：离开对话页时朗读还在响。原来只停了走服务商的那条
     * （Players.stop()），而设备合成是系统自己排队播放的，压根没人停它——
     * 而自动模式下默认走的就是它。
     */
    @Test
    fun stoppingSpeakingReachesEveryBackend() {
        val device = FakeSynthesizer()
        val model = FakeSynthesizer()
        Voice.installDevice(synthesizer = device)
        Voice.installModel(synthesizer = model)

        Voice.stopSpeaking()

        assertEquals(1, device.stops, "设备那路没被停——它才是默认在响的那条")
        assertEquals(1, model.stops)
    }

    @Test
    fun stoppingWithoutStartingSaysSoInsteadOfReturningNothing() = runTest {
        Voice.installModel(FakeTranscriber(text = "x"))
        assertIs<SpeechOutcome.Failed>(Voice.stopListening())
    }

    // ── 替身 ────────────────────────────────────

    private class FakeTranscriber(
        override val ready: Boolean = true,
        private val text: String,
    ) : Transcriber {
        override suspend fun start(): Boolean = true
        override suspend fun finish(): SpeechOutcome<String> = SpeechOutcome.Ok(text)
        override fun cancel() = Unit
    }

    private class FakeSynthesizer(override val ready: Boolean = true) : Synthesizer {
        var calls = 0
        var stops = 0

        override suspend fun speak(text: String): SpeechOutcome<Unit> {
            calls++
            return SpeechOutcome.Ok(Unit)
        }

        override fun stop() {
            stops++
        }
    }
}
