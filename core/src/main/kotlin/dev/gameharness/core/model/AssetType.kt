package dev.gameharness.core.model

import kotlinx.serialization.Serializable

/**
 * Categories of game assets that can be generated.
 *
 * Each type maps to a user-facing display name and a subdirectory under
 * `<workspace>/assets/` where generated files are stored.
 *
 * @property displayName Human-readable label shown in the UI.
 * @property subdirectory Folder name under the workspace `assets/` directory.
 */
@Serializable
enum class AssetType(val displayName: String, val subdirectory: String) {
    /** 2D sprite or texture image (PNG/WebP), generated via NanoBanana (Gemini). */
    SPRITE("2D Sprite", "sprites"),
    /** 3D model (GLB), generated via Meshy. */
    MODEL_3D("3D Model", "models"),
    /** Music track (MP3), generated via Suno. */
    MUSIC("Music", "music"),
    /** Sound effect (MP3), generated via ElevenLabs. */
    SOUND_EFFECT("Sound Effect", "sfx");
}
