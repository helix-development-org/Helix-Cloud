package org.helix.addons.npc.paper.rig

import org.helix.addons.npc.paper.villager.RigPart
import org.helix.addons.npc.paper.villager.RigSpec
import org.helix.addons.npc.paper.villager.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RigPoseTest {
    private val spec = RigSpec(
        listOf(
            RigPart("root", null, listOf(8.0, 0.0, 8.0), model = false),
            RigPart("body", "root", listOf(8.0, 7.0, 8.0)),
            RigPart("leg_l", "body", listOf(10.0, 7.0, 8.0)),
            RigPart("head", "body", listOf(8.0, 16.0, 8.0)),
        ),
        height = 27.0,
    )

    private fun assertVec(expected: Vec3, actual: Vec3) {
        assertTrue(
            abs(expected.x - actual.x) < 1e-9 && abs(expected.y - actual.y) < 1e-9 && abs(expected.z - actual.z) < 1e-9,
            "expected $expected, got $actual",
        )
    }

    @Test
    fun `rest pose matches the kinematics and skips parts without model`() {
        val pose = RigPose(spec).solve()
        assertEquals(setOf("body", "leg_l", "head"), pose.keys)
        assertVec(Vec3(0.0, 16.0 / 16, 0.0), pose.getValue("head").translation)
    }

    @Test
    fun `rig scale scales translations and part scales about the feet`() {
        val rigPose = RigPose(spec)
        rigPose.scale = 0.5
        val pose = rigPose.solve()
        assertVec(Vec3(0.0, 8.0 / 16, 0.0), pose.getValue("head").translation)
        assertVec(Vec3(0.5, 0.5, 0.5), pose.getValue("head").scale)
    }

    @Test
    fun `offsets are inherited, part scales are not`() {
        val rigPose = RigPose(spec)
        rigPose.offset("body", 0.0, 2.0, 0.0)
        rigPose.scale("body", 2.0, 2.0, 2.0)
        val pose = rigPose.solve()
        assertVec(Vec3(0.0, 18.0 / 16, 0.0), pose.getValue("head").translation)
        assertVec(Vec3(1.0, 1.0, 1.0), pose.getValue("head").scale)
        assertVec(Vec3(2.0, 2.0, 2.0), pose.getValue("body").scale)
    }

    @Test
    fun `rotation in degrees turns children about the pivot and reset restores rest`() {
        val rigPose = RigPose(spec)
        // tip the whole body 90 degrees about Z: the head pivot (9 px above the body pivot) swings sideways
        rigPose.rotate("body", 0.0, 0.0, 90.0)
        val tipped = rigPose.solve().getValue("head").translation
        assertTrue(abs(tipped.y - 7.0 / 16) < 1e-9, "head drops to the body pivot height, got $tipped")
        assertTrue(abs(abs(tipped.x) - 9.0 / 16) < 1e-9, "head swings 9 px sideways, got $tipped")
        rigPose.reset("body")
        assertVec(Vec3(0.0, 1.0, 0.0), rigPose.solve().getValue("head").translation)
    }

    @Test
    fun `unknown parts are ignored`() {
        val rigPose = RigPose(spec)
        rigPose.rotate("tail", 10.0, 0.0, 0.0)
        assertEquals(RigPose(spec).solve(), rigPose.solve())
    }

    @Test
    fun `rig descriptions parse and validate`() {
        val parsed = RigSpecs.parse(
            """{"parts":[{"name":"root","pivot":[8,0,8],"model":false},{"name":"body","parent":"root","pivot":[8,7,8],"rest":[0,0,0],"scale":1,"extra":1}],"height":27}""",
        )
        assertEquals(listOf("root", "body"), parsed.parts.map { it.name })
        assertEquals(27.0, parsed.height)
        assertFailsWith<IllegalArgumentException> { RigSpecs.parse("not json") }
        assertFailsWith<IllegalArgumentException> { RigSpecs.parse("""{"parts":[]}""") }
        assertFailsWith<IllegalArgumentException> {
            RigSpecs.parse("""{"parts":[{"name":"body","parent":"root","pivot":[8,7,8]},{"name":"root","pivot":[8,0,8]}]}""")
        }
        assertFailsWith<IllegalArgumentException> { RigSpecs.parse("""{"parts":[{"name":"a","pivot":[8,7]}]}""") }
    }
}
