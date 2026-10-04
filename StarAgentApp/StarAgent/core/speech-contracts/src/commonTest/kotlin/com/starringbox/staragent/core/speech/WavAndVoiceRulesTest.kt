package com.starringbox.staragent.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 不依赖平台的两件事：WAV 封装与录音准入规则。 */
class WavAndVoiceRulesTest {

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

    /**
     * 一段合规的录音。
     *
     * 采样得是真有响度的——全零的音频**不再算健康**了，那是静音，
     * 见 [silenceIsRejectedBeforeItCanBeHallucinatedInto]。
     */
    @Test
    fun aHealthyClipPasses() {
        val pcm = pcm(32_000) { index -> if ((index / 40) % 2 == 0) 6_500 else -6_500 }
        assertNull(VoiceRules.rejection(clip(Wav.wrap(pcm), 1_000)))
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

    // ── 静音 ────────────────────────────────────

    /**
     * 一段两秒的静音，格式、时长、大小每条都合规——正是这种录音会换回一句
     * `Thank you.`。挡在本地，用户看到的是"没听到说话"而不是一句他没说过的话。
     */
    @Test
    fun silenceIsRejectedBeforeItCanBeHallucinatedInto() {
        val rejection = VoiceRules.rejection(clip(Wav.wrap(ByteArray(64_000)), 2_000))
        assertTrue(rejection is VoiceRules.Rejection.Silent, "全零的音频不该被放出去")
    }

    /** 底噪不是说话。安静房间里录到的是很小的随机起伏，不该当成人声放过去。 */
    @Test
    fun roomNoiseIsStillSilence() {
        val pcm = pcm(32_000) { index ->
            // 满量程 0.3% 上下的起伏，约 -50 dBFS
            if (index % 2 == 0) 90 else -70
        }
        val rejection = VoiceRules.rejection(clip(Wav.wrap(pcm), 1_000))
        assertTrue(rejection is VoiceRules.Rejection.Silent, "底噪级别的起伏不算说话")
    }

    /** 说了话就要放过去。这条是上面两条的反面——闸门不能把正常录音一起拦下。 */
    @Test
    fun actualSpeechPasses() {
        val pcm = pcm(32_000) { index ->
            // 满量程 20% 的方波，相当于正常距离说话的响度
            if ((index / 40) % 2 == 0) 6_500 else -6_500
        }
        assertNull(VoiceRules.rejection(clip(Wav.wrap(pcm), 1_000)), "正常响度的录音不该被拦")
    }

    /**
     * 长录音里只说了两个字也要放过去。
     *
     * 这条钉的是"逐帧算"而不是"整段算一个均值"：均值会被大段的静音稀释到
     * 和一段安静没有区别，然后把用户真说的那句话一起挡掉。
     */
    @Test
    fun aShortWordInsideALongSilencePasses() {
        // 十秒，只有第 1.0 ~ 1.4 秒有声音
        val pcm = pcm(320_000) { index ->
            if (index in 16_000..22_400) {
                if ((index / 40) % 2 == 0) 6_500 else -6_500
            } else {
                0
            }
        }
        assertNull(
            VoiceRules.rejection(clip(Wav.wrap(pcm), 10_000)),
            "十秒里说了 0.4 秒也是说了——按整段均值判就会把它当静音",
        )
    }

    /**
     * 读不出采样的格式不该被这道闸拦。
     *
     * 设备后端交过来的是 mpeg/mp4，里面是压缩数据；按 PCM 解出来必然是一堆噪声
     * 或者一片零。判断不了就不判断，这道闸只管我们自己录的 WAV。
     */
    @Test
    fun aCompressedClipIsNotJudgedForLoudness() {
        assertNull(
            VoiceRules.rejection(VoiceClip(ByteArray(64_000), "audio/mp4", 2_000)),
            "读不出采样时不该猜它是静音",
        )
    }

    @Test
    fun voicedFractionSaysSoWhenItCannotTell() {
        assertNull(Wav.voicedFraction(ByteArray(10), VoiceRules.SILENCE_FLOOR), "连头都不完整")
        assertNull(Wav.voicedFraction(ByteArray(100), VoiceRules.SILENCE_FLOOR), "认不出 RIFF")
        assertNull(
            Wav.voicedFraction(Wav.wrap(ByteArray(200)), VoiceRules.SILENCE_FLOOR),
            "不足一帧（20ms 要 640 字节）时没有可算的东西",
        )
    }

    // ── 替身 ────────────────────────────────────

    private fun clip(bytes: ByteArray, durationMillis: Long) =
        VoiceClip(bytes, Wav.MIME_TYPE, durationMillis)

    /** 造一段 16 位小端单声道 PCM，[sample] 按采样下标给出振幅。 */
    private fun pcm(bytes: Int, sample: (Int) -> Int): ByteArray {
        val out = ByteArray(bytes)
        for (index in 0 until bytes / 2) {
            val value = sample(index)
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }
}

private fun ByteArray.intAt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortAt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
