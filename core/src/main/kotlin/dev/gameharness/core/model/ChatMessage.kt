package dev.gameharness.core.model

import kotlinx.serialization.Serializable

/** Identifies the sender of a chat message. */
@Serializable
enum class ChatRole {
    /** Message from the human user. */
    USER,
    /** Message from the AI agent. */
    ASSISTANT,
    /** System-level message (not shown to user in the chat UI). */
    SYSTEM;
}

/**
 * A single message in the workspace conversation history.
 *
 * @property role Who sent the message.
 * @property content The text body of the message.
 * @property timestamp Epoch millis when the message was created.
 * @property assetRef Optional ID of a [GeneratedAsset] referenced by this message.
 * @property attachmentPaths File paths of images or other files attached to this message.
 */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val assetRef: String? = null,
    val attachmentPaths: List<String> = emptyList()
)
