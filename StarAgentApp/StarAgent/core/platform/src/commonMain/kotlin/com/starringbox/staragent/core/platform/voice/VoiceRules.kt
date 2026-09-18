package com.starringbox.staragent.core.platform.voice

/**
 * 录音的准入规则。
 *
 * 和 [com.starringbox.staragent.core.platform.media.MediaRules] 同一个思路：
 * 宁可在本地就挡下来给出清晰提示，也不要传出去换一个 400。
 */
object VoiceRules {

    /** 识别接口收的格式。我们自己录的是 wav；mpeg/mp4 是为了将来接设备能力留的口子。 */
    val supportedMimeTypes: Set<String> = setOf(Wav.MIME_TYPE, "audio/mpeg", "audio/mp4")

    /**
     * 单次上限 30 秒。
     *
     * 不是随便定的：智谱的识别接口文档明确写「音频时长 ≤ 30 秒」，
     * 国内可用的服务商里它这条最紧。取它当上限，换哪家都不会超。
     */
    const val MAX_DURATION_MILLIS: Long = 30_000

    /** 太短的按误触处理——半秒都不到，多半是点错了而不是说话。 */
    const val MIN_DURATION_MILLIS: Long = 500

    const val MAX_BYTES: Int = 8 * 1024 * 1024

    /**
     * 一帧要多响才算"有人在说话"，满量程的占比。
     *
     * 0.01 大约是 -40 dBFS。安静房间里笔记本麦克风的底噪通常在 -55 ~ -45 dBFS，
     * 正常距离说话的帧在 -30 dBFS 上下——这条线落在两者中间，而且离底噪更远，
     * 偏向"宁可放过去"。
     */
    const val SILENCE_FLOOR: Double = 0.01

    /**
     * 至少要有这么大比例的帧是有声的。
     *
     * 定得很低（2%），因为这道闸只负责认出**完全没说话**：一秒的录音里
     * 有一帧响就放过去。压低是故意的——把说得轻的人挡在门外，
     * 比放过一段静音要糟得多，静音后面还有服务端那道闸。
     */
    const val MIN_VOICED_FRACTION: Double = 0.02

    sealed interface Rejection {
        val message: String

        data class UnsupportedFormat(val mimeType: String) : Rejection {
            override val message: String get() = "不支持这种音频格式：$mimeType"
        }

        data object Empty : Rejection {
            override val message: String get() = "没有录到声音"
        }

        data class TooShort(val actualMillis: Long) : Rejection {
            override val message: String get() = "说得太短了，再说一次"
        }

        data class TooLong(val actualMillis: Long, val limitMillis: Long) : Rejection {
            override val message: String
                get() = "一次最多说 ${limitMillis / 1000} 秒，这次录了 ${actualMillis / 1000} 秒"
        }

        data class TooLarge(val actualBytes: Int, val limitBytes: Int) : Rejection {
            override val message: String
                get() = "录音太大了（${actualBytes / 1024 / 1024} MB），上限 ${limitBytes / 1024 / 1024} MB"
        }

        /**
         * 录到了，但里面没人说话。
         *
         * 这条不是为了省一次请求，是为了不拿到一句**编出来的话**。
         * Whisper 是个必须解码出字来的模型，它没有"什么都没听到"这个输出；
         * 喂一段静音进去，它会给出训练语料里最常跟在无声后面的那句字幕——
         * 英文是 `Thank you.` / `Thanks for watching!`，中文是「字幕由 Amara.org 社群提供」。
         * 用户什么都没说，输入框里冒出一句 Thank you，就是这么来的。
         */
        data object Silent : Rejection {
            override val message: String get() = "没听到说话，再说一次"
        }
    }

    /** 通过则返回 null，否则给出可直接展示给用户的理由。 */
    fun rejection(clip: VoiceClip): Rejection? = when {
        clip.mimeType !in supportedMimeTypes -> Rejection.UnsupportedFormat(clip.mimeType)
        clip.bytes.isEmpty() -> Rejection.Empty
        clip.durationMillis < MIN_DURATION_MILLIS -> Rejection.TooShort(clip.durationMillis)
        clip.durationMillis > MAX_DURATION_MILLIS -> Rejection.TooLong(clip.durationMillis, MAX_DURATION_MILLIS)
        clip.sizeBytes > MAX_BYTES -> Rejection.TooLarge(clip.sizeBytes, MAX_BYTES)
        // 放最后：这条要把整段音频扫一遍，前面几条只看几个数
        isSilent(clip) -> Rejection.Silent
        else -> null
    }

    /**
     * 这段音频里有没有人说话。
     *
     * 只对我们自己录的 PCM/WAV 有效。设备后端交过来的 mpeg/mp4 读不出采样，
     * [Wav.voicedFraction] 返回 null，这里按"没有依据"处理——不拦。
     */
    private fun isSilent(clip: VoiceClip): Boolean {
        val voiced = Wav.voicedFraction(clip.bytes, SILENCE_FLOOR) ?: return false
        return voiced < MIN_VOICED_FRACTION
    }
}
