package dev.gameharness.gui.viewer3d

import org.lwjgl.opengl.GL33.*
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.FloatBuffer
import java.nio.IntBuffer

private val logger = LoggerFactory.getLogger("ModelRenderer")

/**
 * OpenGL 3.3 renderer for GLB models.
 *
 * Uses Blinn-Phong shading with PBR material base color and metallic/roughness approximation.
 * Must be called from the AWT/GL thread where the context is current.
 */
class ModelRenderer {

    private var shaderProgram = 0
    private var gridVao = 0
    private var gridVbo = 0
    private var gridVertexCount = 0
    private var initialized = false

    // Uniform locations
    private var uModel = -1
    private var uView = -1
    private var uProjection = -1
    private var uBaseColor = -1
    private var uMetallic = -1
    private var uRoughness = -1
    private var uEmissive = -1
    private var uLightDir = -1
    private var uViewPos = -1
    private var uHighlight = -1

    // Per-mesh GPU resources
    private val meshGpuData = mutableListOf<MeshGpuData>()

    private data class MeshGpuData(
        val vao: Int,
        val vboPositions: Int,
        val vboNormals: Int,
        val ebo: Int,
        val indexCount: Int,
        val vertexCount: Int,
        val hasIndices: Boolean,
        val materialIndex: Int,
        /** The owning node's global transform; see [GlbMesh.transform]. */
        val transform: FloatArray
    )

    fun init() {
        // GL.createCapabilities() is called by AWTGLCanvas.initGL()
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glClearColor(0.18f, 0.18f, 0.20f, 1.0f)

        shaderProgram = createShaderProgram()
        glUseProgram(shaderProgram)
        uModel = glGetUniformLocation(shaderProgram, "uModel")
        uView = glGetUniformLocation(shaderProgram, "uView")
        uProjection = glGetUniformLocation(shaderProgram, "uProjection")
        uBaseColor = glGetUniformLocation(shaderProgram, "uBaseColor")
        uMetallic = glGetUniformLocation(shaderProgram, "uMetallic")
        uRoughness = glGetUniformLocation(shaderProgram, "uRoughness")
        uEmissive = glGetUniformLocation(shaderProgram, "uEmissive")
        uLightDir = glGetUniformLocation(shaderProgram, "uLightDir")
        uViewPos = glGetUniformLocation(shaderProgram, "uViewPos")
        uHighlight = glGetUniformLocation(shaderProgram, "uHighlight")

        buildGrid()
        initialized = true
        logger.info("ModelRenderer initialized (OpenGL ${glGetString(GL_VERSION)})")
    }

    fun loadModel(model: GlbModel) {
        disposeModelData()

        for (mesh in model.meshes) {
            val vao = glGenVertexArrays()
            glBindVertexArray(vao)

            // Positions (location 0)
            val vboPos = glGenBuffers()
            glBindBuffer(GL_ARRAY_BUFFER, vboPos)
            val posBuf = allocFloatBuffer(mesh.positions)
            glBufferData(GL_ARRAY_BUFFER, posBuf, GL_STATIC_DRAW)
            MemoryUtil.memFree(posBuf)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
            glEnableVertexAttribArray(0)

            // Normals (location 1)
            val vboNorm = glGenBuffers()
            val normals = mesh.normals ?: generateFlatNormals(mesh.positions, mesh.indices)
            glBindBuffer(GL_ARRAY_BUFFER, vboNorm)
            val normBuf = allocFloatBuffer(normals)
            glBufferData(GL_ARRAY_BUFFER, normBuf, GL_STATIC_DRAW)
            MemoryUtil.memFree(normBuf)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)
            glEnableVertexAttribArray(1)

            // Index buffer
            val ebo = glGenBuffers()
            val meshIndices = mesh.indices
            val hasIndices = meshIndices != null
            val indexCount = meshIndices?.size ?: 0
            if (meshIndices != null) {
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)
                val idxBuf = allocIntBuffer(meshIndices)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, idxBuf, GL_STATIC_DRAW)
                MemoryUtil.memFree(idxBuf)
            }

            glBindVertexArray(0)

            meshGpuData.add(
                MeshGpuData(
                    vao = vao, vboPositions = vboPos, vboNormals = vboNorm,
                    ebo = ebo, indexCount = indexCount, vertexCount = mesh.vertexCount,
                    hasIndices = hasIndices, materialIndex = mesh.materialIndex,
                    transform = mesh.transform
                )
            )
        }
    }

    fun render(camera: OrbitCamera, wireframe: Boolean, model: GlbModel?, highlightMaterial: Int = -1) {
        if (!initialized) return

        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glUseProgram(shaderProgram)

        val aspect = getViewportAspect()
        val view = camera.viewMatrix()
        val proj = camera.projectionMatrix(aspect)
        val eye = camera.eyePosition()

        glUniformMatrix4fv(uView, false, view)
        glUniformMatrix4fv(uProjection, false, proj)
        glUniformMatrix4fv(uModel, false, Mat4.identity()) // the grid is world-space
        glUniform3f(uLightDir, 0.5f, 0.8f, 0.3f)
        glUniform3f(uViewPos, eye[0], eye[1], eye[2])

        // Draw grid
        glUniform4f(uBaseColor, 0.35f, 0.35f, 0.35f, 1.0f)
        glUniform1f(uMetallic, 0.0f)
        glUniform1f(uRoughness, 1.0f)
        glUniform3f(uEmissive, 0.0f, 0.0f, 0.0f)
        glUniform1f(uHighlight, 0.0f)
        glBindVertexArray(gridVao)
        glDrawArrays(GL_LINES, 0, gridVertexCount)

        // Draw model meshes
        if (model != null && meshGpuData.isNotEmpty()) {
            if (wireframe) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
                glDisable(GL_CULL_FACE)
            }

            for (gpuMesh in meshGpuData) {
                // Each primitive lives in its own node's space
                glUniformMatrix4fv(uModel, false, gpuMesh.transform)
                val mat = model.materials.getOrElse(gpuMesh.materialIndex) { GlbMaterial.DEFAULT }
                glUniform4f(uBaseColor, mat.baseColor[0], mat.baseColor[1], mat.baseColor[2], mat.baseColor[3])
                glUniform1f(uMetallic, mat.metallic)
                glUniform1f(uRoughness, mat.roughness)
                glUniform3f(uEmissive, mat.emissive[0], mat.emissive[1], mat.emissive[2])
                glUniform1f(uHighlight, if (gpuMesh.materialIndex == highlightMaterial) 1.0f else 0.0f)

                glBindVertexArray(gpuMesh.vao)
                if (gpuMesh.hasIndices) {
                    glDrawElements(GL_TRIANGLES, gpuMesh.indexCount, GL_UNSIGNED_INT, 0)
                } else {
                    glDrawArrays(GL_TRIANGLES, 0, gpuMesh.vertexCount)
                }
            }

            if (wireframe) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
                glEnable(GL_CULL_FACE)
            }
        }

        glBindVertexArray(0)
    }

    fun dispose() {
        if (!initialized) return
        disposeModelData()
        if (gridVao != 0) { glDeleteVertexArrays(gridVao); gridVao = 0 }
        if (gridVbo != 0) { glDeleteBuffers(gridVbo); gridVbo = 0 }
        if (shaderProgram != 0) { glDeleteProgram(shaderProgram); shaderProgram = 0 }
        initialized = false
    }

    private fun disposeModelData() {
        for (mesh in meshGpuData) {
            glDeleteVertexArrays(mesh.vao)
            glDeleteBuffers(mesh.vboPositions)
            glDeleteBuffers(mesh.vboNormals)
            if (mesh.hasIndices) glDeleteBuffers(mesh.ebo)
        }
        meshGpuData.clear()
    }

    private fun getViewportAspect(): Float {
        val viewport = IntArray(4)
        glGetIntegerv(GL_VIEWPORT, viewport)
        return if (viewport[3] > 0) viewport[2].toFloat() / viewport[3].toFloat() else 1f
    }

    private fun buildGrid() {
        val lines = mutableListOf<Float>()
        val gridSize = 10
        val y = 0f
        for (i in -gridSize..gridSize) {
            // X-parallel lines
            lines.addAll(listOf(i.toFloat(), y, -gridSize.toFloat()))
            lines.addAll(listOf(0f, 1f, 0f)) // normal (up)
            lines.addAll(listOf(i.toFloat(), y, gridSize.toFloat()))
            lines.addAll(listOf(0f, 1f, 0f))
            // Z-parallel lines
            lines.addAll(listOf(-gridSize.toFloat(), y, i.toFloat()))
            lines.addAll(listOf(0f, 1f, 0f))
            lines.addAll(listOf(gridSize.toFloat(), y, i.toFloat()))
            lines.addAll(listOf(0f, 1f, 0f))
        }

        gridVertexCount = lines.size / 6 // 3 pos + 3 normal per vertex
        gridVao = glGenVertexArrays()
        glBindVertexArray(gridVao)

        gridVbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, gridVbo)
        val buf = allocFloatBuffer(lines.toFloatArray())
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW)
        MemoryUtil.memFree(buf)

        // Position (location 0): stride = 24 bytes (6 floats), offset 0
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
        glEnableVertexAttribArray(0)
        // Normal (location 1): stride = 24 bytes, offset 12
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
        glEnableVertexAttribArray(1)

        glBindVertexArray(0)
    }

    private fun generateFlatNormals(positions: FloatArray, indices: IntArray?): FloatArray {
        val normals = FloatArray(positions.size)
        val triCount = if (indices != null) indices.size / 3 else positions.size / 9

        for (t in 0 until triCount) {
            val i0 = if (indices != null) indices[t * 3] else t * 3
            val i1 = if (indices != null) indices[t * 3 + 1] else t * 3 + 1
            val i2 = if (indices != null) indices[t * 3 + 2] else t * 3 + 2

            val ax = positions[i1 * 3] - positions[i0 * 3]
            val ay = positions[i1 * 3 + 1] - positions[i0 * 3 + 1]
            val az = positions[i1 * 3 + 2] - positions[i0 * 3 + 2]
            val bx = positions[i2 * 3] - positions[i0 * 3]
            val by = positions[i2 * 3 + 1] - positions[i0 * 3 + 1]
            val bz = positions[i2 * 3 + 2] - positions[i0 * 3 + 2]

            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0f) { nx /= len; ny /= len; nz /= len }

            for (idx in listOf(i0, i1, i2)) {
                normals[idx * 3] += nx
                normals[idx * 3 + 1] += ny
                normals[idx * 3 + 2] += nz
            }
        }

        // Normalize accumulated normals
        for (i in 0 until normals.size / 3) {
            val x = normals[i * 3]; val y = normals[i * 3 + 1]; val z = normals[i * 3 + 2]
            val len = kotlin.math.sqrt(x * x + y * y + z * z)
            if (len > 0f) {
                normals[i * 3] /= len; normals[i * 3 + 1] /= len; normals[i * 3 + 2] /= len
            }
        }
        return normals
    }

    companion object {
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

            uniform mat4 uModel;
            uniform mat4 uView;
            uniform mat4 uProjection;

            out vec3 vWorldPos;
            out vec3 vNormal;

            void main() {
                vec4 worldPos = uModel * vec4(aPosition, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = mat3(uModel) * aNormal;
                gl_Position = uProjection * uView * worldPos;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 330 core
            in vec3 vWorldPos;
            in vec3 vNormal;

            uniform vec4 uBaseColor;
            uniform float uMetallic;
            uniform float uRoughness;
            uniform vec3 uEmissive;
            uniform vec3 uLightDir;
            uniform vec3 uViewPos;
            uniform float uHighlight;

            out vec4 fragColor;

            void main() {
                vec3 N = normalize(vNormal);
                vec3 L = normalize(uLightDir);
                vec3 V = normalize(uViewPos - vWorldPos);
                vec3 H = normalize(L + V);

                // Ambient
                vec3 ambient = 0.15 * uBaseColor.rgb;

                // Diffuse (Lambert)
                float NdotL = max(dot(N, L), 0.0);
                vec3 diffuse = uBaseColor.rgb * NdotL;

                // Specular (Blinn-Phong) - roughness controls shininess
                float shininess = mix(256.0, 4.0, uRoughness);
                float NdotH = max(dot(N, H), 0.0);
                float spec = pow(NdotH, shininess);
                // Metallic surfaces reflect base color, dielectrics reflect white
                vec3 specColor = mix(vec3(0.04), uBaseColor.rgb, uMetallic);
                vec3 specular = specColor * spec * mix(0.5, 1.5, uMetallic);

                vec3 color = ambient + diffuse * 0.7 + specular + uEmissive;

                // Highlight selected material
                if (uHighlight > 0.5) {
                    color = mix(color, vec3(1.0, 0.8, 0.2), 0.3);
                }

                // Tone mapping (simple Reinhard)
                color = color / (color + vec3(1.0));

                // Linear -> sRGB. glTF stores baseColorFactor in LINEAR space and
                // the default framebuffer is not sRGB, so writing linear straight
                // out renders everything far darker than authored: a #a5652c oak
                // is 0.25 linear and lands near black on screen.
                color = pow(color, vec3(1.0 / 2.2));

                fragColor = vec4(color, uBaseColor.a);
            }
        """.trimIndent()
    }
}
