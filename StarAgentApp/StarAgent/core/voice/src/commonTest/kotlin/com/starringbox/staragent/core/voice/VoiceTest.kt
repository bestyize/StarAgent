package com.starringbox.staragent.core.voice

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 语音层里不依赖平台的几件事：WAV 封装、准入规则、以及**两路后端怎么挑**。
 *
 * 挑后端这条最要紧——它是"统一接口层"的全部价值所在，
 * 而两路后端在真机上没法同时验证（这台机器没有 Android）。
 */
class VoiceTest {

    @AfterTest
    fun reset() {
        // Voice 是全局单例，跑完清干净，免得影响同模块别的测试
        Voice.installDevice(null, null)
        Voice.installModel(null, null)
        Voice.preference = null
    }

    // ── WAV 封装 ────────────────────────────────

    @Test
    fun wrappingPcmProducesAReadableWav() {
        // 0.5 秒：16000 采样/秒 × 2 字节 × 0.5 秒
        val pcm = ByteArray(16_000)
        val wav = Wav.wrap(pcm)

        assertEquals(Wav.HEADER_BYTES + pcm.size, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals("fmt ", wav.decodeToString(12, 16))
        assertEquals("data", wav.decodeToString(36, 40))
        assertEquals(Wav.SAMPLE_RATE, wav.intAt(24))
        assertEquals(1, wav.shortAt(22), "单声道")
        assertEquals(16, wav.shortAt(34), "16 位")
    }

    @Test
    fun durationComesBackOutOfTheHeader() {
        val half = Wav.wrap(ByteArray(16_000))
        assertEquals(500, Wav.durationMillis(half))

        val twoSeconds = Wav.wrap(ByteArray(64_000))
        assertEquals(2_000, Wav.durationMillis(twoSeconds))
    }

    @Test
    fun nonsenseBytesReportZeroDurationInsteadOfBlowingUp() {
        assertEquals(0, Wav.durationMillis(ByteArray(10)))
        assertEquals(0, Wav.durationMillis(ByteArray(100))) // 全是 0，认不出 RIFF
    }

    // ── 准入规则 ────────────────────────────────

    @Test
    fun aHealthyClipPasses() {
        assertNull(VoiceRules.rejection(clip(Wav.wrap(ByteArray(16_000)), 1_000)))
    }

    @Test
    fun tooShortIsRejectedAsAMisTap() {
        val rejection = VoiceRules.rejection(clip(Wav.wrap(ByteArray(1_000)), 100))
        assertTrue(rejection is VoiceRules.Rejection.TooShort)
    }

    @Test
    fun tooLongIsRejectedWithTheLimitInTheMessage() {
        val rejection = VoiceRules.rejection(clip(Wav.wrap(ByteArray(16_000)), 45_000))
        assertTrue(rejection is VoiceRules.Rejection.TooLong)
        assertTrue(rejection.message.contains("30"), "理由里要写清楚上限是多少秒")
    }

    @Test
    fun emptyAudioIsRejected() {
        val rejection = VoiceRules.rejection(clip(ByteArray(0), 1_000))
        assertTrue(rejection is VoiceRules.Rejection.Empty)
    }

    @Test
    fun anUnknownFormatIsRejectedByName() {
        val rejection = VoiceRules.rejection(
            VoiceClip(ByteArray(1_000), "audio/ogg", 1_000)
        )
        assertTrue(rejection is VoiceRules.Rejection.UnsupportedFormat)
        assertTrue(rejection.message.contains("audio/ogg"))
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
        Voice.preference = VoiceSource.Model

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
        Voice.preference = VoiceSource.Model

        assertFalse(Voice.canListen, "用户明确指定了模型那一路，就不该偷偷换回设备")
    }

    @Test
    fun speakingBlankTextSucceedsWithoutCallingAnything() = runTest {
        val synthesizer = FakeSynthesizer()
        Voice.installModel(synthesizer = synthesizer)

        assertTrue(Voice.speak("   ") is SpeechOutcome.Ok)
        assertEquals(0, synthesizer.calls, "没内容可念不该真的去调合成")
    }

    @Test
    fun stoppingWithoutStartingSaysSoInsteadOfReturningNothing() = runTest {
        Voice.installModel(FakeTranscriber(text = "x"))
        assertIs<SpeechOutcome.Failed>(Voice.stopListening())
    }

    // ── 替身 ────────────────────────────────────

    private fun clip(bytes: ByteArray, durationMillis: Long) =
        VoiceClip(bytes, Wav.MIME_TYPE, durationMillis)

    private class FakeTranscriber(
        override val ready: Boolean = true,
        private val text: String,
    ) : Transcriber {
        override suspend fun start(): Boolean = true
        override suspend fun finish(): SpeechOutcome<String> = SpeechOutcome.Ok(text)
        override fun cancel() = Unit
    }

    private class FakeSynthesizer : Synthesizer {
        var calls = 0
        override val ready: Boolean get() = true
        override suspend fun speak(text: String): SpeechOutcome<Unit> {
            calls++
            return SpeechOutcome.Ok(Unit)
        }
    }
}

private fun ByteArray.intAt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortAt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
