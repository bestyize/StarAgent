package com.starringbox.edge.tts

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A streaming client for Microsoft Edge's read-aloud endpoint.
 *
 * The network protocol is intentionally isolated here. Consumers only see
 * [EdgeTtsEvent] values and do not need to know about SSML or WebSocket frames.
 */
class EdgeTtsClient(
    val config: EdgeTtsConfig = EdgeTtsConfig(),
    private val client: HttpClient = defaultEdgeTtsHttpClient(),
    private val ownsClient: Boolean = true,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun voices(): List<EdgeTtsVoice> {
        val response = client.get(EdgeTtsProtocol.voicesUrl()) {
            applyBrowserHeaders()
        }
        if (response.status != HttpStatusCode.OK) {
            throw EdgeTtsHttpException(
                response.status.value,
                "Edge voice list failed with HTTP ${response.status.value}",
            )
        }
        val raw = response.bodyAsText()
        return try {
            json.decodeFromString<List<VoiceDto>>(raw).map { it.toPublic() }
        } catch (failure: Exception) {
            throw EdgeTtsProtocolException("Edge voice list response could not be parsed", failure)
        }
    }

    /**
     * Stream audio and metadata for [text].
     *
     * Long input is split before XML escaping, so an XML entity is never cut in
     * half by the request size limit. Each split request uses a fresh connection,
     * matching the behavior of the Edge client.
     */
    fun synthesize(
        text: String,
        outputFormat: EdgeTtsOutputFormat = config.outputFormat,
        boundary: EdgeTtsBoundaryType = config.boundary,
    ): Flow<EdgeTtsEvent> = flow {
        val chunks = Utf8TextChunker.split(text, config.maxTextBytes)
        if (chunks.isEmpty()) {
            emit(EdgeTtsEvent.Completed)
            return@flow
        }

        var cumulativeAudioBytes = 0L
        var receivedAudio = false
        for (chunk in chunks) {
            var chunkAudioBytes = 0L
            client.webSocket(
                urlString = EdgeTtsProtocol.webSocketUrl(EdgeTtsProtocol.connectionId()),
                request = {
                    applyBrowserHeaders()
                    header("Pragma", "no-cache")
                    header("Cache-Control", "no-cache")
                },
            ) {
                send(io.ktor.websocket.Frame.Text(EdgeTtsProtocol.speechConfig(config.copy(outputFormat = outputFormat, boundary = boundary))))
                send(
                    io.ktor.websocket.Frame.Text(
                        EdgeTtsProtocol.ssmlRequest(
                            requestId = EdgeTtsProtocol.connectionId(),
                            config = config.copy(outputFormat = outputFormat, boundary = boundary),
                            text = chunk,
                        ),
                    ),
                )

                for (frame in incoming) {
                    when (frame) {
                        is io.ktor.websocket.Frame.Text -> {
                            val response = parseTextResponse(frame.readText())
                            when (response.path) {
                                "audio.metadata" -> {
                                    val metadata = parseMetadata(response.payload)
                                    metadata.forEach { item ->
                                        val type = when (item.Type) {
                                            "WordBoundary" -> EdgeTtsBoundaryType.WordBoundary
                                            "SentenceBoundary" -> EdgeTtsBoundaryType.SentenceBoundary
                                            "SessionEnd" -> return@forEach
                                            else -> throw EdgeTtsProtocolException("Unknown metadata type: ${item.Type}")
                                        }
                                        val offset = (item.Data.Offset + cumulativeAudioBytes * EdgeTtsProtocol.TICKS_PER_SECOND /
                                            outputFormat.bytesPerSecond) / 10_000
                                        val duration = item.Data.Duration / 10_000
                                        emit(
                                            EdgeTtsEvent.Boundary(
                                                type = type,
                                                text = item.Data.text?.Text.orEmpty(),
                                                offsetMillis = offset,
                                                durationMillis = duration,
                                            ),
                                        )
                                    }
                                }
                                "turn.end" -> {
                                    cumulativeAudioBytes += chunkAudioBytes
                                    chunkAudioBytes = 0
                                    break
                                }
                                "response", "turn.start" -> Unit
                                else -> throw EdgeTtsProtocolException("Unknown text response path: ${response.path}")
                            }
                        }
                        is io.ktor.websocket.Frame.Binary -> {
                            val audioFrame = parseBinaryAudioFrame(frame.readBytes())
                            if (audioFrame.data.isEmpty()) continue
                            if (audioFrame.headers["Path"] != "audio") {
                                throw EdgeTtsProtocolException("Binary response path is not audio")
                            }
                            chunkAudioBytes += audioFrame.data.size
                            receivedAudio = true
                            emit(EdgeTtsEvent.Audio(audioFrame.data))
                        }
                        is io.ktor.websocket.Frame.Close -> break
                        else -> Unit
                    }
                }
            }
            cumulativeAudioBytes += chunkAudioBytes
        }

        if (!receivedAudio) {
            throw EdgeTtsProtocolException(
                "Edge returned no audio for ${outputFormat.wireName} and the supplied text",
            )
        }
        emit(EdgeTtsEvent.Completed)
    }

    suspend fun synthesizeToBytes(
        text: String,
        outputFormat: EdgeTtsOutputFormat = config.outputFormat,
        boundary: EdgeTtsBoundaryType = config.boundary,
    ): ByteArray {
        val output = ArrayList<ByteArray>()
        synthesize(text, outputFormat, boundary).collect { event ->
            if (event is EdgeTtsEvent.Audio) output += event.bytes
        }
        val total = output.sumOf { it.size }
        val result = ByteArray(total)
        var offset = 0
        output.forEach { bytes ->
            bytes.copyInto(result, offset)
            offset += bytes.size
        }
        return result
    }

    fun close() {
        if (ownsClient) client.close()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyBrowserHeaders() {
        header(HttpHeaders.UserAgent, EdgeTtsProtocol.USER_AGENT)
        header(HttpHeaders.Accept, "*/*")
        header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
        header(HttpHeaders.Origin, EdgeTtsProtocol.ORIGIN)
        header("Cookie", "muid=${EdgeTtsProtocol.muid()};")
    }

    private fun parseMetadata(raw: String): List<MetadataItem> = try {
        metadataJson.decodeFromString<MetadataEnvelope>(raw).metadata
    } catch (failure: Exception) {
        throw EdgeTtsProtocolException("Edge metadata response could not be parsed", failure)
    }

    @Serializable
    private data class VoiceDto(
        val Name: String = "",
        val ShortName: String = "",
        val Gender: String = "",
        val Locale: String = "",
        val SuggestedCodec: String = "",
        val FriendlyName: String = "",
        val Status: String = "",
        val VoiceTag: VoiceTagDto? = null,
    ) {
        fun toPublic() = EdgeTtsVoice(
            name = Name,
            shortName = ShortName,
            gender = Gender,
            locale = Locale,
            suggestedCodec = SuggestedCodec,
            friendlyName = FriendlyName,
            status = Status,
            contentCategories = VoiceTag?.ContentCategories.orEmpty(),
            voicePersonalities = VoiceTag?.VoicePersonalities.orEmpty(),
        )
    }

    @Serializable
    private data class VoiceTagDto(
        val ContentCategories: List<String> = emptyList(),
        val VoicePersonalities: List<String> = emptyList(),
    )
}
