package dev.gameharness.gui.viewer3d

import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The arithmetic behind a playing animation: sampling keys, walking the node
 * tree, and the joint matrices a skinned mesh is deformed by.
 *
 * A mistake here still draws a model, only bent the wrong way or frozen, so
 * each case checks numbers that were worked out by hand.
 */
class ScenePoseTest {

    private val eps = 1e-4f

    @Test
    fun `decompose undoes compose`() {
        // 90 degrees about Y, scaled unevenly, moved
        val h = sqrt(0.5f)
        val t = floatArrayOf(1f, -2f, 3f)
        val r = floatArrayOf(0f, h, 0f, h)
        val s = floatArrayOf(2f, 0.5f, 3f)
        val m = FloatArray(16)
        Mat4.compose(t, 0, r, 0, s, 0, m)

        val t2 = FloatArray(3); val r2 = FloatArray(4); val s2 = FloatArray(3)
        Mat4.decompose(m, t2, r2, s2)
        val back = FloatArray(16)
        Mat4.compose(t2, 0, r2, 0, s2, 0, back)

        for (i in 0 until 16) assertEquals(m[i], back[i], eps, "element $i")
        for (i in 0 until 3) assertEquals(s[i], s2[i], eps, "scale $i")
    }

    @Test
    fun `a translation is interpolated between keys and held outside them`() {
        val clip = SceneAnimation(
            "slide", 2f,
            listOf(AnimationChannel(0, ChannelPath.TRANSLATION, floatArrayOf(0f, 2f), floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f))),
        )
        val pose = ScenePose(SceneModel(listOf(SceneNode("a", -1)), emptyList(), emptyList()))

        pose.apply(clip, 0.5f)
        assertEquals(2.5f, pose.globals[12], eps)
        pose.apply(clip, -1f)
        assertEquals(0f, pose.globals[12], eps, "before the first key")
        pose.apply(clip, 99f)
        assertEquals(10f, pose.globals[12], eps, "after the last key")
        pose.apply(null, 0f)
        assertEquals(0f, pose.globals[12], eps, "no clip is the rest pose")
    }

    @Test
    fun `a rotation takes the short way round between keys`() {
        // identity to 90 degrees about Y; halfway must be 45 degrees, unit length
        val h = sqrt(0.5f)
        val clip = SceneAnimation(
            "turn", 1f,
            listOf(AnimationChannel(0, ChannelPath.ROTATION, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 1f, 0f, -h, 0f, -h))),
        )
        val pose = ScenePose(SceneModel(listOf(SceneNode("a", -1)), emptyList(), emptyList()))
        pose.apply(clip, 0.5f)

        // +X rotated 45 degrees about Y lands on (cos 45, 0, -sin 45): the first column.
        // The second key is written as -q, the same rotation; going the long way would give -45.
        val a = Math.toRadians(45.0)
        assertEquals(cos(a).toFloat(), pose.globals[0], eps)
        assertEquals(-sin(a).toFloat(), pose.globals[2], eps)
    }

    @Test
    fun `step and cubic spline channels are read as glTF stores them`() {
        val step = SceneAnimation(
            "step", 1f,
            listOf(AnimationChannel(0, ChannelPath.TRANSLATION, floatArrayOf(0f, 1f), floatArrayOf(1f, 0f, 0f, 5f, 0f, 0f), Interpolation.STEP)),
        )
        // per key: in-tangent, value, out-tangent. Zero tangents ease in and out, so halfway is the midpoint.
        val cubic = SceneAnimation(
            "cubic", 1f,
            listOf(
                AnimationChannel(
                    0, ChannelPath.TRANSLATION, floatArrayOf(0f, 1f),
                    floatArrayOf(0f, 0f, 0f, /**/ 2f, 0f, 0f, /**/ 0f, 0f, 0f, /* key 2 */ 0f, 0f, 0f, /**/ 6f, 0f, 0f, /**/ 0f, 0f, 0f),
                    Interpolation.CUBICSPLINE,
                )
            ),
        )
        val pose = ScenePose(SceneModel(listOf(SceneNode("a", -1)), emptyList(), emptyList()))

        pose.apply(step, 0.9f)
        assertEquals(1f, pose.globals[12], eps, "a step channel holds the earlier key")
        pose.apply(cubic, 0.5f)
        assertEquals(4f, pose.globals[12], eps)
        pose.apply(cubic, 0.25f)
        assertEquals(2f + 4f * (3 * 0.0625f - 2 * 0.015625f), pose.globals[12], eps, "eased, not linear")
    }

    @Test
    fun `a child moves with its parent`() {
        val nodes = listOf(
            SceneNode("parent", -1, translation = floatArrayOf(0f, 1f, 0f)),
            SceneNode("child", 0, translation = floatArrayOf(2f, 0f, 0f)),
        )
        val clip = SceneAnimation(
            "lift", 1f,
            listOf(AnimationChannel(0, ChannelPath.TRANSLATION, floatArrayOf(0f, 1f), floatArrayOf(0f, 1f, 0f, 0f, 5f, 0f))),
        )
        val pose = ScenePose(SceneModel(nodes, emptyList(), emptyList()))
        assertEquals(1f, pose.globals[16 + 13], eps, "rest")

        pose.apply(clip, 1f)
        assertEquals(2f, pose.globals[16 + 12], eps)
        assertEquals(5f, pose.globals[16 + 13], eps)
    }

    /** One joint a unit above the origin, and a vertex a unit above the joint, bound wholly to it. */
    private fun oneBoneModel(): SceneModel {
        val nodes = listOf(SceneNode("joint", -1, translation = floatArrayOf(0f, 1f, 0f)))
        val inverseBind = Mat4.identity().also { it[13] = -1f } // undoes the joint's rest transform
        val mesh = SceneMesh(
            positions = floatArrayOf(0f, 2f, 0f),
            normals = null, texCoords = null, indices = null,
            materialIndex = 0, vertexCount = 1,
            skinIndex = 0,
            joints = intArrayOf(0, 0, 0, 0),
            weights = floatArrayOf(1f, 0f, 0f, 0f),
        )
        val bend = SceneAnimation(
            "bend", 1f,
            // 90 degrees about Z: +Y swings to -X
            listOf(AnimationChannel(0, ChannelPath.ROTATION, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, sqrt(0.5f), sqrt(0.5f)))),
        )
        return SceneModel(nodes, listOf(mesh), listOf(SceneMaterial.DEFAULT), skins = listOf(SceneSkin(intArrayOf(0), inverseBind)), animations = listOf(bend))
    }

    @Test
    fun `at rest a joint matrix leaves its vertices where they were bound`() {
        val pose = ScenePose(oneBoneModel())
        val joint = pose.jointMatrices(0)
        val identity = Mat4.identity()
        for (i in 0 until 16) assertEquals(identity[i], joint[i], eps, "element $i")
    }

    @Test
    fun `a rotated joint carries its vertices round its own origin`() {
        val model = oneBoneModel()
        val pose = ScenePose(model)
        pose.apply(model.animations[0], 1f)

        // The vertex sat one unit above the joint; the joint (at y = 1) turned it to one unit left.
        val box = assertNotNull(pose.bounds())
        assertEquals(-1f, box[0], eps, "x")
        assertEquals(1f, box[1], eps, "y")
        assertEquals(0f, box[2], eps, "z")
    }

    @Test
    fun `the framing box covers the whole clip, not only its first frame`() {
        val model = oneBoneModel()
        val atRest = PreparedModel.framingBounds(model, null)
        val throughClip = PreparedModel.framingBounds(model, model.animations[0])

        assertEquals(0f, atRest[0], eps, "at rest the vertex is on the axis")
        assertEquals(-1f, throughClip[0], eps, "by the end of the clip it has swung to x = -1")
        assertEquals(2f, throughClip[4], eps, "and it started at y = 2")
    }
}
