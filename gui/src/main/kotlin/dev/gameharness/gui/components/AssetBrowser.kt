package dev.gameharness.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.gameharness.core.model.GeneratedAsset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Grid-based asset browser with folder navigation, multi-selection, and drag-to-folder support.
 *
 * Displays folders and asset cards in an adaptive grid. Supports Ctrl+click for toggle selection,
 * Shift+click for range selection, and drag-and-drop onto folder cards.
 */
@Composable
internal fun AssetBrowser(
    assets: List<GeneratedAsset>,
    folders: Set<String>,
    currentFolder: String,
    onAssetSelected: (GeneratedAsset) -> Unit,
    onDeleteAsset: (assetId: String) -> Unit,
    onDeleteAssets: (assetIds: Set<String>) -> Unit,
    onReviseAsset: (GeneratedAsset) -> Unit,
    onMoveAsset: (assetId: String, targetFolder: String) -> Unit,
    onMoveAssets: (assetIds: Set<String>, targetFolder: String) -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit
) {
    // Assets in the current folder only
    val currentAssets by remember(assets, currentFolder) {
        derivedStateOf {
            assets.filter { it.folder == currentFolder }.sortedByDescending { it.createdAt }
        }
    }

    // Direct child folders of currentFolder
    val childFolders = folders
        .filter { folderPath ->
            val parent = folderPath.substringBeforeLast("/", "")
            parent == currentFolder && folderPath != currentFolder
        }
        .map { it.substringAfterLast("/") to it }
        .sortedBy { it.first.lowercase() }

    // ─── Selection State ─────────────────────────────────────
    var selectedAssetIds by remember { mutableStateOf(emptySet<String>()) }
    var lastClickedIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(currentAssets.map { it.id }) {
        val validIds = currentAssets.map { it.id }.toSet()
        selectedAssetIds = selectedAssetIds.intersect(validIds)
    }

    LaunchedEffect(currentFolder) {
        selectedAssetIds = emptySet()
        lastClickedIndex = null
    }

    // ─── Drag State ──────────────────────────────────────────
    var isDragging by remember { mutableStateOf(false) }
    var draggedAssetIds by remember { mutableStateOf(emptySet<String>()) }
    var dragWindowPosition by remember { mutableStateOf(Offset.Zero) }
    var dropTargetFolder by remember { mutableStateOf<String?>(null) }
    val folderCoordinates = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    var browserCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun hitTestFolder(windowPos: Offset): String? {
        return folderCoordinates.entries.firstOrNull { (_, coords) ->
            if (!coords.isAttached) return@firstOrNull false
            coords.boundsInWindow().contains(windowPos)
        }?.key
    }

    // ─── Click Handlers ──────────────────────────────────────
    fun handleAssetClick(asset: GeneratedAsset, index: Int, isCtrl: Boolean, isShift: Boolean) {
        when {
            isCtrl -> {
                selectedAssetIds = if (asset.id in selectedAssetIds) {
                    selectedAssetIds - asset.id
                } else {
                    selectedAssetIds + asset.id
                }
                lastClickedIndex = index
            }
            isShift && lastClickedIndex != null -> {
                val start = min(lastClickedIndex!!, index)
                val end = max(lastClickedIndex!!, index)
                val rangeIds = currentAssets.subList(start, end + 1).map { it.id }.toSet()
                selectedAssetIds = selectedAssetIds + rangeIds
            }
            else -> {
                selectedAssetIds = emptySet()
                lastClickedIndex = index
                onAssetSelected(asset)
            }
        }
    }

    fun handleAssetRightClick(asset: GeneratedAsset) {
        if (asset.id !in selectedAssetIds || selectedAssetIds.size <= 1) {
            selectedAssetIds = setOf(asset.id)
            lastClickedIndex = currentAssets.indexOf(asset)
        }
    }

    // ─── Dialog State ────────────────────────────────────────
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf<GeneratedAsset?>(null) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }
    var showDeleteAssetDialog by remember { mutableStateOf<GeneratedAsset?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { browserCoordinates = it }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with breadcrumb + selection info + New Folder button
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BreadcrumbBar(
                        currentFolder = currentFolder,
                        onNavigate = onNavigateToFolder,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(8.dp))

                    if (selectedAssetIds.isNotEmpty()) {
                        Text(
                            text = "${selectedAssetIds.size} selected",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { selectedAssetIds = emptySet() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Clear", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Text(
                            text = "${currentAssets.size} item${if (currentAssets.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    OutlinedButton(
                        onClick = { showNewFolderDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("+ Folder", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Grid of folders + asset cards
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (currentFolder.isNotEmpty()) {
                    item(key = "back") {
                        BackFolderCard(onClick = onNavigateUp)
                    }
                }

                items(childFolders, key = { "folder:${it.second}" }) { (displayName, fullPath) ->
                    Box(
                        modifier = Modifier.onGloballyPositioned { coords ->
                            folderCoordinates[fullPath] = coords
                        }
                    ) {
                        FolderCard(
                            name = displayName,
                            assetCount = assets.count { it.folder == fullPath || it.folder.startsWith("$fullPath/") },
                            onClick = { onNavigateToFolder(fullPath) },
                            onRename = { renameFolderTarget = fullPath to displayName },
                            onDelete = { deleteFolderTarget = fullPath },
                            isDropTarget = dropTargetFolder == fullPath
                        )
                    }
                }

                items(currentAssets, key = { it.id }) { asset ->
                    val index = currentAssets.indexOf(asset)
                    AssetCardWithContextMenu(
                        asset = asset,
                        isSelected = asset.id in selectedAssetIds,
                        selectedCount = selectedAssetIds.size,
                        onPrimaryClick = { isCtrl, isShift ->
                            handleAssetClick(asset, index, isCtrl, isShift)
                        },
                        onRightClick = { handleAssetRightClick(asset) },
                        onRevise = { onReviseAsset(asset) },
                        onMove = { showMoveDialog = asset },
                        onDelete = { showDeleteAssetDialog = asset },
                        onBatchMove = { showBatchMoveDialog = true },
                        onBatchDelete = { showBatchDeleteDialog = true },
                        onDragStart = {
                            draggedAssetIds = if (asset.id in selectedAssetIds && selectedAssetIds.size > 1) {
                                selectedAssetIds
                            } else {
                                setOf(asset.id)
                            }
                            isDragging = true
                        },
                        onDragMove = { windowPos ->
                            dragWindowPosition = windowPos
                            dropTargetFolder = hitTestFolder(windowPos)
                        },
                        onDragEnd = {
                            val target = dropTargetFolder
                            if (target != null && draggedAssetIds.isNotEmpty()) {
                                if (draggedAssetIds.size == 1) {
                                    onMoveAsset(draggedAssetIds.first(), target)
                                } else {
                                    onMoveAssets(draggedAssetIds, target)
                                }
                            }
                            isDragging = false
                            draggedAssetIds = emptySet()
                            dragWindowPosition = Offset.Zero
                            dropTargetFolder = null
                        }
                    )
                }
            }
        }

        // Drag overlay badge
        if (isDragging && draggedAssetIds.isNotEmpty()) {
            val coords = browserCoordinates
            if (coords != null && coords.isAttached) {
                val localPos = coords.windowToLocal(dragWindowPosition)
                Box(
                    modifier = Modifier
                        .offset { IntOffset((localPos.x + 16).roundToInt(), (localPos.y + 16).roundToInt()) }
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${draggedAssetIds.size} asset${if (draggedAssetIds.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }

    // ─── Dialogs ──────────────────────────────────────────────────────

    if (showNewFolderDialog) {
        NewFolderDialog(
            onConfirm = { name ->
                onCreateFolder(name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    showMoveDialog?.let { asset ->
        MoveToFolderDialog(
            folders = folders,
            currentFolder = asset.folder,
            onConfirm = { targetFolder ->
                onMoveAsset(asset.id, targetFolder)
                showMoveDialog = null
            },
            onDismiss = { showMoveDialog = null }
        )
    }

    if (showBatchMoveDialog) {
        MoveToFolderDialog(
            folders = folders,
            currentFolder = currentFolder,
            onConfirm = { targetFolder ->
                onMoveAssets(selectedAssetIds, targetFolder)
                showBatchMoveDialog = false
            },
            onDismiss = { showBatchMoveDialog = false }
        )
    }

    showDeleteAssetDialog?.let { asset ->
        DeleteConfirmDialog(
            assetName = asset.fileName,
            onConfirm = {
                onDeleteAsset(asset.id)
                showDeleteAssetDialog = null
            },
            onDismiss = { showDeleteAssetDialog = null }
        )
    }

    if (showBatchDeleteDialog) {
        BatchDeleteConfirmDialog(
            count = selectedAssetIds.size,
            onConfirm = {
                onDeleteAssets(selectedAssetIds)
                showBatchDeleteDialog = false
            },
            onDismiss = { showBatchDeleteDialog = false }
        )
    }

    renameFolderTarget?.let { (fullPath, displayName) ->
        RenameFolderDialog(
            currentName = displayName,
            onConfirm = { newName ->
                onRenameFolder(fullPath, newName)
                renameFolderTarget = null
            },
            onDismiss = { renameFolderTarget = null }
        )
    }

    deleteFolderTarget?.let { folderPath ->
        val affectedCount = assets.count { it.folder == folderPath || it.folder.startsWith("$folderPath/") }
        DeleteFolderConfirmDialog(
            folderName = folderPath.substringAfterLast("/"),
            assetCount = affectedCount,
            onConfirm = {
                onDeleteFolder(folderPath)
                deleteFolderTarget = null
            },
            onDismiss = { deleteFolderTarget = null }
        )
    }
}

/** Breadcrumb navigation bar showing the folder path with clickable segments. */
@Composable
private fun BreadcrumbBar(
    currentFolder: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = { onNavigate("") },
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            Text(
                text = "Root",
                style = MaterialTheme.typography.labelMedium,
                color = if (currentFolder.isEmpty())
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.primary
            )
        }

        if (currentFolder.isNotEmpty()) {
            val segments = currentFolder.split("/")
            segments.forEachIndexed { index, segment ->
                Text(
                    text = " > ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                val pathUpToHere = segments.take(index + 1).joinToString("/")
                val isLast = index == segments.lastIndex
                TextButton(
                    onClick = { onNavigate(pathUpToHere) },
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        text = segment,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isLast)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
