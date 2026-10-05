package com.starringbox.staragent.core.speech.cloud

import com.starringbox.edge.tts.EdgeTtsClient
import com.starringbox.staragent.core.model.SpeechVoiceOption

/** Load Edge's current voice catalog while keeping provider DTOs out of the UI layer. */
suspend fun listEdgeTtsVoices(): List<SpeechVoiceOption> {
    val client = EdgeTtsClient()
    return try {
        client.voices()
            .map { voice ->
                SpeechVoiceOption(
                    id = voice.shortName,
                    displayName = voice.friendlyName.ifBlank { voice.shortName },
                    locale = voice.locale,
                    gender = voice.gender,
                )
            }
            .sortedWith(
                compareBy(
                    { voice -> localePriority(voice.locale) },
                    { it.locale },
                    { it.displayName },
                ),
            )
    } finally {
        client.close()
    }
}

private fun localePriority(locale: String): Int = when {
    locale.startsWith("zh-CN") -> 0
    locale.startsWith("zh-TW") || locale.startsWith("zh-HK") -> 1
    locale.startsWith("en-") -> 2
    locale.startsWith("ja-") || locale.startsWith("ko-") -> 3
    else -> 4
}
