package dev.gameharness.gui.thumbs

import dev.gameharness.gui.viewer3d.ModelSnapshotter
import dev.gameharness.gui.viewer3d.PreparedModel
import dev.gameharness.gui.viewer3d.RenderOptions
import dev.gameharness.gui.viewer3d.VIEWABLE_MODEL_FORMATS
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.Image
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors

private val logger = LoggerFactory.getLogger("ModelThumbnails")

/**
 * Draws the pictures of 3D models on the asset cards.
 *
 * A folder of models used to be a wall of identical brick icons under file
 * names cut off at the same place. Each card now shows the model itself, from
 * the front quarter, a rigged one partway through its first clip so that an
 * idle, an aim and a fall of the same character can be told apart.
 *
 * One low-priority thread owns an off-screen OpenGL context and draws the
 * models one at a time: a model file runs to tens of megabytes, so loading
 * several at once would only fight over memory. Results go through the
 * [ThumbnailStore] caches like any other thumbnail, so each file is drawn once.
 */
object ModelThumbnails {

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "model-thumbnails").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    // touched only on the thumbnail thread
    private var snapshotter: ModelSnapshotter? = null
    private var unavailable = false

    /** How far through its first clip a rigged model is posed. */
    private const val CLIP_FRACTION = 0.35f

    /** A model's texture is decoded no larger than this: a card is at most a few hundred pixels. */
    private const val TEXTURE_EDGE = 512

    fun install(store: ThumbnailStore) {
        store.register(ThumbnailKind.MODEL, dispatcher) { file, edge -> render(file, edge) }
    }

    private fun render(file: File, edge: Int): Image? {
        if (unavailable) return null
        if (file.extension.lowercase() !in VIEWABLE_MODEL_FORMATS) return null
        val renderer = snapshotter ?: try {
            ModelSnapshotter().also { snapshotter = it }
        } catch (e: Throwable) {
            // no OpenGL here: the cards keep their icons, and nothing retries
            unavailable = true
            logger.warn("Model thumbnails are off: {}", e.message)
            return null
        }
        val prepared = PreparedModel.load(file.toPath(), TEXTURE_EDGE)
        return renderer.snapshot(
            prepared, edge, edge,
            clipFraction = CLIP_FRACTION,
            options = RenderOptions(grid = false, background = floatArrayOf(0f, 0f, 0f, 0f)),
        )
    }
}
