package de.tytoss.igui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenSpritesTest {
    @Test
    fun `slot centres follow the chest, inventory and hotbar rows`() {
        assertEquals(16, ScreenSprites.slotCenterX(0))
        assertEquals(160, ScreenSprites.slotCenterX(8))
        assertEquals(26, ScreenSprites.slotCenterY(0))
        assertEquals(116, ScreenSprites.slotCenterY(5))
        assertEquals(147, ScreenSprites.slotCenterY(6))
        assertEquals(205, ScreenSprites.slotCenterY(9))
    }

    @Test
    fun `every ledger sprite stays within the display translation limit`() {
        LedgerLayout.SPRITES.forEach { sprite ->
            assertTrue(kotlin.math.abs(sprite.translationX) <= ScreenSprites.MAX_SHIFT, sprite.model)
            assertTrue(kotlin.math.abs(sprite.translationY) <= ScreenSprites.MAX_SHIFT, sprite.model)
            ScreenSprites.modelJson("helix_guis", sprite)
        }
    }

    @Test
    fun `sprites in the header row use distinct slots`() {
        // the life signs share the desk slot on purpose (one per menu)
        val header = LedgerLayout.SPRITES.filter { it.row == 0 && it != LedgerLayout.QUILL }
        assertEquals(header.size, header.map { it.column }.toSet().size)
    }

    @Test
    fun `model json shifts and scales the texture`() {
        val json = ScreenSprites.modelJson("ns", ScreenSprite("anim/a", "anim/t", 32, column = 0, row = 0, centerX = 6, centerY = 6))
        assertTrue(json.contains(""""translation":[-10,20,0]"""))
        assertTrue(json.contains(""""scale":[2.0,2.0,2.0]"""))
        assertTrue(json.contains(""""layer0":"ns:item/anim/t""""))
    }

    @Test
    fun `art file order matches the style enums`() {
        assertEquals(LedgerLayout.BUTTON_FILES, ButtonStyle.entries.map { it.file })
        assertEquals(LedgerLayout.PILL_FILES, PillColor.entries.map { it.file })
    }
}
