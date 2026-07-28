package org.helix.bridge.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatModerationGateTest {
    @Test
    fun `permanent mute is always active`() {
        assertTrue(ChatModerationGate.isMuted(mapOf("steve" to 0L), "Steve", nowEpochMs = 999_999_999L))
    }

    @Test
    fun `temporary mute expires at its recorded time`() {
        val mutes = mapOf("steve" to 10_000L)

        assertTrue(ChatModerationGate.isMuted(mutes, "Steve", nowEpochMs = 5_000L))
        assertFalse(ChatModerationGate.isMuted(mutes, "Steve", nowEpochMs = 10_001L))
    }

    @Test
    fun `player without an entry is not muted`() {
        assertFalse(ChatModerationGate.isMuted(mapOf("steve" to 0L), "Alex", nowEpochMs = 0L))
    }

    @Test
    fun `blocklist matches whole words only, case-insensitively`() {
        val blocked = listOf("BadWord")

        assertTrue(ChatModerationGate.isBlocked(blocked, "that is a badword to say"))
        assertFalse(ChatModerationGate.isBlocked(blocked, "badwordsmith is fine"))
        assertFalse(ChatModerationGate.isBlocked(emptyList(), "badword"))
    }

    @Test
    fun `localize falls back from requested language to english to any entry`() {
        val texts = mapOf("en" to "hello", "de" to "hallo")

        assertEquals("hallo", ChatModerationGate.localize(texts, "de"))
        assertEquals("hello", ChatModerationGate.localize(texts, "fr"))
        assertEquals("hola", ChatModerationGate.localize(mapOf("es" to "hola"), "fr"))
        assertEquals("", ChatModerationGate.localize(emptyMap(), "en"))
    }
}
