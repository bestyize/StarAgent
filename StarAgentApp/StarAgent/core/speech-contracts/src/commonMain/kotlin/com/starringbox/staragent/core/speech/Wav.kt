package com.starringbox.staragent.core.speech

import kotlin.math.sqrt

/**
 * 最小的 WAV 封装。
 *
 * 两个平台的录音器拿到的都是裸 PCM，而各家识别接口要的是"一个音频文件"。
 * 自己补 44 字节的头，比为了转格式引一个音频库划算得多。
 *
 * 语音识别和大多数云端合成统一用 PCM/WAV。Edge TTS 公开端点固定返回 MP3，
 * 那一条由播放实现按 MIME 处理，不改变这里 WAV 契约的用途。
 */
object Wav {

    const val MIME_TYPE = "audio/wav"

    /** 16kHz 单声道 16 位——语音识别的通用规格，也是各家都收的。 */
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val HEADER_BYTES = 44

    /** 一帧 20ms。取的是音节的时间尺度：比一个字短，比一次停顿长。 */
    const val FRAME_MILLIS = 20

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

    /**
     * 有声帧的占比：切成 [FRAME_MILLIS] 一帧，数其中响度过 [floor] 的帧。
     *
     * 逐帧算而不是整段算一个均值，是因为均值会被稀释：一句两个字的话摊到
     * 三十秒里，整段 RMS 和一段安静没什么区别。逐帧看的话，说话的那几帧照样是响的。
     * 用帧 RMS 而不是峰值也是同一个道理反过来——一次桌面磕碰的峰值很高，
     * 但它只占一帧，摊到帧里立刻显出来它不是说话。
     *
     * [floor] 是满量程的占比（1.0 = 削顶）。不是 WAV、或者短到不足一帧时返回
     * null：**判断不了就不下判断**，让调用方按"没有依据"处理，而不是按"没声音"。
     */
    fun voicedFraction(bytes: ByteArray, floor: Double): Double? {
        if (bytes.size < HEADER_BYTES) return null
        if (bytes.decodeToString(0, 4) != "RIFF" || bytes.decodeToString(8, 12) != "WAVE") return null

        // 头里写的长度可能和实际字节数不一致（录音被截断就是这样），取小的那个
        val declared = bytes.intLe(40)
        val available = bytes.size - HEADER_BYTES
        val dataBytes = if (declared in 1..available) declared else available

        val perFrame = SAMPLE_RATE * FRAME_MILLIS / 1000
        val frames = dataBytes / 2 / perFrame
        if (frames <= 0) return null

        var voiced = 0
        for (frame in 0 until frames) {
            var energy = 0.0
            val base = HEADER_BYTES + frame * perFrame * 2
            for (i in 0 until perFrame) {
                val sample = bytes.sampleLe(base + i * 2) / FULL_SCALE
                energy += sample * sample
            }
            if (sqrt(energy / perFrame) >= floor) voiced++
        }
        return voiced.toDouble() / frames
    }

    /** 16 位有符号的满量程。 */
    private const val FULL_SCALE = 32_768.0
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

/** 一个 16 位小端有符号采样。要带符号——按无符号读会把负半周当成极大值。 */
private fun ByteArray.sampleLe(offset: Int): Int {
    val raw = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    return raw.toShort().toInt()
}

private fun ByteArray.intLe(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
