package dev.gameharness.gui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gameharness.agent.bridge.AssetPreview as AssetPreviewData
import dev.gameharness.core.model.GeneratedAsset

/**
 * Top-level asset panel that routes between four views:
 *
 * 1. **Agent review** — when [preview] is non-null, shows the generated asset for approval/denial
 * 2. **Detail view** — when the user clicks an asset card, shows a full-screen preview with actions
 * 3. **Browser grid** — when the workspace has assets or folders, shows an adaptive card grid
 * 4. **Empty state** — when no assets exist, shows available capabilities
 */
@Composable
fun AssetPreview(
    preview: AssetPreviewData?,
    assets: List<GeneratedAsset> = emptyList(),
    capabilities: List<String> = emptyList(),
    folders: Set<String> = emptySet(),
    currentFolder: String = "",
    onDeleteAsset: (assetId: String) -> Unit = {},
    onDeleteAssets: (assetIds: Set<String>) -> Unit = {},
    onReviseAsset: (asset: GeneratedAsset, request: String) -> Unit = { _, _ -> },
    onMoveAsset: (assetId: String, targetFolder: String) -> Unit = { _, _ -> },
    onMoveAssets: (assetIds: Set<String>, targetFolder: String) -> Unit = { _, _ -> },
    onNavigateToFolder: (String) -> Unit = {},
    onNavigateUp: () -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onDeleteFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // Track which asset the user is browsing (null = grid view)
    var browsingAsset by remember { mutableStateOf<GeneratedAsset?>(null) }

    // Clear browsing selection when agent presents a new asset for review
    LaunchedEffect(preview) {
        if (preview != null) browsingAsset = null
    }

    // If the browsed asset was deleted, go back to grid
    LaunchedEffect(assets) {
        val current = browsingAsset
        if (current != null && assets.none { it.id == current.id }) {
            browsingAsset = null
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp
    ) {
        when {
            // Agent is presenting an asset for review — takes priority
            preview != null -> AssetPreviewContent(preview)

            // User is browsing a specific asset from the grid
            browsingAsset != null -> AssetDetailView(
                asset = browsingAsset!!,
                onBack = { browsingAsset = null },
                onDelete = { assetId ->
                    onDeleteAsset(assetId)
                    browsingAsset = null
                },
                onRevise = { asset, request ->
                    onReviseAsset(asset, request)
                    browsingAsset = null
                }
            )

            // Workspace has assets or folders — show the browser grid
            assets.isNotEmpty() || folders.isNotEmpty() -> AssetBrowser(
                assets = assets,
                folders = folders,
                currentFolder = currentFolder,
                onAssetSelected = { browsingAsset = it },
                onDeleteAsset = onDeleteAsset,
                onDeleteAssets = onDeleteAssets,
                onReviseAsset = { browsingAsset = it },
                onMoveAsset = onMoveAsset,
                onMoveAssets = onMoveAssets,
                onNavigateToFolder = onNavigateToFolder,
                onNavigateUp = onNavigateUp,
                onCreateFolder = onCreateFolder,
                onDeleteFolder = onDeleteFolder,
                onRenameFolder = onRenameFolder
            )

            // No assets yet — show the empty/capabilities state
            else -> EmptyPreviewState(capabilities)
        }
    }
}
