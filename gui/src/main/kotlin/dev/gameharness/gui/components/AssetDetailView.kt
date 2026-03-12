package dev.gameharness.gui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GeneratedAsset
import java.io.File

/**
 * Full-screen detail view for browsing a single asset.
 *
 * Shows a type-specific preview (image, audio player, 3D info, or generic file card),
 * file metadata, and action buttons for revising or deleting the asset.
 */
@Composable
internal fun AssetDetailView(
    asset: GeneratedAsset,
    onBack: () -> Unit,
    onDelete: (assetId: String) -> Unit,
    onRevise: (asset: GeneratedAsset, request: String) -> Unit,
    onSplitSheet: (asset: GeneratedAsset, tileWidth: Int, tileHeight: Int,
                   skipEmpty: Boolean, removeBgColor: java.awt.Color?, bgTolerance: Int,
                   targetFolder: String?) -> Unit = { _, _, _, _, _, _, _ -> }
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReviseDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Back button + title row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("\u2190 Back to Assets")
            }
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = asset.type.displayName,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = asset.fileName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (asset.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = asset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Type-specific preview
        when (asset.type) {
            AssetType.SPRITE -> ImagePreview(asset.filePath, Modifier.weight(1f))
            AssetType.MUSIC -> AudioPreview(asset.filePath, AssetType.MUSIC, Modifier.weight(1f))
            AssetType.SOUND_EFFECT -> AudioPreview(asset.filePath, AssetType.SOUND_EFFECT, Modifier.weight(1f))
            AssetType.MODEL_3D -> ModelPreview(asset.filePath, Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        // File info footer
        val file = File(asset.filePath)
        if (file.exists()) {
            val sizeKb = file.length() / 1024
            val sizeDisplay = if (sizeKb > 1024) "${"%.1f".format(sizeKb / 1024.0)} MB" else "$sizeKb KB"
            Text(
                text = "$sizeDisplay  \u2022  ${asset.format.uppercase()}  \u2022  ${asset.status.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            Button(
                onClick = { showReviseDialog = true }
            ) {
                Text("\u270F  Revise")
            }

            if (asset.type == AssetType.SPRITE) {
                OutlinedButton(
                    onClick = { showSplitDialog = true }
                ) {
                    Text("\u2702  Split Sheet")
                }
            }

            OutlinedButton(
                onClick = { showDeleteDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("\uD83D\uDDD1  Delete")
            }
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            assetName = asset.fileName,
            onConfirm = {
                showDeleteDialog = false
                onDelete(asset.id)
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showReviseDialog) {
        ReviseAssetDialog(
            assetName = asset.fileName,
            onSubmit = { request ->
                showReviseDialog = false
                onRevise(asset, request)
            },
            onDismiss = { showReviseDialog = false }
        )
    }

    if (showSplitDialog) {
        SplitSheetDialog(
            asset = asset,
            onSplit = { splitAsset, tw, th, skipEmpty, bgColor, bgTolerance, targetFolder ->
                showSplitDialog = false
                onSplitSheet(splitAsset, tw, th, skipEmpty, bgColor, bgTolerance, targetFolder)
            },
            onDismiss = { showSplitDialog = false }
        )
    }
}
