package dev.gameharness.gui.viewer3d

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.awt.AWTGLCanvas
import org.lwjgl.opengl.awt.GLData
import org.slf4j.LoggerFactory
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.file.Path
import javax.swing.SwingUtilities

private val logger = LoggerFactory.getLogger("ModelViewer")

/**
 * Interactive 3D model viewer composable.
 *
 * Embeds an OpenGL viewport via SwingPanel + AWTGLCanvas for rendering GLB models
 * with orbit camera, wireframe toggle, and material inspection.
 */
@Composable
fun ModelViewer(filePath: String, modifier: Modifier = Modifier) {
    var model by remember(filePath) { mutableStateOf<GlbModel?>(null) }
    var loadError by remember(filePath) { mutableStateOf<String?>(null) }
    var wireframe by remember { mutableStateOf(false) }
    var showMaterialInspector by remember { mutableStateOf(false) }
    var highlightMaterial by remember { mutableStateOf(-1) }

    // Load model off the GL thread
    LaunchedEffect(filePath) {
        try {
            val loaded = loadGlb(Path.of(filePath))
            model = loaded
            loadError = null
        } catch (e: Exception) {
            logger.error("Failed to load GLB: $filePath", e)
            loadError = e.message ?: "Unknown error loading model"
            model = null
        }
    }

    if (loadError != null) {
        ModelViewerError(loadError!!, filePath, modifier)
        return
    }

    val currentModel = model ?: run {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(modifier) {
        // Toolbar (above SwingPanel to avoid overlay issues)
        ModelViewerToolbar(
            model = currentModel,
            wireframe = wireframe,
            onToggleWireframe = { wireframe = !wireframe },
            showMaterialInspector = showMaterialInspector,
            onToggleMaterialInspector = { showMaterialInspector = !showMaterialInspector },
            onResetCamera = { } // Will be wired to camera ref below
        )

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 3D Viewport
            val cameraRef = remember { OrbitCamera() }
            val rendererRef = remember { ModelRenderer() }

            // Auto-fit camera to model bounds on load
            LaunchedEffect(currentModel) {
                fitCameraToModel(cameraRef, currentModel)
            }

            // Wire reset callback
            var resetCameraCallback by remember { mutableStateOf({}) }
            resetCameraCallback = {
                cameraRef.reset()
                fitCameraToModel(cameraRef, currentModel)
            }

            GlCanvas(
                model = currentModel,
                camera = cameraRef,
                renderer = rendererRef,
                wireframe = wireframe,
                highlightMaterial = highlightMaterial,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // Material inspector side panel
            if (showMaterialInspector) {
                MaterialInspectorPanel(
                    materials = currentModel.materials,
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
    }
}

@Composable
private fun ModelViewerToolbar(
    model: GlbModel,
    wireframe: Boolean,
    onToggleWireframe: () -> Unit,
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
            // Wireframe toggle
            FilterChip(
                selected = wireframe,
                onClick = onToggleWireframe,
                label = { Text("Wireframe", style = MaterialTheme.typography.labelSmall) }
            )

            // Material inspector toggle
            FilterChip(
                selected = showMaterialInspector,
                onClick = onToggleMaterialInspector,
                label = { Text("Materials", style = MaterialTheme.typography.labelSmall) }
            )

            // Reset camera
            TextButton(onClick = onResetCamera, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Reset View", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.weight(1f))

            // Model info
            Text(
                text = "${model.totalVertices} verts  |  ${model.totalTriangles} tris  |  ${model.materials.size} materials",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun GlCanvas(
    model: GlbModel,
    camera: OrbitCamera,
    renderer: ModelRenderer,
    wireframe: Boolean,
    highlightMaterial: Int,
    modifier: Modifier = Modifier
) {
    // Track mutable state that the GL canvas reads each frame
    val wireframeState = rememberUpdatedState(wireframe)
    val highlightState = rememberUpdatedState(highlightMaterial)
    val modelState = rememberUpdatedState(model)

    SwingPanel(
        modifier = modifier,
        factory = {
            val glData = GLData().apply {
                majorVersion = 3
                minorVersion = 3
                profile = GLData.Profile.CORE
                samples = 4
            }

            val canvas = object : AWTGLCanvas(glData) {
                private var glInitialized = false
                private var currentModelLoaded: GlbModel? = null

                override fun initGL() {
                    GL.createCapabilities()
                    renderer.init()
                    glInitialized = true
                }

                override fun paintGL() {
                    val w = framebufferWidth
                    val h = framebufferHeight
                    org.lwjgl.opengl.GL33.glViewport(0, 0, w, h)

                    val currentModel = modelState.value
                    if (currentModel !== currentModelLoaded) {
                        renderer.loadModel(currentModel)
                        currentModelLoaded = currentModel
                    }

                    renderer.render(camera, wireframeState.value, currentModel, highlightState.value)
                    swapBuffers()
                }
            }

            // Mouse controls
            var lastX = 0
            var lastY = 0
            var dragging = false
            var shiftDown = false

            canvas.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    lastX = e.x
                    lastY = e.y
                    dragging = true
                    shiftDown = e.isShiftDown
                }
                override fun mouseReleased(e: MouseEvent) {
                    dragging = false
                }
            })

            canvas.addMouseMotionListener(object : MouseAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (!dragging) return
                    val dx = (e.x - lastX).toFloat()
                    val dy = (e.y - lastY).toFloat()
                    lastX = e.x
                    lastY = e.y

                    if (shiftDown || e.isShiftDown) {
                        camera.pan(dx, dy)
                    } else {
                        camera.rotate(dx, dy)
                    }
                }
            })

            canvas.addMouseWheelListener { e: MouseWheelEvent ->
                camera.zoom(-e.wheelRotation.toFloat())
            }

            // Start render loop
            val renderLoop = object : Runnable {
                override fun run() {
                    if (!canvas.isValid) {
                        GL.setCapabilities(null)
                        return
                    }
                    canvas.render()
                    SwingUtilities.invokeLater(this)
                }
            }
            SwingUtilities.invokeLater(renderLoop)

            canvas
        },
        update = { }
    )

    DisposableEffect(Unit) {
        onDispose {
            renderer.dispose()
        }
    }
}

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

private fun fitCameraToModel(camera: OrbitCamera, model: GlbModel) {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE; var maxZ = Float.MIN_VALUE

    for (mesh in model.meshes) {
        for (i in 0 until mesh.vertexCount) {
            val x = mesh.positions[i * 3]
            val y = mesh.positions[i * 3 + 1]
            val z = mesh.positions[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
    }

    if (minX <= maxX) {
        camera.fitToBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }
}
