package dev.gameharness.gui.viewer3d

import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Path

/**
 * Renders a model file to a PNG with no window on screen.
 *
 * WHY THIS EXISTS: the img2threejs sculpt pipeline gates every pass on a
 * reference-vs-render comparison sheet, and its Python side deliberately ships
 * no renderer. Rather than bolting a headless browser onto the project, this
 * reuses the viewer's own [ModelRenderer] / [SceneModel] / [OrbitCamera] — the
 * screenshots therefore match exactly what the asset window shows.
 *
 * The context comes from a hidden GLFW window ([GlContext]). Note this still
 * requires a display server (it is a desktop tool); a truly headless CI box
 * would need EGL/OSMesa.
 */
object OffscreenRenderer {

    class RenderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    data class Result(val outputPath: Path, val width: Int, val height: Int, val triangles: Int)

    /**
     * @param azimuth/[elevation] orbit angles in degrees; the default
     *   three-quarter view matches how the reference images are shot.
     */
    fun render(
        glb: Path,
        out: Path,
        width: Int = 720,
        height: Int = 720,
        azimuth: Float = 35f,
        elevation: Float = 20f,
        background: Triple<Float, Float, Float> = Triple(0.10f, 0.11f, 0.13f),
    ): Result {
        val prepared = try {
            PreparedModel.load(glb)
        } catch (e: Exception) {
            throw RenderException("could not load ${glb.fileName}: ${e.message}", e)
        }

        val snapshotter = try {
            ModelSnapshotter()
        } catch (e: Throwable) {
            prepared.discard()
            throw RenderException(e.message ?: "could not create an OpenGL 3.3 context", e)
        }
        try {
            val options = RenderOptions(
                background = floatArrayOf(background.first, background.second, background.third, 1f),
            )
            val image = snapshotter.snapshot(prepared, width, height, azimuth, elevation, options = options)
            val png = image.use { it.encodeToData(EncodedImageFormat.PNG) }
                ?: throw RenderException("no PNG writer available")
            out.toFile().parentFile?.mkdirs()
            out.toFile().writeBytes(png.bytes)
            return Result(out, width, height, prepared.model.totalTriangles)
        } finally {
            snapshotter.close()
        }
    }
}

/**
 * Process entry point so the CLI can render without the GUI module having to be
 * on its classpath (GL wants to own its own process anyway).
 *
 *   OffscreenRenderMain <model.glb> <out.png> [width] [height] [azimuth] [elevation]
 */
object OffscreenRenderMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("""{"ok":false,"error":"usage: <model.glb> <out.png> [w] [h] [azimuth] [elevation]"}""")
            kotlin.system.exitProcess(2)
        }
        try {
            val r = OffscreenRenderer.render(
                glb = File(args[0]).toPath(),
                out = File(args[1]).toPath(),
                width = args.getOrNull(2)?.toIntOrNull() ?: 720,
                height = args.getOrNull(3)?.toIntOrNull() ?: 720,
                azimuth = args.getOrNull(4)?.toFloatOrNull() ?: 35f,
                elevation = args.getOrNull(5)?.toFloatOrNull() ?: 20f,
            )
            println("""{"ok":true,"out":"${r.outputPath.toString().replace("\\", "\\\\")}","width":${r.width},"height":${r.height},"triangles":${r.triangles}}""")
            // GLFW keeps a helper window alive; do not wait on it
            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            System.err.println("""{"ok":false,"error":"${(e.message ?: e.toString()).replace("\"", "'")}"}""")
            kotlin.system.exitProcess(1)
        }
    }
}
