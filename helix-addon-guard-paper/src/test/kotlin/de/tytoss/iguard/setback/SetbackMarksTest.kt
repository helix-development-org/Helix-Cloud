package de.tytoss.iguard.setback

import de.tytoss.iguard.PLAYER
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetbackMarksTest {
    @Test
    fun `a setback is recent for one second`() {
        val marks = SetbackMarks()
        assertFalse(marks.isRecent(PLAYER, 1_000))
        marks.mark(PLAYER, 1_000)

        assertTrue(marks.isRecent(PLAYER, 1_000))
        assertTrue(marks.isRecent(PLAYER, 1_900))
        assertFalse(marks.isRecent(PLAYER, 2_100))
        assertFalse(marks.isRecent(PLAYER, 1_500), "expired marks are dropped")
    }

    @Test
    fun `remove forgets the player`() {
        val marks = SetbackMarks()
        marks.mark(PLAYER, 1_000)
        marks.remove(PLAYER)

        assertFalse(marks.isRecent(PLAYER, 1_000))
    }
}
