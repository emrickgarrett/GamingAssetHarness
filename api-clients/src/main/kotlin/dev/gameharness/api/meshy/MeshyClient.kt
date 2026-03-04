package dev.gameharness.api.meshy

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
 * Client for the Meshy text-to-3D API that generates GLB models with PBR textures.
 *
 * Generation follows a two-stage pipeline: a fast *preview* task produces a low-fidelity
 * model, which is then *refined* into a high-quality GLB. Both stages are polled until
 * completion, with progress reported via [onProgress].
 *
 * @param apiKey Meshy API key for authentication
 * @param httpClient Ktor HTTP client (defaults to [createDefaultHttpClient])
 * @param pollInterval delay between consecutive status polls
 * @param maxPollDuration maximum wall-clock time to wait for each stage before timing out
 */
class MeshyClient(
    private val apiKey: String,
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val pollInterval: Duration = 5.seconds,
    private val maxPollDuration: Duration = 10.minutes
) : AssetGenerationClient {

    override val assetType = AssetType.MODEL_3D

    /** Optional callback invoked during polling with (progressPercent, statusMessage). */
    var onProgress: ((Int, String) -> Unit)? = null

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        // Stage 1: Create and poll preview task
        onProgress?.invoke(5, "Creating preview task...")
        val previewTaskId = createTask(request, "preview")

        onProgress?.invoke(10, "Generating preview...")
        val previewResult = pollTask(previewTaskId, baseProgress = 10, maxProgress = 45)

        // Stage 2: Create and poll refine task
        onProgress?.invoke(50, "Creating refine task...")
        val refineTaskId = createRefineTask(previewTaskId)

        onProgress?.invoke(55, "Refining model...")
        val refineResult = pollTask(refineTaskId, baseProgress = 55, maxProgress = 85)

        // Stage 3: Download final model
        onProgress?.invoke(90, "Downloading model...")
        val modelUrl = refineResult.modelUrls?.glb
            ?: return GenerationResult.Failed("No GLB model URL in response")

        val modelBytes = httpClient.downloadFileBytes(modelUrl, "model file")
        val (assetId, fileName) = generateAssetIdentifiers(request.description, "glb")

        val asset = GeneratedAsset(
            id = assetId,
            type = AssetType.MODEL_3D,
            fileName = fileName,
            filePath = "",
            format = "glb",
            description = request.description,
            generationParams = request.params,
            sizeBytes = modelBytes.size.toLong()
        )

        return GenerationResult.Completed(asset, modelBytes)
    }

    private suspend fun createTask(request: GenerationRequest, mode: String): String {
        val meshyRequest = MeshyCreateRequest(
            prompt = request.description,
            mode = mode,
            artStyle = request.params["artStyle"] ?: "realistic"
        )

        val response = httpClient.post(BASE_URL) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(meshyRequest)
        }

        return when (response.status.value) {
            200, 201, 202 -> response.body<MeshyCreateResponse>().result
            401, 403 -> throw ApiException.AuthenticationFailed("Invalid Meshy API key")
            429 -> throw ApiException.RateLimited()
            else -> throw ApiException.GenerationFailed("Failed to create Meshy task: HTTP ${response.status.value}")
        }
    }

    private suspend fun createRefineTask(previewTaskId: String): String {
        val response = httpClient.post(BASE_URL) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(MeshyRefineRequest(previewTaskId = previewTaskId))
        }

        return when (response.status.value) {
            200, 201, 202 -> response.body<MeshyCreateResponse>().result
            else -> throw ApiException.GenerationFailed("Failed to create refine task: HTTP ${response.status.value}")
        }
    }

    internal suspend fun pollTask(
        taskId: String,
        baseProgress: Int = 10,
        maxProgress: Int = 89
    ): MeshyTaskResponse = pollUntilComplete(
        maxDuration = maxPollDuration,
        pollInterval = pollInterval,
        onProgress = onProgress,
        progressMessage = { elapsed -> "Processing 3D model... (${elapsed}s)" },
        baseProgress = baseProgress,
        maxProgress = maxProgress,
        poll = {
            val response = httpClient.get("$BASE_URL/$taskId") {
                header("Authorization", "Bearer $apiKey")
            }
            if (response.status.value == 401 || response.status.value == 403) {
                throw ApiException.AuthenticationFailed("Invalid Meshy API key")
            }
            response.body<MeshyTaskResponse>()
        },
        isComplete = { it.status == STATUS_SUCCEEDED },
        isFailed = { task ->
            when (task.status) {
                STATUS_FAILED, STATUS_EXPIRED ->
                    task.taskError?.message ?: "Task $taskId failed with status: ${task.status}"
                else -> null
            }
        },
        timeoutMessage = "Meshy task $taskId timed out after $maxPollDuration"
    )

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val BASE_URL = "https://api.meshy.ai/openapi/v2/text-to-3d"

        /** Task status returned when generation completes successfully. */
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        /** Task status returned when generation fails. */
        const val STATUS_FAILED = "FAILED"
        /** Task status returned when the task expires before completing. */
        const val STATUS_EXPIRED = "EXPIRED"
    }
}
