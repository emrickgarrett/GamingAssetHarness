package dev.gameharness.gui.viewer3d

import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL33.*
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

private val logger = LoggerFactory.getLogger("ModelRenderer")

/** What to draw besides the model itself, and how. */
class RenderOptions(
    val wireframe: Boolean = false,
    /** Off draws every surface in plain grey, which shows the shape without the paint. */
    val textures: Boolean = true,
    val grid: Boolean = true,
    /** Draws the skeleton over the model. */
    val bones: Boolean = false,
    /** Material tinted to stand out, or -1. */
    val highlightMaterial: Int = -1,
    /** Clear color as it should appear on screen (RGBA). An alpha of zero gives a picture with a transparent background. */
    val background: FloatArray = floatArrayOf(0.118f, 0.125f, 0.141f, 1f),
)

/**
 * OpenGL 3.3 renderer for [SceneModel]s.
 *
 * Draws textured, lit surfaces (base color, normal, metallic-roughness and
 * emissive maps), deforms skinned meshes on the GPU from the joint matrices of
 * a [ScenePose], and can add a floor grid and the skeleton. Lighting is two
 * lights that travel with the camera over a sky/ground ambient, so whichever
 * side is turned to the viewer is lit.
 *
 * Every method must be called on the thread whose OpenGL context is current.
 */
class ModelRenderer {

    private var program = 0
    private var initialized = false
    private val uniforms = HashMap<String, Int>()

    private var gridVao = 0
    private var gridVbo = 0
    private var gridVertexCount = 0
    private var gridAxisVertexCount = 0

    private var bonesVao = 0
    private var bonesVbo = 0
    private var bonesBuffer: FloatBuffer? = null

    private var model: SceneModel? = null
    private val meshGpuData = ArrayList<MeshGpuData>()
    private val textureIds = HashMap<Long, Int>()       // texture index and color space -> GL name
    private val jointTextures = ArrayList<Int>()        // one per skin
    private val jointBuffers = ArrayList<FloatBuffer>()
    private val jointScratch = ArrayList<FloatArray>()
    private val matrixScratch = FloatArray(16)

    private class MeshGpuData(
        val mesh: SceneMesh,
        val vao: Int,
        val buffers: IntArray,
        val indexCount: Int,
    )

    fun init() {
        glEnable(GL_DEPTH_TEST)
        glCullFace(GL_BACK)

        program = createShaderProgram()
        glUseProgram(program)
        // texture units are fixed for the life of the program
        glUniform1i(uniform("uBaseTex"), 0)
        glUniform1i(uniform("uNormalTex"), 1)
        glUniform1i(uniform("uMrTex"), 2)
        glUniform1i(uniform("uEmissiveTex"), 3)
        glUniform1i(uniform("uJointTex"), 4)

        gridVao = glGenVertexArrays()
        gridVbo = glGenBuffers()
        bonesVao = glGenVertexArrays()
        bonesVbo = glGenBuffers()
        buildGrid(0f, 1f)
        initialized = true
        logger.info("ModelRenderer initialized (OpenGL {})", glGetString(GL_VERSION))
    }

    private fun uniform(name: String): Int = uniforms.getOrPut(name) { glGetUniformLocation(program, name) }

    /**
     * Uploads [model]'s geometry and [textures] (one entry per
     * [SceneModel.textures], null where a texture could not be decoded),
     * replacing whatever was loaded before. The decoded textures are closed.
     */
    fun loadModel(model: SceneModel, textures: List<DecodedTexture?> = emptyList()) {
        disposeModelData()
        this.model = model

        for (mesh in model.meshes) {
            val vao = glGenVertexArrays()
            glBindVertexArray(vao)
            val buffers = ArrayList<Int>()

            fun floats(location: Int, size: Int, data: FloatArray) {
                val vbo = glGenBuffers()
                buffers.add(vbo)
                glBindBuffer(GL_ARRAY_BUFFER, vbo)
                val buf = allocFloatBuffer(data)
                glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW)
                MemoryUtil.memFree(buf)
                glVertexAttribPointer(location, size, GL_FLOAT, false, 0, 0)
                glEnableVertexAttribArray(location)
            }

            floats(0, 3, mesh.positions)
            floats(1, 3, mesh.normals ?: generateSmoothNormals(mesh.positions, mesh.indices))
            mesh.texCoords?.let { floats(2, 2, it) }

            val joints = mesh.joints
            val weights = mesh.weights
            if (mesh.isSkinned && joints != null && weights != null) {
                val vbo = glGenBuffers()
                buffers.add(vbo)
                glBindBuffer(GL_ARRAY_BUFFER, vbo)
                val buf = allocIntBuffer(joints)
                glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW)
                MemoryUtil.memFree(buf)
                glVertexAttribIPointer(3, 4, GL_INT, 0, 0)
                glEnableVertexAttribArray(3)
                floats(4, 4, weights)
            }

            var indexCount = 0
            mesh.indices?.let { indices ->
                val ebo = glGenBuffers()
                buffers.add(ebo)
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)
                val buf = allocIntBuffer(indices)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, buf, GL_STATIC_DRAW)
                MemoryUtil.memFree(buf)
                indexCount = indices.size
            }

            glBindVertexArray(0)
            meshGpuData.add(MeshGpuData(mesh, vao, buffers.toIntArray(), indexCount))
        }

        uploadTextures(model, textures)

        // one float texture per skin: four texels (a matrix) per joint, rewritten every frame
        for (skin in model.skins) {
            val count = skin.joints.size.coerceAtLeast(1)
            val tex = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, tex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, count, 0, GL_RGBA, GL_FLOAT, null as FloatBuffer?)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            jointTextures.add(tex)
            jointBuffers.add(MemoryUtil.memAllocFloat(count * 16))
            jointScratch.add(FloatArray(count * 16))
        }
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    private fun uploadTextures(model: SceneModel, decoded: List<DecodedTexture?>) {
        // Color maps are stored sRGB and must be linearised when sampled; data
        // maps (normals, roughness) must not. One image can serve both ways.
        val wanted = LinkedHashSet<Long>()
        for (material in model.materials) {
            if (material.baseColorTexture >= 0) wanted.add(textureKey(material.baseColorTexture, true))
            if (material.emissiveTexture >= 0) wanted.add(textureKey(material.emissiveTexture, true))
            if (material.normalTexture >= 0) wanted.add(textureKey(material.normalTexture, false))
            if (material.metallicRoughnessTexture >= 0) wanted.add(textureKey(material.metallicRoughnessTexture, false))
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        for (key in wanted) {
            val index = (key shr 1).toInt()
            val srgb = (key and 1L) == 1L
            val pixels = decoded.getOrNull(index) ?: continue
            val address = pixels.address
            if (address == 0L) continue
            val source = model.textures[index]
            val tex = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, tex)
            GL11C.nglTexImage2D(
                GL_TEXTURE_2D, 0, if (srgb) GL_SRGB8_ALPHA8 else GL_RGBA8,
                pixels.width, pixels.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, address,
            )
            glGenerateMipmap(GL_TEXTURE_2D)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, if (source.repeatS) GL_REPEAT else GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, if (source.repeatT) GL_REPEAT else GL_CLAMP_TO_EDGE)
            textureIds[key] = tex
        }
        decoded.forEach { it?.close() }
    }

    private fun textureKey(index: Int, srgb: Boolean): Long = (index.toLong() shl 1) or (if (srgb) 1L else 0L)

    /**
     * Sizes the floor grid for a model standing on [floorY] whose bounding
     * sphere has radius [radius]: about eight squares across the model, the
     * square a round number of units.
     */
    fun setFloor(floorY: Float, radius: Float) {
        val target = (radius / 4f).coerceAtLeast(1e-6f)
        val magnitude = 10.0.pow(floor(log10(target.toDouble()))).toFloat()
        val step = when {
            target / magnitude >= 5f -> 5f * magnitude
            target / magnitude >= 2f -> 2f * magnitude
            else -> magnitude
        }
        buildGrid(floorY, step)
    }

    /**
     * Draws one frame into the bound framebuffer. [pose] places the nodes and
     * joints; null draws the model at rest.
     */
    fun render(camera: OrbitCamera, aspect: Float, pose: ScenePose?, options: RenderOptions = RenderOptions()) {
        if (!initialized) return

        val bg = options.background
        glClearColor(bg[0], bg[1], bg[2], bg[3])
        glDepthMask(true)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glUseProgram(program)

        val view = camera.viewMatrix()
        val eye = camera.eyePosition()
        glUniformMatrix4fv(uniform("uView"), false, view)
        glUniformMatrix4fv(uniform("uProjection"), false, camera.projectionMatrix(aspect))
        glUniform3f(uniform("uViewPos"), eye[0], eye[1], eye[2])

        // Lights ride with the camera. Rows of the view matrix are its right, up and back axes.
        val rx = view[0]; val ry = view[4]; val rz = view[8]
        val ux = view[1]; val uy = view[5]; val uz = view[9]
        val bx = view[2]; val by = view[6]; val bz = view[10]
        setDirection("uKeyDir", rx * 0.45f + ux * 0.70f + bx * 0.55f, ry * 0.45f + uy * 0.70f + by * 0.55f, rz * 0.45f + uz * 0.70f + bz * 0.55f)
        setDirection("uFillDir", -rx * 0.75f + ux * 0.15f + bx * 0.5f, -ry * 0.75f + uy * 0.15f + by * 0.5f, -rz * 0.75f + uz * 0.15f + bz * 0.5f)

        glUniform1i(uniform("uSkinned"), 0)
        glUniform1f(uniform("uHighlight"), 0f)

        if (options.grid) drawGrid()

        val model = this.model
        if (model != null && meshGpuData.isNotEmpty()) {
            val activePose = pose?.takeIf { it.model === model }
            if (activePose != null) uploadJoints(activePose)

            if (options.wireframe) glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
            glUniform1i(uniform("uUnlit"), 0)

            // opaque and cut-out surfaces first, then the see-through ones over them
            for (blended in booleanArrayOf(false, true)) {
                if (blended) {
                    glEnable(GL_BLEND)
                    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
                    glDepthMask(false)
                }
                for (gpu in meshGpuData) {
                    val material = model.materials.getOrElse(gpu.mesh.materialIndex) { SceneMaterial.DEFAULT }
                    if ((material.alphaMode == AlphaMode.BLEND) != blended) continue
                    drawMesh(gpu, material, activePose, options)
                }
            }
            glDepthMask(true)
            glDisable(GL_BLEND)
            glEnable(GL_CULL_FACE)
            if (options.wireframe) glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)

            if (options.bones && activePose != null && model.skins.isNotEmpty()) drawBones(model, activePose)
        }

        glBindVertexArray(0)
    }

    private fun setDirection(name: String, x: Float, y: Float, z: Float) {
        val len = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
        glUniform3f(uniform(name), x / len, y / len, z / len)
    }

    private fun uploadJoints(pose: ScenePose) {
        for (s in jointTextures.indices) {
            val matrices = pose.jointMatrices(s, jointScratch[s])
            val buffer = jointBuffers[s]
            buffer.clear()
            buffer.put(matrices).flip()
            glBindTexture(GL_TEXTURE_2D, jointTextures[s])
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, matrices.size / 16, GL_RGBA, GL_FLOAT, buffer)
        }
    }

    private fun drawMesh(gpu: MeshGpuData, material: SceneMaterial, pose: ScenePose?, options: RenderOptions) {
        val mesh = gpu.mesh
        val skinned = mesh.isSkinned && pose != null && mesh.skinIndex < jointTextures.size
        if (skinned) {
            glActiveTexture(GL_TEXTURE4)
            glBindTexture(GL_TEXTURE_2D, jointTextures[mesh.skinIndex])
            glUniform1i(uniform("uSkinned"), 1)
        } else {
            glUniform1i(uniform("uSkinned"), 0)
            // each primitive lives in its own node's space
            val matrix = if (pose != null) pose.meshMatrix(mesh, matrixScratch) else mesh.transform
            glUniformMatrix4fv(uniform("uModel"), false, matrix)
        }

        val textured = options.textures && mesh.texCoords != null
        if (options.textures) {
            val c = material.baseColor
            glUniform4f(uniform("uBaseColor"), c[0], c[1], c[2], c[3])
            glUniform1f(uniform("uMetallic"), material.metallic)
            glUniform1f(uniform("uRoughness"), material.roughness)
            glUniform3f(uniform("uEmissive"), material.emissive[0], material.emissive[1], material.emissive[2])
        } else {
            glUniform4f(uniform("uBaseColor"), 0.62f, 0.62f, 0.62f, 1f)
            glUniform1f(uniform("uMetallic"), 0f)
            glUniform1f(uniform("uRoughness"), 0.7f)
            glUniform3f(uniform("uEmissive"), 0f, 0f, 0f)
        }
        bindTexture(0, "uHasBaseTex", if (textured) textureIds[textureKey(material.baseColorTexture, true)] else null)
        bindTexture(1, "uHasNormalTex", if (textured) textureIds[textureKey(material.normalTexture, false)] else null)
        bindTexture(2, "uHasMrTex", if (textured) textureIds[textureKey(material.metallicRoughnessTexture, false)] else null)
        bindTexture(3, "uHasEmissiveTex", if (textured) textureIds[textureKey(material.emissiveTexture, true)] else null)

        glUniform1i(uniform("uAlphaMode"), if (options.textures) material.alphaMode.ordinal else 0)
        glUniform1f(uniform("uAlphaCutoff"), material.alphaCutoff)
        glUniform1f(uniform("uHighlight"), if (mesh.materialIndex == options.highlightMaterial) 1f else 0f)

        if (material.doubleSided || options.wireframe) glDisable(GL_CULL_FACE) else glEnable(GL_CULL_FACE)

        glBindVertexArray(gpu.vao)
        if (gpu.indexCount > 0) {
            glDrawElements(GL_TRIANGLES, gpu.indexCount, GL_UNSIGNED_INT, 0)
        } else {
            glDrawArrays(GL_TRIANGLES, 0, mesh.vertexCount)
        }
    }

    private fun bindTexture(unit: Int, flag: String, texture: Int?) {
        glUniform1i(uniform(flag), if (texture != null) 1 else 0)
        if (texture != null) {
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, texture)
        }
    }

    private fun drawGrid() {
        if (gridVertexCount == 0) return
        glUniformMatrix4fv(uniform("uModel"), false, IDENTITY) // the grid is world-space
        glUniform1i(uniform("uUnlit"), 1)
        glEnable(GL_BLEND)
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glBindVertexArray(gridVao)
        glUniform4f(uniform("uBaseColor"), 0.30f, 0.31f, 0.34f, 0.55f)
        glDrawArrays(GL_LINES, gridAxisVertexCount, gridVertexCount - gridAxisVertexCount)
        glUniform4f(uniform("uBaseColor"), 0.50f, 0.52f, 0.58f, 0.8f)
        glDrawArrays(GL_LINES, 0, gridAxisVertexCount)
        glDisable(GL_BLEND)
        glUniform1i(uniform("uUnlit"), 0)
    }

    /** A line from every joint to its parent joint, and a dot on each, drawn over the model. */
    private fun drawBones(model: SceneModel, pose: ScenePose) {
        val g = pose.globals
        var lineVertices = 0
        var jointTotal = 0
        for (skin in model.skins) jointTotal += skin.joints.size
        val needed = jointTotal * 9
        var buffer = bonesBuffer
        if (buffer == null || buffer.capacity() < needed) {
            buffer?.let { MemoryUtil.memFree(it) }
            buffer = MemoryUtil.memAllocFloat(needed)
            bonesBuffer = buffer
        }
        buffer.clear()
        for (skin in model.skins) {
            val isJoint = HashSet<Int>(skin.joints.size * 2)
            skin.joints.forEach { isJoint.add(it) }
            for (node in skin.joints) {
                if (node !in model.nodes.indices) continue
                val parent = model.nodes[node].parent
                if (parent < 0 || parent !in isJoint) continue
                buffer.put(g[node * 16 + 12]).put(g[node * 16 + 13]).put(g[node * 16 + 14])
                buffer.put(g[parent * 16 + 12]).put(g[parent * 16 + 13]).put(g[parent * 16 + 14])
                lineVertices += 2
            }
        }
        var pointVertices = 0
        for (skin in model.skins) {
            for (node in skin.joints) {
                if (node !in model.nodes.indices) continue
                buffer.put(g[node * 16 + 12]).put(g[node * 16 + 13]).put(g[node * 16 + 14])
                pointVertices++
            }
        }
        buffer.flip()

        glBindVertexArray(bonesVao)
        glBindBuffer(GL_ARRAY_BUFFER, bonesVbo)
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STREAM_DRAW)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
        glEnableVertexAttribArray(0)

        glUniform1i(uniform("uSkinned"), 0)
        glUniform1i(uniform("uUnlit"), 1)
        glUniformMatrix4fv(uniform("uModel"), false, IDENTITY)
        glDisable(GL_DEPTH_TEST)
        glUniform4f(uniform("uBaseColor"), 1.0f, 0.62f, 0.05f, 1f)
        glDrawArrays(GL_LINES, 0, lineVertices)
        glPointSize(5f)
        glUniform4f(uniform("uBaseColor"), 1.0f, 0.92f, 0.55f, 1f)
        glDrawArrays(GL_POINTS, lineVertices, pointVertices)
        glEnable(GL_DEPTH_TEST)
        glUniform1i(uniform("uUnlit"), 0)
    }

    fun dispose() {
        if (!initialized) return
        disposeModelData()
        if (gridVao != 0) { glDeleteVertexArrays(gridVao); gridVao = 0 }
        if (gridVbo != 0) { glDeleteBuffers(gridVbo); gridVbo = 0 }
        if (bonesVao != 0) { glDeleteVertexArrays(bonesVao); bonesVao = 0 }
        if (bonesVbo != 0) { glDeleteBuffers(bonesVbo); bonesVbo = 0 }
        bonesBuffer?.let { MemoryUtil.memFree(it) }
        bonesBuffer = null
        if (program != 0) { glDeleteProgram(program); program = 0 }
        uniforms.clear()
        initialized = false
    }

    private fun disposeModelData() {
        for (gpu in meshGpuData) {
            glDeleteVertexArrays(gpu.vao)
            gpu.buffers.forEach { glDeleteBuffers(it) }
        }
        meshGpuData.clear()
        textureIds.values.forEach { glDeleteTextures(it) }
        textureIds.clear()
        jointTextures.forEach { glDeleteTextures(it) }
        jointTextures.clear()
        jointBuffers.forEach { MemoryUtil.memFree(it) }
        jointBuffers.clear()
        jointScratch.clear()
        model = null
    }

    /** Lines of a square grid on the plane y = [y], [step] apart; the two through the origin come first. */
    private fun buildGrid(y: Float, step: Float) {
        val cells = 12
        val extent = cells * step
        val lines = ArrayList<Float>()
        fun line(x0: Float, z0: Float, x1: Float, z1: Float) {
            lines.add(x0); lines.add(y); lines.add(z0)
            lines.add(x1); lines.add(y); lines.add(z1)
        }
        line(0f, -extent, 0f, extent)
        line(-extent, 0f, extent, 0f)
        gridAxisVertexCount = lines.size / 3
        for (i in -cells..cells) {
            if (i == 0) continue
            line(i * step, -extent, i * step, extent)
            line(-extent, i * step, extent, i * step)
        }
        gridVertexCount = lines.size / 3

        glBindVertexArray(gridVao)
        glBindBuffer(GL_ARRAY_BUFFER, gridVbo)
        val buf = allocFloatBuffer(lines.toFloatArray())
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW)
        MemoryUtil.memFree(buf)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
        glEnableVertexAttribArray(0)
        glBindVertexArray(0)
    }

    /** Per-vertex normals averaged from the faces around each vertex, for meshes that carry none. */
    private fun generateSmoothNormals(positions: FloatArray, indices: IntArray?): FloatArray {
        val normals = FloatArray(positions.size)
        val vertexCount = positions.size / 3
        val triCount = if (indices != null) indices.size / 3 else positions.size / 9

        for (t in 0 until triCount) {
            val i0 = if (indices != null) indices[t * 3] else t * 3
            val i1 = if (indices != null) indices[t * 3 + 1] else t * 3 + 1
            val i2 = if (indices != null) indices[t * 3 + 2] else t * 3 + 2
            if (i0 !in 0 until vertexCount || i1 !in 0 until vertexCount || i2 !in 0 until vertexCount) continue

            val ax = positions[i1 * 3] - positions[i0 * 3]
            val ay = positions[i1 * 3 + 1] - positions[i0 * 3 + 1]
            val az = positions[i1 * 3 + 2] - positions[i0 * 3 + 2]
            val bx = positions[i2 * 3] - positions[i0 * 3]
            val by = positions[i2 * 3 + 1] - positions[i0 * 3 + 1]
            val bz = positions[i2 * 3 + 2] - positions[i0 * 3 + 2]

            // not normalised: a bigger face pulls the vertex normal harder
            val nx = ay * bz - az * by
            val ny = az * bx - ax * bz
            val nz = ax * by - ay * bx

            normals[i0 * 3] += nx; normals[i0 * 3 + 1] += ny; normals[i0 * 3 + 2] += nz
            normals[i1 * 3] += nx; normals[i1 * 3 + 1] += ny; normals[i1 * 3 + 2] += nz
            normals[i2 * 3] += nx; normals[i2 * 3 + 1] += ny; normals[i2 * 3 + 2] += nz
        }

        for (i in 0 until vertexCount) {
            val x = normals[i * 3]; val y = normals[i * 3 + 1]; val z = normals[i * 3 + 2]
            val len = sqrt(x * x + y * y + z * z)
            if (len > 0f) {
                normals[i * 3] /= len; normals[i * 3 + 1] /= len; normals[i * 3 + 2] /= len
            } else {
                normals[i * 3 + 1] = 1f
            }
        }
        return normals
    }

    companion object {
        private val IDENTITY = Mat4.identity()

        private fun allocFloatBuffer(data: FloatArray): FloatBuffer {
            val buf = MemoryUtil.memAllocFloat(data.size)
            buf.put(data).flip()
            return buf
        }

        private fun allocIntBuffer(data: IntArray): IntBuffer {
            val buf = MemoryUtil.memAllocInt(data.size)
            buf.put(data).flip()
            return buf
        }

        private fun createShaderProgram(): Int {
            val vert = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER)
            val frag = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val program = glCreateProgram()
            glAttachShader(program, vert)
            glAttachShader(program, frag)
            glLinkProgram(program)
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                val log = glGetProgramInfoLog(program)
                glDeleteProgram(program)
                throw RuntimeException("Shader link failed: $log")
            }
            glDeleteShader(vert)
            glDeleteShader(frag)
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = glCreateShader(type)
            glShaderSource(shader, source)
            glCompileShader(shader)
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                val log = glGetShaderInfoLog(shader)
                glDeleteShader(shader)
                throw RuntimeException("Shader compile failed: $log")
            }
            return shader
        }

        private val VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;
            layout(location = 3) in ivec4 aJoints;
            layout(location = 4) in vec4 aWeights;

            uniform mat4 uModel;
            uniform mat4 uView;
            uniform mat4 uProjection;
            uniform int uSkinned;
            uniform sampler2D uJointTex;   // row j holds joint j's matrix, one column per texel

            out vec3 vWorldPos;
            out vec3 vNormal;
            out vec2 vUv;

            mat4 jointMatrix(int j) {
                return mat4(
                    texelFetch(uJointTex, ivec2(0, j), 0),
                    texelFetch(uJointTex, ivec2(1, j), 0),
                    texelFetch(uJointTex, ivec2(2, j), 0),
                    texelFetch(uJointTex, ivec2(3, j), 0));
            }

            void main() {
                mat4 m = uModel;
                if (uSkinned == 1) {
                    // a skinned vertex is placed by its joints alone; the mesh node's own transform does not apply
                    m = aWeights.x * jointMatrix(aJoints.x)
                      + aWeights.y * jointMatrix(aJoints.y)
                      + aWeights.z * jointMatrix(aJoints.z)
                      + aWeights.w * jointMatrix(aJoints.w);
                }
                vec4 worldPos = m * vec4(aPosition, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = mat3(m) * aNormal;
                vUv = aUv;
                gl_Position = uProjection * uView * worldPos;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 330 core
            in vec3 vWorldPos;
            in vec3 vNormal;
            in vec2 vUv;

            uniform vec4 uBaseColor;
            uniform float uMetallic;
            uniform float uRoughness;
            uniform vec3 uEmissive;
            uniform sampler2D uBaseTex;
            uniform sampler2D uNormalTex;
            uniform sampler2D uMrTex;
            uniform sampler2D uEmissiveTex;
            uniform int uHasBaseTex;
            uniform int uHasNormalTex;
            uniform int uHasMrTex;
            uniform int uHasEmissiveTex;
            uniform int uAlphaMode;      // 0 opaque, 1 cut-out, 2 blended
            uniform float uAlphaCutoff;
            uniform vec3 uKeyDir;
            uniform vec3 uFillDir;
            uniform vec3 uViewPos;
            uniform float uHighlight;
            uniform int uUnlit;

            out vec4 fragColor;

            // Tangent space from screen-space derivatives: no tangents needed in the mesh.
            vec3 applyNormalMap(vec3 N) {
                vec3 dp1 = dFdx(vWorldPos);
                vec3 dp2 = dFdy(vWorldPos);
                vec2 duv1 = dFdx(vUv);
                vec2 duv2 = dFdy(vUv);
                vec3 dp2perp = cross(dp2, N);
                vec3 dp1perp = cross(N, dp1);
                vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
                vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
                float longest = max(dot(T, T), dot(B, B));
                if (longest <= 0.0) return N;
                float scale = inversesqrt(longest);
                vec3 n = texture(uNormalTex, vUv).xyz * 2.0 - 1.0;
                return normalize(mat3(T * scale, B * scale, N) * n);
            }

            void main() {
                if (uUnlit == 1) {
                    fragColor = uBaseColor;
                    return;
                }

                vec4 base = uBaseColor;
                if (uHasBaseTex == 1) base *= texture(uBaseTex, vUv);
                if (uAlphaMode == 1 && base.a < uAlphaCutoff) discard;
                float alpha = uAlphaMode == 2 ? base.a : 1.0;

                vec3 N = normalize(vNormal);
                if (!gl_FrontFacing) N = -N;
                if (uHasNormalTex == 1) N = applyNormalMap(N);

                float metallic = uMetallic;
                float roughness = uRoughness;
                if (uHasMrTex == 1) {
                    vec4 mr = texture(uMrTex, vUv);
                    roughness *= mr.g;
                    metallic *= mr.b;
                }
                roughness = clamp(roughness, 0.05, 1.0);

                vec3 V = normalize(uViewPos - vWorldPos);
                vec3 diffuseColor = base.rgb * (1.0 - metallic);
                vec3 specColor = mix(vec3(0.04), base.rgb, metallic);

                // sky above, ground below, a key light over the viewer's shoulder, a weak fill from the other side
                vec3 ambient = mix(vec3(0.20, 0.19, 0.18), vec3(0.42, 0.44, 0.48), N.y * 0.5 + 0.5);
                float key = max(dot(N, uKeyDir), 0.0);
                float fill = max(dot(N, uFillDir), 0.0);
                vec3 light = ambient + vec3(0.78) * key + vec3(0.16) * fill;

                vec3 H = normalize(uKeyDir + V);
                float shininess = mix(160.0, 6.0, roughness);
                float spec = pow(max(dot(N, H), 0.0), shininess) * (shininess + 8.0) / 8.0 * key * mix(1.0, 0.3, roughness);

                vec3 emissive = uEmissive;
                if (uHasEmissiveTex == 1) emissive *= texture(uEmissiveTex, vUv).rgb;

                vec3 color = diffuseColor * light + specColor * (ambient * 0.7 + vec3(spec)) + emissive;

                // Highlight selected material
                if (uHighlight > 0.5) {
                    color = mix(color, vec3(1.0, 0.6, 0.05), 0.35);
                }

                // Linear -> sRGB. Material colors and sRGB textures are linear
                // here and the framebuffer is not sRGB, so writing linear
                // straight out renders everything far darker than authored: a
                // #a5652c oak is 0.25 linear and lands near black on screen.
                color = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));

                fragColor = vec4(color, alpha);
            }
        """.trimIndent()
    }
}
