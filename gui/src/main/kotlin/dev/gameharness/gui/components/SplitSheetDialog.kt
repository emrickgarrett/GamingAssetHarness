package dev.gameharness.gui.components

import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.gameharness.core.model.GeneratedAsset
import dev.gameharness.core.util.SplitTileInfo
import dev.gameharness.core.util.SpriteSheetSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Maximum number of tile previews to render to avoid UI lag on very large sheets. */
internal const val MAX_TILE_PREVIEWS = 64

/** Preset tile size options displayed as selectable chips. */
private val TILE_PRESETS = listOf(16, 32, 48, 64, 128, 256)

/** The sheet being split: on its way, unreadable, or decoded (as pixels to cut up and as a picture to draw). */
private sealed interface SheetLoad {
    data object Loading : SheetLoad
    data object Failed : SheetLoad
    class Ready(val image: BufferedImage, val bitmap: ImageBitmap) : SheetLoad
}

/** What one set of split settings leaves: the preview tiles, and the count of every tile kept. */
private class SplitPreview(
    val settings: List<Any>,
    val tiles: List<Pair<SplitTileInfo, ImageBitmap>>,
    val keptCount: Int,
)

/**
 * Cuts [source] into [tiles], applies background removal ([removeBgColor], null
 * for none) and the empty-tile filter, and returns the pictures of the kept
 * tiles among the first [MAX_TILE_PREVIEWS], with the number kept overall.
 * Gives up between tiles when the coroutine is cancelled, which is what makes
 * dragging the tolerance slider cheap.
 */
internal suspend fun buildSplitPreview(
    source: BufferedImage,
    tiles: List<SplitTileInfo>,
    skipEmpty: Boolean,
    removeBgColor: java.awt.Color?,
    bgTolerance: Int,
): Pair<List<Pair<SplitTileInfo, ImageBitmap>>, Int> {
    val previews = ArrayList<Pair<SplitTileInfo, ImageBitmap>>()
    var kept = 0
    for ((index, tileInfo) in tiles.withIndex()) {
        val needsPixels = index < MAX_TILE_PREVIEWS || skipEmpty
        if (!needsPixels) {
            kept++
            continue
        }
        coroutineContext.ensureActive()
        var img = SpriteSheetSplitter.extractTile(source, tileInfo)
        if (removeBgColor != null) {
            img = SpriteSheetSplitter.removeBackgroundColor(img, removeBgColor, bgTolerance)
        }
        if (skipEmpty && SpriteSheetSplitter.isFullyTransparent(img)) continue
        kept++
        if (index < MAX_TILE_PREVIEWS) previews.add(tileInfo to img.toComposeImageBitmap())
    }
    return previews to kept
}

/**
 * Dialog for splitting a sprite sheet image into individual tiles.
 *
 * Displays a live grid overlay on the source image, tile size presets and
 * custom dimension inputs, split analysis summary, and a scrollable preview
 * grid of extracted tiles. The user confirms by clicking "Split into N tiles".
 */
@Composable
internal fun SplitSheetDialog(
    asset: GeneratedAsset,
    onSplit: (asset: GeneratedAsset, tileWidth: Int, tileHeight: Int,
              skipEmpty: Boolean, removeBgColor: java.awt.Color?, bgTolerance: Int,
              targetFolder: String?) -> Unit,
    onDismiss: () -> Unit
) {
    // Load the source image once, off the UI thread: a large sheet takes long
    // enough to decode that the window would stop answering while it did.
    val sheet by produceState<SheetLoad>(SheetLoad.Loading, asset.filePath) {
        value = withContext(Dispatchers.IO) {
            try {
                val image = ImageIO.read(File(asset.filePath))
                if (image == null) SheetLoad.Failed else SheetLoad.Ready(image, image.toComposeImageBitmap())
            } catch (_: Exception) {
                SheetLoad.Failed
            }
        }
    }

    val loaded = when (val current = sheet) {
        SheetLoad.Loading -> return // the dialog opens when the image is in
        SheetLoad.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Cannot Load Image") },
                text = { Text("Failed to load the sprite sheet image.") },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
            )
            return
        }
        is SheetLoad.Ready -> current
    }
    val sourceImage = loaded.image

    var tileWidth by remember { mutableStateOf(32) }
    var tileHeight by remember { mutableStateOf(32) }
    var linkDimensions by remember { mutableStateOf(true) }
    var customMode by remember { mutableStateOf(false) }
    var customWidthText by remember { mutableStateOf("32") }
    var customHeightText by remember { mutableStateOf("32") }

    // Filtering state
    var skipEmptyTiles by remember { mutableStateOf(true) }
    var removeBgEnabled by remember { mutableStateOf(false) }
    var selectedBgColor by remember { mutableStateOf(java.awt.Color.WHITE) }
    var bgColorPreset by remember { mutableStateOf("White") }
    var customHexText by remember { mutableStateOf("FFFFFF") }
    var bgTolerance by remember { mutableIntStateOf(30) }

    // Organization state
    var createSubfolder by remember { mutableStateOf(true) }
    val defaultFolderName = remember(asset.fileName) {
        asset.fileName.substringBeforeLast(".")
    }
    var subfolderName by remember { mutableStateOf(defaultFolderName) }

    // Analyze the split every time dimensions change
    val splitResult = remember(tileWidth, tileHeight) {
        SpriteSheetSplitter.analyze(sourceImage.width, sourceImage.height, tileWidth, tileHeight)
    }

    // The tile previews (the first MAX_TILE_PREVIEWS tiles, filtered) and how many
    // tiles survive filtering. Worked out in the background: it reads every pixel
    // of every tile, and it is asked for again on each step of the tolerance
    // slider. The last result stays on screen until the next one is ready.
    val previewSettings = listOf(tileWidth, tileHeight, skipEmptyTiles, removeBgEnabled, selectedBgColor, bgTolerance)
    var preview by remember { mutableStateOf<SplitPreview?>(null) }
    LaunchedEffect(previewSettings) {
        val computed = withContext(Dispatchers.Default) {
            buildSplitPreview(
                sourceImage, splitResult.tiles, skipEmptyTiles,
                if (removeBgEnabled) selectedBgColor else null, bgTolerance,
            )
        }
        preview = SplitPreview(previewSettings, computed.first, computed.second)
    }
    val tilePreviews = preview?.tiles ?: emptyList()
    val previewedCount = preview?.keptCount ?: 0
    // Null while the count for the current settings is still being worked out.
    val filteredTileCount = preview?.takeIf { it.settings == previewSettings }?.keptCount
    val skippedCount = if (filteredTileCount != null) splitResult.totalTiles - filteredTileCount else 0

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Split Sprite Sheet",
        state = rememberDialogState(size = DpSize(720.dp, 880.dp)),
        resizable = true
    ) {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ── Source image with grid overlay ──────────────────────
                    Text(
                        text = "Source: ${sourceImage.width} x ${sourceImage.height} px",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.45f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        GridOverlayPreview(
                            sourceImage = sourceImage,
                            imageBitmap = loaded.bitmap,
                            tileWidth = tileWidth,
                            tileHeight = tileHeight,
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        )
                    }

                    // ── Analysis summary ────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val summaryText = buildString {
                            append("Tile: ${tileWidth} x ${tileHeight}  |  ")
                            append("${splitResult.columns} cols x ${splitResult.rows} rows = ")
                            append("${splitResult.totalTiles} tiles")
                            if (skippedCount > 0) {
                                append("  ($skippedCount empty, $filteredTileCount kept)")
                            }
                        }
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!splitResult.isExactFit && splitResult.totalTiles > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "(${splitResult.remainderX}px, ${splitResult.remainderY}px remainder)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }

                    // ── Tile size selection ─────────────────────────────────
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Tile size:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TILE_PRESETS.forEach { size ->
                                val isSelected = !customMode && tileWidth == size && tileHeight == size
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        customMode = false
                                        tileWidth = size
                                        tileHeight = size
                                        customWidthText = size.toString()
                                        customHeightText = size.toString()
                                    },
                                    label = { Text("${size}px") }
                                )
                            }

                            FilterChip(
                                selected = customMode,
                                onClick = { customMode = true },
                                label = { Text("Custom") }
                            )
                        }

                        if (customMode) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customWidthText,
                                    onValueChange = { text ->
                                        customWidthText = text.filter { it.isDigit() }
                                        val w = customWidthText.toIntOrNull()
                                        if (w != null && w > 0) {
                                            tileWidth = w
                                            if (linkDimensions) {
                                                tileHeight = w
                                                customHeightText = customWidthText
                                            }
                                        }
                                    },
                                    label = { Text("Width") },
                                    modifier = Modifier.width(100.dp),
                                    singleLine = true
                                )

                                Text("x", style = MaterialTheme.typography.bodyLarge)

                                OutlinedTextField(
                                    value = customHeightText,
                                    onValueChange = { text ->
                                        customHeightText = text.filter { it.isDigit() }
                                        val h = customHeightText.toIntOrNull()
                                        if (h != null && h > 0) {
                                            tileHeight = h
                                            if (linkDimensions) {
                                                tileWidth = h
                                                customWidthText = customHeightText
                                            }
                                        }
                                    },
                                    label = { Text("Height") },
                                    modifier = Modifier.width(100.dp),
                                    singleLine = true,
                                    enabled = !linkDimensions
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = linkDimensions,
                                        onCheckedChange = { linked ->
                                            linkDimensions = linked
                                            if (linked) {
                                                tileHeight = tileWidth
                                                customHeightText = customWidthText
                                            }
                                        }
                                    )
                                    Text(
                                        text = "Link W & H",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    // ── Filtering options ─────────────────────────────────
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Filtering:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = skipEmptyTiles,
                                onCheckedChange = { skipEmptyTiles = it }
                            )
                            Text(
                                text = "Skip fully transparent tiles",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = removeBgEnabled,
                                onCheckedChange = { removeBgEnabled = it }
                            )
                            Text(
                                text = "Remove background color",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (removeBgEnabled) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 40.dp)
                            ) {
                                FilterChip(
                                    selected = bgColorPreset == "White",
                                    onClick = {
                                        bgColorPreset = "White"
                                        selectedBgColor = java.awt.Color.WHITE
                                        customHexText = "FFFFFF"
                                    },
                                    label = { Text("White") }
                                )
                                FilterChip(
                                    selected = bgColorPreset == "Black",
                                    onClick = {
                                        bgColorPreset = "Black"
                                        selectedBgColor = java.awt.Color.BLACK
                                        customHexText = "000000"
                                    },
                                    label = { Text("Black") }
                                )
                                FilterChip(
                                    selected = bgColorPreset == "Custom",
                                    onClick = { bgColorPreset = "Custom" },
                                    label = { Text("Custom") }
                                )

                                if (bgColorPreset == "Custom") {
                                    Text("#", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedTextField(
                                        value = customHexText,
                                        onValueChange = { text ->
                                            val hex = text.filter { it.isLetterOrDigit() }.take(6)
                                            customHexText = hex
                                            if (hex.length == 6) {
                                                try {
                                                    selectedBgColor = java.awt.Color.decode("#$hex")
                                                } catch (_: NumberFormatException) { }
                                            }
                                        },
                                        modifier = Modifier.width(90.dp),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall
                                    )
                                }

                                // Color preview swatch
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            Color(
                                                selectedBgColor.red / 255f,
                                                selectedBgColor.green / 255f,
                                                selectedBgColor.blue / 255f
                                            ),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                )
                            }

                            // Tolerance slider
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 40.dp)
                            ) {
                                Text(
                                    text = "Tolerance:",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(65.dp)
                                )
                                Slider(
                                    value = bgTolerance.toFloat(),
                                    onValueChange = { bgTolerance = it.toInt() },
                                    valueRange = 0f..100f,
                                    steps = 9,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "$bgTolerance",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(30.dp),
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }

                    // ── Organization ─────────────────────────────────────────
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Organization:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = createSubfolder,
                                onCheckedChange = { createSubfolder = it }
                            )
                            Text(
                                text = "Place tiles in subfolder",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (createSubfolder) {
                            OutlinedTextField(
                                value = subfolderName,
                                onValueChange = { subfolderName = it },
                                label = { Text("Folder name") },
                                modifier = Modifier.padding(start = 40.dp).width(250.dp),
                                singleLine = true
                            )
                        }
                    }

                    // ── Tile preview grid ───────────────────────────────────
                    if (tilePreviews.isNotEmpty()) {
                        Text(
                            text = if (previewedCount > MAX_TILE_PREVIEWS)
                                "Tile preview (showing ${tilePreviews.size} of $previewedCount):"
                            else
                                "Tile preview ($previewedCount tiles):",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 56.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.35f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(tilePreviews) { (tileInfo, bitmap) ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(2.dp)
                                    ) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = "Tile ${tileInfo.row},${tileInfo.col}",
                                            modifier = Modifier.size(48.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                        Text(
                                            text = "${tileInfo.row},${tileInfo.col}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Action buttons ──────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                onSplit(
                                    asset, tileWidth, tileHeight,
                                    skipEmptyTiles,
                                    if (removeBgEnabled) selectedBgColor else null,
                                    bgTolerance,
                                    if (createSubfolder && subfolderName.isNotBlank()) subfolderName.trim() else null
                                )
                            },
                            enabled = filteredTileCount != null && filteredTileCount > 0
                        ) {
                            Text(if (filteredTileCount != null) "Split into $filteredTileCount tiles" else "Counting tiles…")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Canvas composable that draws the source image scaled to fit, with a red
 * grid overlay showing where tile boundaries will fall.
 */
@Composable
private fun GridOverlayPreview(
    sourceImage: BufferedImage,
    imageBitmap: ImageBitmap,
    tileWidth: Int,
    tileHeight: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Scale image to fit the canvas preserving aspect ratio
        val imgW = sourceImage.width.toFloat()
        val imgH = sourceImage.height.toFloat()
        val scale = minOf(canvasWidth / imgW, canvasHeight / imgH)
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        val offsetX = (canvasWidth - scaledW) / 2f
        val offsetY = (canvasHeight - scaledH) / 2f

        // Draw the source image
        drawImage(
            image = imageBitmap,
            dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(scaledW.toInt(), scaledH.toInt())
        )

        // Draw grid overlay
        val gridColor = Color.Red.copy(alpha = 0.6f)
        val strokeWidth = 1.5f

        // Vertical lines
        val cols = sourceImage.width / tileWidth
        for (i in 0..cols) {
            val x = offsetX + (i * tileWidth * scale)
            if (x <= offsetX + scaledW + 0.5f) {
                drawLine(
                    color = gridColor,
                    start = Offset(x, offsetY),
                    end = Offset(x, offsetY + scaledH),
                    strokeWidth = strokeWidth
                )
            }
        }

        // Horizontal lines
        val rows = sourceImage.height / tileHeight
        for (i in 0..rows) {
            val y = offsetY + (i * tileHeight * scale)
            if (y <= offsetY + scaledH + 0.5f) {
                drawLine(
                    color = gridColor,
                    start = Offset(offsetX, y),
                    end = Offset(offsetX + scaledW, y),
                    strokeWidth = strokeWidth
                )
            }
        }

        // Draw remainder region with a different tint if not exact fit
        if (cols * tileWidth < sourceImage.width || rows * tileHeight < sourceImage.height) {
            val usedW = cols * tileWidth * scale
            val usedH = rows * tileHeight * scale

            // Right remainder strip
            if (cols * tileWidth < sourceImage.width) {
                drawRect(
                    color = Color.Yellow.copy(alpha = 0.15f),
                    topLeft = Offset(offsetX + usedW, offsetY),
                    size = androidx.compose.ui.geometry.Size(scaledW - usedW, scaledH)
                )
            }

            // Bottom remainder strip
            if (rows * tileHeight < sourceImage.height) {
                drawRect(
                    color = Color.Yellow.copy(alpha = 0.15f),
                    topLeft = Offset(offsetX, offsetY + usedH),
                    size = androidx.compose.ui.geometry.Size(usedW, scaledH - usedH)
                )
            }
        }
    }
}
