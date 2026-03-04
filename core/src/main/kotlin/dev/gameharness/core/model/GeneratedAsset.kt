package dev.gameharness.core.model

import kotlinx.serialization.Serializable

/**
 * Metadata for a single generated game asset stored in a workspace.
 *
 * The actual file bytes live on disk at [filePath]; this record tracks
 * identity, provenance, and approval status.
 *
 * @property id Unique identifier (typically a UUID).
 * @property type The category of asset (sprite, 3D model, music, sound effect).
 * @property fileName The file's base name (e.g. `"hero_idle.png"`).
 * @property filePath Absolute path to the asset file on disk.
 * @property format File format / extension (e.g. `"png"`, `"glb"`, `"mp3"`).
 * @property description Human-readable description of what was generated.
 * @property generationParams Key-value pairs of parameters sent to the generation API.
 * @property sizeBytes File size in bytes (populated after saving to disk).
 * @property createdAt Epoch millis when the asset was generated.
 * @property status Current review status (pending, approved, or denied).
 * @property folder Virtual folder path within the workspace for organization.
 */
@Serializable
data class GeneratedAsset(
    val id: String,
    val type: AssetType,
    val fileName: String,
    val filePath: String,
    val format: String,
    val description: String,
    val generationParams: Map<String, String> = emptyMap(),
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val status: AssetDecision = AssetDecision.PENDING,
    val folder: String = ""
)
