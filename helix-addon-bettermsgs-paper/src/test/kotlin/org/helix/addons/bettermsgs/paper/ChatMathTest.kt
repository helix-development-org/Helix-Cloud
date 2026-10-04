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
    fun `pages put the newest window last and the oldest first`() {
        assertEquals(0 to 1, ChatMath.pages(0, 5))
        assertEquals(2 to 3, ChatMath.pages(0, 20))
        assertEquals(0 to 3, ChatMath.pages(20 - ChatMath.WINDOW, 20))
        assertEquals(1 to 3, ChatMath.pages(ChatMath.WINDOW, 20))
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
