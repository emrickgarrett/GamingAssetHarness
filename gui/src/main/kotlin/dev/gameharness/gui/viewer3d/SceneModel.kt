package dev.gameharness.gui.viewer3d

import java.nio.file.Path

/**
 * A 3D model as the viewer draws it, whatever file it came from: a node tree,
 * the mesh primitives hanging off it, materials with their textures, skins and
 * animation clips.
 *
 * Loaders ([loadGlb] for glTF/GLB, [loadWithAssimp] for FBX and OBJ) fill this
 * in; [ScenePose] animates it; [ModelRenderer] draws it. Nothing here touches
 * OpenGL, so a model can be loaded and inspected on any thread.
 */
class SceneModel(
    /** The node tree, ordered so that a parent always comes before its children. */
    val nodes: List<SceneNode>,
    val meshes: List<SceneMesh>,
    val materials: List<SceneMaterial>,
    val textures: List<SceneTexture> = emptyList(),
    val skins: List<SceneSkin> = emptyList(),
    val animations: List<SceneAnimation> = emptyList(),
    /** "glb", "gltf", "fbx", "obj": shown in the viewer's status line. */
    val format: String = "glb",
) {
    val totalVertices: Int get() = meshes.sumOf { it.vertexCount }
    val totalTriangles: Int get() = meshes.sumOf {
        if (it.indices != null) it.indices.size / 3 else it.vertexCount / 3
    }
    val jointCount: Int get() = skins.sumOf { it.joints.size }
    val isRigged: Boolean get() = skins.isNotEmpty()
}

/**
 * One node of the tree. Its place relative to [parent] is either a ready-made
 * [matrix] or translation, rotation (quaternion x, y, z, w) and scale;
 * animation only ever drives the latter three.
 */
class SceneNode(
    val name: String,
    /** Index into [SceneModel.nodes], or -1 for a root. */
    val parent: Int,
    val translation: FloatArray = floatArrayOf(0f, 0f, 0f),
    val rotation: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
    val scale: FloatArray = floatArrayOf(1f, 1f, 1f),
    val matrix: FloatArray? = null,
)

/** One drawable primitive: triangles with a single material. */
class SceneMesh(
    val positions: FloatArray,
    val normals: FloatArray?,
    val texCoords: FloatArray?,
    val indices: IntArray?,
    val materialIndex: Int,
    val vertexCount: Int,
    /**
     * The owning node's global transform at rest (column-major, GL order).
     * Positions are in node-local space, so an unskinned primitive must be
     * drawn with this as its model matrix: drawing everything at identity
     * collapses the hierarchy, which is very visible on models that scale
     * parts (a 0.001-scaled grouping anchor renders as a full-size box
     * swallowing the whole model).
     */
    val transform: FloatArray = Mat4.identity(),
    /** Index into [SceneModel.nodes] of the node that places this primitive, or -1. */
    val nodeIndex: Int = -1,
    /** Index into [SceneModel.skins], or -1 when the primitive is not skinned. */
    val skinIndex: Int = -1,
    /** Four joint numbers per vertex, each an index into the skin's joint list. */
    val joints: IntArray? = null,
    /** Four weights per vertex, summing to one. */
    val weights: FloatArray? = null,
) {
    val isSkinned: Boolean get() = skinIndex >= 0 && joints != null && weights != null

    /** [x,y,z] taken through [transform]. */
    fun transformPoint(x: Float, y: Float, z: Float): FloatArray {
        val m = transform
        return floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14],
        )
    }
}

enum class AlphaMode { OPAQUE, MASK, BLEND }

class SceneMaterial(
    val name: String,
    val baseColor: FloatArray,  // RGBA, linear
    val metallic: Float,
    val roughness: Float,
    val emissive: FloatArray,   // RGB, linear
    /** Indices into [SceneModel.textures], or -1. */
    val baseColorTexture: Int = -1,
    val normalTexture: Int = -1,
    val metallicRoughnessTexture: Int = -1,
    val emissiveTexture: Int = -1,
    val alphaMode: AlphaMode = AlphaMode.OPAQUE,
    val alphaCutoff: Float = 0.5f,
    val doubleSided: Boolean = false,
) {
    fun named(newName: String) = SceneMaterial(
        newName, baseColor, metallic, roughness, emissive, baseColorTexture, normalTexture,
        metallicRoughnessTexture, emissiveTexture, alphaMode, alphaCutoff, doubleSided,
    )

    companion object {
        val DEFAULT = SceneMaterial(
            name = "Default",
            baseColor = floatArrayOf(0.8f, 0.8f, 0.8f, 1.0f),
            metallic = 0.0f,
            roughness = 0.5f,
            emissive = floatArrayOf(0.0f, 0.0f, 0.0f)
        )
    }
}

/**
 * An image a material samples, still encoded (PNG, JPEG, WebP): either the
 * bytes embedded in the model file or a file beside it. Decoding waits until
 * the renderer wants it, at the size it wants.
 */
class SceneTexture(
    val name: String,
    val encoded: ByteArray?,
    val file: Path? = null,
    val repeatS: Boolean = true,
    val repeatT: Boolean = true,
) {
    fun bytes(): ByteArray? = encoded ?: file?.let { runCatching { it.toFile().readBytes() }.getOrNull() }
}

/** A skeleton: the joint nodes and, for each, the matrix taking a bind-pose vertex into that joint's space. */
class SceneSkin(
    /** Indices into [SceneModel.nodes]. */
    val joints: IntArray,
    /** 16 floats per joint, column-major. */
    val inverseBind: FloatArray,
)

enum class ChannelPath(val components: Int) { TRANSLATION(3), ROTATION(4), SCALE(3) }

enum class Interpolation { STEP, LINEAR, CUBICSPLINE }

/** The keys driving one property of one node. */
class AnimationChannel(
    val node: Int,
    val path: ChannelPath,
    /** Key times in seconds, ascending. */
    val times: FloatArray,
    /**
     * [ChannelPath.components] floats per key; for [Interpolation.CUBICSPLINE]
     * three times that (in-tangent, value, out-tangent), as glTF stores it.
     */
    val values: FloatArray,
    val interpolation: Interpolation = Interpolation.LINEAR,
)

class SceneAnimation(
    val name: String,
    /** Seconds from the first key to the last. */
    val duration: Float,
    val channels: List<AnimationChannel>,
)
