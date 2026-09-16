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
    }

    /** 通过则返回 null，否则给出可直接展示给用户的理由。 */
    fun rejection(clip: VoiceClip): Rejection? = when {
        clip.mimeType !in supportedMimeTypes -> Rejection.UnsupportedFormat(clip.mimeType)
        clip.bytes.isEmpty() -> Rejection.Empty
        clip.durationMillis < MIN_DURATION_MILLIS -> Rejection.TooShort(clip.durationMillis)
        clip.durationMillis > MAX_DURATION_MILLIS -> Rejection.TooLong(clip.durationMillis, MAX_DURATION_MILLIS)
        clip.sizeBytes > MAX_BYTES -> Rejection.TooLarge(clip.sizeBytes, MAX_BYTES)
        else -> null
    }
}
