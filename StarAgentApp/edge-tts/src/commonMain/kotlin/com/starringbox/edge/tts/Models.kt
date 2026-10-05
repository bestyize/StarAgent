package com.starringbox.edge.tts

/** The audio encodings understood by the Edge read-aloud endpoint. */
enum class EdgeTtsOutputFormat(
    val wireName: String,
    val mimeType: String,
    val bytesPerSecond: Int,
) {
    Audio24KhZ48KbpsMonoMp3(
        wireName = "audio-24khz-48kbitrate-mono-mp3",
        mimeType = "audio/mpeg",
        bytesPerSecond = 6_000,
    ),

}

enum class EdgeTtsBoundaryType {
    WordBoundary,
    SentenceBoundary,
}

data class EdgeTtsConfig(
    val voice: String = "zh-CN-XiaoxiaoNeural",
    val rate: String = "+0%",
    val volume: String = "+0%",
    val pitch: String = "+0Hz",
    val boundary: EdgeTtsBoundaryType = EdgeTtsBoundaryType.WordBoundary,
    val outputFormat: EdgeTtsOutputFormat = EdgeTtsOutputFormat.Audio24KhZ48KbpsMonoMp3,
    /** Maximum escaped UTF-8 bytes sent in one SSML request. */
    val maxTextBytes: Int = 4_096,
) {
    init {
        require(maxTextBytes >= 128) { "maxTextBytes must be at least 128" }
        require(rate.matches(Regex("^[+-]\\d+%$"))) { "Invalid rate '$rate'" }
        require(volume.matches(Regex("^[+-]\\d+%$"))) { "Invalid volume '$volume'" }
        require(pitch.matches(Regex("^[+-]\\d+Hz$"))) { "Invalid pitch '$pitch'" }
    }
}

data class EdgeTtsVoice(
    val name: String,
    val shortName: String,
    val gender: String,
    val locale: String,
    val suggestedCodec: String,
    val friendlyName: String,
    val status: String,
    val contentCategories: List<String> = emptyList(),
    val voicePersonalities: List<String> = emptyList(),
)

sealed interface EdgeTtsEvent {
    /** Audio bytes are emitted as they arrive and are not copied by the client. */
    class Audio(val bytes: ByteArray) : EdgeTtsEvent

    data class Boundary(
        val type: EdgeTtsBoundaryType,
        val text: String,
        val offsetMillis: Long,
        val durationMillis: Long,
    ) : EdgeTtsEvent

    data object Completed : EdgeTtsEvent
}

open class EdgeTtsException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class EdgeTtsProtocolException(message: String, cause: Throwable? = null) :
    EdgeTtsException(message, cause)

class EdgeTtsHttpException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : EdgeTtsException(message, cause)
