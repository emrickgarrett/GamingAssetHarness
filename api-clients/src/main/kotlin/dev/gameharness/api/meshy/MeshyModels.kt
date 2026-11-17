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

/**
 * Request body for Meshy's image-to-3D (one view, [imageUrl]) or multi-image-to-3D (1-4 views, [imageUrls]), the
 * images as public URLs or data URIs. [targetPolycount] turns on Meshy's remesh to that many polygons.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MeshyImagesTo3dRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("image_url") val imageUrl: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("image_urls") val imageUrls: List<String>? = null,
    @SerialName("ai_model") val aiModel: String = "latest",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("pose_mode") val poseMode: String = "",
    @SerialName("should_remesh") val shouldRemesh: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("target_polycount") val targetPolycount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("texture_prompt") val texturePrompt: String = ""
)

/** Request body for Meshy's retexture: a new texture for an existing model from a text prompt or a style image. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MeshyRetextureRequest(
    @SerialName("model_url") val modelUrl: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("text_style_prompt") val textStylePrompt: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("image_style_url") val imageStyleUrl: String = "",
    @SerialName("enable_original_uv") val enableOriginalUv: Boolean = true
)

/** Request body for Meshy's auto-rigging with a chosen skeleton template (meshyrig, mixamo, ue5_mannequin, ...). */
@Serializable
data class MeshyRigTemplateRequest(
    @SerialName("model_url") val modelUrl: String,
    @SerialName("height_meters") val heightMeters: Double = 1.7,
    @SerialName("skeleton_template") val skeletonTemplate: String = "meshyrig",
    @SerialName("include_fingers") val includeFingers: Boolean = false
)

/** The account's credit balance (`/openapi/v1/balance`). */
@Serializable
data class MeshyBalance(
    val balance: Int = 0,
    @SerialName("monthly_credits") val monthlyCredits: Int? = null,
    @SerialName("permanent_credits") val permanentCredits: Int? = null
)

/** One action in Meshy's animation library (`/openapi/v1/animations/library`). */
@Serializable
data class MeshyLibraryAction(
    @SerialName("action_id") val actionId: Int,
    val name: String = "",
    val category: String? = null,
    @SerialName("sub_category") val subCategory: String? = null
)

/** Request body for Meshy's text-to-motion: an animation made from words, 2-10 s, then applied to a rig. */
@Serializable
data class MeshyTextToMotionRequest(
    val prompt: String,
    val duration: Double,
    val mode: String = "prime"
)

/** Status of a text-to-motion task. */
@Serializable
data class MeshyMotionTaskResponse(
    val id: String,
    val status: String,
    val progress: Int = 0,
    @SerialName("task_error") val taskError: MeshyTaskError? = null
)

/** Request body for applying a text-to-motion clip to a rigged character. */
@Serializable
data class MeshyMotionAnimationRequest(
    @SerialName("rig_task_id") val rigTaskId: String,
    @SerialName("motion_task_id") val motionTaskId: String
)
