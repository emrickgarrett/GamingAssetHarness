package dev.gameharness.api.meshy

import dev.gameharness.api.common.*
import dev.gameharness.core.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.Base64
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
        val imageUrl = request.params["imageUrl"]
        return if (imageUrl != null) {
            generateFromImage(imageUrl, request)
        } else {
            generateFromText(request)
        }
    }

    private suspend fun generateFromText(request: GenerationRequest): GenerationResult {
        val format = request.params["format"] ?: "glb"

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
        return downloadModel(refineResult, format, request)
    }

    /**
     * Generates a 3D model from an image URL using the Meshy image-to-3D API.
     *
     * Unlike text-to-3D, image-to-3D uses a single-stage pipeline: one task is
     * created and polled until completion (no separate preview/refine stages).
     */
    private suspend fun generateFromImage(imageUrl: String, request: GenerationRequest): GenerationResult {
        val format = request.params["format"] ?: "glb"

        onProgress?.invoke(5, "Creating image-to-3D task...")
        val taskId = createImageTo3dTask(imageUrl, request.params["poseMode"] ?: "")

        onProgress?.invoke(10, "Converting image to 3D model...")
        val result = pollTask(taskId, baseProgress = 10, maxProgress = 85, pollBaseUrl = IMAGE_TO_3D_URL)

        return downloadModel(result, format, request)
    }

    private suspend fun downloadModel(
        taskResult: MeshyTaskResponse,
        format: String,
        request: GenerationRequest
    ): GenerationResult {
        onProgress?.invoke(90, "Downloading model...")
        val modelUrl = selectModelUrl(taskResult, format)
            ?: return GenerationResult.Failed("No ${format.uppercase()} model URL in response")

        val modelBytes = httpClient.downloadFileBytes(modelUrl, "model file")
        val (assetId, fileName) = generateAssetIdentifiers(request.description, format)

        val asset = GeneratedAsset(
            id = assetId,
            type = AssetType.MODEL_3D,
            fileName = fileName,
            filePath = "",
            format = format,
            description = request.description,
            generationParams = request.params,
            sizeBytes = modelBytes.size.toLong()
        )

        return GenerationResult.Completed(asset, modelBytes)
    }

    private fun selectModelUrl(task: MeshyTaskResponse, format: String): String? =
        when (format) {
            "fbx" -> task.modelUrls?.fbx
            "obj" -> task.modelUrls?.obj
            else -> task.modelUrls?.glb
        }

    private suspend fun createTask(request: GenerationRequest, mode: String): String {
        val meshyRequest = MeshyCreateRequest(
            prompt = request.description,
            mode = mode,
            artStyle = request.params["artStyle"] ?: "realistic",
            poseMode = request.params["poseMode"] ?: ""
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

    private suspend fun createImageTo3dTask(imageUrl: String, poseMode: String = ""): String {
        val response = httpClient.post(IMAGE_TO_3D_URL) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(MeshyImageTo3dRequest(imageUrl = imageUrl, poseMode = poseMode))
        }

        return when (response.status.value) {
            200, 201, 202 -> response.body<MeshyCreateResponse>().result
            401, 403 -> throw ApiException.AuthenticationFailed("Invalid Meshy API key")
            429 -> throw ApiException.RateLimited()
            else -> throw ApiException.GenerationFailed("Failed to create image-to-3D task: HTTP ${response.status.value}")
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

    /** A finished auto-rig: the task ID (needed to request animations) and the files it produced. */
    data class RigOutcome(
        val rigTaskId: String,
        val riggedGlbUrl: String,
        val walkingGlbUrl: String?,
        val runningGlbUrl: String?
    )

    /**
     * Auto-rigs a humanoid GLB (sent inline as a data URI). Meshy only rigs bipedal characters
     * that face +Z and have at most 380k triangles.
     */
    suspend fun rig(modelGlb: ByteArray, heightMeters: Double, skeletonTemplate: String = ""): RigOutcome {
        onProgress?.invoke(5, "Uploading model for rigging...")
        val dataUri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(modelGlb)
        val taskId = if (skeletonTemplate.isEmpty()) {
            createV1Task(RIGGING_URL, MeshyRigRequest(dataUri, heightMeters), "rigging")
        } else {
            createV1Task(RIGGING_URL, MeshyRigTemplateRequest(dataUri, heightMeters, skeletonTemplate), "rigging")
        }
        onProgress?.invoke(10, "Rigging character...")
        val task = pollUntilComplete(
            maxDuration = maxPollDuration,
            pollInterval = pollInterval,
            onProgress = onProgress,
            progressMessage = { elapsed -> "Rigging character... (${elapsed}s)" },
            baseProgress = 10,
            maxProgress = 90,
            poll = { getV1Task<MeshyRigTaskResponse>("$RIGGING_URL/$taskId") },
            isComplete = { it.status == STATUS_SUCCEEDED },
            isFailed = { failureMessage(taskId, it.status, it.taskError) },
            timeoutMessage = "Meshy rigging task $taskId timed out after $maxPollDuration"
        )
        val result = task.result
        val rigged = result?.riggedCharacterGlbUrl
            ?: throw ApiException.GenerationFailed("Rigging task $taskId returned no GLB")
        return RigOutcome(taskId, rigged, result.basicAnimations?.walkingGlbUrl, result.basicAnimations?.runningGlbUrl)
    }

    /**
     * Applies 1-10 animation-library actions to a rigged character and returns the URL of a GLB
     * holding one clip per action, in the order given.
     */
    suspend fun animate(rigTaskId: String, actionIds: List<Int>): String {
        require(actionIds.size in 1..10) { "Meshy accepts 1 to 10 actions per animation task" }
        onProgress?.invoke(5, "Requesting animations...")
        val taskId = createV1Task(ANIMATIONS_URL, MeshyAnimationRequest(rigTaskId, actionIds), "animation")
        val task = pollUntilComplete(
            maxDuration = maxPollDuration,
            pollInterval = pollInterval,
            onProgress = onProgress,
            progressMessage = { elapsed -> "Animating character... (${elapsed}s)" },
            baseProgress = 10,
            maxProgress = 90,
            poll = { getV1Task<MeshyAnimationTaskResponse>("$ANIMATIONS_URL/$taskId") },
            isComplete = { it.status == STATUS_SUCCEEDED },
            isFailed = { failureMessage(taskId, it.status, it.taskError) },
            timeoutMessage = "Meshy animation task $taskId timed out after $maxPollDuration"
        )
        return task.result?.animationGlbUrl
            ?: throw ApiException.GenerationFailed("Animation task $taskId returned no GLB")
    }

    /**
     * Makes a model from one view (image-to-3D) or 2-4 views of one object (multi-image-to-3D), the images sent
     * inline. [targetPolycount] above 0 has Meshy remesh it to about that many polygons. Returns the finished task.
     */
    suspend fun fromImages(
        images: List<ByteArray>,
        poseMode: String = "",
        targetPolycount: Int = 0,
        texturePrompt: String = ""
    ): MeshyTaskResponse {
        require(images.size in 1..4) { "Meshy takes 1 to 4 images" }
        val uris = images.map { dataUri(it) }
        onProgress?.invoke(5, "Uploading ${uris.size} image(s)...")
        val single = uris.size == 1
        val url = if (single) IMAGE_TO_3D_URL else MULTI_IMAGE_TO_3D_URL
        val body = MeshyImagesTo3dRequest(
            imageUrl = if (single) uris[0] else null,
            imageUrls = if (single) null else uris,
            poseMode = poseMode,
            shouldRemesh = targetPolycount > 0,
            targetPolycount = targetPolycount.takeIf { it > 0 },
            texturePrompt = texturePrompt
        )
        val taskId = createV1Task(url, body, if (single) "image-to-3D" else "multi-image-to-3D")
        onProgress?.invoke(10, "Making the model...")
        return pollTask(taskId, baseProgress = 10, maxProgress = 90, pollBaseUrl = url)
    }

    /** Gives an existing model a new texture from a prompt (or a style image), keeping its UV layout. */
    suspend fun retexture(modelGlb: ByteArray, prompt: String, styleImage: ByteArray? = null): MeshyTaskResponse {
        val model = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(modelGlb)
        onProgress?.invoke(5, "Uploading model for retexturing...")
        val body = if (styleImage != null) {
            MeshyRetextureRequest(model, imageStyleUrl = dataUri(styleImage))
        } else {
            MeshyRetextureRequest(model, textStylePrompt = prompt)
        }
        val taskId = createV1Task(RETEXTURE_URL, body, "retexture")
        onProgress?.invoke(10, "Retexturing...")
        return pollTask(taskId, baseProgress = 10, maxProgress = 90, pollBaseUrl = RETEXTURE_URL)
    }

    /** The account's credits (free to ask). */
    suspend fun balance(): MeshyBalance = getV1Task(BALANCE_URL)

    /** Meshy's animation library, optionally searched by name (free to ask). */
    suspend fun library(search: String = ""): List<MeshyLibraryAction> =
        getV1Task(if (search.isEmpty()) LIBRARY_URL else "$LIBRARY_URL?search=${search.encodeURLParameter()}")

    private fun dataUri(image: ByteArray): String {
        val mime = when {
            image.size > 3 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte() -> "image/png"
            image.size > 3 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte() -> "image/jpeg"
            image.size > 12 && String(image, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> "image/png"
        }
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(image)
    }

    /** Downloads a file produced by a Meshy task. */
    suspend fun download(url: String, what: String): ByteArray = httpClient.downloadFileBytes(url, what)

    private suspend inline fun <reified B> createV1Task(url: String, body: B, what: String): String {
        val response = httpClient.post(url) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return when (response.status.value) {
            200, 201, 202 -> response.body<MeshyCreateResponse>().result
            401, 403 -> throw ApiException.AuthenticationFailed("Invalid Meshy API key")
            402 -> throw ApiException.GenerationFailed("Not enough Meshy credits for the $what task")
            422 -> throw ApiException.GenerationFailed("Meshy could not detect a humanoid pose for $what (HTTP 422)")
            429 -> throw ApiException.RateLimited()
            else -> throw ApiException.GenerationFailed(
                "Failed to create Meshy $what task: HTTP ${response.status.value} ${response.bodyAsText().take(300)}"
            )
        }
    }

    private suspend inline fun <reified T> getV1Task(url: String): T {
        val response = httpClient.get(url) {
            header("Authorization", "Bearer $apiKey")
        }
        if (response.status.value == 401 || response.status.value == 403) {
            throw ApiException.AuthenticationFailed("Invalid Meshy API key")
        }
        return response.body<T>()
    }

    private fun failureMessage(taskId: String, status: String, error: MeshyTaskError?): String? =
        when (status) {
            STATUS_FAILED, STATUS_EXPIRED, STATUS_CANCELED ->
                error?.message ?: "Task $taskId failed with status: $status"
            else -> null
        }

    internal suspend fun pollTask(
        taskId: String,
        baseProgress: Int = 10,
        maxProgress: Int = 89,
        pollBaseUrl: String = BASE_URL
    ): MeshyTaskResponse = pollUntilComplete(
        maxDuration = maxPollDuration,
        pollInterval = pollInterval,
        onProgress = onProgress,
        progressMessage = { elapsed -> "Processing 3D model... (${elapsed}s)" },
        baseProgress = baseProgress,
        maxProgress = maxProgress,
        poll = {
            val response = httpClient.get("$pollBaseUrl/$taskId") {
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
        const val IMAGE_TO_3D_URL = "https://api.meshy.ai/openapi/v1/image-to-3d" // (v1: only text-to-3d is v2; v2 here is a 404)
        const val RIGGING_URL = "https://api.meshy.ai/openapi/v1/rigging"
        const val ANIMATIONS_URL = "https://api.meshy.ai/openapi/v1/animations"
        const val MULTI_IMAGE_TO_3D_URL = "https://api.meshy.ai/openapi/v1/multi-image-to-3d"
        const val RETEXTURE_URL = "https://api.meshy.ai/openapi/v1/retexture"
        const val BALANCE_URL = "https://api.meshy.ai/openapi/v1/balance"
        const val LIBRARY_URL = "https://api.meshy.ai/openapi/v1/animations/library"

        /** Task status returned when generation completes successfully. */
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        /** Task status returned when generation fails. */
        const val STATUS_FAILED = "FAILED"
        /** Task status returned when the task expires before completing. */
        const val STATUS_EXPIRED = "EXPIRED"
        /** Task status returned when a task was cancelled. */
        const val STATUS_CANCELED = "CANCELED"
    }
}
