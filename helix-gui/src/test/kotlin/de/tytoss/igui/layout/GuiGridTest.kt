package de.tytoss.igui.layout

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuiGridTest {
    @Test
    fun `chest-only grid covers the chest rows`() {
        val grid = GuiGrid(3)
        assertEquals(3, grid.totalRows)
        assertEquals(27, grid.slotCount)
        assertEquals(27, grid.chestSlotCount)
        assertNull(grid.cell(27))
        assertFalse(grid.isPlayerInventorySlot(20))
        assertNull(grid.storageIndex(20))
    }

    @Test
    fun `full-window grid numbers slots like bukkit raw slots`() {
        val grid = GuiGrid(6, includesPlayerInventory = true)
        assertEquals(10, grid.totalRows)
        assertEquals(90, grid.slotCount)
        assertEquals(GuiCell(0, 6), grid.cell(54))
        assertEquals(GuiCell(8, 9), grid.cell(89))
        assertEquals(58, grid.slot(4, 6))
        assertNull(grid.cell(-999))
    }

    @Test
    fun `player inventory slots map to storage indices`() {
        val grid = GuiGrid(6, includesPlayerInventory = true)
        assertTrue(grid.isPlayerInventorySlot(54))
        assertEquals(9, grid.storageIndex(54), "first main-inventory slot")
        assertEquals(35, grid.storageIndex(80), "last main-inventory slot")
        assertEquals(0, grid.storageIndex(81), "first hotbar slot")
        assertEquals(8, grid.storageIndex(89), "last hotbar slot")
        assertNull(grid.storageIndex(53))
    }

    @Test
    fun `item pixels follow the vanilla chest layout`() {
        val grid = GuiGrid(6, includesPlayerInventory = true)
        assertEquals(8, grid.itemX(0))
        assertEquals(152, grid.itemX(8))
        assertEquals(18, grid.itemY(0))
        assertEquals(108, grid.itemY(5))
        assertEquals(139, grid.itemY(6), "main inventory of a double chest")
        assertEquals(175, grid.itemY(8))
        assertEquals(197, grid.itemY(9), "hotbar of a double chest")
        assertEquals(85, GuiGrid(3, includesPlayerInventory = true).itemY(3))
    }

    @Test
    fun `ascent puts a glyph top on a gui pixel`() {
        assertEquals(13, GuiGrid.ascentForTop(0))
        assertEquals(-5, GuiGrid.ascentForTop(18))
    }

    @Test
    fun `invalid geometry is rejected`() {
        assertFailsWith<IllegalArgumentException> { GuiGrid(7) }
        assertFailsWith<IllegalArgumentException> { GuiGrid(3).slot(9, 0) }
        assertFailsWith<IllegalArgumentException> { GuiGrid(3).itemY(3) }
    }

    @Test
    fun `areas resolve relative cells and slots`() {
        val area = GuiArea(column = 2, row = 3, width = 2, height = 3)
        assertTrue(GuiCell(3, 5) in area)
        assertFalse(GuiCell(4, 5) in area)
        assertEquals(GuiCell(1, 2), area.relative(GuiCell(3, 5)))
        assertNull(area.relative(GuiCell(1, 3)))
        assertContentEquals(intArrayOf(29, 30, 38, 39, 47, 48), area.slots())
        val collected = ArrayList<Int>()
        area.selection().forEach(90, collected::add)
        assertEquals(area.slots().toList(), collected)
    }

    @Test
    fun `areas outside the grid are rejected`() {
        assertFailsWith<IllegalArgumentException> { GuiArea(8, 0, 2, 1) }
        assertFailsWith<IllegalArgumentException> { GuiArea(0, 0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { GuiArea(-1, 0, 1, 1) }
    }
}
