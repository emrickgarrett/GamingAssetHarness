package dev.gameharness.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GeneratedAsset
import java.io.File

/** Card for navigating up to the parent folder in the asset browser. */
@Composable
internal fun BackFolderCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "\u2190", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "..",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Card representing a folder in the asset browser grid, with right-click context menu. */
@Composable
internal fun FolderCard(
    name: String,
    assetCount: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    isDropTarget: Boolean = false
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .then(
                    if (isDropTarget) Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp)
                    ) else Modifier
                )
                .clickable(onClick = onClick)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press &&
                                event.buttons.isSecondaryPressed
                            ) {
                                val position = event.changes.first().position
                                contextMenuOffset = DpOffset(
                                    (position.x / density).dp,
                                    (position.y / density).dp
                                )
                                showContextMenu = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDropTarget)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else
                    MaterialTheme.colorScheme.secondaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = "\uD83D\uDCC1", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$assetCount item${if (assetCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                )
            }
        }

        // Right-click context menu
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = contextMenuOffset
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    showContextMenu = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showContextMenu = false
                    onDelete()
                }
            )
        }
    }
}

/**
 * Asset card with click/drag handling and a right-click context menu.
 *
 * Supports Ctrl+click (toggle selection), Shift+click (range selection), and
 * drag-to-folder. The context menu adapts for single vs. multi-selection.
 */
@Composable
internal fun AssetCardWithContextMenu(
    asset: GeneratedAsset,
    isSelected: Boolean,
    selectedCount: Int,
    onPrimaryClick: (isCtrl: Boolean, isShift: Boolean) -> Unit,
    onRightClick: () -> Unit,
    onRevise: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onBatchMove: () -> Unit,
    onBatchDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val file = File(asset.filePath)
    var cardCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val isMultiMenu = isSelected && selectedCount > 1

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .onGloballyPositioned { cardCoordinates = it }
                .then(
                    if (isSelected) Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp)
                    ) else Modifier
                )
                .pointerInput(asset.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Press) continue

                            if (event.buttons.isSecondaryPressed) {
                                val pos = event.changes.first().position
                                contextMenuOffset = DpOffset(
                                    (pos.x / density).dp,
                                    (pos.y / density).dp
                                )
                                onRightClick()
                                showContextMenu = true
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            if (!event.buttons.isPrimaryPressed) continue

                            val startPos = event.changes.first().position
                            val isCtrl = event.keyboardModifiers.isCtrlPressed
                            val isShift = event.keyboardModifiers.isShiftPressed
                            var dragStarted = false

                            while (true) {
                                val nextEvent = awaitPointerEvent()
                                val change = nextEvent.changes.firstOrNull() ?: break

                                if (!change.pressed) {
                                    if (dragStarted) {
                                        onDragEnd()
                                    } else {
                                        onPrimaryClick(isCtrl, isShift)
                                    }
                                    break
                                }

                                val distance = (change.position - startPos).getDistance()
                                if (!dragStarted && distance > viewConfiguration.touchSlop) {
                                    dragStarted = true
                                    onDragStart()
                                }

                                if (dragStarted) {
                                    change.consume()
                                    val coords = cardCoordinates
                                    if (coords != null && coords.isAttached) {
                                        onDragMove(coords.localToWindow(change.position))
                                    }
                                }
                            }
                        }
                    }
                },
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box {
                AssetCardContent(asset, file)

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "\u2713",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // Right-click context menu
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = contextMenuOffset
        ) {
            if (isMultiMenu) {
                DropdownMenuItem(
                    text = { Text("\uD83D\uDCC2  Move $selectedCount items to Folder...") },
                    onClick = {
                        showContextMenu = false
                        onBatchMove()
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("\uD83D\uDDD1  Delete $selectedCount items", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onBatchDelete()
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("\u270F  Revise") },
                    onClick = {
                        showContextMenu = false
                        onRevise()
                    }
                )
                DropdownMenuItem(
                    text = { Text("\uD83D\uDCC2  Move to Folder...") },
                    onClick = {
                        showContextMenu = false
                        onMove()
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("\uD83D\uDDD1  Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun AssetCardContent(asset: GeneratedAsset, file: File) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Thumbnail area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            when (asset.type) {
                AssetType.SPRITE -> SpriteThumbnail(asset.filePath)
                AssetType.MUSIC -> AssetIcon("\uD83C\uDFB5")
                AssetType.SOUND_EFFECT -> AssetIcon("\uD83D\uDD0A")
                AssetType.MODEL_3D -> AssetIcon("\uD83E\uDDF1")
            }
        }

        // Info area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = asset.fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 0.dp
                ) {
                    Text(
                        text = asset.type.displayName,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (file.exists()) {
                    val sizeKb = file.length() / 1024
                    val sizeDisplay = if (sizeKb > 1024) "${"%.1f".format(sizeKb / 1024.0)} MB" else "$sizeKb KB"
                    Text(
                        text = sizeDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/** Empty state shown when no assets exist yet. Displays available capabilities. */
@Composable
internal fun EmptyPreviewState(capabilities: List<String>) {
    val assetCapabilities = capabilities.drop(1)

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Asset Preview",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Generated assets will appear here for review",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(24.dp))

            if (assetCapabilities.isNotEmpty()) {
                Text(
                    text = "Available asset types:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    assetCapabilities.forEach { cap ->
                        val displayText = when {
                            cap.contains("Sprites") -> "2D Sprites (PNG)"
                            cap.contains("3D") -> "3D Models (GLB)"
                            cap.contains("Music") -> "Music (MP3)"
                            cap.contains("Sound") -> "Sound Effects (MP3)"
                            else -> cap
                        }
                        Text(
                            text = "- $displayText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            } else {
                Text(
                    text = "No asset generation keys configured yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Open Settings (\u2699) to add API keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
        }
    }
}
