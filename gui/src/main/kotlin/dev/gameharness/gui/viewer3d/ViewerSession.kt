package dev.gameharness.gui.viewer3d

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import kotlin.concurrent.thread

private val logger = LoggerFactory.getLogger("ViewerSession")

/** One finished picture of the model. Whoever receives it closes [image]. */
class ViewerFrame(
    val image: Image,
    /** Seconds into the current animation clip this frame shows. */
    val animationTime: Float,
)

/**
 * Draws a model for the interactive viewer on a thread of its own.
 *
 * The UI calls the setters (orbit, resize, pick a clip, ...) from its thread;
 * they only record what is wanted and wake the render thread. That thread owns
 * the OpenGL context, draws when something changed or an animation is playing
 * (at most 60 times a second, and not at all otherwise), and hands each frame
 * to [onFrame] as a Skia image. Nothing here blocks the caller, and nothing
 * here runs on the UI thread.
 */
class ViewerSession(
    private val onFrame: (ViewerFrame) -> Unit,
    private val onUnavailable: (String) -> Unit,
) : AutoCloseable {

    private val lock = Object()

    // what the UI asked for; guarded by lock
    private var pendingModel: PreparedModel? = null
    private var width = 0
    private var height = 0
    private val camera = OrbitCamera()
    private var bounds: FloatArray? = null
    private var options = RenderOptions()
    private var animationIndex = -1
    private var playing = false
    private var speed = 1f
    private var time = 0f
    private var duration = 0f
    private var dirty = false
    private var closed = false

    private val renderThread = thread(name = "model-viewer-gl", isDaemon = true) { run() }

    /** Shows [prepared], framed, playing its first clip if it has one. Takes over its textures. */
    fun setModel(prepared: PreparedModel) = update {
        pendingModel?.discard()
        pendingModel = prepared
        bounds = prepared.bounds
        animationIndex = prepared.defaultAnimation
        duration = prepared.model.animations.getOrNull(animationIndex)?.duration ?: 0f
        time = 0f
        playing = animationIndex >= 0
        camera.reset()
        fitCamera()
    }

    fun resize(w: Int, h: Int) = update {
        val first = width == 0 || height == 0
        width = w.coerceIn(0, MAX_EDGE)
        height = h.coerceIn(0, MAX_EDGE)
        if (first) fitCamera()
    }

    fun orbit(dx: Float, dy: Float) = update { camera.rotate(dx, dy) }

    fun pan(dx: Float, dy: Float) = update { camera.pan(dx, dy, height.toFloat()) }

    fun zoom(notches: Float) = update { camera.zoom(notches) }

    fun resetView() = update {
        camera.reset()
        fitCamera()
    }

    fun setOptions(newOptions: RenderOptions) = update { options = newOptions }

    /** Switches to clip [index] (-1 for the rest pose) from its start. [clipDuration] is that clip's length. */
    fun selectAnimation(index: Int, clipDuration: Float) = update {
        animationIndex = index
        duration = clipDuration
        time = 0f
        if (index < 0) playing = false
    }

    fun setPlaying(play: Boolean) = update { playing = play && animationIndex >= 0 }

    fun seek(seconds: Float) = update { time = seconds.coerceIn(0f, duration) }

    fun setSpeed(multiplier: Float) = update { speed = multiplier }

    override fun close() {
        synchronized(lock) {
            closed = true
            pendingModel?.discard()
            pendingModel = null
            lock.notifyAll()
        }
    }

    private inline fun update(change: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            change()
            dirty = true
            lock.notifyAll()
        }
    }

    private fun fitCamera() {
        val b = bounds ?: return
        val aspect = if (width > 0 && height > 0) width.toFloat() / height else 1f
        camera.fitToBounds(b[0], b[1], b[2], b[3], b[4], b[5], aspect)
    }

    private fun run() {
        val context = try {
            GlContext.create()
        } catch (e: Throwable) {
            logger.warn("3D view unavailable: {}", e.message)
            onUnavailable(e.message ?: "OpenGL is not available")
            return
        }
        val renderer = ModelRenderer()
        val target = RenderTarget()
        var pixels: ByteBuffer? = null
        var rows = ByteArray(0)
        var model: SceneModel? = null
        var pose: ScenePose? = null
        val frameCamera = OrbitCamera()
        var lastFrame = 0L
        var lastTick = System.nanoTime()

        try {
            renderer.init()
            while (true) {
                var newModel: PreparedModel? = null
                var w: Int
                var h: Int
                var frameOptions: RenderOptions
                var frameAnimation: Int
                var frameTime: Float

                synchronized(lock) {
                    while (!closed && !dirty && !(playing && duration > 0f)) lock.wait()
                    // never more than one frame per interval, however fast the pointer moves
                    while (!closed) {
                        val remaining = FRAME_NANOS - (System.nanoTime() - lastFrame)
                        if (remaining <= 0) break
                        lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    }
                    if (closed) return

                    val now = System.nanoTime()
                    // paced from the start of a frame, so drawing time comes out of the interval, not on top of it
                    lastFrame = now
                    if (playing && duration > 0f) {
                        // a stall (the window was hidden, the machine slept) must not fling the clip forward
                        val dt = ((now - lastTick) / 1e9f).coerceAtMost(0.1f)
                        time = (time + dt * speed) % duration
                        if (time < 0f) time += duration
                    }
                    lastTick = now

                    newModel = pendingModel
                    pendingModel = null
                    w = width
                    h = height
                    frameOptions = options
                    frameAnimation = animationIndex
                    frameTime = time
                    frameCamera.copyFrom(camera)
                    dirty = false
                }

                newModel?.let { prepared ->
                    renderer.loadModel(prepared.model, prepared.textures)
                    renderer.setFloor(prepared.bounds[1], frameCamera.sceneRadius)
                    model = prepared.model
                    pose = ScenePose(prepared.model)
                }
                val currentModel = model
                val currentPose = pose
                if (currentModel == null || currentPose == null || w <= 0 || h <= 0) continue

                currentPose.apply(currentModel.animations.getOrNull(frameAnimation), frameTime)
                target.ensure(w, h)
                target.bind()
                renderer.render(frameCamera, w.toFloat() / h, currentPose, frameOptions)

                val size = w * h * 4
                var buffer = pixels
                if (buffer == null || buffer.capacity() < size) {
                    buffer?.let { MemoryUtil.memFree(it) }
                    buffer = MemoryUtil.memAlloc(size)
                    pixels = buffer
                }
                target.read(buffer)
                if (rows.size != size) rows = ByteArray(size)
                // OpenGL's first row is the bottom one; an image's is the top
                val rowBytes = w * 4
                for (y in 0 until h) {
                    buffer.position((h - 1 - y) * rowBytes)
                    buffer.get(rows, y * rowBytes, rowBytes)
                }
                val image = Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), rows, rowBytes)
                onFrame(ViewerFrame(image, frameTime))
            }
        } catch (e: InterruptedException) {
            // closing
        } catch (e: Throwable) {
            logger.error("Model viewer stopped", e)
            onUnavailable(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { renderer.dispose() }
            runCatching { target.dispose() }
            pixels?.let { MemoryUtil.memFree(it) }
            context.close()
        }
    }

    private companion object {
        const val FRAME_NANOS = 1_000_000_000L / 60
        const val MAX_EDGE = 4096
    }
}
