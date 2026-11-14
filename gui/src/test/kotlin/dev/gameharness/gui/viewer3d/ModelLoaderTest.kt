package dev.gameharness.gui.viewer3d

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the loaders must bring across for the viewer to show a rigged, textured
 * model: the skin, the clip, the texture. Each is something a loader can drop
 * without failing: the model still opens, grey, or stiff, or folded up.
 */
class ModelLoaderTest {

    @Test
    fun `a rigged GLB keeps its skin, its clip and its texture`(@TempDir tmp: Path) {
        val glb = tmp.resolve("rigged.glb")
        Files.write(glb, riggedGlb())

        val model = loadModel(glb)

        // the skin: one joint, found by node, with the bind matrix from the file
        val skin = model.skins.single()
        assertEquals("joint", model.nodes[skin.joints.single()].name)
        assertEquals(-1f, skin.inverseBind[13], 1e-6f)
        assertEquals(1, model.jointCount)

        val mesh = model.meshes.single()
        assertTrue(mesh.isSkinned)
        // JOINTS_0 is stored as unsigned bytes; WEIGHTS_0 of (2, 2, 0, 0) must come out summing to one
        assertContentEquals(intArrayOf(0, 0, 0, 0), assertNotNull(mesh.joints).copyOfRange(0, 4))
        assertContentEquals(floatArrayOf(0.5f, 0.5f, 0f, 0f), assertNotNull(mesh.weights).copyOfRange(0, 4))
        assertContentEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), assertNotNull(mesh.texCoords))

        // the clip: keyed from 4 s to 6 s in the file, so two seconds long starting at zero
        val clip = model.animations.single()
        assertEquals("wave", clip.name)
        assertEquals(2f, clip.duration, 1e-6f)
        val channel = clip.channels.single()
        assertEquals(ChannelPath.TRANSLATION, channel.path)
        assertEquals("joint", model.nodes[channel.node].name)
        assertContentEquals(floatArrayOf(0f, 2f), channel.times)

        // the texture: the PNG bytes embedded in the file, wired to the material
        val material = model.materials.single()
        assertEquals(0, material.baseColorTexture)
        assertEquals(AlphaMode.MASK, material.alphaMode)
        assertTrue(material.doubleSided)
        val decoded = assertNotNull(DecodedTexture.decode(assertNotNull(model.textures.single().bytes()), 4096))
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        decoded.close()

        // and the pose follows the clip: the joint, and with it the mesh, slides 3 units along X
        val pose = ScenePose(model)
        pose.apply(clip, 2f)
        assertEquals(3f, assertNotNull(pose.bounds())[0], 1e-5f)
    }

    @Test
    fun `nodes are ordered parents first even when the file lists the child first`(@TempDir tmp: Path) {
        val glb = tmp.resolve("rigged.glb")
        Files.write(glb, riggedGlb())
        val model = loadModel(glb)

        // the file's node 0 is the joint, a child of its node 2
        model.nodes.forEachIndexed { index, node -> assertTrue(node.parent < index, "${node.name} comes before its parent") }
        assertEquals("root", model.nodes[model.nodes.first { it.name == "joint" }.parent].name)
    }

    @Test
    fun `an OBJ brings its faces, texture coordinates and the texture beside it`(@TempDir tmp: Path) {
        Files.write(tmp.resolve("skin.png"), tinyPng())
        Files.writeString(
            tmp.resolve("quad.mtl"),
            // the path an exporter on another machine left behind; only the file name is any use
            "newmtl paint\nKd 0.5 0.5 0.5\nmap_Kd C:/somewhere/else/skin.png\n",
        )
        val obj = tmp.resolve("quad.obj")
        Files.writeString(
            obj,
            """
            mtllib quad.mtl
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            usemtl paint
            f 1/1 2/2 3/3 4/4
            """.trimIndent() + "\n",
        )

        val model = loadModel(obj)

        assertEquals("obj", model.format)
        assertEquals(2, model.totalTriangles, "the quad is split into two triangles")
        val mesh = model.meshes.single()
        assertEquals(4, mesh.vertexCount)
        // OBJ puts v = 0 at the bottom of the image; the viewer, like glTF, puts it at the top
        val uv = assertNotNull(mesh.texCoords)
        val bottomLeft = (0 until 4).first { mesh.positions[it * 3] == 0f && mesh.positions[it * 3 + 1] == 0f }
        assertEquals(1f, uv[bottomLeft * 2 + 1], 1e-6f)

        val material = model.materials[mesh.materialIndex]
        assertEquals("paint", material.name)
        val texture = model.textures[material.baseColorTexture]
        assertNotNull(texture.bytes(), "found beside the model by file name")
        assertContentEquals(floatArrayOf(1f, 1f, 1f, 1f), material.baseColor, "a textured material is not tinted by its flat color")
    }

    @Test
    fun `a file that is not a model is refused with a reason`(@TempDir tmp: Path) {
        val text = tmp.resolve("notes.txt")
        Files.writeString(text, "hello")
        assertFailsWith<IllegalArgumentException> { loadModel(text) }

        val broken = tmp.resolve("broken.fbx")
        Files.writeString(broken, "this is not an FBX file")
        assertFailsWith<IllegalArgumentException> { loadModel(broken) }
    }

    private companion object {

        /** A 2x2 opaque PNG. */
        fun tinyPng(): ByteArray {
            val surface = Surface.makeRasterN32Premul(2, 2)
            surface.canvas.clear(0xFF3366CC.toInt())
            val data = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!
            surface.close()
            return data.bytes
        }

        /**
         * One triangle skinned to one joint, with a translation clip keyed at 4 s
         * and 6 s and an embedded PNG. The nodes are listed child first (joint,
         * mesh, root) because real exporters do that too.
         */
        fun riggedGlb(): ByteArray {
            val png = tinyPng()
            val bin = ByteArrayOutputStream()
            val views = ArrayList<String>()
            fun view(bytes: ByteArray): Int {
                while (bin.size() % 4 != 0) bin.write(0)
                views.add("""{"buffer":0,"byteOffset":${bin.size()},"byteLength":${bytes.size}}""")
                bin.write(bytes)
                return views.size - 1
            }
            fun floats(vararg v: Float): ByteArray =
                ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach(b::putFloat) }.array()

            val positions = view(floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f))
            val uvs = view(floats(0f, 0f, 1f, 0f, 0f, 1f))
            val joints = view(ByteArray(12)) // three vertices, four unsigned bytes each, all joint 0
            val weights = view(floats(2f, 2f, 0f, 0f, 2f, 2f, 0f, 0f, 2f, 2f, 0f, 0f))
            val inverseBind = view(floats(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 1f))
            val times = view(floats(4f, 6f))
            val values = view(floats(0f, 1f, 0f, 3f, 1f, 0f))
            val image = view(png)

            val json = """
                {"asset":{"version":"2.0"},
                 "scene":0,
                 "scenes":[{"nodes":[2]}],
                 "nodes":[{"name":"joint","translation":[0,1,0]},
                          {"name":"body","mesh":0,"skin":0},
                          {"name":"root","children":[0,1]}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1,"JOINTS_0":2,"WEIGHTS_0":3},"material":0}]}],
                 "skins":[{"joints":[0],"inverseBindMatrices":4}],
                 "animations":[{"name":"wave",
                   "samplers":[{"input":5,"output":6,"interpolation":"LINEAR"}],
                   "channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}]}],
                 "materials":[{"name":"paint","alphaMode":"MASK","doubleSided":true,
                   "pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],
                 "textures":[{"source":0}],
                 "images":[{"bufferView":$image,"mimeType":"image/png"}],
                 "accessors":[
                   {"bufferView":$positions,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                   {"bufferView":$uvs,"componentType":5126,"count":3,"type":"VEC2"},
                   {"bufferView":$joints,"componentType":5121,"count":3,"type":"VEC4"},
                   {"bufferView":$weights,"componentType":5126,"count":3,"type":"VEC4"},
                   {"bufferView":$inverseBind,"componentType":5126,"count":1,"type":"MAT4"},
                   {"bufferView":$times,"componentType":5126,"count":2,"type":"SCALAR","min":[4],"max":[6]},
                   {"bufferView":$values,"componentType":5126,"count":2,"type":"VEC3"}],
                 "bufferViews":[${views.joinToString(",")}],
                 "buffers":[{"byteLength":${bin.size()}}]}
            """.trimIndent().replace(Regex("\\s*\\n\\s*"), "")

            return assembleGlb(json.toByteArray(Charsets.UTF_8), bin.toByteArray())
        }

        /** header + JSON chunk + BIN chunk, each chunk padded to 4 bytes. */
        fun assembleGlb(json: ByteArray, bin: ByteArray): ByteArray {
            val jsonPad = (4 - json.size % 4) % 4
            val binPad = (4 - bin.size % 4) % 4
            val total = 12 + (8 + json.size + jsonPad) + (8 + bin.size + binPad)
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.put("glTF".toByteArray(Charsets.US_ASCII)).putInt(2).putInt(total)
            out.putInt(json.size + jsonPad).putInt(0x4E4F534A).put(json)
            repeat(jsonPad) { out.put(0x20) }
            out.putInt(bin.size + binPad).putInt(0x004E4942).put(bin)
            repeat(binPad) { out.put(0) }
            return out.array()
        }
    }
}
