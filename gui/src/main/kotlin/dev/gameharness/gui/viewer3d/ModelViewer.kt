package dev.gameharness.gui.viewer3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("ModelViewer")

/** The viewport's backdrop; the renderer clears to the same color so no seam shows while a frame is on its way. */
private val VIEWPORT_BACKGROUND = floatArrayOf(0.118f, 0.125f, 0.141f, 1f)

private sealed interface LoadState {
    data object Loading : LoadState
    class Failed(val message: String) : LoadState
    class Loaded(val prepared: PreparedModel) : LoadState
}

/**
 * Interactive 3D model viewer.
 *
 * Shows the model at [filePath] (GLB, glTF, FBX or OBJ) with its textures.
 * Drag orbits, right-drag or Shift-drag pans, the wheel zooms. A rigged model
 * plays its first animation clip; the bar under the viewport picks the clip,
 * pauses, scrubs and changes speed.
 *
 * The picture is rendered off screen by a [ViewerSession] and drawn here as an
 * image, so the viewer is ordinary Compose content: dialogs and menus open
 * over it, and nothing 3D runs on the UI thread.
 */
@Composable
fun ModelViewer(filePath: String, modifier: Modifier = Modifier) {
    var loadState by remember(filePath) { mutableStateOf<LoadState>(LoadState.Loading) }
    var unavailable by remember { mutableStateOf<String?>(null) }
    // Read only where it is drawn (the viewport) and where the clock is shown, so
    // that a new frame redraws those and recomposes nothing else.
    val frameState = remember { mutableStateOf<ViewerFrame?>(null) }

    // Frames cross from the render thread through a channel that keeps only the newest.
    val frames = remember {
        Channel<ViewerFrame>(Channel.CONFLATED, onUndeliveredElement = { it.image.close() })
    }
    val session = remember {
        ViewerSession(
            // a frame that arrives after the viewer has gone is nobody's to draw
            onFrame = { if (frames.trySend(it).isFailure) it.image.close() },
            onUnavailable = { unavailable = it },
        )
    }
    LaunchedEffect(session) {
        for (next in frames) {
            val previous = frameState.value
            frameState.value = next
            // Safe only because the viewport reads the state while drawing: this
            // runs between composition and drawing, so a draw that kept hold of
            // the image it was composed with would be handed a closed one.
            previous?.image?.close()
        }
    }
    DisposableEffect(session) {
        onDispose {
            session.close()
            frames.close()
            frameState.value?.image?.close()
            frameState.value = null
        }
    }

    // What the toolbar and the animation bar control
    var wireframe by remember { mutableStateOf(false) }
    var textures by remember { mutableStateOf(true) }
    var grid by remember { mutableStateOf(true) }
    var bones by remember { mutableStateOf(false) }
    var showMaterialInspector by remember { mutableStateOf(false) }
    var highlightMaterial by remember { mutableStateOf(-1) }
    var clip by remember(filePath) { mutableStateOf(-1) }
    var playing by remember(filePath) { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }

    LaunchedEffect(filePath) {
        val result = withContext(Dispatchers.IO) {
            try {
                val started = System.nanoTime()
                val prepared = PreparedModel.load(Path.of(filePath))
                logger.info("Opened {} in {} ms", Path.of(filePath).fileName, (System.nanoTime() - started) / 1_000_000)
                LoadState.Loaded(prepared)
            } catch (e: Throwable) {
                logger.error("Failed to load model: $filePath", e)
                LoadState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        if (result is LoadState.Loaded) {
            clip = result.prepared.defaultAnimation
            playing = clip >= 0
            highlightMaterial = -1
            session.setSpeed(speed)
            session.setModel(result.prepared)
        }
        loadState = result
    }

    LaunchedEffect(wireframe, textures, grid, bones, highlightMaterial) {
        session.setOptions(
            RenderOptions(
                wireframe = wireframe, textures = textures, grid = grid, bones = bones,
                highlightMaterial = highlightMaterial, background = VIEWPORT_BACKGROUND,
            )
        )
    }

    val failure = (loadState as? LoadState.Failed)?.message
        ?: unavailable?.let { "The 3D view could not start: $it" }
    if (failure != null) {
        ModelViewerError(failure, filePath, modifier)
        return
    }
    val prepared = (loadState as? LoadState.Loaded)?.prepared
    val model = prepared?.model

    Column(modifier) {
        ModelViewerToolbar(
            rigged = model?.isRigged == true,
            wireframe = wireframe,
            onToggleWireframe = { wireframe = !wireframe },
            textures = textures,
            onToggleTextures = { textures = !textures },
            grid = grid,
            onToggleGrid = { grid = !grid },
            bones = bones,
            onToggleBones = { bones = !bones },
            showMaterialInspector = showMaterialInspector,
            onToggleMaterialInspector = {
                showMaterialInspector = !showMaterialInspector
                if (!showMaterialInspector) highlightMaterial = -1
            },
            onResetCamera = { session.resetView() }
        )

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Viewport(
                session = session,
                frame = frameState,
                info = model?.let { describe(it, prepared.largestTexture) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            if (showMaterialInspector && model != null) {
                MaterialInspectorPanel(
                    materials = model.materials,
                    selectedIndex = if (highlightMaterial >= 0) highlightMaterial else null,
                    onSelect = { idx ->
                        highlightMaterial = if (highlightMaterial == idx) -1 else idx
                    },
                    onDismiss = {
                        showMaterialInspector = false
                        highlightMaterial = -1
                    }
                )
            }
        }

        if (model != null && model.animations.isNotEmpty()) {
            AnimationBar(
                animations = model.animations,
                clip = clip,
                playing = playing,
                frame = frameState,
                speed = speed,
                onSelectClip = { index ->
                    clip = index
                    session.selectAnimation(index, model.animations.getOrNull(index)?.duration ?: 0f)
                    playing = index >= 0
                    session.setPlaying(playing)
                },
                onTogglePlay = {
                    playing = !playing
                    session.setPlaying(playing)
                },
                onSeek = { session.seek(it) },
                onSpeed = {
                    speed = it
                    session.setSpeed(it)
                }
            )
        }
    }
}

/** The picture itself, and the pointer gestures that move the camera. */
@Composable
private fun Viewport(session: ViewerSession, frame: State<ViewerFrame?>, info: String?, modifier: Modifier = Modifier) {
    val backdrop = Color(VIEWPORT_BACKGROUND[0], VIEWPORT_BACKGROUND[1], VIEWPORT_BACKGROUND[2])
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
            .background(backdrop)
            .onSizeChanged { session.resize(it.width, it.height) }
            .pointerInput(session) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                // wheel away from you zooms in
                                session.zoom(-change.scrollDelta.y)
                                change.consume()
                            }
                            PointerEventType.Move -> {
                                val buttons = event.buttons
                                val delta = change.position - change.previousPosition
                                if (delta.x == 0f && delta.y == 0f) continue
                                val panning = buttons.isSecondaryPressed || buttons.isTertiaryPressed ||
                                    (buttons.isPrimaryPressed && event.keyboardModifiers.isShiftPressed)
                                if (panning) {
                                    session.pan(delta.x, delta.y)
                                    change.consume()
                                } else if (buttons.isPrimaryPressed) {
                                    session.orbit(delta.x, delta.y)
                                    change.consume()
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // the newest frame at the moment of drawing, never one remembered from composition
            val image = frame.value?.image ?: return@Canvas
            drawIntoCanvas { canvas ->
                // a frame of the previous size, during a resize, is stretched for the moment it lasts
                canvas.nativeCanvas.drawImageRect(image, Rect.makeWH(size.width, size.height))
            }
        }
        if (info == null) {
            CircularProgressIndicator()
        } else {
            // In the picture rather than on the toolbar: at the panel's default
            // width the toolbar has no room left for it beside the buttons.
            Text(
                text = info,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 10.dp, vertical = 6.dp)
            )
            Text(
                text = "Drag to orbit  •  Right-drag or Shift-drag to pan  •  Scroll to zoom",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
            )
        }
    }
}

@Composable
private fun ModelViewerToolbar(
    rigged: Boolean,
    wireframe: Boolean,
    onToggleWireframe: () -> Unit,
    textures: Boolean,
    onToggleTextures: () -> Unit,
    grid: Boolean,
    onToggleGrid: () -> Unit,
    bones: Boolean,
    onToggleBones: () -> Unit,
    showMaterialInspector: Boolean,
    onToggleMaterialInspector: () -> Unit,
    onResetCamera: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolbarChip("Textures", textures, onToggleTextures)
            ToolbarChip("Wireframe", wireframe, onToggleWireframe)
            if (rigged) ToolbarChip("Bones", bones, onToggleBones)
            ToolbarChip("Grid", grid, onToggleGrid)
            ToolbarChip("Materials", showMaterialInspector, onToggleMaterialInspector)

            Spacer(Modifier.weight(1f))

            TextButton(onClick = onResetCamera, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Reset View", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ToolbarChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
    )
}

/** "GLB  |  41,200 verts  |  68,310 tris  |  1 material  |  2048x2048 texture  |  24 joints  |  3 clips" */
private fun describe(model: SceneModel, largestTexture: Pair<Int, Int>?): String {
    val parts = ArrayList<String>()
    parts.add(model.format.uppercase())
    parts.add("%,d verts".format(model.totalVertices))
    parts.add("%,d tris".format(model.totalTriangles))
    parts.add("${model.materials.size} material${if (model.materials.size != 1) "s" else ""}")
    if (largestTexture != null) parts.add("${largestTexture.first}x${largestTexture.second} texture")
    if (model.isRigged) parts.add("${model.jointCount} joints")
    if (model.animations.isNotEmpty()) {
        parts.add("${model.animations.size} clip${if (model.animations.size != 1) "s" else ""}")
    }
    return parts.joinToString("  |  ")
}

/** Clip picker, play/pause, a timeline to scrub, and playback speed. */
@Composable
private fun AnimationBar(
    animations: List<SceneAnimation>,
    clip: Int,
    playing: Boolean,
    frame: State<ViewerFrame?>,
    speed: Float,
    onSelectClip: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeed: (Float) -> Unit
) {
    val current = animations.getOrNull(clip)
    val duration = current?.duration ?: 0f
    var menuOpen by remember { mutableStateOf(false) }
    // while the thumb is held, show where it is rather than where playback is
    val scrubbing = remember { mutableStateOf<Float?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(top = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalButton(
                onClick = onTogglePlay,
                // a clip of a single key is a pose, not a motion: nothing to play
                enabled = current != null && duration > 0f,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = if (playing && duration > 0f) "⏸  Pause" else "▶  Play",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Box {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp).widthIn(max = 200.dp)
                ) {
                    Text(
                        text = (current?.name ?: "Rest pose") + "  ▾",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    animations.forEachIndexed { index, animation ->
                        DropdownMenuItem(
                            text = { Text("${animation.name}  (${formatSeconds(animation.duration)})") },
                            onClick = {
                                menuOpen = false
                                onSelectClip(index)
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Rest pose") },
                        onClick = {
                            menuOpen = false
                            onSelectClip(-1)
                        }
                    )
                }
            }

            Timeline(
                frame = frame,
                scrubbing = scrubbing,
                duration = duration,
                enabled = current != null && duration > 0f,
                onSeek = onSeek,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = { onSpeed(SPEEDS[(SPEEDS.indexOf(speed) + 1).mod(SPEEDS.size)]) },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("${formatSpeed(speed)}×", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * The scrub slider and the clock beside it. On its own so that the sixty frames
 * a second of a playing clip recompose these two and not the whole bar.
 */
@Composable
private fun Timeline(
    frame: State<ViewerFrame?>,
    scrubbing: MutableState<Float?>,
    duration: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val time = scrubbing.value ?: frame.value?.animationTime ?: 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Slider(
            value = time.coerceIn(0f, duration.coerceAtLeast(0f)),
            onValueChange = {
                scrubbing.value = it
                onSeek(it)
            },
            onValueChangeFinished = { scrubbing.value = null },
            valueRange = 0f..duration.coerceAtLeast(0.001f),
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${formatSeconds(time)} / ${formatSeconds(duration)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val SPEEDS = listOf(1f, 2f, 0.25f, 0.5f)

private fun formatSeconds(seconds: Float): String = "%.2fs".format(seconds)

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

@Composable
private fun ModelViewerError(error: String, filePath: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Failed to load 3D model",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = filePath.substringAfterLast('/').substringAfterLast('\\'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f)
            )
        }
    }
}
