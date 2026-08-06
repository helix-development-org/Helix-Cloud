package org.helix.addons.profile.paper

import org.helix.api.addon.ProfileSettingOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChoiceCyclingTest {
    private val none = ProfileSettingOption("none", "None")
    private val angel = ProfileSettingOption("angel", "Angel", unlocked = true)
    private val demon = ProfileSettingOption("demon", "Demon", unlocked = false)

    @Test
    fun `cycles to the next unlocked option`() {
        assertEquals(angel, ChoiceCycling.next(listOf(none, angel, demon), "none"))
    }

    @Test
    fun `wraps around to the first unlocked option`() {
        assertEquals(none, ChoiceCycling.next(listOf(none, angel, demon), "angel"))
    }

    @Test
    fun `skips locked options entirely, wrapping back to the only unlocked one`() {
        assertEquals(none, ChoiceCycling.next(listOf(none, demon), "none"))
    }

    @Test
    fun `an unknown current value starts from the first unlocked option`() {
        assertEquals(none, ChoiceCycling.next(listOf(none, angel), "ghost-value"))
    }

    @Test
    fun `no unlocked options at all returns null`() {
        assertNull(ChoiceCycling.next(listOf(demon), "demon"))
    }
}
