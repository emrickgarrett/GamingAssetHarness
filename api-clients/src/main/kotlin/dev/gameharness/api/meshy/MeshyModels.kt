package dev.gameharness.api.meshy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for creating a Meshy text-to-3D task (preview or refine). */
@Serializable
data class MeshyCreateRequest(
    val prompt: String,
    val mode: String = "preview",
    @SerialName("art_style") val artStyle: String = "realistic",
    @SerialName("ai_model") val aiModel: String = "meshy-4"
)

/** Request body for creating a refine task from an existing preview. */
@Serializable
data class MeshyRefineRequest(
    @SerialName("preview_task_id") val previewTaskId: String
)

/** Response from a Meshy task creation endpoint containing the new task ID. */
@Serializable
data class MeshyCreateResponse(
    val result: String
)

/** Response from the Meshy task status endpoint, polled until a terminal [status]. */
@Serializable
data class MeshyTaskResponse(
    val id: String,
    val status: String,
    val progress: Int = 0,
    @SerialName("model_urls") val modelUrls: MeshyModelUrls? = null,
    @SerialName("texture_urls") val textureUrls: List<MeshyTextureUrl>? = null,
    @SerialName("task_error") val taskError: MeshyTaskError? = null
)

/** URLs to download the generated 3D model in various formats. */
@Serializable
data class MeshyModelUrls(
    val glb: String? = null,
    val fbx: String? = null,
    val obj: String? = null
)

/** PBR texture URL from a completed Meshy task. */
@Serializable
data class MeshyTextureUrl(
    @SerialName("base_color") val baseColor: String? = null
)

/** Error details attached to a failed Meshy task. */
@Serializable
data class MeshyTaskError(
    val message: String? = null
)
