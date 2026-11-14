package dev.gameharness.gui.viewer3d

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * Renders still pictures of models with no window: the thumbnails on the asset
 * cards, and the screenshots `harness sculpt render` writes. It draws with the
 * viewer's own [ModelRenderer], so a still matches what the viewer shows.
 *
 * Owns an OpenGL context bound to the thread that constructs it; every call,
 * [close] included, must come from that thread.
 */
class ModelSnapshotter : AutoCloseable {

    private val context = GlContext.create()
    private val renderer = ModelRenderer()
    private val target = RenderTarget()
    private var pixels: ByteBuffer? = null

    init {
        try {
            renderer.init()
        } catch (e: Throwable) {
            context.close()
            throw e
        }
    }

    /**
     * Draws [prepared] framed to fill a [width] x [height] picture and returns
     * it, top row first. Uploads (and so closes) the prepared textures.
     *
     * @param clipFraction how far through the model's first animation to pose it
     *   (0 to 1), ignored for a model without one
     * @param options a background alpha of zero gives a transparent picture
     */
    fun snapshot(
        prepared: PreparedModel,
        width: Int,
        height: Int,
        azimuth: Float = OrbitCamera.DEFAULT_AZIMUTH,
        elevation: Float = OrbitCamera.DEFAULT_ELEVATION,
        clipFraction: Float = 0f,
        options: RenderOptions = RenderOptions(),
    ): Image {
        val model = prepared.model
        renderer.loadModel(model, prepared.textures)

        val pose = ScenePose(model)
        val animation = model.animations.getOrNull(prepared.defaultAnimation)
        // framed on the pose being drawn, not on the whole clip: a still should fill its picture
        var box = prepared.bounds
        if (animation != null) {
            pose.apply(animation, animation.duration * clipFraction.coerceIn(0f, 1f))
            pose.bounds(maxOf(1, model.totalVertices / 20_000))?.let { box = it }
        }

        val camera = OrbitCamera().apply {
            fitToBounds(box[0], box[1], box[2], box[3], box[4], box[5], width.toFloat() / height)
            this.azimuth = azimuth
            this.elevation = elevation
        }
        renderer.setFloor(box[1], camera.sceneRadius)

        target.ensure(width, height)
        target.bind()
        renderer.render(camera, width.toFloat() / height, pose, options)

        val size = width * height * 4
        var buffer = pixels
        if (buffer == null || buffer.capacity() < size) {
            buffer?.let { MemoryUtil.memFree(it) }
            buffer = MemoryUtil.memAlloc(size)
            pixels = buffer
        }
        target.read(buffer)
        val rows = ByteArray(size)
        val rowBytes = width * 4
        for (y in 0 until height) {
            buffer.position((height - 1 - y) * rowBytes)
            buffer.get(rows, y * rowBytes, rowBytes)
        }
        // Edges blended against a clear background of zero come out premultiplied.
        val alphaType = if (options.background[3] < 1f) ColorAlphaType.PREMUL else ColorAlphaType.OPAQUE
        return Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, alphaType), rows, rowBytes)
    }

    override fun close() {
        runCatching { renderer.dispose() }
        runCatching { target.dispose() }
        pixels?.let { MemoryUtil.memFree(it) }
        pixels = null
        context.close()
    }
}
