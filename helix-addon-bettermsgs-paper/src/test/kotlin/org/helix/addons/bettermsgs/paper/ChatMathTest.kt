package org.helix.addons.bettermsgs.paper

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMathTest {
    @Test
    fun `offset clamps to the scrollable range`() {
        assertEquals(0, ChatMath.clampOffset(-5, 20))
        assertEquals(20 - ChatMath.WINDOW, ChatMath.clampOffset(50, 20))
        assertEquals(0, ChatMath.clampOffset(3, 5))
    }

    @Test
    fun `thumb sits at the bottom for the newest window and at the top for the oldest`() {
        assertEquals(7, ChatMath.thumbIndex(0, 5))
        assertEquals(7, ChatMath.thumbIndex(0, 100))
        assertEquals(0, ChatMath.thumbIndex(100 - ChatMath.WINDOW, 100))
        assertEquals(4, ChatMath.thumbIndex((100 - ChatMath.WINDOW) / 2, 100))
    }

    @Test
    fun `wrap keeps words and splits oversized ones`() {
        assertEquals(listOf("hello world"), ChatMath.wrap("hello world"))
        assertEquals(listOf(""), ChatMath.wrap("   "))
        val wrapped = ChatMath.wrap("a".repeat(90), width = 40)
        assertEquals(3, wrapped.size)
        assertEquals(40, wrapped[0].length)
    }
}
