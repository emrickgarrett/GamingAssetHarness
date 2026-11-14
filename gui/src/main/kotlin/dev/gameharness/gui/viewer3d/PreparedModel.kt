package dev.gameharness.gui.viewer3d

import java.nio.file.Path
import kotlin.math.max

/**
 * A model read from disk with everything the renderer needs worked out off the
 * render thread: textures decoded, and the box the camera should frame.
 */
class PreparedModel(
    val model: SceneModel,
    /** One entry per [SceneModel.textures]; null where a texture is unused or could not be decoded. */
    val textures: List<DecodedTexture?>,
    /** [minX, minY, minZ, maxX, maxY, maxZ] the model occupies, through the whole of [defaultAnimation] if it has one. */
    val bounds: FloatArray,
    /** The clip to show first, or -1 for a model without animation. */
    val defaultAnimation: Int,
    /** Pixel size of the largest texture as stored in the file, or null without textures. */
    val largestTexture: Pair<Int, Int>?,
) {
    /** Releases textures that were never handed to a renderer. */
    fun discard() = textures.forEach { it?.close() }

    companion object {
        /**
         * Loads and prepares the model at [path]. Textures are decoded no
         * larger than [maxTextureEdge] on their longest side where the format
         * allows a cheap smaller decode. Blocking: call from a background thread.
         */
        fun load(path: Path, maxTextureEdge: Int = 4096): PreparedModel {
            val model = loadModel(path)
            val used = HashSet<Int>()
            for (material in model.materials) {
                used.add(material.baseColorTexture)
                used.add(material.normalTexture)
                used.add(material.metallicRoughnessTexture)
                used.add(material.emissiveTexture)
            }
            var largest: Pair<Int, Int>? = null
            val textures = model.textures.mapIndexed { index, texture ->
                if (index !in used) return@mapIndexed null
                val bytes = texture.bytes() ?: return@mapIndexed null
                DecodedTexture.decode(bytes, maxTextureEdge)?.also { decoded ->
                    val current = largest
                    if (current == null || decoded.width * decoded.height > current.first * current.second) {
                        largest = decoded.width to decoded.height
                    }
                }
            }
            val defaultAnimation = if (model.animations.isNotEmpty()) 0 else -1
            return PreparedModel(
                model, textures, framingBounds(model, model.animations.getOrNull(defaultAnimation)),
                defaultAnimation, largest,
            )
        }

        /**
         * The box to point the camera at: the rest pose, or for an animated
         * model the union of the clip sampled at five moments, so a character
         * that swings an arm or falls over stays in frame.
         */
        fun framingBounds(model: SceneModel, animation: SceneAnimation?): FloatArray {
            val pose = ScenePose(model)
            val stride = max(1, model.totalVertices / 20_000)
            var box: FloatArray? = null
            if (animation == null) {
                box = pose.bounds(stride)
            } else {
                for (k in 0..4) {
                    pose.apply(animation, animation.duration * k / 4f)
                    val b = pose.bounds(stride) ?: continue
                    val current = box
                    if (current == null) {
                        box = b
                    } else {
                        for (i in 0..2) if (b[i] < current[i]) current[i] = b[i]
                        for (i in 3..5) if (b[i] > current[i]) current[i] = b[i]
                    }
                }
            }
            return box ?: floatArrayOf(-1f, -1f, -1f, 1f, 1f, 1f)
        }
    }
}
