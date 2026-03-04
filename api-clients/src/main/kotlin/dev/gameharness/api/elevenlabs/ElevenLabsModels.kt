package dev.gameharness.api.elevenlabs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for the ElevenLabs sound-effect generation endpoint. */
@Serializable
data class ElevenLabsSfxRequest(
    val text: String,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("prompt_influence") val promptInfluence: Double = 0.3
)
