package dev.gameharness.gui.viewer3d

import kotlin.math.*

/**
 * Orbit camera for 3D model viewing.
 *
 * Circles a target point at an azimuth, elevation and distance. All limits and
 * the clip planes follow the size of the model it was last fitted to, so a
 * 2-unit prop and a 180-unit character rig both zoom, pan and clip sensibly.
 */
class OrbitCamera {
    var azimuth: Float = DEFAULT_AZIMUTH      // degrees, horizontal rotation
    var elevation: Float = DEFAULT_ELEVATION  // degrees, vertical rotation (clamped -89..89)
    var distance: Float = 3.0f                // distance from target
    var targetX: Float = 0f
    var targetY: Float = 0f
    var targetZ: Float = 0f

    /** Half the diagonal of the bounds last passed to [fitToBounds]. */
    var sceneRadius: Float = 1f
        private set

    private val minDistance get() = sceneRadius * 0.05f
    private val maxDistance get() = sceneRadius * 40f

    /** Orbits by a drag of ([dx], [dy]) pixels: the model follows the pointer. */
    fun rotate(dx: Float, dy: Float) {
        azimuth -= dx * 0.4f
        elevation = (elevation + dy * 0.4f).coerceIn(-89f, 89f)
    }

    /** Positive [notches] move closer, a tenth of the way per notch. */
    fun zoom(notches: Float) {
        distance = (distance * 0.9f.pow(notches)).coerceIn(minDistance, maxDistance)
    }

    /**
     * Slides the target in the view plane by a drag of ([dx], [dy]) pixels, so
     * that the point under the pointer stays under it. [viewportHeight] is in
     * the same pixels.
     */
    fun pan(dx: Float, dy: Float, viewportHeight: Float) {
        val unitsPerPixel = 2f * distance * tan(Math.toRadians(FOV_DEGREES / 2.0)).toFloat() /
            viewportHeight.coerceAtLeast(1f)
        val az = Math.toRadians(azimuth.toDouble())
        val el = Math.toRadians(elevation.toDouble())
        val rightX = cos(az).toFloat()
        val rightZ = -sin(az).toFloat()
        val upX = (-sin(az) * sin(el)).toFloat()
        val upY = cos(el).toFloat()
        val upZ = (-cos(az) * sin(el)).toFloat()
        val sx = -dx * unitsPerPixel
        val sy = dy * unitsPerPixel
        targetX += rightX * sx + upX * sy
        targetY += upY * sy
        targetZ += rightZ * sx + upZ * sy
    }

    fun reset() {
        azimuth = DEFAULT_AZIMUTH
        elevation = DEFAULT_ELEVATION
        distance = 3.0f
        targetX = 0f
        targetY = 0f
        targetZ = 0f
    }

    /**
     * Aims at the centre of the given box from far enough away that all of it
     * is in view, for a viewport of the given [aspect] (width / height).
     */
    fun fitToBounds(
        minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float,
        aspect: Float = 1f,
    ) {
        targetX = (minX + maxX) / 2f
        targetY = (minY + maxY) / 2f
        targetZ = (minZ + maxZ) / 2f
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        val radius = sqrt(dx * dx + dy * dy + dz * dz) / 2f
        sceneRadius = if (radius > 0f && radius.isFinite()) radius else 1f
        // the narrower of the two fields of view decides
        val halfFov = Math.toRadians(FOV_DEGREES / 2.0)
        val half = if (aspect < 1f) atan(tan(halfFov) * aspect) else halfFov
        distance = (sceneRadius / sin(half) * 0.92).toFloat().coerceIn(minDistance, maxDistance)
    }

    fun copyFrom(other: OrbitCamera) {
        azimuth = other.azimuth
        elevation = other.elevation
        distance = other.distance
        targetX = other.targetX
        targetY = other.targetY
        targetZ = other.targetZ
        sceneRadius = other.sceneRadius
    }

    fun eyePosition(): FloatArray {
        val elevRad = Math.toRadians(elevation.toDouble())
        val azimRad = Math.toRadians(azimuth.toDouble())
        return floatArrayOf(
            targetX + (distance * cos(elevRad) * sin(azimRad)).toFloat(),
            targetY + (distance * sin(elevRad)).toFloat(),
            targetZ + (distance * cos(elevRad) * cos(azimRad)).toFloat()
        )
    }

    fun viewMatrix(): FloatArray = Mat4.lookAt(eyePosition(), floatArrayOf(targetX, targetY, targetZ))

    fun projectionMatrix(aspect: Float): FloatArray {
        val near = max(distance * 0.02f, sceneRadius * 0.002f)
        val far = distance + sceneRadius * 60f
        return Mat4.perspective(FOV_DEGREES, aspect, near, far)
    }

    companion object {
        const val FOV_DEGREES = 40f
        const val DEFAULT_AZIMUTH = 30f
        const val DEFAULT_ELEVATION = 15f
    }
}

/**
 * Minimal 4x4 matrix math for OpenGL camera transforms.
 * Column-major layout matching OpenGL conventions.
 */
object Mat4 {

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    fun perspective(fovDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovDeg / 2.0)).toFloat()
        val nf = 1f / (near - far)
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) * nf, -1f,
            0f, 0f, 2f * far * near * nf, 0f
        )
    }

    fun lookAt(eye: FloatArray, center: FloatArray, up: FloatArray = floatArrayOf(0f, 1f, 0f)): FloatArray {
        val fx = center[0] - eye[0]
        val fy = center[1] - eye[1]
        val fz = center[2] - eye[2]
        val fLen = sqrt(fx * fx + fy * fy + fz * fz)
        val fdx = fx / fLen; val fdy = fy / fLen; val fdz = fz / fLen

        // side = forward x up
        var sx = fdy * up[2] - fdz * up[1]
        var sy = fdz * up[0] - fdx * up[2]
        var sz = fdx * up[1] - fdy * up[0]
        val sLen = sqrt(sx * sx + sy * sy + sz * sz)
        sx /= sLen; sy /= sLen; sz /= sLen

        // recomputed up = side x forward
        val ux = sy * fdz - sz * fdy
        val uy = sz * fdx - sx * fdz
        val uz = sx * fdy - sy * fdx

        return floatArrayOf(
            sx, ux, -fdx, 0f,
            sy, uy, -fdy, 0f,
            sz, uz, -fdz, 0f,
            -(sx * eye[0] + sy * eye[1] + sz * eye[2]),
            -(ux * eye[0] + uy * eye[1] + uz * eye[2]),
            fdx * eye[0] + fdy * eye[1] + fdz * eye[2],
            1f
        )
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        multiplyInto(a, 0, b, 0, r, 0)
        return r
    }

    /**
     * out = a x b, each a 4x4 at an offset into its array. [out] may be the
     * array holding [a] or [b], but not at the same offset as either.
     */
    fun multiplyInto(a: FloatArray, ao: Int, b: FloatArray, bo: Int, out: FloatArray, oo: Int) {
        for (col in 0..3) {
            val b0 = b[bo + col * 4]
            val b1 = b[bo + col * 4 + 1]
            val b2 = b[bo + col * 4 + 2]
            val b3 = b[bo + col * 4 + 3]
            for (row in 0..3) {
                out[oo + col * 4 + row] =
                    a[ao + row] * b0 + a[ao + 4 + row] * b1 + a[ao + 8 + row] * b2 + a[ao + 12 + row] * b3
            }
        }
    }

    /** Translation x rotation (quaternion x, y, z, w) x scale, read at offsets, into [out]. */
    fun compose(t: FloatArray, to: Int, r: FloatArray, ro: Int, s: FloatArray, so: Int, out: FloatArray) {
        val x = r[ro]; val y = r[ro + 1]; val z = r[ro + 2]; val w = r[ro + 3]
        val sx = s[so]; val sy = s[so + 1]; val sz = s[so + 2]
        out[0] = (1f - 2f * (y * y + z * z)) * sx
        out[1] = (2f * (x * y + z * w)) * sx
        out[2] = (2f * (x * z - y * w)) * sx
        out[3] = 0f
        out[4] = (2f * (x * y - z * w)) * sy
        out[5] = (1f - 2f * (x * x + z * z)) * sy
        out[6] = (2f * (y * z + x * w)) * sy
        out[7] = 0f
        out[8] = (2f * (x * z + y * w)) * sz
        out[9] = (2f * (y * z - x * w)) * sz
        out[10] = (1f - 2f * (x * x + y * y)) * sz
        out[11] = 0f
        out[12] = t[to]
        out[13] = t[to + 1]
        out[14] = t[to + 2]
        out[15] = 1f
    }

    /**
     * Splits a column-major transform [m] into translation [t], rotation [r]
     * (quaternion x, y, z, w) and scale [s], the inverse of [compose]. Shear is
     * not represented; a mirrored transform comes out with a negative x scale.
     */
    fun decompose(m: FloatArray, t: FloatArray, r: FloatArray, s: FloatArray) {
        t[0] = m[12]; t[1] = m[13]; t[2] = m[14]

        var sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
        val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
        val determinant = m[0] * (m[5] * m[10] - m[9] * m[6]) -
            m[4] * (m[1] * m[10] - m[9] * m[2]) +
            m[8] * (m[1] * m[6] - m[5] * m[2])
        if (determinant < 0f) sx = -sx
        s[0] = sx; s[1] = sy; s[2] = sz
        if (sx == 0f || sy == 0f || sz == 0f) {
            r[0] = 0f; r[1] = 0f; r[2] = 0f; r[3] = 1f
            return
        }

        // the rotation matrix, entry (row, column)
        val m00 = m[0] / sx; val m10 = m[1] / sx; val m20 = m[2] / sx
        val m01 = m[4] / sy; val m11 = m[5] / sy; val m21 = m[6] / sy
        val m02 = m[8] / sz; val m12 = m[9] / sz; val m22 = m[10] / sz
        val trace = m00 + m11 + m22
        if (trace > 0f) {
            val k = 0.5f / sqrt(trace + 1f)
            r[3] = 0.25f / k
            r[0] = (m21 - m12) * k
            r[1] = (m02 - m20) * k
            r[2] = (m10 - m01) * k
        } else if (m00 > m11 && m00 > m22) {
            val k = 2f * sqrt(1f + m00 - m11 - m22)
            r[3] = (m21 - m12) / k
            r[0] = 0.25f * k
            r[1] = (m01 + m10) / k
            r[2] = (m02 + m20) / k
        } else if (m11 > m22) {
            val k = 2f * sqrt(1f + m11 - m00 - m22)
            r[3] = (m02 - m20) / k
            r[0] = (m01 + m10) / k
            r[1] = 0.25f * k
            r[2] = (m12 + m21) / k
        } else {
            val k = 2f * sqrt(1f + m22 - m00 - m11)
            r[3] = (m10 - m01) / k
            r[0] = (m02 + m20) / k
            r[1] = (m12 + m21) / k
            r[2] = 0.25f * k
        }
    }
}
