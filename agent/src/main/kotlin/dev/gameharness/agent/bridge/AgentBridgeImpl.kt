package dev.gameharness.agent.bridge

import dev.gameharness.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference

/**
 * Production implementation of [AgentBridge] that mediates between the KOOG agent
 * coroutine and the Compose GUI thread.
 *
 * Thread safety is achieved via [AtomicReference] for the two [CompletableDeferred]
 * handshake points and [MutableStateFlow] for all observable state. No locking is
 * required because every mutation is either atomic or performed through StateFlow's
 * built-in CAS loop.
 */
class AgentBridgeImpl : AgentBridge {

    private val _conversationState = MutableStateFlow(ConversationState())
    override val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

    private val _generationProgress = MutableStateFlow<GenerationProgress?>(null)
    override val generationProgress: StateFlow<GenerationProgress?> = _generationProgress.asStateFlow()

    private val _latestAttachmentPaths = MutableStateFlow<List<String>>(emptyList())
    override val latestAttachmentPaths: List<String> get() = _latestAttachmentPaths.value

    private val userInputDeferred = AtomicReference<CompletableDeferred<String>?>(null)
    private val assetReviewDeferred = AtomicReference<CompletableDeferred<AssetReviewDecision>?>(null)

    override fun sendAgentMessage(message: String) {
        _conversationState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = message
                )
            )
        }
    }

    override suspend fun awaitUserInput(): String {
        val deferred = CompletableDeferred<String>()
        userInputDeferred.set(deferred)
        _conversationState.update { it.copy(isAgentThinking = false) }
        return deferred.await()
    }

    override suspend fun presentAssetForReview(
        assetId: String,
        summary: String,
        assetType: AssetType?,
        filePath: String?
    ): AssetReviewDecision {
        _conversationState.update {
            it.copy(currentAssetPreview = AssetPreview(
                assetId = assetId,
                summary = summary,
                assetType = assetType,
                filePath = filePath
            ))
        }
        val deferred = CompletableDeferred<AssetReviewDecision>()
        assetReviewDeferred.set(deferred)
        return deferred.await()
    }

    /**
     * Called by the GUI when the user sends a chat message.
     *
     * Stores any attachment paths, appends the user message to conversation state,
     * and completes the pending [awaitUserInput] deferred to unblock the agent loop.
     */
    fun submitUserInput(message: String, attachmentPaths: List<String> = emptyList()) {
        _latestAttachmentPaths.value = attachmentPaths
        _conversationState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(
                    role = ChatRole.USER,
                    content = message,
                    attachmentPaths = attachmentPaths
                ),
                isAgentThinking = true
            )
        }
        userInputDeferred.get()?.complete(message)
    }

    /**
     * Called by the GUI when the user clicks approve, deny, or regenerate on an asset preview.
     */
    fun submitAssetDecision(decision: AssetReviewDecision) {
        _conversationState.update { it.copy(currentAssetPreview = null) }
        assetReviewDeferred.get()?.complete(decision)
    }

    override fun clearAttachments() {
        _latestAttachmentPaths.value = emptyList()
    }

    /** Updates the "agent is thinking" indicator shown in the UI. */
    fun updateThinkingState(thinking: Boolean) {
        _conversationState.update { it.copy(isAgentThinking = thinking) }
    }

    /** Publishes generation progress (percentage, status message) for long-running operations. */
    fun updateProgress(progress: GenerationProgress?) {
        _generationProgress.value = progress
    }

    /** Restores previously saved messages (e.g. from chat history on workspace switch). */
    fun restoreMessages(messages: List<ChatMessage>) {
        _conversationState.value = ConversationState(messages = messages)
    }

    /** Resets all conversation state and cancels any pending deferreds for a fresh start. */
    fun reset() {
        userInputDeferred.getAndSet(null)?.cancel()
        assetReviewDeferred.getAndSet(null)?.cancel()
        _latestAttachmentPaths.value = emptyList()
        _conversationState.value = ConversationState()
        _generationProgress.value = null
    }
}
