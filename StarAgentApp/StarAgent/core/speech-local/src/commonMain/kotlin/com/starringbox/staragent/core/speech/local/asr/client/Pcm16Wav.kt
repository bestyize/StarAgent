package com.starringbox.staragent.core.speech.local.asr.client

/** Parse a 16 kHz mono PCM16 RIFF/WAVE file without loading the native recognizer. */
internal fun pcm16WavToFloat(wav: ByteArray): FloatArray {
    fun u16(at: Int) = (wav[at].toInt() and 255) or ((wav[at + 1].toInt() and 255) shl 8)
    fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
    fun tag(at: Int) = buildString(4) { repeat(4) { append(wav[at + it].toInt().toChar()) } }

    require(wav.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "需要 WAV 录音" }
    var offset = 12
    var formatOk = false
    var data = -1
    var length = 0
    while (offset + 8 <= wav.size) {
        val size = u32(offset + 4)
        require(size <= wav.size.toLong() - offset - 8) { "WAV 数据不完整" }
        when (tag(offset)) {
            "fmt " -> {
                require(size >= 16) { "WAV 格式信息不完整" }
                formatOk = u16(offset + 8) == 1 && u16(offset + 10) == 1 &&
                    u32(offset + 12) == 16_000L && u16(offset + 22) == 16
            }
            "data" -> {
                data = offset + 8
                length = size.toInt()
            }
        }
        offset += 8 + size.toInt() + (size.toInt() and 1)
    }
    require(formatOk && data >= 0 && length >= 2 && length % 2 == 0) {
        "仅支持 16 kHz 单声道 PCM16 WAV"
    }
    return FloatArray(length / 2) { index -> u16(data + index * 2).toShort() / 32768f }
}
