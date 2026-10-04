package com.starringbox.staragent.core.speech

/**
 * 一段语音。录到的和要播的是同一个类型——两者都只是"一段音频字节 + 格式"。
 *
 * 用普通 class 而不是 data class：内部是 [ByteArray]，
 * 自动生成的 equals 按引用比，既误导调用方，也会让 Compose 漏掉重组。
 * （和 [com.starringbox.staragent.core.model.MediaAsset] 同一个理由。）
 */
class VoiceClip(
    val bytes: ByteArray,
    val mimeType: String = Wav.MIME_TYPE,
    /** 时长。录到的由录音器填，合成出来的从 WAV 头里读。 */
    val durationMillis: Long = 0,
) {
    val sizeBytes: Int get() = bytes.size

    override fun toString(): String = "VoiceClip($mimeType, ${sizeBytes}B, ${durationMillis}ms)"
}
