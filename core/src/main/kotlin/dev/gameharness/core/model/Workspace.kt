package dev.gameharness.core.model

import kotlinx.serialization.Serializable

/**
 * A named project workspace where generated assets and conversation history are stored.
 *
 * Workspaces live in user-chosen directories anywhere on the filesystem. Each workspace
 * has its own `workspace.json` metadata file, `chat-history.json`, and an `assets/`
 * directory tree organized by [AssetType].
 *
 * This is an immutable data class; mutating operations in [dev.gameharness.core.workspace.WorkspaceManager]
 * return new instances with the changes applied.
 *
 * @property name Display name shown in the workspace selector (must not be blank).
 * @property directoryPath Absolute filesystem path to the workspace root directory.
 * @property createdAt Epoch millis when the workspace was created.
 * @property assets Immutable list of all generated assets in this workspace.
 * @property folders Immutable set of virtual folder paths for asset organization.
 */
@Serializable
data class Workspace(
    val name: String,
    val directoryPath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val assets: List<GeneratedAsset> = emptyList(),
    val folders: Set<String> = emptySet()
) {
    init {
        require(name.isNotBlank()) { "Workspace name must not be blank" }
    }
}
