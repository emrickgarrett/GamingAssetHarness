package dev.gameharness.gui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gameharness.agent.bridge.AssetPreview as AssetPreviewData
import dev.gameharness.core.model.AssetType

/**
 * Content panel shown when the agent presents an asset for user review.
 *
 * Displays a summary of the generated asset, a type-specific preview renderer,
 * and the asset ID. The approve/deny action bar is rendered separately.
 */
@Composable
internal fun AssetPreviewContent(preview: AssetPreviewData) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Asset Preview",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = preview.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(16.dp))

        val filePath = preview.filePath
        val assetType = preview.assetType

        when {
            filePath != null && assetType == AssetType.SPRITE -> {
                ImagePreview(filePath, Modifier.weight(1f))
            }
            filePath != null && (assetType == AssetType.MUSIC || assetType == AssetType.SOUND_EFFECT) -> {
                AudioPreview(filePath, assetType, Modifier.weight(1f))
            }
            filePath != null && assetType == AssetType.MODEL_3D -> {
                ModelPreview(filePath, Modifier.weight(1f))
            }
            filePath != null -> {
                FileInfoCard(filePath, Modifier.weight(1f))
            }
            else -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Awaiting your review decision",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "ID: ${preview.assetId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}
