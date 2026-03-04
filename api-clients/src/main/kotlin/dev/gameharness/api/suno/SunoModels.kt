package dev.gameharness.api.suno

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for creating a Suno music generation task. */
@Serializable
data class SunoCreateRequest(
    val prompt: String,
    @SerialName("make_instrumental") val makeInstrumental: Boolean = true,
    @SerialName("wait_audio") val waitAudio: Boolean = false
)

/** Response from the Suno creation endpoint, containing an ID and/or initial clip list. */
@Serializable
data class SunoCreateResponse(
    val id: String? = null,
    val clips: List<SunoClip>? = null,
    val status: String? = null
)

/** A single audio clip returned by the Suno API, polled until [status] is terminal. */
@Serializable
data class SunoClip(
    val id: String,
    val status: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    val title: String? = null,
    val duration: Double? = null
)

/** Alternative status response format from the Suno API. */
@Serializable
data class SunoStatusResponse(
    val id: String? = null,
    val status: String? = null,
    val clips: List<SunoClip>? = null
)
