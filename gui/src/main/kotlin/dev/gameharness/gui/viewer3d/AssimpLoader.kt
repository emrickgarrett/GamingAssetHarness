package dev.gameharness.gui.viewer3d

import org.lwjgl.assimp.AIAnimation
import org.lwjgl.assimp.AIBone
import org.lwjgl.assimp.AIColor4D
import org.lwjgl.assimp.AIMaterial
import org.lwjgl.assimp.AIMatrix4x4
import org.lwjgl.assimp.AIMesh
import org.lwjgl.assimp.AINode
import org.lwjgl.assimp.AINodeAnim
import org.lwjgl.assimp.AIScene
import org.lwjgl.assimp.AIString
import org.lwjgl.assimp.AITexture
import org.lwjgl.assimp.Assimp.*
import org.lwjgl.system.MemoryStack
import org.slf4j.LoggerFactory
import java.nio.IntBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.pow

private val logger = LoggerFactory.getLogger("AssimpLoader")

/**
 * Loads an FBX or OBJ file through Assimp: geometry, the node tree, bones and
 * their weights, animation clips, and the color, normal and emissive
 * textures, whether embedded in the file or sitting beside it.
 *
 * Assimp's conventions are turned into the viewer's (which are glTF's) here:
 * row-major matrices become column-major, texture coordinates are flipped to a
 * top-left origin, material colors are taken as sRGB and made linear, and key
 * times in ticks become seconds.
 */
fun loadWithAssimp(path: Path): SceneModel {
    val flags = aiProcess_Triangulate or aiProcess_JoinIdenticalVertices or aiProcess_GenSmoothNormals or
        aiProcess_LimitBoneWeights or aiProcess_SortByPType or aiProcess_FlipUVs
    val scene = aiImportFile(path.toAbsolutePath().toString(), flags)
        ?: throw IllegalArgumentException(aiGetErrorString() ?: "Assimp could not read ${path.fileName}")
    try {
        return convert(scene, path)
    } finally {
        aiReleaseImport(scene)
    }
}

private class MeshData(
    val positions: FloatArray,
    val normals: FloatArray?,
    val texCoords: FloatArray?,
    val indices: IntArray,
    val material: Int,
    val skin: Int,
    val joints: IntArray?,
    val weights: FloatArray?,
)

private fun convert(scene: AIScene, path: Path): SceneModel {
    val format = path.fileName.toString().substringAfterLast('.', "").lowercase()

    // --- nodes, parents first; bones and animation channels find theirs by name ---
    val nodes = ArrayList<SceneNode>()
    val nodeByName = HashMap<String, Int>()
    val meshPlacements = ArrayList<Pair<Int, Int>>() // node index, mesh index
    fun visit(node: AINode, parent: Int) {
        val index = nodes.size
        val name = node.mName().dataString()
        val t = FloatArray(3)
        val r = FloatArray(4)
        val s = FloatArray(3)
        Mat4.decompose(columnMajor(node.mTransformation()), t, r, s)
        nodes.add(SceneNode(name, parent, t, r, s))
        nodeByName.putIfAbsent(name, index)
        node.mMeshes()?.let { meshes -> for (i in 0 until node.mNumMeshes()) meshPlacements.add(index to meshes.get(i)) }
        node.mChildren()?.let { children ->
            for (i in 0 until node.mNumChildren()) visit(AINode.create(children.get(i)), index)
        }
    }
    val root = scene.mRootNode() ?: throw IllegalArgumentException("${path.fileName} has no scene")
    visit(root, -1)

    // --- textures and materials ---
    val textures = ArrayList<SceneTexture>()
    val textureByPath = HashMap<String, Int>()
    fun texture(material: AIMaterial, type: Int): Int {
        val texturePath = texturePath(material, type) ?: return -1
        textureByPath[texturePath]?.let { return it }
        val source = embeddedTexture(scene, texturePath)?.let { SceneTexture(texturePath, it) }
            ?: externalTexture(path, texturePath)?.let { SceneTexture(texturePath, null, it) }
        if (source == null) {
            logger.info("Texture not found for {}: {}", path.fileName, texturePath)
            textureByPath[texturePath] = -1
            return -1
        }
        textures.add(source)
        textureByPath[texturePath] = textures.size - 1
        return textures.size - 1
    }

    val materials = ArrayList<SceneMaterial>()
    scene.mMaterials()?.let { pointers ->
        for (i in 0 until scene.mNumMaterials()) {
            val material = AIMaterial.create(pointers.get(i))
            val name = materialName(material).ifEmpty { "Material $i" }

            var baseTexture = texture(material, aiTextureType_BASE_COLOR)
            if (baseTexture < 0) baseTexture = texture(material, aiTextureType_DIFFUSE)
            val flat = color(material, AI_MATKEY_BASE_COLOR) ?: color(material, AI_MATKEY_COLOR_DIFFUSE)
            // A textured material's flat color is often a leftover (black in many
            // FBX files) that would blot the texture out; the texture stands alone.
            val base = if (baseTexture >= 0 || flat == null) floatArrayOf(1f, 1f, 1f, 1f) else flat
            val opacity = float(material, AI_MATKEY_OPACITY) ?: 1f
            if (opacity > 0f && opacity < 1f) base[3] = opacity
            val emissiveTexture = texture(material, aiTextureType_EMISSIVE)
            val emissive = color(material, AI_MATKEY_COLOR_EMISSIVE)
                ?: if (emissiveTexture >= 0) floatArrayOf(1f, 1f, 1f, 1f) else floatArrayOf(0f, 0f, 0f, 1f)

            materials.add(
                SceneMaterial(
                    name = name,
                    baseColor = base,
                    metallic = float(material, AI_MATKEY_METALLIC_FACTOR)?.coerceIn(0f, 1f) ?: 0f,
                    roughness = float(material, AI_MATKEY_ROUGHNESS_FACTOR)?.coerceIn(0f, 1f) ?: 0.7f,
                    emissive = floatArrayOf(emissive[0], emissive[1], emissive[2]),
                    baseColorTexture = baseTexture,
                    normalTexture = texture(material, aiTextureType_NORMALS),
                    emissiveTexture = emissiveTexture,
                    alphaMode = if (base[3] < 1f) AlphaMode.BLEND else AlphaMode.OPAQUE,
                    // FBX and OBJ carry no reliable sidedness; showing both is the safe reading
                    doubleSided = true,
                )
            )
        }
    }
    if (materials.isEmpty()) materials.add(SceneMaterial.DEFAULT)

    // --- meshes and skins ---
    val skins = ArrayList<SceneSkin>()
    val meshData = arrayOfNulls<MeshData>(scene.mNumMeshes())
    scene.mMeshes()?.let { pointers ->
        for (m in 0 until scene.mNumMeshes()) {
            meshData[m] = readMesh(AIMesh.create(pointers.get(m)), nodeByName, skins, materials.size)
        }
    }

    val rest = ScenePose(SceneModel(nodes, emptyList(), materials))
    val meshes = ArrayList<SceneMesh>()
    for ((nodeIndex, meshIndex) in meshPlacements) {
        val data = meshData.getOrNull(meshIndex) ?: continue
        meshes.add(
            SceneMesh(
                positions = data.positions,
                normals = data.normals,
                texCoords = data.texCoords,
                indices = data.indices,
                materialIndex = data.material,
                vertexCount = data.positions.size / 3,
                transform = rest.globals.copyOfRange(nodeIndex * 16, nodeIndex * 16 + 16),
                nodeIndex = nodeIndex,
                skinIndex = data.skin,
                joints = data.joints,
                weights = data.weights,
            )
        )
    }

    val animations = readAnimations(scene, nodeByName)

    logger.info(
        "Loaded {}: {} meshes, {} vertices, {} materials, {} textures, {} joints, {} animations",
        path.fileName, meshes.size, meshes.sumOf { it.vertexCount }, materials.size, textures.size,
        skins.sumOf { it.joints.size }, animations.size,
    )
    return SceneModel(nodes, meshes, materials, textures, skins, animations, format)
}

private fun readMesh(mesh: AIMesh, nodeByName: Map<String, Int>, skins: MutableList<SceneSkin>, materialCount: Int): MeshData? {
    if (mesh.mPrimitiveTypes() and aiPrimitiveType_TRIANGLE == 0) return null
    val count = mesh.mNumVertices()

    val positions = FloatArray(count * 3)
    val vertices = mesh.mVertices()
    for (v in 0 until count) {
        val p = vertices.get(v)
        positions[v * 3] = p.x(); positions[v * 3 + 1] = p.y(); positions[v * 3 + 2] = p.z()
    }
    val normals = mesh.mNormals()?.let { buffer ->
        val out = FloatArray(count * 3)
        for (v in 0 until count) {
            val n = buffer.get(v)
            out[v * 3] = n.x(); out[v * 3 + 1] = n.y(); out[v * 3 + 2] = n.z()
        }
        out
    }
    val texCoords = mesh.mTextureCoords(0)?.let { buffer ->
        val out = FloatArray(count * 2)
        for (v in 0 until count) {
            val uv = buffer.get(v)
            out[v * 2] = uv.x(); out[v * 2 + 1] = uv.y()
        }
        out
    }

    val faces = mesh.mFaces()
    var indices = IntArray(mesh.mNumFaces() * 3)
    var written = 0
    for (f in 0 until mesh.mNumFaces()) {
        val face = faces.get(f)
        if (face.mNumIndices() != 3) continue
        val ix = face.mIndices()
        indices[written++] = ix.get(0); indices[written++] = ix.get(1); indices[written++] = ix.get(2)
    }
    if (written != indices.size) indices = indices.copyOf(written)

    var skin = -1
    var joints: IntArray? = null
    var weights: FloatArray? = null
    val bones = mesh.mBones()
    if (bones != null && mesh.mNumBones() > 0) {
        val boneCount = mesh.mNumBones()
        val jointNodes = IntArray(boneCount)
        val inverseBind = FloatArray(boneCount * 16)
        val vertexJoints = IntArray(count * 4)
        val vertexWeights = FloatArray(count * 4)
        val used = IntArray(count)
        for (b in 0 until boneCount) {
            val bone = AIBone.create(bones.get(b))
            jointNodes[b] = nodeByName[bone.mName().dataString()] ?: -1
            columnMajor(bone.mOffsetMatrix()).copyInto(inverseBind, b * 16)
            val boneWeights = bone.mWeights()
            for (w in 0 until bone.mNumWeights()) {
                val weight = boneWeights.get(w)
                val vertex = weight.mVertexId()
                if (vertex !in 0 until count || used[vertex] >= 4) continue
                vertexJoints[vertex * 4 + used[vertex]] = b
                vertexWeights[vertex * 4 + used[vertex]] = weight.mWeight()
                used[vertex]++
            }
        }
        normalizeWeights(vertexWeights)
        skin = skins.size
        skins.add(SceneSkin(jointNodes, inverseBind))
        joints = vertexJoints
        weights = vertexWeights
    }

    return MeshData(
        positions, normals, texCoords, indices,
        mesh.mMaterialIndex().coerceIn(0, materialCount - 1), skin, joints, weights,
    )
}

private fun readAnimations(scene: AIScene, nodeByName: Map<String, Int>): List<SceneAnimation> {
    val animations = ArrayList<SceneAnimation>()
    val pointers = scene.mAnimations() ?: return animations
    for (a in 0 until scene.mNumAnimations()) {
        val animation = AIAnimation.create(pointers.get(a))
        val ticksPerSecond = animation.mTicksPerSecond().takeIf { it > 0.0 } ?: 25.0
        val channels = ArrayList<AnimationChannel>()
        val nodeChannels = animation.mChannels() ?: continue
        for (c in 0 until animation.mNumChannels()) {
            val channel = AINodeAnim.create(nodeChannels.get(c))
            val node = nodeByName[channel.mNodeName().dataString()] ?: continue

            val positions = channel.mPositionKeys()
            val positionCount = channel.mNumPositionKeys()
            if (positions != null && positionCount > 0) {
                val times = FloatArray(positionCount)
                val values = FloatArray(positionCount * 3)
                for (k in 0 until positionCount) {
                    val key = positions.get(k)
                    times[k] = (key.mTime() / ticksPerSecond).toFloat()
                    val v = key.mValue()
                    values[k * 3] = v.x(); values[k * 3 + 1] = v.y(); values[k * 3 + 2] = v.z()
                }
                channels.add(AnimationChannel(node, ChannelPath.TRANSLATION, times, values))
            }

            val rotations = channel.mRotationKeys()
            val rotationCount = channel.mNumRotationKeys()
            if (rotations != null && rotationCount > 0) {
                val times = FloatArray(rotationCount)
                val values = FloatArray(rotationCount * 4)
                for (k in 0 until rotationCount) {
                    val key = rotations.get(k)
                    times[k] = (key.mTime() / ticksPerSecond).toFloat()
                    val q = key.mValue()
                    values[k * 4] = q.x(); values[k * 4 + 1] = q.y(); values[k * 4 + 2] = q.z(); values[k * 4 + 3] = q.w()
                }
                channels.add(AnimationChannel(node, ChannelPath.ROTATION, times, values))
            }

            val scalings = channel.mScalingKeys()
            val scalingCount = channel.mNumScalingKeys()
            if (scalings != null && scalingCount > 0) {
                val times = FloatArray(scalingCount)
                val values = FloatArray(scalingCount * 3)
                for (k in 0 until scalingCount) {
                    val key = scalings.get(k)
                    times[k] = (key.mTime() / ticksPerSecond).toFloat()
                    val v = key.mValue()
                    values[k * 3] = v.x(); values[k * 3 + 1] = v.y(); values[k * 3 + 2] = v.z()
                }
                channels.add(AnimationChannel(node, ChannelPath.SCALE, times, values))
            }
        }
        if (channels.isEmpty()) continue
        val start = channels.minOf { it.times.first() }
        if (start != 0f) channels.forEach { ch -> for (k in ch.times.indices) ch.times[k] -= start }
        val name = animation.mName().dataString().ifEmpty { "Animation ${a + 1}" }
        animations.add(SceneAnimation(name, channels.maxOf { it.times.last() }, channels))
    }
    return animations
}

/** Assimp's row-major matrix as the column-major array OpenGL and the rest of the viewer use. */
private fun columnMajor(m: AIMatrix4x4): FloatArray = floatArrayOf(
    m.a1(), m.b1(), m.c1(), m.d1(),
    m.a2(), m.b2(), m.c2(), m.d2(),
    m.a3(), m.b3(), m.c3(), m.d3(),
    m.a4(), m.b4(), m.c4(), m.d4(),
)

private fun materialName(material: AIMaterial): String {
    MemoryStack.stackPush().use { stack ->
        val out = AIString.calloc(stack)
        return if (aiGetMaterialString(material, AI_MATKEY_NAME, aiTextureType_NONE, 0, out) == aiReturn_SUCCESS) {
            out.dataString()
        } else ""
    }
}

/** The path the material gives for its first texture of [type], or null if it has none. */
private fun texturePath(material: AIMaterial, type: Int): String? {
    MemoryStack.stackPush().use { stack ->
        val out = AIString.calloc(stack)
        val result = aiGetMaterialTexture(
            material, type, 0, out, null as IntBuffer?, null, null, null, null, null,
        )
        if (result != aiReturn_SUCCESS) return null
        return out.dataString().ifEmpty { null }
    }
}

/** A material color, read as sRGB and returned linear; null when the material does not set it. */
private fun color(material: AIMaterial, key: String): FloatArray? {
    MemoryStack.stackPush().use { stack ->
        val out = AIColor4D.calloc(stack)
        if (aiGetMaterialColor(material, key, aiTextureType_NONE, 0, out) != aiReturn_SUCCESS) return null
        val alpha = out.a().coerceIn(0f, 1f)
        return floatArrayOf(linear(out.r()), linear(out.g()), linear(out.b()), if (alpha == 0f) 1f else alpha)
    }
}

private fun linear(srgb: Float): Float = srgb.coerceIn(0f, 1f).toDouble().pow(2.2).toFloat()

private fun float(material: AIMaterial, key: String): Float? {
    MemoryStack.stackPush().use { stack ->
        val out = stack.mallocFloat(1)
        val max = stack.ints(1)
        if (aiGetMaterialFloatArray(material, key, aiTextureType_NONE, 0, out, max) != aiReturn_SUCCESS) return null
        return out.get(0)
    }
}

/** The bytes of a texture stored inside the model file: "*3" is the fourth, anything else is matched by file name. */
private fun embeddedTexture(scene: AIScene, texturePath: String): ByteArray? {
    val pointers = scene.mTextures() ?: return null
    val count = scene.mNumTextures()
    var found: AITexture? = null
    if (texturePath.startsWith("*")) {
        val index = texturePath.drop(1).toIntOrNull() ?: return null
        if (index in 0 until count) found = AITexture.create(pointers.get(index))
    } else {
        val wanted = texturePath.replace('\\', '/').substringAfterLast('/')
        for (i in 0 until count) {
            val candidate = AITexture.create(pointers.get(i))
            val name = candidate.mFilename().dataString().replace('\\', '/').substringAfterLast('/')
            if (name.isNotEmpty() && name.equals(wanted, ignoreCase = true)) {
                found = candidate
                break
            }
        }
    }
    val texture = found ?: return null
    // a height of zero marks an encoded image (PNG, JPEG) of mWidth bytes; raw texel arrays are not read
    if (texture.mHeight() != 0) return null
    val data = texture.pcDataCompressed()
    return ByteArray(data.remaining()).also { data.get(it) }
}

/**
 * A texture file named by the model. The path is tried as written, relative to
 * the model, and then by file name alone beside the model: FBX files often keep
 * the absolute path of the machine they were exported on.
 */
private fun externalTexture(model: Path, texturePath: String): Path? {
    val dir = model.toAbsolutePath().parent ?: return null
    val normalized = texturePath.replace('\\', '/')
    val candidates = listOfNotNull(
        runCatching { Path.of(texturePath) }.getOrNull()?.takeIf { it.isAbsolute },
        runCatching { dir.resolve(normalized) }.getOrNull(),
        runCatching { dir.resolve(normalized.substringAfterLast('/')) }.getOrNull(),
    )
    return candidates.firstOrNull { Files.isRegularFile(it) }
}
