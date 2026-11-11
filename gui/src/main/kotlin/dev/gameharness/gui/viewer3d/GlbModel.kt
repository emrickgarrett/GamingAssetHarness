package dev.gameharness.gui.viewer3d

import de.javagl.jgltf.model.AccessorByteData
import de.javagl.jgltf.model.AccessorFloatData
import de.javagl.jgltf.model.AccessorIntData
import de.javagl.jgltf.model.AccessorShortData
import de.javagl.jgltf.model.MeshModel
import de.javagl.jgltf.model.io.GltfModelReader
import de.javagl.jgltf.model.v2.MaterialModelV2
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("GlbModel")

private fun identityMatrix() = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)

/**
 * Loaded GLB model with mesh geometry and material data ready for OpenGL rendering.
 */
data class GlbModel(
    val meshes: List<GlbMesh>,
    val materials: List<GlbMaterial>
) {
    val totalVertices: Int get() = meshes.sumOf { it.vertexCount }
    val totalTriangles: Int get() = meshes.sumOf {
        if (it.indices != null) it.indices.size / 3 else it.vertexCount / 3
    }
}

data class GlbMesh(
    val positions: FloatArray,
    val normals: FloatArray?,
    val texCoords: FloatArray?,
    val indices: IntArray?,
    val materialIndex: Int,
    val vertexCount: Int,
    /**
     * The owning node's global transform (column-major, GL order). Positions are
     * in node-local space, so this must be fed to the shader as the model matrix —
     * drawing everything with identity collapses the hierarchy, which is very
     * visible on models that scale parts (a 0.001-scaled grouping anchor renders
     * as a full-size box swallowing the whole model).
     */
    val transform: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    ),
) {
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

data class GlbMaterial(
    val name: String,
    val baseColor: FloatArray,  // RGBA
    val metallic: Float,
    val roughness: Float,
    val emissive: FloatArray    // RGB
) {
    companion object {
        val DEFAULT = GlbMaterial(
            name = "Default",
            baseColor = floatArrayOf(0.8f, 0.8f, 0.8f, 1.0f),
            metallic = 0.0f,
            roughness = 0.5f,
            emissive = floatArrayOf(0.0f, 0.0f, 0.0f)
        )
    }
}

/**
 * Loads a GLB file and extracts mesh/material data for OpenGL rendering.
 */
fun loadGlb(path: Path): GlbModel {
    val reader = GltfModelReader()
    val gltfModel = reader.read(path)

    // Extract materials
    val materials = mutableListOf<GlbMaterial>()
    for (matModel in gltfModel.materialModels) {
        val mat = matModel as? MaterialModelV2
        if (mat != null) {
            materials.add(
                GlbMaterial(
                    name = mat.name ?: "Material ${materials.size}",
                    baseColor = mat.baseColorFactor ?: floatArrayOf(1f, 1f, 1f, 1f),
                    metallic = mat.metallicFactor,
                    roughness = mat.roughnessFactor,
                    emissive = mat.emissiveFactor ?: floatArrayOf(0f, 0f, 0f)
                )
            )
        } else {
            materials.add(GlbMaterial.DEFAULT.copy(name = "Material ${materials.size}"))
        }
    }
    if (materials.isEmpty()) {
        materials.add(GlbMaterial.DEFAULT)
    }

    // Extract meshes by walking the NODES, not gltfModel.meshModels: a mesh is
    // only positioned by the node that references it, and one mesh may be
    // instanced by several nodes at different transforms.
    val meshes = mutableListOf<GlbMesh>()
    val placements = mutableListOf<Pair<MeshModel, FloatArray>>()
    val placed = mutableSetOf<MeshModel>()
    for (nodeModel in gltfModel.nodeModels) {
        val nodeMeshes = nodeModel.meshModels
        if (nodeMeshes.isEmpty()) continue
        val global = FloatArray(16)
        nodeModel.computeGlobalTransform(global)
        for (meshModel in nodeMeshes) {
            placements.add(meshModel to global)
            placed.add(meshModel)
        }
    }
    // Meshes no node references shouldn't exist in a valid glTF, but rendering
    // them at identity beats silently dropping geometry.
    for (meshModel in gltfModel.meshModels) {
        if (meshModel !in placed) {
            logger.warn("Mesh is not referenced by any node; drawing it untransformed")
            placements.add(meshModel to identityMatrix())
        }
    }

    for ((meshModel, nodeTransform) in placements) {
        for (primitive in meshModel.meshPrimitiveModels) {
            try {
                val posAccessor = primitive.attributes["POSITION"] ?: continue
                val posData = posAccessor.accessorData as? AccessorFloatData ?: continue
                val vertexCount = posAccessor.count

                // Positions (vec3)
                val positions = FloatArray(vertexCount * 3)
                for (i in 0 until vertexCount) {
                    positions[i * 3] = posData.get(i, 0)
                    positions[i * 3 + 1] = posData.get(i, 1)
                    positions[i * 3 + 2] = posData.get(i, 2)
                }

                // Normals (vec3, optional)
                val normals = primitive.attributes["NORMAL"]?.let { acc ->
                    val data = acc.accessorData as? AccessorFloatData ?: return@let null
                    FloatArray(vertexCount * 3) { idx ->
                        data.get(idx / 3, idx % 3)
                    }
                }

                // Texture coordinates (vec2, optional)
                val texCoords = primitive.attributes["TEXCOORD_0"]?.let { acc ->
                    val data = acc.accessorData as? AccessorFloatData ?: return@let null
                    FloatArray(vertexCount * 2) { idx ->
                        data.get(idx / 2, idx % 2)
                    }
                }

                // Indices (optional). glTF allows UNSIGNED_BYTE/SHORT/INT here and
                // exporters overwhelmingly emit UNSIGNED_SHORT, so accepting only
                // AccessorIntData silently drops the index buffer — the renderer
                // then falls back to glDrawArrays and shreds the mesh into slivers.
                // getInt() is the type's own unsigned-aware component read.
                val indices = primitive.indices?.let { acc ->
                    when (val data = acc.accessorData) {
                        // UNSIGNED_INT: the raw bits are what GL_UNSIGNED_INT wants
                        is AccessorIntData -> IntArray(acc.count) { idx -> data.get(idx, 0) }
                        is AccessorShortData -> IntArray(acc.count) { idx -> data.getInt(idx, 0) }
                        is AccessorByteData -> IntArray(acc.count) { idx -> data.get(idx, 0).toInt() and 0xFF }
                        else -> {
                            logger.warn("Unsupported index accessor type: ${data?.javaClass?.simpleName}")
                            null
                        }
                    }
                }

                // Material index
                val matModel = primitive.materialModel
                val matIndex = if (matModel != null) {
                    gltfModel.materialModels.indexOf(matModel).coerceAtLeast(0)
                } else {
                    0
                }

                meshes.add(
                    GlbMesh(
                        positions = positions,
                        normals = normals,
                        texCoords = texCoords,
                        indices = indices,
                        materialIndex = matIndex,
                        vertexCount = vertexCount,
                        transform = nodeTransform,
                    )
                )
            } catch (e: Exception) {
                logger.warn("Failed to load mesh primitive: ${e.message}")
            }
        }
    }

    logger.info("Loaded GLB: ${meshes.size} meshes, ${materials.size} materials, " +
            "${meshes.sumOf { it.vertexCount }} vertices")
    return GlbModel(meshes, materials)
}
