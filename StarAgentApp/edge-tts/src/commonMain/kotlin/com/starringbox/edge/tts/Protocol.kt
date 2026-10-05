package com.starringbox.edge.tts

import kotlin.random.Random
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

internal object EdgeTtsProtocol {
    const val BASE_HOST = "speech.platform.bing.com"
    const val BASE_PATH = "/consumer/speech/synthesize/readaloud"
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
    const val WIN_EPOCH_SECONDS = 11_644_473_600L
    const val TICKS_PER_SECOND = 10_000_000L

    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    fun webSocketUrl(connectionId: String, unixSeconds: Long = nowSeconds()): String =
        "wss://$BASE_HOST$BASE_PATH/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${secMsGec(unixSeconds)}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    fun voicesUrl(unixSeconds: Long = nowSeconds()): String =
        "https://$BASE_HOST$BASE_PATH/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=${secMsGec(unixSeconds)}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    /**
     * Generate the short-lived token used by both the voice-list endpoint and WebSocket.
     *
     * The service rounds the timestamp to a five-minute window before hashing it with
     * the public trusted-client token. The token is not a user API key and must not be
     * treated as one by callers.
     */
    fun secMsGec(unixSeconds: Long = nowSeconds()): String {
        val windowsSeconds = unixSeconds + WIN_EPOCH_SECONDS
        val roundedSeconds = windowsSeconds - windowsSeconds.mod(300L)
        val ticks = roundedSeconds * TICKS_PER_SECOND
        return Sha256.hexUpper("$ticks$TRUSTED_CLIENT_TOKEN".encodeToByteArray())
    }

    fun connectionId(): String = randomHex(16)

    fun muid(): String = randomHex(16)

    fun serviceTimestamp(unixSeconds: Long = nowSeconds()): String {
        val local = Instant.fromEpochMilliseconds(unixSeconds * 1_000)
            .toLocalDateTime(TimeZone.UTC)
        val weekday = local.dayOfWeek.name.take(3).replaceFirstChar { it.uppercaseChar() }
        val month = local.month.name.take(3).replaceFirstChar { it.uppercaseChar() }
        val day = local.day.toString().padStart(2, '0')
        val year = local.year.toString().padStart(4, '0')
        val hour = local.hour.toString().padStart(2, '0')
        val minute = local.minute.toString().padStart(2, '0')
        val second = local.second.toString().padStart(2, '0')
        return "$weekday $month $day $year $hour:$minute:$second GMT+0000 (Coordinated Universal Time)"
    }

    fun normalizeVoice(voice: String): String {
        val value = voice.trim()
        if (value.startsWith("Microsoft Server Speech Text to Speech Voice")) return value
        val match = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(value)
            ?: return value
        val language = match.groupValues[1]
        var region = match.groupValues[2]
        var name = match.groupValues[3]
        val hyphen = name.indexOf('-')
        if (hyphen >= 0) {
            region = "$region-${name.substring(0, hyphen)}"
            name = name.substring(hyphen + 1)
        }
        return "Microsoft Server Speech Text to Speech Voice ($language-$region, $name)"
    }

    fun ssml(config: EdgeTtsConfig, text: String): String {
        val voice = normalizeVoice(config.voice).escapeXml()
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='${config.pitch.escapeXml()}' rate='${config.rate.escapeXml()}' " +
            "volume='${config.volume.escapeXml()}'>${text.escapeXml()}</prosody></voice></speak>"
    }

    fun speechConfig(config: EdgeTtsConfig): String {
        val wordBoundary = if (config.boundary == EdgeTtsBoundaryType.WordBoundary) "true" else "false"
        val sentenceBoundary = if (config.boundary == EdgeTtsBoundaryType.SentenceBoundary) "true" else "false"
        return "X-Timestamp:${serviceTimestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"$sentenceBoundary","wordBoundaryEnabled":"$wordBoundary"},"outputFormat":"${config.outputFormat.wireName}"}}}}"""
    }

    fun ssmlRequest(requestId: String, config: EdgeTtsConfig, text: String): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${serviceTimestamp()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml(config, text)

    private fun nowSeconds(): Long = Clock.System.now().toEpochMilliseconds() / 1_000

    private fun randomHex(byteCount: Int): String {
        val bytes = Random.nextBytes(byteCount)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase() }
    }
}

internal fun String.escapeXml(): String = buildString(length) {
    for (char in this@escapeXml) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(char)
        }
    }
}

internal fun String.sanitizeControlCharacters(): String = buildString(length) {
    for (char in this@sanitizeControlCharacters) {
        val code = char.code
        append(if (code in 0..8 || code in 11..12 || code in 14..31) ' ' else char)
    }
}

internal object Utf8TextChunker {
    fun split(text: String, maxBytes: Int = 4_096): List<String> {
        require(maxBytes >= 4) { "maxBytes must be at least 4" }
        val sanitized = text.sanitizeControlCharacters()
        if (sanitized.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        var start = 0
        while (start < sanitized.length) {
            var end = start
            var usedBytes = 0
            while (end < sanitized.length) {
                val width = sanitized.codePointWidth(end)
                val bytes = sanitized.utf8Width(end, width)
                if (usedBytes + bytes > maxBytes) break
                usedBytes += bytes
                end += width
            }
            if (end == start) {
                end += sanitized.codePointWidth(start)
            }

            // Prefer a natural boundary without making tiny chunks.
            val preferred = (end - 1 downTo start + maxOf(1, (end - start) / 2))
                .firstOrNull { sanitized[it].isWhitespace() }
            if (preferred != null) end = preferred

            val chunk = sanitized.substring(start, end).trim()
            if (chunk.isNotEmpty()) result += chunk
            start = end
        }
        return result
    }

    private fun String.codePointWidth(index: Int): Int {
        val current = this[index]
        return if (current.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
    }

    private fun String.utf8Width(index: Int, width: Int): Int {
        val code = if (width == 2) {
            0x10000 + ((this[index].code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
        } else {
            this[index].code
        }
        return when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            code < 0x10000 -> 3
            else -> 4
        }
    }
}

internal data class BinaryAudioFrame(
    val headers: Map<String, String>,
    val data: ByteArray,
)

internal fun parseBinaryAudioFrame(frame: ByteArray): BinaryAudioFrame {
    if (frame.size < 2) throw EdgeTtsProtocolException("Binary response is missing its header length")
    val headerLength = ((frame[0].toInt() and 0xff) shl 8) or (frame[1].toInt() and 0xff)
    // The service closes an audio stream with a two-byte zero-length binary frame.
    if (frame.size == 2 && headerLength == 0) return BinaryAudioFrame(emptyMap(), ByteArray(0))
    // Unlike ordinary HTTP framing, Edge's length includes the two-byte length
    // prefix. The separator itself is the following CRLF pair.
    if (headerLength < 4 || headerLength + 2 > frame.size ||
        frame[headerLength] != '\r'.code.toByte() ||
        frame[headerLength + 1] != '\n'.code.toByte()
    ) {
        throw EdgeTtsProtocolException("Binary response has an invalid header length")
    }
    val headers = parseHeaders(frame.decodeToString(2, headerLength))
    return BinaryAudioFrame(headers, frame.copyOfRange(headerLength + 2, frame.size))
}

internal data class TextResponse(
    val path: String,
    val headers: Map<String, String>,
    val payload: String,
)

internal fun parseTextResponse(text: String): TextResponse {
    val separator = text.indexOf("\r\n\r\n")
    if (separator < 0) throw EdgeTtsProtocolException("Text response is missing the header separator")
    val headers = parseHeaders(text.substring(0, separator))
    val path = headers["Path"] ?: headers["path"]
        ?: throw EdgeTtsProtocolException("Text response is missing Path")
    return TextResponse(path, headers, text.substring(separator + 4))
}

private fun parseHeaders(raw: String): Map<String, String> =
    raw.lineSequence()
        .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        .toMap()

@Serializable
internal data class MetadataEnvelope(
    @SerialName("Metadata") val metadata: List<MetadataItem> = emptyList(),
)

@Serializable
internal data class MetadataItem(
    val Type: String,
    val Data: MetadataData,
)

@Serializable
internal data class MetadataData(
    val Offset: Long = 0,
    val Duration: Long = 0,
    val text: MetadataText? = null,
)

@Serializable
internal data class MetadataText(val Text: String = "")

internal val metadataJson = Json { ignoreUnknownKeys = true }
