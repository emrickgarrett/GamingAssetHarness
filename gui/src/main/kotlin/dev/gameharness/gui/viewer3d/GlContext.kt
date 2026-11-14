package dev.gameharness.gui.viewer3d

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33.*
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import kotlin.math.ceil

private val logger = LoggerFactory.getLogger("GlContext")

/**
 * An OpenGL 3.3 core context with nothing on screen, current on the thread
 * that created it.
 *
 * WHY NOT a canvas in the window: the first viewer embedded an AWT OpenGL
 * canvas in the Compose window. A heavyweight canvas always paints over
 * Compose content (dialogs and menus opened above it disappeared behind the
 * model), it rendered and waited for vsync on the UI thread, and it could not
 * be drawn without a visible window. Here the model is drawn into a
 * framebuffer on a thread of its own and the pixels are handed to Compose as an
 * ordinary image.
 *
 * The context belongs to a hidden GLFW window. GLFW asks to be driven from the
 * process's first thread, which matters on macOS and not on Windows or Linux;
 * on macOS [create] throws and the viewer says the 3D view is unavailable.
 */
class GlContext private constructor(private val window: Long) : AutoCloseable {

    override fun close() {
        runCatching { GL.setCapabilities(null) }
        runCatching { glfwMakeContextCurrent(NULL) }
        synchronized(lock) { runCatching { glfwDestroyWindow(window) } }
    }

    companion object {
        private val lock = Any()
        private var initialised = false
        private var errorCallback: GLFWErrorCallback? = null

        class Unavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

        /** Creates a context and makes it current on the calling thread, which must also close it. */
        fun create(): GlContext {
            val window: Long
            try {
                synchronized(lock) {
                    if (!initialised) {
                        errorCallback = GLFWErrorCallback.create { code, description ->
                            logger.debug("GLFW error {}: {}", code, MemoryUtil.memUTF8(description))
                        }.also { glfwSetErrorCallback(it) }
                        if (!glfwInit()) throw Unavailable("GLFW could not start (no display available?)")
                        initialised = true
                    }
                    glfwDefaultWindowHints()
                    // a context, not a UI: never shown, never swapped
                    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
                    glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE)
                    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
                    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
                    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
                    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
                    window = glfwCreateWindow(16, 16, "gameharness-offscreen", NULL, NULL)
                }
            } catch (e: Unavailable) {
                throw e
            } catch (e: Throwable) {
                // UnsatisfiedLinkError, or GLFW refusing a thread that is not the first
                throw Unavailable("OpenGL is not available here: ${e.message ?: e.javaClass.simpleName}", e)
            }
            if (window == NULL) throw Unavailable("Could not create an OpenGL 3.3 context")
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            return GlContext(window)
        }
    }
}

/**
 * A framebuffer the scene is drawn into and read back from: multisampled for
 * smooth edges, resolved into a plain one that [read] copies out.
 */
class RenderTarget(private val requestedSamples: Int = 4) {
    var width = 0
        private set
    var height = 0
        private set

    private var msaaFbo = 0
    private var msaaColor = 0
    private var msaaDepth = 0
    private var resolveFbo = 0
    private var resolveColor = 0

    /** Makes the target [w] x [h] pixels, reallocating only when the size changed. */
    fun ensure(w: Int, h: Int) {
        if (w == width && h == height && msaaFbo != 0) return
        dispose()
        width = w
        height = h
        val samples = requestedSamples.coerceAtMost(glGetInteger(GL_MAX_SAMPLES)).coerceAtLeast(1)

        msaaFbo = glGenFramebuffers()
        glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo)
        msaaColor = glGenRenderbuffers()
        glBindRenderbuffer(GL_RENDERBUFFER, msaaColor)
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, w, h)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msaaColor)
        msaaDepth = glGenRenderbuffers()
        glBindRenderbuffer(GL_RENDERBUFFER, msaaDepth)
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, w, h)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, msaaDepth)
        check("multisampled")

        resolveFbo = glGenFramebuffers()
        glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo)
        resolveColor = glGenRenderbuffers()
        glBindRenderbuffer(GL_RENDERBUFFER, resolveColor)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, resolveColor)
        check("resolve")
    }

    private fun check(what: String) {
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("$what framebuffer incomplete (0x${status.toString(16)})")
        }
    }

    /** Directs drawing at this target. */
    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo)
        glViewport(0, 0, width, height)
    }

    /**
     * Copies the finished picture into [into] as RGBA bytes, bottom row first
     * (OpenGL's order). [into] needs room for width x height x 4.
     */
    fun read(into: ByteBuffer) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, msaaFbo)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFbo)
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        into.clear()
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, into)
    }

    fun dispose() {
        if (msaaFbo != 0) glDeleteFramebuffers(msaaFbo)
        if (resolveFbo != 0) glDeleteFramebuffers(resolveFbo)
        if (msaaColor != 0) glDeleteRenderbuffers(msaaColor)
        if (msaaDepth != 0) glDeleteRenderbuffers(msaaDepth)
        if (resolveColor != 0) glDeleteRenderbuffers(resolveColor)
        msaaFbo = 0; resolveFbo = 0; msaaColor = 0; msaaDepth = 0; resolveColor = 0
        width = 0; height = 0
    }
}

/**
 * A texture's pixels, decoded and waiting to be uploaded: RGBA, 8 bits a
 * channel, alpha not premultiplied, top row first. Close it once uploaded.
 */
class DecodedTexture(private val bitmap: Bitmap) : AutoCloseable {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    /** Address of the first pixel, valid until [close]. Zero if the pixels are not reachable. */
    val address: Long get() = bitmap.peekPixels()?.addr ?: 0L

    override fun close() = bitmap.close()

    companion object {
        /**
         * Decodes an encoded image (PNG, JPEG, WebP). A JPEG whose longest side
         * exceeds [maxEdge] is decoded at the smallest half, quarter or eighth
         * that still reaches [maxEdge], which is what makes a thumbnail of a
         * model with a 4K texture cheap. Null if the bytes are not an image.
         */
        fun decode(bytes: ByteArray, maxEdge: Int): DecodedTexture? {
            return try {
                Data.makeFromBytes(bytes).use { data ->
                    Codec.makeFromData(data).use { codec ->
                        val srcW = codec.width
                        val srcH = codec.height
                        if (srcW <= 0 || srcH <= 0) return null
                        if (codec.encodedImageFormat == EncodedImageFormat.JPEG && maxOf(srcW, srcH) > maxEdge) {
                            for (eighths in intArrayOf(1, 2, 4)) {
                                val w = ceil(srcW * eighths / 8.0).toInt()
                                val h = ceil(srcH * eighths / 8.0).toInt()
                                if (maxOf(w, h) < maxEdge) continue
                                read(codec, w, h)?.let { return it }
                            }
                        }
                        read(codec, srcW, srcH)
                    }
                }
            } catch (e: Exception) {
                logger.debug("Texture could not be decoded: {}", e.toString())
                null
            }
        }

        private fun read(codec: Codec, w: Int, h: Int): DecodedTexture? {
            val bitmap = Bitmap()
            return try {
                bitmap.allocPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
                codec.readPixels(bitmap, 0)
                DecodedTexture(bitmap)
            } catch (e: Exception) {
                bitmap.close()
                null
            }
        }
    }
}
