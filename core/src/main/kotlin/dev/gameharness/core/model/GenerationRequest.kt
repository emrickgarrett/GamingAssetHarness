package dev.gameharness.core.model

import kotlinx.serialization.Serializable

/**
 * A request to generate a game asset, passed from the agent to an API client.
 *
 * @property description Natural-language description of the desired asset (must not be blank).
 * @property type The kind of asset to generate (sprite, 3D model, music, sound effect).
 * @property params Additional key-value parameters for the generation API (e.g. style hints).
 * @property referenceImagePaths Paths to reference images that guide generation (sprites only).
 */
@Serializable
data class GenerationRequest(
    val description: String,
    val type: AssetType,
    val params: Map<String, String> = emptyMap(),
    val referenceImagePaths: List<String> = emptyList()
) {
    init {
        require(description.isNotBlank()) { "Description must not be blank" }
    }
}
