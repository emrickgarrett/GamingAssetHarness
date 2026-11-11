package dev.gameharness.gui.viewer3d

import kotlin.math.*

/**
 * Arcball orbit camera for 3D model viewing.
 *
 * Orbits around a target point with adjustable azimuth, elevation, and distance.
 */
class OrbitCamera {
    var azimuth: Float = 45f        // degrees, horizontal rotation
    var elevation: Float = 25f      // degrees, vertical rotation (clamped -89..89)
    var distance: Float = 3.0f      // distance from target
    var targetX: Float = 0f
    var targetY: Float = 0f
    var targetZ: Float = 0f

    private val minDistance = 0.1f
    private val maxDistance = 100f

    fun rotate(dx: Float, dy: Float) {
        azimuth += dx * 0.5f
        elevation = (elevation - dy * 0.5f).coerceIn(-89f, 89f)
    }

    fun zoom(delta: Float) {
        distance = (distance * (1f - delta * 0.1f)).coerceIn(minDistance, maxDistance)
    }

    fun pan(dx: Float, dy: Float) {
        val rad = Math.toRadians(azimuth.toDouble()).toFloat()
        val scale = distance * 0.002f
        targetX += (-cos(rad) * dx - sin(rad) * dy) * scale
        targetZ += (sin(rad) * dx - cos(rad) * dy) * scale
        targetY += dy * scale
    }

    fun reset() {
        azimuth = 45f
        elevation = 25f
        distance = 3.0f
        targetX = 0f
        targetY = 0f
        targetZ = 0f
    }

    /**
     * Fits the camera to show the given bounding box, filling ~80% of the viewport.
     */
    fun fitToBounds(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) {
        targetX = (minX + maxX) / 2f
        targetY = (minY + maxY) / 2f
        targetZ = (minZ + maxZ) / 2f
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        val radius = sqrt(dx * dx + dy * dy + dz * dz) / 2f
        distance = (radius / sin(Math.toRadians(22.5)) * 1.25f).toFloat().coerceIn(minDistance, maxDistance)
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

    fun projectionMatrix(aspect: Float): FloatArray = Mat4.perspective(45f, aspect, 0.01f, 200f)
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
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                r[col * 4 + row] = sum
            }
        }
        return r
    }
}
