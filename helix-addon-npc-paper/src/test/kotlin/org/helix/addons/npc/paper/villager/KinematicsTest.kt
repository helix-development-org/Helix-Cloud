package org.helix.addons.npc.paper.villager

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KinematicsTest {
    private val spec = RigSpec(
        listOf(
            RigPart("root", null, listOf(8.0, 0.0, 8.0)),
            RigPart("body", "root", listOf(8.0, 0.0, 8.0)),
            RigPart("head", "body", listOf(8.0, 20.0, 8.0), scale = 2.0),
            RigPart("nose", "head", listOf(8.0, 26.0, 2.0)),
            RigPart("pivot_only", "head", listOf(8.0, 30.0, 8.0), model = false),
        ),
    )

    private fun assertVec(expected: Vec3, actual: Vec3) {
        assertTrue(
            abs(expected.x - actual.x) < 1e-9 && abs(expected.y - actual.y) < 1e-9 && abs(expected.z - actual.z) < 1e-9,
            "expected $expected, got $actual",
        )
    }

    @Test
    fun `rest pose places each pivot relative to the feet, flipped into the display frame`() {
        val pose = Kinematics.solve(spec, emptyMap())
        assertVec(Vec3.ZERO, pose.getValue("root").translation)
        assertVec(Vec3(0.0, 20.0 / 16, 0.0), pose.getValue("head").translation)
        // nose is 6 px towards -Z (the face) in model space -> +Z in the display frame
        assertVec(Vec3(0.0, 26.0 / 16, 6.0 / 16), pose.getValue("nose").translation)
        assertVec(Vec3(2.0, 2.0, 2.0), pose.getValue("head").scale)
        assertTrue("pivot_only" !in pose)
    }

    @Test
    fun `parent rotation carries the child pivot around`() {
        // head turned 90 degrees about +Y: the face (-Z) turns to -X in model space
        val pose = Kinematics.solve(spec, mapOf("head" to PartAnim(rotation = Quat.rotY(PI / 2))))
        // model-space nose offset (0, 6, -6) -> (-6, 6, 0); display frame flips x and z
        assertVec(Vec3(6.0 / 16, 26.0 / 16, 0.0), pose.getValue("nose").translation)
        val r = pose.getValue("nose").rotation
        // the display-frame rotation is the same 90 degrees about +Y (flip keeps Y axis)
        assertVec(Vec3(1.0, 0.0, 0.0), r.rotate(Vec3(0.0, 0.0, 1.0)))
    }

    @Test
    fun `body offset moves all children`() {
        val pose = Kinematics.solve(spec, mapOf("body" to PartAnim(offset = Vec3(0.0, 0.35, 0.0))))
        assertVec(Vec3(0.0, 0.35 / 16, 0.0), pose.getValue("body").translation)
        assertVec(Vec3(0.0, 26.35 / 16, 6.0 / 16), pose.getValue("nose").translation)
        assertVec(Vec3.ZERO, pose.getValue("root").translation)
    }

    @Test
    fun `look angles face a player in front, to the left and above`() {
        // yaw 0 faces +Z (south): a target due south is straight ahead
        val (ahead, flat) = Kinematics.lookAngles(0.0, 0.0, 0.0, 3.0)
        assertEquals(0.0, ahead, 1e-9)
        assertEquals(0.0, flat, 1e-9)
        // facing south, east (+X) is on the villager's left; the head turns the face to model -X (positive yaw)
        assertEquals(PI / 2, Kinematics.lookAngles(0.0, 3.0, 0.0, 0.0).first, 1e-9)
        // yaw 90 faces west (-X): a target due west is ahead
        assertEquals(0.0, Kinematics.lookAngles(90.0, -3.0, 0.0, 0.0).first, 1e-9)
        assertEquals(PI / 4, Kinematics.lookAngles(0.0, 0.0, 2.0, 2.0).second, 1e-9)
    }

    @Test
    fun `turned head in model space really faces the target in the world`() {
        // world = Ry(-yaw) * Ry(pi) * model; the model face is -Z
        for (yaw in listOf(0.0, 37.0, 90.0, -120.0)) {
            val target = Vec3(1.5, 0.0, -2.0)
            val (headYaw, _) = Kinematics.lookAngles(yaw, target.x, target.y, target.z)
            val face = Quat.rotY(Math.toRadians(-yaw)) * Quat.rotY(PI) * Quat.rotY(headYaw)
            val dir = face.rotate(Vec3(0.0, 0.0, -1.0))
            val len = Math.sqrt(target.x * target.x + target.z * target.z)
            assertVec(Vec3(target.x / len, 0.0, target.z / len), dir)
        }
    }

    @Test
    fun `euler rest rotation matches single-axis rotation`() {
        val q = Quat.eulerZYX(listOf(0.0, 90.0, 0.0))
        assertVec(Vec3(1.0, 0.0, 0.0), q.rotate(Vec3(0.0, 0.0, 1.0)))
    }
}
