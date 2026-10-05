package com.starringbox.staragent.core.model

/** A selectable TTS voice independent of any provider-specific response shape. */
data class SpeechVoiceOption(
    val id: String,
    val displayName: String,
    val locale: String,
    val gender: String,
)
