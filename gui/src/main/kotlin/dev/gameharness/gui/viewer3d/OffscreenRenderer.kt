package dev.gameharness.gui.viewer3d

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33.*
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Renders a GLB to a PNG with no window on screen.
 *
 * WHY THIS EXISTS: the img2threejs sculpt pipeline gates every pass on a
 * reference-vs-render comparison sheet, and its Python side deliberately ships
 * no renderer. Rather than bolting a headless browser onto the project, this
 * reuses the viewer's own [ModelRenderer] / [GlbModel] / [OrbitCamera] — the
 * screenshots therefore match exactly what the asset window shows.
 *
 * The interactive viewer gets its GL context from AWT, which needs a real
 * component. Here we make a HIDDEN GLFW window instead purely to own a context,
 * and draw into an off-screen framebuffer sized to the requested output. Note
 * this still requires a display server (it is a desktop tool); a truly headless
 * CI box would need EGL/OSMesa.
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
        val model = try {
            loadGlb(glb) // top-level in GlbModel.kt, not a companion member
        } catch (e: Exception) {
            throw RenderException("could not load ${glb.fileName}: ${e.message}", e)
        }

        glfwSetErrorCallback { _, description ->
            System.err.println("glfw: ${org.lwjgl.system.MemoryUtil.memUTF8(description)}")
        }
        if (!glfwInit()) throw RenderException("GLFW init failed (no display available?)")

        var window = NULL
        var fbo = 0
        var colorTex = 0
        var depthRbo = 0
        val renderer = ModelRenderer()
        try {
            // a context, not a UI: never shown, never swapped
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
            window = glfwCreateWindow(width, height, "sculpt-offscreen", NULL, NULL)
            if (window == NULL) throw RenderException("could not create an OpenGL 3.3 context")
            glfwMakeContextCurrent(window)
            GL.createCapabilities()

            // draw into our own framebuffer so the result is exactly width×height
            // regardless of what the window manager did with the hidden window
            fbo = glGenFramebuffers()
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            colorTex = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, colorTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0)
            depthRbo = glGenRenderbuffers()
            glBindRenderbuffer(GL_RENDERBUFFER, depthRbo)
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthRbo)
            val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
            if (status != GL_FRAMEBUFFER_COMPLETE) throw RenderException("framebuffer incomplete (0x${status.toString(16)})")

            renderer.init()
            renderer.loadModel(model)

            // frame the whole model so nothing is cropped, whatever its scale
            val camera = OrbitCamera().apply {
                val b = bounds(model)
                fitToBounds(b[0], b[1], b[2], b[3], b[4], b[5])
                this.azimuth = azimuth
                this.elevation = elevation
            }

            glViewport(0, 0, width, height)
            glClearColor(background.first, background.second, background.third, 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glEnable(GL_DEPTH_TEST)
            renderer.render(camera, wireframe = false, model = model)
            glFinish()

            val pixels = ByteBuffer.allocateDirect(width * height * 4)
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            writePng(pixels, width, height, out)
            return Result(out, width, height, model.totalTriangles)
        } finally {
            runCatching { renderer.dispose() }
            if (depthRbo != 0) glDeleteRenderbuffers(depthRbo)
            if (colorTex != 0) glDeleteTextures(colorTex)
            if (fbo != 0) glDeleteFramebuffers(fbo)
            if (window != NULL) glfwDestroyWindow(window)
            glfwTerminate()
        }
    }

    /**
     * [minX, minY, minZ, maxX, maxY, maxZ] over every vertex, in WORLD space —
     * node transforms included, or the framing is computed for a model that
     * isn't the one being drawn.
     */
    private fun bounds(model: GlbModel): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (mesh in model.meshes) {
            var i = 0
            while (i + 2 < mesh.positions.size) {
                val p = mesh.transformPoint(mesh.positions[i], mesh.positions[i + 1], mesh.positions[i + 2])
                val x = p[0]; val y = p[1]; val z = p[2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 3
            }
        }
        if (minX > maxX) return floatArrayOf(-1f, -1f, -1f, 1f, 1f, 1f) // empty model
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun writePng(pixels: ByteBuffer, width: Int, height: Int, out: Path) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            // GL origin is bottom-left, BufferedImage is top-left
            val row = height - 1 - y
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                val r = pixels.get(i).toInt() and 0xFF
                val g = pixels.get(i + 1).toInt() and 0xFF
                val b = pixels.get(i + 2).toInt() and 0xFF
                image.setRGB(x, row, (r shl 16) or (g shl 8) or b)
            }
        }
        out.toFile().parentFile?.mkdirs()
        if (!ImageIO.write(image, "png", out.toFile())) throw RenderException("no PNG writer available")
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
        } catch (e: Exception) {
            System.err.println("""{"ok":false,"error":"${(e.message ?: e.toString()).replace("\"", "'")}"}""")
            kotlin.system.exitProcess(1)
        }
    }
}
