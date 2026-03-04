package dev.gameharness.agent.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.gameharness.api.common.ApiException
import dev.gameharness.api.elevenlabs.ElevenLabsClient
import dev.gameharness.core.model.*
import dev.gameharness.core.workspace.WorkspaceManager
import kotlinx.serialization.Serializable

/**
 * KOOG tool that generates game sound effects via the [ElevenLabsClient].
 *
 * This is a single-shot API call (no polling). The resulting MP3 is saved
 * to `assets/sfx/`. Duration is configurable between 0.5 and 22 seconds.
 */
class SoundGeneratorTool(
    private val elevenLabsClient: ElevenLabsClient,
    private val workspaceManager: WorkspaceManager,
    private val currentWorkspace: () -> Workspace,
    private val onWorkspaceUpdated: (Workspace) -> Unit = {}
) : SimpleTool<SoundGeneratorTool.Args>(
    argsSerializer = Args.serializer(),
    name = "generate_sound_effect",
    description = "Generate a game sound effect using AI. Produces MP3 audio files for game SFX like explosions, footsteps, UI clicks, etc."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Description of the sound effect, e.g. 'sword clashing against a metal shield' or 'magical spell casting whoosh'")
        val description: String,
        @property:LLMDescription("Duration in seconds (0.5 to 22.0). Default: 2.0")
        val durationSeconds: Double = 2.0
    ) {
        init {
            require(description.isNotBlank()) { "Description must not be blank" }
            require(durationSeconds in 0.5..22.0) { "Duration must be between 0.5 and 22.0 seconds" }
        }
    }

    /** Generates a sound effect, saves it to the workspace, and returns a status string for the LLM. */
    override suspend fun execute(args: Args): String {
        val request = GenerationRequest(
            description = args.description,
            type = AssetType.SOUND_EFFECT,
            params = mapOf("duration" to args.durationSeconds.toString())
        )

        return try {
            when (val result = elevenLabsClient.generate(request)) {
                is GenerationResult.Completed -> {
                    val (savedPath, updatedWs) = workspaceManager.saveAsset(currentWorkspace(), result.asset, result.fileBytes)
                    onWorkspaceUpdated(updatedWs)
                    "Successfully generated sound effect '${args.description}'. " +
                        "Saved as ${result.asset.fileName} (${result.asset.sizeBytes} bytes, MP3). " +
                        "Asset ID: ${result.asset.id}. File: $savedPath"
                }
                is GenerationResult.Failed -> "Failed to generate sound effect: ${result.error}"
                is GenerationResult.InProgress -> "Sound effect generation is in progress"
            }
        } catch (e: ApiException.AuthenticationFailed) {
            "Failed to generate sound effect: ElevenLabs API key is invalid or lacks permission. " +
                "Please check your ElevenLabs API key in Settings."
        } catch (e: ApiException.RateLimited) {
            "Failed to generate sound effect: ElevenLabs API rate limit exceeded. Please wait a moment and try again."
        } catch (e: ApiException) {
            "Failed to generate sound effect: ${e.message}"
        }
    }
}
