package dev.gameharness.agent.bridge

import dev.gameharness.core.model.AssetReviewDecision
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.ChatMessage
import kotlinx.coroutines.flow.StateFlow

/** Snapshot of the conversation visible in the UI. */
data class ConversationState(
    val messages: List<ChatMessage> = emptyList(),
    val currentAssetPreview: AssetPreview? = null,
    val isAgentThinking: Boolean = false
)

/** Metadata for an asset that is currently awaiting user review. */
data class AssetPreview(
    val assetId: String,
    val summary: String,
    val assetType: AssetType? = null,
    val filePath: String? = null
)

/** Progress information for a long-running generation operation (e.g. Meshy 3D, Suno music). */
data class GenerationProgress(
    val assetType: AssetType,
    val progressPercent: Int,
    val statusMessage: String
)

/**
 * Bridge interface between the KOOG agent coroutine and the Compose GUI.
 *
 * The agent side suspends on [awaitUserInput] and [presentAssetForReview].
 * The GUI side calls the corresponding `submit*` methods on [AgentBridgeImpl]
 * to unblock the agent. All observable state is exposed as [StateFlow]s so
 * Compose can collect them reactively.
 */
interface AgentBridge {
    /** Observable conversation state (messages, asset preview, thinking indicator). */
    val conversationState: StateFlow<ConversationState>

    /** Observable progress for long-running generation operations, or null when idle. */
    val generationProgress: StateFlow<GenerationProgress?>

    /** File paths attached by the user to the most recent message. */
    val latestAttachmentPaths: List<String>

    /** Appends an assistant message to the conversation. */
    fun sendAgentMessage(message: String)

    /** Suspends until the user sends a chat message; returns the message text. */
    suspend fun awaitUserInput(): String

    /**
     * Suspends until the user approves, denies, or requests regeneration of an asset.
     * The GUI displays a preview card while this is suspended.
     */
    suspend fun presentAssetForReview(
        assetId: String,
        summary: String,
        assetType: AssetType? = null,
        filePath: String? = null
    ): AssetReviewDecision

    /** Clears attachment paths after the agent has consumed them. */
    fun clearAttachments()
}
