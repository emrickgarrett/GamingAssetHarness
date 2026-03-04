package dev.gameharness.api.suno

import dev.gameharness.api.common.*
import dev.gameharness.core.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Client for the Suno music generation API that produces MP3 audio tracks.
 *
 * Generation follows a create-then-poll pipeline: a creation request returns a clip ID,
 * which is polled until the clip status becomes [STATUS_COMPLETE]. Progress is reported
 * via [onProgress].
 *
 * @param apiKey Suno API key for authentication
 * @param baseUrl API base URL (defaults to [DEFAULT_BASE_URL])
 * @param httpClient Ktor HTTP client (defaults to [createDefaultHttpClient])
 * @param pollInterval delay between consecutive status polls
 * @param maxPollDuration maximum wall-clock time to wait before timing out
 */
class SunoClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val pollInterval: Duration = 5.seconds,
    private val maxPollDuration: Duration = 5.minutes
) : AssetGenerationClient {

    override val assetType = AssetType.MUSIC

    /** Optional callback invoked during polling with (progressPercent, statusMessage). */
    var onProgress: ((Int, String) -> Unit)? = null

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        val instrumental = request.params["instrumental"]?.toBoolean() ?: true

        val sunoRequest = SunoCreateRequest(
            prompt = buildPrompt(request),
            makeInstrumental = instrumental
        )

        // Step 1: Create generation
        val createResponse = try {
            val response = httpClient.post("$baseUrl/api/generate") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(sunoRequest)
            }

            when (response.status.value) {
                200, 201, 202 -> response.body<SunoCreateResponse>()
                401, 403 -> throw ApiException.AuthenticationFailed("Invalid Suno API key")
                429 -> throw ApiException.RateLimited()
                else -> throw ApiException.GenerationFailed("Suno API error: HTTP ${response.status.value}")
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            return GenerationResult.Failed("Network error: ${e.message}", retryable = true)
        }

        val clipId = createResponse.clips?.firstOrNull()?.id
            ?: createResponse.id
            ?: return GenerationResult.Failed("No clip ID in Suno response")

        // Step 2: Poll for completion
        onProgress?.invoke(10, "Generating music...")
        val completedClip = pollClip(clipId)

        // Step 3: Download audio
        onProgress?.invoke(90, "Downloading audio...")
        val audioUrl = completedClip.audioUrl
            ?: return GenerationResult.Failed("No audio URL in completed clip")

        val audioBytes = httpClient.downloadFileBytes(audioUrl, "audio")
        val (assetId, fileName) = generateAssetIdentifiers(request.description, "mp3")

        val asset = GeneratedAsset(
            id = assetId,
            type = AssetType.MUSIC,
            fileName = fileName,
            filePath = "",
            format = "mp3",
            description = request.description,
            generationParams = request.params,
            sizeBytes = audioBytes.size.toLong()
        )

        return GenerationResult.Completed(asset, audioBytes)
    }

    internal suspend fun pollClip(clipId: String): SunoClip {
        // The generic polling function returns SunoClip? because the poll lambda may
        // return null when the response can't be parsed. isComplete guarantees non-null.
        val result: SunoClip? = pollUntilComplete(
            maxDuration = maxPollDuration,
            pollInterval = pollInterval,
            onProgress = onProgress,
            progressMessage = { elapsed -> "Generating music... (${elapsed}s)" },
            baseProgress = 10,
            maxProgress = 89,
            poll = {
                val response = httpClient.get("$baseUrl/api/get?ids=$clipId") {
                    header("Authorization", "Bearer $apiKey")
                }
                if (response.status.value == 401 || response.status.value == 403) {
                    throw ApiException.AuthenticationFailed("Invalid Suno API key")
                }
                val clips = try {
                    response.body<List<SunoClip>>()
                } catch (_: Exception) {
                    null
                }
                clips?.firstOrNull()
            },
            isComplete = { it != null && it.status == STATUS_COMPLETE },
            isFailed = { clip ->
                if (clip != null && clip.status == STATUS_ERROR) "Suno generation failed for clip $clipId"
                else null
            },
            timeoutMessage = "Suno generation timed out after $maxPollDuration"
        )
        return result!!
    }

    private fun buildPrompt(request: GenerationRequest): String {
        val genre = request.params["genre"] ?: ""
        val mood = request.params["mood"] ?: ""
        return buildString {
            append(request.description)
            if (genre.isNotBlank()) append(". Genre: $genre")
            if (mood.isNotBlank()) append(". Mood: $mood")
            append(". Suitable for use in a video game.")
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://studio-api.suno.ai"

        /** Clip status returned when generation completes successfully. */
        const val STATUS_COMPLETE = "complete"
        /** Clip status returned when generation fails. */
        const val STATUS_ERROR = "error"
    }
}
