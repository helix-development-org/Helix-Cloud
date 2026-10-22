package org.helix.addons.guardlab.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationsTest {
    private class Recorder : BlockSink {
        val blocks = HashMap<Triple<Int, Int, Int>, String>()
        var writes = 0

        override fun set(x: Int, y: Int, z: Int, data: String) {
            writes++
            if (data == "minecraft:air") blocks.remove(Triple(x, y, z)) else blocks[Triple(x, y, z)] = data
        }

        fun at(x: Int, y: Int, z: Int): String = blocks[Triple(x, y, z)] ?: "minecraft:air"
    }

    private fun world(): Recorder = Recorder().also { r -> Stations.ALL.forEach { Stations.build(it.name, r) } }

    /** Blocks a player can stand inside (feet/head). */
    private val passable = setOf("minecraft:air", "minecraft:white_carpet", "minecraft:snow[layers=4]")

    @Test
    fun `layout is deterministic`() {
        assertEquals(world().blocks, world().blocks)
        val rebuilt = world()
        Stations.build("build", rebuilt, clear = true)
        assertEquals(world().blocks, rebuilt.blocks)
    }

    @Test
    fun `stations do not overlap`() {
        val boxes = Stations.ALL.map { Stations.bounds(it.name)!! }
        boxes.forEachIndexed { i, a ->
            boxes.drop(i + 1).forEach { b -> assertTrue(a[3] < b[0] || b[3] < a[0], "stations overlap: ${a.toList()} ${b.toList()}") }
        }
        val world = world()
        Stations.ALL.forEach { def ->
            val box = Stations.bounds(def.name)!!
            val inside = world.blocks.keys.count { (x, y, z) -> x in box[0]..box[3] && y in box[1]..box[4] && z in box[2]..box[5] }
            assertTrue(inside > 0, def.name)
        }
        val total = world.blocks.size
        val inAny = world.blocks.keys.count { (x, y, z) ->
            Stations.ALL.any { def -> Stations.bounds(def.name)!!.let { x in it[0]..it[3] && y in it[1]..it[4] && z in it[2]..it[5] } }
        }
        assertEquals(total, inAny, "every block lies inside a station's bounds (rebuild clears it)")
    }

    @Test
    fun `every spot resolves and players can stand there`() {
        val world = world()
        assertTrue(Stations.spotRefs.size >= 30)
        Stations.ALL.forEach { def ->
            def.spots.forEach { (name, spot) ->
                val pos = assertNotNull(Stations.resolve("${def.name}.$name"))
                val x = kotlin.math.floor(pos.x).toInt()
                val y = pos.y.toInt()
                val z = kotlin.math.floor(pos.z).toInt()
                if (spot.free) return@forEach
                assertTrue(world.at(x, y, z) in passable, "${def.name}.$name: feet blocked by ${world.at(x, y, z)}")
                assertTrue(world.at(x, y + 1, z) in passable, "${def.name}.$name: head blocked by ${world.at(x, y + 1, z)}")
                val below = world.at(x, y - 1, z)
                assertFalse(below == "minecraft:air" || below.contains("water") || below.contains("lava"), "${def.name}.$name: nothing to stand on ($below)")
            }
        }
        assertNull(Stations.resolve("flat"))
        assertNull(Stations.resolve("flat.nowhere"))
        assertNull(Stations.resolve("nowhere.start"))
        assertFalse(Stations.exists("combat"))
    }

    @Test
    fun `mechanisms sit where the runner expects them`() {
        val world = world()
        val (x, y, z) = Stations.pulses("launcher").single()
        assertEquals("minecraft:air", world.at(x, y, z))
        assertTrue(world.at(x, y + 1, z).startsWith("minecraft:sticky_piston"))
        assertEquals("minecraft:slime_block", world.at(x, y + 2, z))
        val pad = Stations.resolve("launcher.pad")!!
        assertEquals(y + 3, pad.y.toInt())
        // the build station has a gap behind the bridge spot and a catch floor below it
        val bridge = Stations.resolve("build.bridge")!!
        val bx = kotlin.math.floor(bridge.x).toInt()
        assertEquals("minecraft:air", world.at(bx, Stations.FEET_Y - 1, 3))
        assertEquals(Stations.STONE, world.at(bx, Stations.FEET_Y - 6, 3))
        // bubble column stands on soul sand
        val bubble = Stations.resolve("liquid.bubble")!!
        val bubbleX = kotlin.math.floor(bubble.x).toInt()
        val bubbleZ = kotlin.math.floor(bubble.z).toInt()
        assertEquals("minecraft:soul_sand", world.at(bubbleX, Stations.FEET_Y - 1, bubbleZ))
        assertTrue(world.at(bubbleX, Stations.FEET_Y, bubbleZ).startsWith("minecraft:bubble_column"))
        // every station is fenced at its floor ends
        Stations.ALL.forEach { def ->
            val ox = Stations.originX(def.name)!!
            assertEquals("minecraft:barrier", world.at(ox, Stations.FEET_Y, -11), def.name)
        }
    }
}
