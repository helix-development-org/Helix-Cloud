package org.helix.addons.lobby.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceListTest {
    @Test
    fun `state follows load and maintenance`() {
        assertEquals(InstanceState.OPEN, InstanceList.state(10, 60, false))
        assertEquals(InstanceState.BUSY, InstanceList.state(48, 60, false))
        assertEquals(InstanceState.FULL, InstanceList.state(60, 60, false))
        assertEquals(InstanceState.FULL, InstanceList.state(61, 60, false))
        assertEquals(InstanceState.MAINTENANCE, InstanceList.state(0, 60, true))
        assertEquals(InstanceState.OPEN, InstanceList.state(500, 0, false))
        assertTrue(InstanceState.BUSY.joinable)
        assertFalse(InstanceState.FULL.joinable)
        assertFalse(InstanceState.MAINTENANCE.joinable)
    }

    @Test
    fun `rows keep only the task and sort naturally`() {
        val entries = listOf(
            ServerEntry("CityBuild-10", "CityBuild", 1, 60),
            ServerEntry("MineClash-Game-1", "MineClash-Game", 5, 40),
            ServerEntry("CityBuild-2", "CityBuild", 2, 60),
            ServerEntry("CityBuild-1", "citybuild", 3, 60),
        )
        val rows = InstanceList.rows(entries, "CityBuild", maintenance = false)
        assertEquals(listOf("CityBuild-1", "CityBuild-2", "CityBuild-10"), rows.map { it.id })
        assertTrue(InstanceList.rows(entries, "CityBuild", maintenance = true).all { it.state == InstanceState.MAINTENANCE })
    }

    @Test
    fun `paging clamps and slices`() {
        val rows = (1..19).map { InstanceRow("CityBuild-$it", 0, 60, InstanceState.OPEN) }
        assertEquals(3, InstanceList.pages(rows))
        assertEquals(1, InstanceList.pages(emptyList()))
        assertEquals("CityBuild-17", InstanceList.page(rows, 2).first().id)
        assertEquals(3, InstanceList.page(rows, 9).size)
        assertEquals(8, InstanceList.page(rows, -1).size)
    }

    @Test
    fun `citybuild tasks land on kronstadt with crown and picker`() {
        assertEquals("kronstadt", NavigatorGuess.place("CityBuild"))
        assertEquals("crown", NavigatorGuess.emblem("CityBuild"))
        assertTrue(NavigatorGuess.pickInstance("CityBuild"))
        assertFalse(NavigatorGuess.pickInstance("MineClash-Game"))
        assertEquals("kesseldorf", NavigatorGuess.place("MineClash-Game"))
    }

    @Test
    fun `kronstadt does not collide with another place`() {
        val cells = NavigatorGlyphs.PLACES.flatMap { p ->
            listOf(p.column to p.row, p.column + 1 to p.row, p.column to p.row + 1, p.column + 1 to p.row + 1, p.headColumn to p.headRow)
        }
        assertEquals(cells.size, cells.toSet().size)
        assertTrue(NavigatorGlyphs.PLACES.any { it.id == "kronstadt" })
    }
}
