package dev.gameharness.agent.registry

import ai.koog.agents.core.tools.ToolRegistry
import dev.gameharness.agent.bridge.AgentBridge
import dev.gameharness.agent.bridge.GenerationProgress
import dev.gameharness.agent.tools.*
import dev.gameharness.api.elevenlabs.ElevenLabsClient
import dev.gameharness.api.gemini.GeminiClient
import dev.gameharness.api.meshy.MeshyClient
import dev.gameharness.api.suno.SunoClient
import dev.gameharness.core.model.Workspace
import dev.gameharness.core.workspace.WorkspaceManager

/**
 * Creates a KOOG [ToolRegistry] containing only the generation tools whose
 * API clients are non-null (i.e. the corresponding API key was configured).
 *
 * The [PresentAssetTool] is always registered because it requires no external
 * API key -- it only interacts with the [bridge] to show assets to the user.
 *
 * @param currentWorkspace lambda providing the active workspace; called lazily at tool execution time
 * @param onProgress callback for long-running generation progress (Meshy, Suno)
 * @param onWorkspaceUpdated callback invoked after an asset is saved, carrying the updated workspace
 */
fun createGameToolRegistry(
    geminiClient: GeminiClient?,
    meshyClient: MeshyClient?,
    sunoClient: SunoClient?,
    elevenLabsClient: ElevenLabsClient?,
    workspaceManager: WorkspaceManager,
    currentWorkspace: () -> Workspace,
    bridge: AgentBridge,
    onProgress: ((GenerationProgress?) -> Unit)? = null,
    onWorkspaceUpdated: (Workspace) -> Unit = {}
): ToolRegistry {
    return ToolRegistry {
        if (geminiClient != null) {
            tool(SpriteGeneratorTool(geminiClient, workspaceManager, currentWorkspace, bridge, onWorkspaceUpdated))
        }
        if (meshyClient != null) {
            tool(ModelGeneratorTool(meshyClient, workspaceManager, currentWorkspace, onProgress, onWorkspaceUpdated))
        }
        if (sunoClient != null) {
            tool(MusicGeneratorTool(sunoClient, workspaceManager, currentWorkspace, onProgress, onWorkspaceUpdated))
        }
        if (elevenLabsClient != null) {
            tool(SoundGeneratorTool(elevenLabsClient, workspaceManager, currentWorkspace, onWorkspaceUpdated))
        }
        tool(PresentAssetTool(bridge, currentWorkspace))
    }
}
