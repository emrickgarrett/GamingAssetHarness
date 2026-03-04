package dev.gameharness.api.elevenlabs

import dev.gameharness.api.common.ApiException
import dev.gameharness.api.common.AssetGenerationClient
import dev.gameharness.api.common.createDefaultHttpClient
import dev.gameharness.api.common.generateAssetIdentifiers
import dev.gameharness.core.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

/**
 * Client for the ElevenLabs sound-effect generation API that produces MP3 audio files.
 *
 * Unlike the polling-based clients, ElevenLabs uses a single-shot request that returns
 * the audio bytes directly in the response body.
 *
 * @param apiKey ElevenLabs API key for authentication (sent as `xi-api-key` header)
 * @param httpClient Ktor HTTP client (defaults to [createDefaultHttpClient])
 */
class ElevenLabsClient(
    private val apiKey: String,
    private val httpClient: HttpClient = createDefaultHttpClient()
) : AssetGenerationClient {

    override val assetType = AssetType.SOUND_EFFECT

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        val duration = request.params["duration"]?.toDoubleOrNull()

        val sfxRequest = ElevenLabsSfxRequest(
            text = request.description,
            durationSeconds = duration,
            promptInfluence = 0.3
        )

        val response = try {
            httpClient.post(ENDPOINT) {
                header("xi-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(sfxRequest)
            }
        } catch (e: Exception) {
            return GenerationResult.Failed(
                error = "Network error: ${e.message}",
                retryable = true
            )
        }

        return when (response.status.value) {
            200 -> {
                val audioBytes: ByteArray = response.body()
                val (assetId, fileName) = generateAssetIdentifiers(request.description, "mp3")

                val asset = GeneratedAsset(
                    id = assetId,
                    type = AssetType.SOUND_EFFECT,
                    fileName = fileName,
                    filePath = "",
                    format = "mp3",
                    description = request.description,
                    generationParams = request.params,
                    sizeBytes = audioBytes.size.toLong()
                )

                GenerationResult.Completed(asset, audioBytes)
            }
            401, 403 -> throw ApiException.AuthenticationFailed("Invalid ElevenLabs API key")
            429 -> throw ApiException.RateLimited()
            else -> GenerationResult.Failed(
                error = "ElevenLabs API error: HTTP ${response.status.value}",
                retryable = response.status.value >= 500
            )
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val ENDPOINT = "https://api.elevenlabs.io/v1/sound-generation"
    }
}
