package dev.gameharness.agent.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.gameharness.agent.bridge.GenerationProgress
import dev.gameharness.api.common.ApiException
import dev.gameharness.api.suno.SunoClient
import dev.gameharness.core.model.*
import dev.gameharness.core.workspace.WorkspaceManager
import kotlinx.serialization.Serializable

/**
 * KOOG tool that generates game music tracks via the [SunoClient].
 *
 * Suno uses a create-then-poll pipeline that may take a minute or more.
 * Progress updates are forwarded through [onProgress]. The resulting MP3 is
 * saved to `assets/music/`.
 */
class MusicGeneratorTool(
    private val sunoClient: SunoClient,
    private val workspaceManager: WorkspaceManager,
    private val currentWorkspace: () -> Workspace,
    private val onProgress: ((GenerationProgress?) -> Unit)? = null,
    private val onWorkspaceUpdated: (Workspace) -> Unit = {}
) : SimpleTool<MusicGeneratorTool.Args>(
    argsSerializer = Args.serializer(),
    name = "generate_music",
    description = "Generate game music using AI. Produces MP3 audio files suitable for game soundtracks."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Description of the music to generate, e.g. 'epic orchestral battle theme' or 'calm ambient forest exploration music'")
        val description: String,
        @property:LLMDescription("Music genre, e.g. 'orchestral', 'electronic', 'chiptune', 'ambient'. Default: empty (auto)")
        val genre: String = "",
        @property:LLMDescription("Mood of the music, e.g. 'epic', 'calm', 'tense', 'cheerful'. Default: empty (auto)")
        val mood: String = "",
        @property:LLMDescription("Whether the music should be instrumental (no vocals). Default: true")
        val instrumental: Boolean = true
    ) {
        init {
            require(description.isNotBlank()) { "Description must not be blank" }
        }
    }

    /** Generates a music track, saves it to the workspace, and returns a status string for the LLM. */
    override suspend fun execute(args: Args): String {
        val request = GenerationRequest(
            description = args.description,
            type = AssetType.MUSIC,
            params = buildMap {
                put("instrumental", args.instrumental.toString())
                if (args.genre.isNotBlank()) put("genre", args.genre)
                if (args.mood.isNotBlank()) put("mood", args.mood)
            }
        )

        sunoClient.onProgress = { percent, message ->
            onProgress?.invoke(GenerationProgress(AssetType.MUSIC, percent, message))
        }

        try {
            return when (val result = sunoClient.generate(request)) {
                is GenerationResult.Completed -> {
                    val (savedPath, updatedWs) = workspaceManager.saveAsset(currentWorkspace(), result.asset, result.fileBytes)
                    onWorkspaceUpdated(updatedWs)
                    "Successfully generated music '${args.description}'. " +
                        "Saved as ${result.asset.fileName} (${result.asset.sizeBytes} bytes, MP3). " +
                        "Asset ID: ${result.asset.id}. File: $savedPath"
                }
                is GenerationResult.Failed -> "Failed to generate music: ${result.error}"
                is GenerationResult.InProgress -> "Music generation is in progress (${result.progressPercent}%)"
            }
        } catch (e: ApiException.AuthenticationFailed) {
            return "Failed to generate music: Suno API key is invalid or lacks permission. " +
                "Please check your Suno API key in Settings."
        } catch (e: ApiException.RateLimited) {
            return "Failed to generate music: Suno API rate limit exceeded. Please wait a moment and try again."
        } catch (e: ApiException) {
            return "Failed to generate music: ${e.message}"
        } finally {
            sunoClient.onProgress = null
            onProgress?.invoke(null)
        }
    }
}
