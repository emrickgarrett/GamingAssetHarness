package dev.gameharness.agent.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.gameharness.agent.bridge.AgentBridge
import dev.gameharness.api.common.ApiException
import dev.gameharness.api.gemini.GeminiClient
import dev.gameharness.core.model.*
import dev.gameharness.core.workspace.WorkspaceManager
import kotlinx.serialization.Serializable

/**
 * KOOG tool that generates 2D sprites/textures via the [GeminiClient].
 *
 * Supports pixel-art styles (8-bit, 16-bit) and modern/realistic styles.
 * When the user attaches reference images, the tool forwards their file paths
 * to the Gemini API for visual guidance.
 *
 * On success the generated PNG is saved to the workspace's `assets/sprites/`
 * directory and [onWorkspaceUpdated] is invoked with the updated workspace.
 */
class SpriteGeneratorTool(
    private val geminiClient: GeminiClient,
    private val workspaceManager: WorkspaceManager,
    private val currentWorkspace: () -> Workspace,
    private val bridge: AgentBridge,
    private val onWorkspaceUpdated: (Workspace) -> Unit = {}
) : SimpleTool<SpriteGeneratorTool.Args>(
    argsSerializer = Args.serializer(),
    name = "generate_sprite",
    description = "Generate a 2D game sprite or texture image using AI. Produces pixel art or modern style game sprites as PNG files. If the user attached reference image(s), set useReferenceImages=true to use them as visual guidance."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Detailed description of the sprite to generate, e.g. 'a medieval knight idle pose' or 'a red potion bottle item'")
        val description: String,
        @property:LLMDescription("Art style: '8bit', '16bit', 'modern', or 'realistic'. Default: '16bit'")
        val style: String = "16bit",
        @property:LLMDescription("Aspect ratio: '1:1', '2:3', '3:2', '4:3', '16:9'. Default: '1:1'")
        val aspectRatio: String = "1:1",
        @property:LLMDescription("Set to true if the user attached reference image(s) to use as visual guide. Default: false")
        val useReferenceImages: Boolean = false
    ) {
        init {
            require(description.isNotBlank()) { "Description must not be blank" }
            require(style in VALID_STYLES) { "Style must be one of: ${VALID_STYLES.joinToString()}" }
        }
    }

    /** Generates a sprite, saves it to the workspace, and returns a status string for the LLM. */
    override suspend fun execute(args: Args): String {
        val referenceImagePaths = if (args.useReferenceImages) bridge.latestAttachmentPaths else emptyList()
        val request = GenerationRequest(
            description = args.description,
            type = AssetType.SPRITE,
            params = mapOf("style" to args.style, "aspectRatio" to args.aspectRatio),
            referenceImagePaths = referenceImagePaths
        )

        return try {
            when (val result = geminiClient.generate(request)) {
                is GenerationResult.Completed -> {
                    val (savedPath, updatedWs) = workspaceManager.saveAsset(currentWorkspace(), result.asset, result.fileBytes)
                    onWorkspaceUpdated(updatedWs)
                    "Successfully generated sprite '${args.description}'. " +
                        "Saved as ${result.asset.fileName} (${result.asset.sizeBytes} bytes). " +
                        "Asset ID: ${result.asset.id}. File: $savedPath"
                }
                is GenerationResult.Failed -> "Failed to generate sprite: ${result.error}"
                is GenerationResult.InProgress -> "Sprite generation is still in progress (${result.progressPercent}%)"
            }
        } catch (e: ApiException.AuthenticationFailed) {
            "Failed to generate sprite: Gemini API key is invalid or lacks permission. " +
                "Please check your NanoBanana API key in Settings."
        } catch (e: ApiException.RateLimited) {
            "Failed to generate sprite: Gemini API rate limit exceeded. Please wait a moment and try again."
        } catch (e: ApiException) {
            "Failed to generate sprite: ${e.message}"
        }
    }

    companion object {
        /** Accepted values for the [Args.style] parameter. */
        val VALID_STYLES = setOf("8bit", "16bit", "modern", "realistic")
    }
}
