package dev.gameharness.core.model

import kotlinx.serialization.Serializable

/**
 * The user's verdict on a generated asset, combining the accept/deny decision
 * with optional textual feedback for the AI agent.
 *
 * @property decision Whether the asset was approved or denied.
 * @property feedback Optional reason or instruction from the user (e.g. "make it brighter").
 */
@Serializable
data class AssetReviewDecision(
    val decision: AssetDecision,
    val feedback: String? = null
)
