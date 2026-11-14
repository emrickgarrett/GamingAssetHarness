package dev.gameharness.gui.viewer3d

import de.javagl.jgltf.model.AccessorByteData
import de.javagl.jgltf.model.AccessorFloatData
import de.javagl.jgltf.model.AccessorIntData
import de.javagl.jgltf.model.AccessorModel
import de.javagl.jgltf.model.AccessorShortData
import de.javagl.jgltf.model.AnimationModel
import de.javagl.jgltf.model.GltfModel
import de.javagl.jgltf.model.MaterialModel
import de.javagl.jgltf.model.NodeModel
import de.javagl.jgltf.model.SkinModel
import de.javagl.jgltf.model.TextureModel
import de.javagl.jgltf.model.io.GltfModelReader
import de.javagl.jgltf.model.v2.MaterialModelV2
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.IdentityHashMap

private val logger = LoggerFactory.getLogger("GltfLoader")

/** GL_REPEAT; the glTF default when a sampler names no wrap mode. */
private const val GL_REPEAT_MODE = 10497

/** The formats [loadModel] can open, lower case, without the dot. */
val VIEWABLE_MODEL_FORMATS = setOf("glb", "gltf", "fbx", "obj")

/**
 * Loads a 3D model file for the viewer, choosing the reader by extension:
 * glTF and GLB through jglTF, FBX and OBJ through Assimp.
 */
fun loadModel(path: Path): SceneModel {
    return when (val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()) {
        "glb", "gltf" -> loadGlb(path)
        "fbx", "obj" -> loadWithAssimp(path)
        else -> throw IllegalArgumentException("Cannot open .$extension files as 3D models")
    }
}

/**
 * Loads a GLB or glTF file: geometry, materials and their textures, the node
 * tree, skins and animation clips.
 */
fun loadGlb(path: Path): SceneModel {
    val gltf = GltfModelReader().read(path)
    val format = path.fileName.toString().substringAfterLast('.', "glb").lowercase()

    // Nodes, parents first. The file may list a child before its parent, and
    // posing walks the list once in order, so the order is fixed here.
    val nodeIndex = IdentityHashMap<NodeModel, Int>()
    val ordered = ArrayList<NodeModel>()
    fun visit(node: NodeModel) {
        if (nodeIndex.containsKey(node)) return
        nodeIndex[node] = ordered.size
        ordered.add(node)
        node.children.forEach(::visit)
    }
    gltf.nodeModels.filter { it.parent == null }.forEach(::visit)
    gltf.nodeModels.forEach(::visit) // anything a broken hierarchy left unreachable
    val nodes = ordered.map { node ->
        SceneNode(
            name = node.name ?: "",
            parent = node.parent?.let { nodeIndex[it] } ?: -1,
            translation = node.translation?.copyOf() ?: floatArrayOf(0f, 0f, 0f),
            rotation = node.rotation?.copyOf() ?: floatArrayOf(0f, 0f, 0f, 1f),
            scale = node.scale?.copyOf() ?: floatArrayOf(1f, 1f, 1f),
            matrix = node.matrix?.copyOf(),
        )
    }

    val textureIndex = IdentityHashMap<TextureModel, Int>()
    val textures = gltf.textureModels.mapIndexed { i, texture ->
        textureIndex[texture] = i
        val image = texture.imageModel
        val bytes = image?.imageData?.let { data ->
            val copy = data.duplicate()
            ByteArray(copy.remaining()).also { copy.get(it) }
        }
        SceneTexture(
            name = texture.name ?: image?.name ?: image?.uri ?: "Texture $i",
            encoded = bytes,
            repeatS = (texture.wrapS ?: GL_REPEAT_MODE) == GL_REPEAT_MODE,
            repeatT = (texture.wrapT ?: GL_REPEAT_MODE) == GL_REPEAT_MODE,
        )
    }
    fun textureOf(model: TextureModel?): Int = model?.let { textureIndex[it] } ?: -1

    val materialIndex = IdentityHashMap<MaterialModel, Int>()
    val materials = ArrayList<SceneMaterial>()
    for (matModel in gltf.materialModels) {
        materialIndex[matModel] = materials.size
        val mat = matModel as? MaterialModelV2
        if (mat != null) {
            materials.add(
                SceneMaterial(
                    name = mat.name ?: "Material ${materials.size}",
                    baseColor = mat.baseColorFactor ?: floatArrayOf(1f, 1f, 1f, 1f),
                    metallic = mat.metallicFactor,
                    roughness = mat.roughnessFactor,
                    emissive = mat.emissiveFactor ?: floatArrayOf(0f, 0f, 0f),
                    baseColorTexture = textureOf(mat.baseColorTexture),
                    normalTexture = textureOf(mat.normalTexture),
                    metallicRoughnessTexture = textureOf(mat.metallicRoughnessTexture),
                    emissiveTexture = textureOf(mat.emissiveTexture),
                    alphaMode = when (mat.alphaMode) {
                        MaterialModelV2.AlphaMode.MASK -> AlphaMode.MASK
                        MaterialModelV2.AlphaMode.BLEND -> AlphaMode.BLEND
                        else -> AlphaMode.OPAQUE
                    },
                    alphaCutoff = mat.alphaCutoff,
                    doubleSided = mat.isDoubleSided,
                )
            )
        } else {
            materials.add(SceneMaterial.DEFAULT.named("Material ${materials.size}"))
        }
    }
    if (materials.isEmpty()) {
        materials.add(SceneMaterial.DEFAULT)
    }

    val skinIndex = IdentityHashMap<SkinModel, Int>()
    val skins = gltf.skinModels.mapIndexed { i, skin ->
        skinIndex[skin] = i
        val joints = skin.joints
        val inverseBind = FloatArray(joints.size * 16)
        val one = FloatArray(16)
        for (j in joints.indices) {
            skin.getInverseBindMatrix(j, one)
            one.copyInto(inverseBind, j * 16)
        }
        SceneSkin(IntArray(joints.size) { nodeIndex[joints[it]] ?: -1 }, inverseBind)
    }

    // Meshes by walking the NODES, not gltf.meshModels: a mesh is only
    // positioned by the node that references it, and one mesh may be
    // instanced by several nodes at different transforms.
    val meshes = ArrayList<SceneMesh>()
    val placed = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    for (node in ordered) {
        if (node.meshModels.isEmpty()) continue
        val global = FloatArray(16)
        node.computeGlobalTransform(global)
        val skin = node.skinModel?.let { skinIndex[it] } ?: -1
        for (meshModel in node.meshModels) {
            placed.add(meshModel)
            for (primitive in meshModel.meshPrimitiveModels) {
                readPrimitive(primitive, materialIndex, global, nodeIndex[node] ?: -1, skin)?.let(meshes::add)
            }
        }
    }
    // Meshes no node references shouldn't exist in a valid glTF, but rendering
    // them at identity beats silently dropping geometry.
    for (meshModel in gltf.meshModels) {
        if (meshModel !in placed) {
            logger.warn("Mesh is not referenced by any node; drawing it untransformed")
            for (primitive in meshModel.meshPrimitiveModels) {
                readPrimitive(primitive, materialIndex, Mat4.identity(), -1, -1)?.let(meshes::add)
            }
        }
    }

    val animations = readAnimations(gltf, nodeIndex)

    logger.info(
        "Loaded {}: {} meshes, {} vertices, {} materials, {} textures, {} joints, {} animations",
        path.fileName, meshes.size, meshes.sumOf { it.vertexCount }, materials.size, textures.size,
        skins.sumOf { it.joints.size }, animations.size,
    )
    return SceneModel(nodes, meshes, materials, textures, skins, animations, format)
}

private fun readPrimitive(
    primitive: de.javagl.jgltf.model.MeshPrimitiveModel,
    materialIndex: Map<MaterialModel, Int>,
    nodeTransform: FloatArray,
    node: Int,
    skin: Int,
): SceneMesh? {
    try {
        // 4 = TRIANGLES; points, lines, strips and fans are not drawn
        if (primitive.mode != 4) return null
        val posAccessor = primitive.attributes["POSITION"] ?: return null
        val positions = readFloats(posAccessor, 3) ?: return null
        val vertexCount = posAccessor.count

        val normals = primitive.attributes["NORMAL"]?.let { readFloats(it, 3) }
        val texCoords = primitive.attributes["TEXCOORD_0"]?.let { readFloats(it, 2) }

        // Indices. glTF allows UNSIGNED_BYTE/SHORT/INT here and exporters
        // overwhelmingly emit UNSIGNED_SHORT, so accepting only AccessorIntData
        // silently drops the index buffer — the renderer then falls back to
        // glDrawArrays and shreds the mesh into slivers.
        val indices = primitive.indices?.let { readInts(it, 1) }

        var joints: IntArray? = null
        var weights: FloatArray? = null
        if (skin >= 0) {
            joints = primitive.attributes["JOINTS_0"]?.let { readInts(it, 4) }
            weights = primitive.attributes["WEIGHTS_0"]?.let { readFloats(it, 4) }
            if (joints != null && weights != null) normalizeWeights(weights) else { joints = null; weights = null }
        }

        return SceneMesh(
            positions = positions,
            normals = normals,
            texCoords = texCoords,
            indices = indices,
            materialIndex = primitive.materialModel?.let { materialIndex[it] } ?: 0,
            vertexCount = vertexCount,
            transform = nodeTransform,
            nodeIndex = node,
            skinIndex = if (joints != null) skin else -1,
            joints = joints,
            weights = weights,
        )
    } catch (e: Exception) {
        logger.warn("Failed to load mesh primitive: ${e.message}")
        return null
    }
}

private fun readAnimations(gltf: GltfModel, nodeIndex: Map<NodeModel, Int>): List<SceneAnimation> {
    val animations = ArrayList<SceneAnimation>()
    for ((i, animation) in gltf.animationModels.withIndex()) {
        val channels = ArrayList<AnimationChannel>()
        for (channel in animation.channels) {
            val path = when (channel.path) {
                "translation" -> ChannelPath.TRANSLATION
                "rotation" -> ChannelPath.ROTATION
                "scale" -> ChannelPath.SCALE
                else -> continue // morph target weights: not drawn
            }
            val node = channel.nodeModel?.let { nodeIndex[it] } ?: continue
            val sampler = channel.sampler ?: continue
            val times = readFloats(sampler.input, 1) ?: continue
            val values = readFloats(sampler.output, path.components) ?: continue
            if (times.isEmpty()) continue
            val interpolation = when (sampler.interpolation) {
                AnimationModel.Interpolation.STEP -> Interpolation.STEP
                AnimationModel.Interpolation.CUBICSPLINE -> Interpolation.CUBICSPLINE
                else -> Interpolation.LINEAR
            }
            channels.add(AnimationChannel(node, path, times, values, interpolation))
        }
        if (channels.isEmpty()) continue
        // A clip cut from a longer timeline keeps its original key times; start it at zero.
        val start = channels.minOf { it.times.first() }
        if (start != 0f) channels.forEach { c -> for (k in c.times.indices) c.times[k] -= start }
        val duration = channels.maxOf { it.times.last() }
        animations.add(SceneAnimation(animation.name ?: "Animation ${i + 1}", duration, channels))
    }
    return animations
}

/**
 * The accessor as floats, [components] per element. Integer data is taken as
 * normalised, which is how glTF stores texture coordinates, weights and
 * rotations when they are not floats.
 */
private fun readFloats(accessor: AccessorModel, components: Int): FloatArray? {
    val count = accessor.count
    return when (val data = accessor.accessorData) {
        is AccessorFloatData -> {
            if (data.numComponentsPerElement < components) return null
            FloatArray(count * components) { data.get(it / components, it % components) }
        }
        is AccessorShortData -> {
            if (data.numComponentsPerElement < components) return null
            val unsigned = data.isUnsigned
            FloatArray(count * components) {
                val v = data.getInt(it / components, it % components)
                if (unsigned) v / 65535f else maxOf(v / 32767f, -1f)
            }
        }
        is AccessorByteData -> {
            if (data.numComponentsPerElement < components) return null
            val unsigned = data.isUnsigned
            FloatArray(count * components) {
                val v = data.getInt(it / components, it % components)
                if (unsigned) v / 255f else maxOf(v / 127f, -1f)
            }
        }
        else -> null
    }
}

/** The accessor as ints, [components] per element, unsigned types read as unsigned. */
private fun readInts(accessor: AccessorModel, components: Int): IntArray? {
    val count = accessor.count
    return when (val data = accessor.accessorData) {
        // UNSIGNED_INT: the raw bits are what GL_UNSIGNED_INT wants
        is AccessorIntData -> IntArray(count * components) { data.get(it / components, it % components) }
        is AccessorShortData -> IntArray(count * components) { data.getInt(it / components, it % components) }
        is AccessorByteData -> IntArray(count * components) { data.getInt(it / components, it % components) }
        else -> {
            logger.warn("Unsupported integer accessor type: ${data?.javaClass?.simpleName}")
            null
        }
    }
}

/** Makes each vertex's four weights sum to one; a vertex with none is tied wholly to its first joint. */
internal fun normalizeWeights(weights: FloatArray) {
    var i = 0
    while (i + 3 < weights.size) {
        val sum = weights[i] + weights[i + 1] + weights[i + 2] + weights[i + 3]
        if (sum > 0f) {
            weights[i] /= sum; weights[i + 1] /= sum; weights[i + 2] /= sum; weights[i + 3] /= sum
        } else {
            weights[i] = 1f
        }
        i += 4
    }
}
