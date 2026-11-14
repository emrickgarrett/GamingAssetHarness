package dev.gameharness.gui.viewer3d

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where every node of a [SceneModel] is at one moment: the rest pose, or an
 * animation clip sampled at a time.
 *
 * [apply] fills [globals] (one column-major 4x4 per node). From those come the
 * model matrix of each unskinned primitive ([meshMatrix]) and the joint
 * matrices a skinned one is deformed by ([jointMatrices]). Plain arithmetic on
 * float arrays, no OpenGL: the renderer uploads the results, and the tests
 * check them directly.
 *
 * Not thread-safe; each render thread keeps its own.
 */
class ScenePose(val model: SceneModel) {

    private val count = model.nodes.size
    private val translations = FloatArray(count * 3)
    private val rotations = FloatArray(count * 4)
    private val scales = FloatArray(count * 3)
    private val animated = BooleanArray(count)
    private val local = FloatArray(16)

    /** Global transform of node i at `i * 16`. */
    val globals = FloatArray(count * 16)

    init {
        apply(null, 0f)
    }

    /**
     * Poses the model: every node back to rest, then [animation]'s channels
     * sampled at [time] seconds (clamped to the clip), then the globals.
     * A null animation is the rest pose.
     */
    fun apply(animation: SceneAnimation?, time: Float) {
        for (i in 0 until count) {
            val node = model.nodes[i]
            node.translation.copyInto(translations, i * 3)
            node.rotation.copyInto(rotations, i * 4)
            node.scale.copyInto(scales, i * 3)
            animated[i] = false
        }
        if (animation != null) {
            for (channel in animation.channels) {
                if (channel.node !in 0 until count || channel.times.isEmpty()) continue
                animated[channel.node] = true
                when (channel.path) {
                    ChannelPath.TRANSLATION -> sample(channel, time, translations, channel.node * 3)
                    ChannelPath.SCALE -> sample(channel, time, scales, channel.node * 3)
                    ChannelPath.ROTATION -> sample(channel, time, rotations, channel.node * 4)
                }
            }
        }
        for (i in 0 until count) {
            val node = model.nodes[i]
            val matrix = node.matrix
            if (matrix != null && !animated[i]) {
                matrix.copyInto(local)
            } else {
                Mat4.compose(translations, i * 3, rotations, i * 4, scales, i * 3, local)
            }
            if (node.parent < 0) {
                local.copyInto(globals, i * 16)
            } else {
                // parents come first in the list, so theirs is already final
                Mat4.multiplyInto(globals, node.parent * 16, local, 0, globals, i * 16)
            }
        }
    }

    /** The model matrix of an unskinned [mesh] in the current pose. */
    fun meshMatrix(mesh: SceneMesh, out: FloatArray = FloatArray(16)): FloatArray {
        if (mesh.nodeIndex in 0 until count) {
            System.arraycopy(globals, mesh.nodeIndex * 16, out, 0, 16)
        } else {
            mesh.transform.copyInto(out)
        }
        return out
    }

    /**
     * The matrices that take a bind-pose vertex of skin [skinIndex] to where the
     * current pose puts it, 16 floats per joint: joint global x inverse bind.
     */
    fun jointMatrices(skinIndex: Int, out: FloatArray = FloatArray(model.skins[skinIndex].joints.size * 16)): FloatArray {
        val skin = model.skins[skinIndex]
        for (j in skin.joints.indices) {
            val node = skin.joints[j]
            if (node in 0 until count) {
                Mat4.multiplyInto(globals, node * 16, skin.inverseBind, j * 16, out, j * 16)
            } else {
                Mat4.identity().copyInto(out, j * 16)
            }
        }
        return out
    }

    /**
     * [minX, minY, minZ, maxX, maxY, maxZ] of the model in the current pose,
     * skinning included, looking at every [stride]-th vertex. Null when the
     * model has no vertices.
     */
    fun bounds(stride: Int = 1): FloatArray? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        val m = FloatArray(16)
        val step = stride.coerceAtLeast(1)
        val jointCache = HashMap<Int, FloatArray>()

        for (mesh in model.meshes) {
            val joints = mesh.joints
            val weights = mesh.weights
            val skinned = mesh.isSkinned && mesh.skinIndex < model.skins.size
            val jm = if (skinned) jointCache.getOrPut(mesh.skinIndex) { jointMatrices(mesh.skinIndex) } else null
            if (!skinned) meshMatrix(mesh, m)

            var v = 0
            while (v < mesh.vertexCount) {
                val px = mesh.positions[v * 3]
                val py = mesh.positions[v * 3 + 1]
                val pz = mesh.positions[v * 3 + 2]
                var x = 0f; var y = 0f; var z = 0f
                if (jm != null && joints != null && weights != null) {
                    for (k in 0 until 4) {
                        val w = weights[v * 4 + k]
                        if (w == 0f) continue
                        val o = joints[v * 4 + k] * 16
                        if (o < 0 || o + 15 >= jm.size) continue
                        x += w * (jm[o] * px + jm[o + 4] * py + jm[o + 8] * pz + jm[o + 12])
                        y += w * (jm[o + 1] * px + jm[o + 5] * py + jm[o + 9] * pz + jm[o + 13])
                        z += w * (jm[o + 2] * px + jm[o + 6] * py + jm[o + 10] * pz + jm[o + 14])
                    }
                } else {
                    x = m[0] * px + m[4] * py + m[8] * pz + m[12]
                    y = m[1] * px + m[5] * py + m[9] * pz + m[13]
                    z = m[2] * px + m[6] * py + m[10] * pz + m[14]
                }
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                v += step
            }
        }
        if (minX > maxX) return null
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun sample(channel: AnimationChannel, time: Float, out: FloatArray, offset: Int) {
        val times = channel.times
        val n = channel.path.components
        val cubic = channel.interpolation == Interpolation.CUBICSPLINE
        val stride = if (cubic) n * 3 else n
        val valueAt = if (cubic) n else 0 // within a key: in-tangent, value, out-tangent
        if (channel.values.size < times.size * stride) return

        val last = times.size - 1
        if (last == 0 || time <= times[0]) {
            System.arraycopy(channel.values, valueAt, out, offset, n)
        } else if (time >= times[last]) {
            System.arraycopy(channel.values, last * stride + valueAt, out, offset, n)
        } else {
            // the key at or before `time`
            var lo = 0
            var hi = last
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (times[mid] <= time) lo = mid else hi = mid
            }
            val dt = times[hi] - times[lo]
            val u = if (dt > 0f) (time - times[lo]) / dt else 0f
            val a = lo * stride + valueAt
            val b = hi * stride + valueAt
            val values = channel.values
            when (channel.interpolation) {
                Interpolation.STEP -> System.arraycopy(values, a, out, offset, n)
                Interpolation.LINEAR ->
                    if (channel.path == ChannelPath.ROTATION) {
                        slerp(values, a, values, b, u, out, offset)
                    } else {
                        for (c in 0 until n) out[offset + c] = values[a + c] + (values[b + c] - values[a + c]) * u
                    }
                Interpolation.CUBICSPLINE -> {
                    val u2 = u * u
                    val u3 = u2 * u
                    val h00 = 2 * u3 - 3 * u2 + 1
                    val h10 = (u3 - 2 * u2 + u) * dt
                    val h01 = -2 * u3 + 3 * u2
                    val h11 = (u3 - u2) * dt
                    val outTangent = lo * stride + 2 * n
                    val inTangent = hi * stride
                    for (c in 0 until n) {
                        out[offset + c] = h00 * values[a + c] + h10 * values[outTangent + c] +
                            h01 * values[b + c] + h11 * values[inTangent + c]
                    }
                }
            }
        }
        if (channel.path == ChannelPath.ROTATION) normalizeQuat(out, offset)
    }

    private fun slerp(a: FloatArray, ao: Int, b: FloatArray, bo: Int, u: Float, out: FloatArray, oo: Int) {
        var dot = a[ao] * b[bo] + a[ao + 1] * b[bo + 1] + a[ao + 2] * b[bo + 2] + a[ao + 3] * b[bo + 3]
        // q and -q are the same rotation; go the short way round
        val sign = if (dot < 0f) -1f else 1f
        dot = abs(dot)
        val wa: Float
        val wb: Float
        if (dot > 0.9995f) {
            wa = 1f - u
            wb = u
        } else {
            val theta = acos(dot)
            val s = sin(theta)
            wa = sin((1f - u) * theta) / s
            wb = sin(u * theta) / s
        }
        for (c in 0 until 4) out[oo + c] = wa * a[ao + c] + wb * sign * b[bo + c]
    }

    private fun normalizeQuat(q: FloatArray, o: Int) {
        val len = sqrt(q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3])
        if (len > 0f) {
            q[o] /= len; q[o + 1] /= len; q[o + 2] /= len; q[o + 3] /= len
        } else {
            q[o] = 0f; q[o + 1] = 0f; q[o + 2] = 0f; q[o + 3] = 1f
        }
    }
}
