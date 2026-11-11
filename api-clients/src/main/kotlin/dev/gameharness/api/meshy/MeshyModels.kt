package dev.gameharness.api.meshy

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for creating a Meshy text-to-3D task (preview or refine).
 *
 * [poseMode] is Meshy's `pose_mode` ("a-pose" or "t-pose"): the pose a character is generated in. Characters
 * meant for auto-rigging should use "t-pose"; left empty it is not sent and the pose is whatever the prompt gets.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MeshyCreateRequest(
    val prompt: String,
    val mode: String = "preview",
    @SerialName("art_style") val artStyle: String = "realistic",
    @SerialName("ai_model") val aiModel: String = "meshy-6",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("pose_mode") val poseMode: String = ""
)

/** Request body for creating a refine task from an existing preview. */
@Serializable
data class MeshyRefineRequest(
    @SerialName("preview_task_id") val previewTaskId: String,
    val mode: String = "refine"
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

/** Request body for a Meshy auto-rigging task. [modelUrl] may be a public URL or a base64 data URI of a GLB. */
@Serializable
data class MeshyRigRequest(
    @SerialName("model_url") val modelUrl: String,
    @SerialName("height_meters") val heightMeters: Double = 1.7
)

/** Status of a Meshy rigging task (`/openapi/v1/rigging/:id`). */
@Serializable
data class MeshyRigTaskResponse(
    val id: String,
    val status: String,
    val progress: Int = 0,
    val result: MeshyRigResult? = null,
    @SerialName("task_error") val taskError: MeshyTaskError? = null
)

/** Files produced by a finished rigging task, including the free walking/running clips. */
@Serializable
data class MeshyRigResult(
    @SerialName("rigged_character_glb_url") val riggedCharacterGlbUrl: String? = null,
    @SerialName("rigged_character_fbx_url") val riggedCharacterFbxUrl: String? = null,
    @SerialName("basic_animations") val basicAnimations: MeshyBasicAnimations? = null
)

@Serializable
data class MeshyBasicAnimations(
    @SerialName("walking_glb_url") val walkingGlbUrl: String? = null,
    @SerialName("running_glb_url") val runningGlbUrl: String? = null
)

/** Request body for applying 1-10 animation-library actions to a rigged character. */
@Serializable
data class MeshyAnimationRequest(
    @SerialName("rig_task_id") val rigTaskId: String,
    @SerialName("action_ids") val actionIds: List<Int>
)

/** Status of a Meshy animation task (`/openapi/v1/animations/:id`). */
@Serializable
data class MeshyAnimationTaskResponse(
    val id: String,
    val status: String,
    val progress: Int = 0,
    val result: MeshyAnimationResult? = null,
    @SerialName("task_error") val taskError: MeshyTaskError? = null
)

@Serializable
data class MeshyAnimationResult(
    @SerialName("animation_glb_url") val animationGlbUrl: String? = null,
    @SerialName("animation_fbx_url") val animationFbxUrl: String? = null
)

/** Request body for creating a Meshy image-to-3D task. [poseMode] as in [MeshyCreateRequest]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MeshyImageTo3dRequest(
    @SerialName("image_url") val imageUrl: String,
    @SerialName("ai_model") val aiModel: String = "meshy-6",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("pose_mode") val poseMode: String = ""
)
