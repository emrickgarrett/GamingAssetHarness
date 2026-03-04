package dev.gameharness.core.model

import kotlinx.serialization.Serializable

/**
 * Lifecycle state of a generated asset as it goes through user review.
 *
 * Every asset starts as [PENDING] when first generated, then transitions
 * to [APPROVED] or [DENIED] based on the user's decision.
 */
@Serializable
enum class AssetDecision {
    /** Asset has been generated but not yet reviewed by the user. */
    PENDING,
    /** User accepted the asset into the workspace. */
    APPROVED,
    /** User rejected the asset. */
    DENIED;
}
