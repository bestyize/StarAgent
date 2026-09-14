package com.starringbox.staragent.core.voice

/**
 * 最小的 WAV 封装。
 *
 * 两个平台的录音器拿到的都是裸 PCM，而各家识别接口要的是"一个音频文件"。
 * 自己补 44 字节的头，比为了转格式引一个音频库划算得多。
 *
 * 统一用 PCM/WAV 还有个好处：播放侧两个平台都能直接放，
 * **不用引 MP3 解码器**（JDK 和 Android 的 framework 都不带）。
 */
object Wav {

    const val MIME_TYPE = "audio/wav"

    /** 16kHz 单声道 16 位——语音识别的通用规格，也是各家都收的。 */
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val HEADER_BYTES = 44

    /** 把裸 PCM 包成一个可传输的 WAV 文件。 */
    fun wrap(pcm: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val header = ByteArray(HEADER_BYTES)

        header.putAscii(0, "RIFF")
        header.putIntLe(4, 36 + pcm.size)
        header.putAscii(8, "WAVE")

        header.putAscii(12, "fmt ")
        header.putIntLe(16, 16) // PCM 的 fmt 块固定 16 字节
        header.putShortLe(20, 1) // 1 = PCM，未压缩
        header.putShortLe(22, CHANNELS)
        header.putIntLe(24, SAMPLE_RATE)
        header.putIntLe(28, byteRate)
        header.putShortLe(32, CHANNELS * BITS_PER_SAMPLE / 8)
        header.putShortLe(34, BITS_PER_SAMPLE)

        header.putAscii(36, "data")
        header.putIntLe(40, pcm.size)

        return header + pcm
    }

    /**
     * 从 WAV 头里读时长。不是 WAV、或者头被截断时返回 0——
     * 时长只用来做准入判断和界面显示，读不出来不该让整件事失败。
     */
    fun durationMillis(bytes: ByteArray): Long {
        if (bytes.size < HEADER_BYTES) return 0
        if (bytes.decodeToString(0, 4) != "RIFF" || bytes.decodeToString(8, 12) != "WAVE") return 0

        val byteRate = bytes.intLe(28)
        val dataSize = bytes.intLe(40)
        if (byteRate <= 0 || dataSize <= 0) return 0

        return dataSize.toLong() * 1000 / byteRate
    }
}

private fun ByteArray.putAscii(offset: Int, text: String) {
    text.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
}

private fun ByteArray.putIntLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    this[offset + 2] = ((value shr 16) and 0xFF).toByte()
    this[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun ByteArray.putShortLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.intLe(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
