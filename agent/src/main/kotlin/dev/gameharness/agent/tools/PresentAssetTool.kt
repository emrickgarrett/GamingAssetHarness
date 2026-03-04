package dev.gameharness.agent.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.gameharness.agent.bridge.AgentBridge
import dev.gameharness.core.model.Workspace
import kotlinx.serialization.Serializable

/**
 * KOOG tool that pauses the agent to show a generated asset to the user for review.
 *
 * When executed, it looks up the asset in the current workspace to obtain the
 * file path and type, then calls [AgentBridge.presentAssetForReview] which
 * suspends until the user approves, denies, or requests regeneration. The
 * decision is returned as a plain-text string so the LLM can react accordingly.
 */
class PresentAssetTool(
    private val bridge: AgentBridge,
    private val currentWorkspace: () -> Workspace
) : SimpleTool<PresentAssetTool.Args>(
    argsSerializer = Args.serializer(),
    name = "present_asset_to_user",
    description = "Show a generated asset to the user for review. The user can approve, deny, or request regeneration with feedback."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The asset ID to present for review")
        val assetId: String,
        @property:LLMDescription("Brief summary of what was generated, e.g. '16-bit pixel art sword sprite'")
        val summary: String
    ) {
        init {
            require(assetId.isNotBlank()) { "Asset ID must not be blank" }
        }
    }

    /** Presents the asset for review and returns the user's decision as an LLM-readable string. */
    override suspend fun execute(args: Args): String {
        // Look up the generated asset to get file path and type for the preview
        val asset = try {
            currentWorkspace().assets.find { it.id == args.assetId }
        } catch (_: Exception) { null }

        val decision = bridge.presentAssetForReview(
            assetId = args.assetId,
            summary = args.summary,
            assetType = asset?.type,
            filePath = asset?.filePath
        )
        return when (decision.decision) {
            dev.gameharness.core.model.AssetDecision.APPROVED ->
                "User APPROVED the asset '${args.summary}' (ID: ${args.assetId})."
            dev.gameharness.core.model.AssetDecision.DENIED ->
                "User DENIED the asset '${args.summary}' (ID: ${args.assetId}). " +
                    (decision.feedback?.let { "Feedback: $it" } ?: "No feedback provided.")
            dev.gameharness.core.model.AssetDecision.PENDING ->
                "Asset review is still pending."
        }
    }
}
