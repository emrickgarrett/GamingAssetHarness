package dev.gameharness.gui.viewer3d

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Guards the two ways this loader can silently return a wrong model.
 *
 * Both bugs it covers rendered *something* — no exception, no warning, a
 * plausible-looking triangle count — which is exactly why they need a test:
 *
 *  1. Index accessors are UNSIGNED_SHORT in practice. Accepting only
 *     AccessorIntData dropped the index buffer, and the renderer's
 *     glDrawArrays fallback shredded the mesh into slivers.
 *  2. Positions are node-local. Drawing every primitive at identity collapses
 *     the hierarchy — a 0.001-scaled grouping anchor becomes a full-size box
 *     covering the whole model.
 */
class GlbModelTest {

    @Test
    fun `reads UNSIGNED_SHORT indices and the owning node's transform`(@TempDir tmp: Path) {
        val glb = tmp.resolve("tri.glb")
        glb.toFile().writeBytes(minimalGlb(scale = 2f, translateY = 5f))

        val model = loadGlb(glb)

        assertEquals(1, model.meshes.size, "expected the single primitive")
        val mesh = model.meshes.single()

        val indices = assertNotNull(mesh.indices, "UNSIGNED_SHORT indices must be read, not dropped")
        assertContentEquals(intArrayOf(0, 1, 2), indices)
        assertEquals(1, model.totalTriangles)

        // The node scales by 2 and lifts by 5; positions themselves stay local.
        assertEquals(1f, mesh.positions[3], "positions should remain in node-local space")
        val p = mesh.transformPoint(1f, 0f, 0f)
        assertEquals(2f, p[0], 1e-5f)
        assertEquals(5f, p[1], 1e-5f)
    }

    private companion object {

        /**
         * A one-triangle GLB: `root` (scale/translate) → `child` (the mesh), with
         * UNSIGNED_SHORT indices. Hand-built so the test doesn't depend on a
         * binary fixture or on whatever the exporter of the day happens to emit.
         */
        fun minimalGlb(scale: Float, translateY: Float): ByteArray {
            val positions = floatArrayOf(
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f,
            )
            val indices = shortArrayOf(0, 1, 2)

            val bin = ByteBuffer.allocate(positions.size * 4 + indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            positions.forEach { bin.putFloat(it) }
            indices.forEach { bin.putShort(it) }
            val binBytes = bin.array()
            val posLength = positions.size * 4

            // glTF matrices are column-major
            val matrix = listOf(
                scale, 0f, 0f, 0f,
                0f, scale, 0f, 0f,
                0f, 0f, scale, 0f,
                0f, translateY, 0f, 1f,
            ).joinToString(",")

            val json = """
                {"asset":{"version":"2.0"},
                 "scene":0,
                 "scenes":[{"nodes":[0]}],
                 "nodes":[{"name":"root","matrix":[$matrix],"children":[1]},
                          {"name":"child","mesh":0}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
                 "accessors":[
                   {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3",
                    "min":[0,0,0],"max":[1,1,0]},
                   {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
                 "bufferViews":[
                   {"buffer":0,"byteOffset":0,"byteLength":$posLength,"target":34962},
                   {"buffer":0,"byteOffset":$posLength,"byteLength":${indices.size * 2},"target":34963}],
                 "buffers":[{"byteLength":${binBytes.size}}]}
            """.trimIndent().replace(Regex("\\s*\\n\\s*"), "")

            return assembleGlb(json.toByteArray(Charsets.UTF_8), binBytes)
        }

        /** header + JSON chunk + BIN chunk, each chunk padded to 4 bytes. */
        fun assembleGlb(json: ByteArray, bin: ByteArray): ByteArray {
            val jsonPad = (4 - json.size % 4) % 4
            val binPad = (4 - bin.size % 4) % 4
            val total = 12 + (8 + json.size + jsonPad) + (8 + bin.size + binPad)

            val out = ByteArrayOutputStream()
            fun le(v: Int) = out.write(
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            )

            out.write("glTF".toByteArray(Charsets.US_ASCII))
            le(2)
            le(total)

            le(json.size + jsonPad)
            le(0x4E4F534A) // 'JSON'
            out.write(json)
            repeat(jsonPad) { out.write(0x20) } // JSON pads with spaces

            le(bin.size + binPad)
            le(0x004E4942) // 'BIN\0'
            out.write(bin)
            repeat(binPad) { out.write(0) }

            return out.toByteArray()
        }
    }
}
