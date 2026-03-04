package dev.gameharness.agent.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.gameharness.agent.bridge.GenerationProgress
import dev.gameharness.api.common.ApiException
import dev.gameharness.api.meshy.MeshyClient
import dev.gameharness.core.model.*
import dev.gameharness.core.workspace.WorkspaceManager
import kotlinx.serialization.Serializable

/**
 * KOOG tool that generates 3D game models via the [MeshyClient].
 *
 * Meshy uses a two-stage pipeline (preview then refine) that can take several
 * minutes. Progress updates are forwarded through [onProgress] so the UI can
 * display a progress bar. The resulting GLB file is saved to `assets/models/`.
 */
class ModelGeneratorTool(
    private val meshyClient: MeshyClient,
    private val workspaceManager: WorkspaceManager,
    private val currentWorkspace: () -> Workspace,
    private val onProgress: ((GenerationProgress?) -> Unit)? = null,
    private val onWorkspaceUpdated: (Workspace) -> Unit = {}
) : SimpleTool<ModelGeneratorTool.Args>(
    argsSerializer = Args.serializer(),
    name = "generate_3d_model",
    description = "Generate a 3D game model using AI. Produces GLB files with PBR textures. Takes several minutes to complete."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Detailed description of the 3D model to generate, e.g. 'a low-poly medieval castle' or 'a sci-fi laser gun'")
        val description: String,
        @property:LLMDescription("Art style: 'realistic', 'cartoon', 'low-poly', or 'sculpture'. Default: 'realistic'")
        val artStyle: String = "realistic"
    ) {
        init {
            require(description.isNotBlank()) { "Description must not be blank" }
        }
    }

    /** Generates a 3D model, saves it to the workspace, and returns a status string for the LLM. */
    override suspend fun execute(args: Args): String {
        val request = GenerationRequest(
            description = args.description,
            type = AssetType.MODEL_3D,
            params = mapOf("artStyle" to args.artStyle)
        )

        meshyClient.onProgress = { percent, message ->
            onProgress?.invoke(GenerationProgress(AssetType.MODEL_3D, percent, message))
        }

        try {
            return when (val result = meshyClient.generate(request)) {
                is GenerationResult.Completed -> {
                    val (savedPath, updatedWs) = workspaceManager.saveAsset(currentWorkspace(), result.asset, result.fileBytes)
                    onWorkspaceUpdated(updatedWs)
                    "Successfully generated 3D model '${args.description}'. " +
                        "Saved as ${result.asset.fileName} (${result.asset.sizeBytes} bytes, GLB format). " +
                        "Asset ID: ${result.asset.id}. File: $savedPath"
                }
                is GenerationResult.Failed -> "Failed to generate 3D model: ${result.error}"
                is GenerationResult.InProgress -> "3D model generation is in progress (${result.progressPercent}%)"
            }
        } catch (e: ApiException.AuthenticationFailed) {
            return "Failed to generate 3D model: Meshy API key is invalid or lacks permission. " +
                "Please check your Meshy API key in Settings."
        } catch (e: ApiException.RateLimited) {
            return "Failed to generate 3D model: Meshy API rate limit exceeded. Please wait a moment and try again."
        } catch (e: ApiException) {
            return "Failed to generate 3D model: ${e.message}"
        } finally {
            meshyClient.onProgress = null
            onProgress?.invoke(null)
        }
    }
}
