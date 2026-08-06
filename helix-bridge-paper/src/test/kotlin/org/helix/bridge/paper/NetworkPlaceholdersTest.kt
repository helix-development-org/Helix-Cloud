package org.helix.bridge.paper

import org.helix.api.display.DisplayProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkPlaceholdersTest {
    @Test
    fun `resolves published bridge values`() {
        val values = mapOf(
            "economy.balance.steve" to "42",
            "clan.tag.steve" to "ABC",
            "network.name" to "our network",
            "network.prefix" to "&b[Net] ",
        )

        assertEquals("42", NetworkPlaceholders.resolve("balance", "Steve", values, null, 0))
        assertEquals("ABC", NetworkPlaceholders.resolve("clan", "Steve", values, null, 0))
        assertEquals("our network", NetworkPlaceholders.resolve("network", "Steve", values, null, 0))
        assertEquals("&b[Net] ", NetworkPlaceholders.resolve("prefix", "Steve", values, null, 0))
    }

    @Test
    fun `falls back to empty or the given online count`() {
        assertEquals("", NetworkPlaceholders.resolve("balance", "steve", emptyMap(), null, 0))
        assertEquals("7", NetworkPlaceholders.resolve("online", "steve", emptyMap(), null, 7))
        assertEquals("3", NetworkPlaceholders.resolve("online", "steve", mapOf("network.online" to "3"), null, 7))
    }

    @Test
    fun `nick and displayname use the display profile`() {
        val profile = DisplayProfile(prefix = "&c[Admin] ", name = "Nicked")

        assertEquals("Nicked", NetworkPlaceholders.resolve("nick", "Steve", emptyMap(), profile, 0))
        assertEquals("&c[Admin] Nicked", NetworkPlaceholders.resolve("displayname", "Steve", emptyMap(), profile, 0))
        assertEquals("Steve", NetworkPlaceholders.resolve("nick", "Steve", emptyMap(), null, 0))
    }

    @Test
    fun `unknown identifier resolves to null`() {
        assertNull(NetworkPlaceholders.resolve("unknown-thing", "steve", emptyMap(), null, 0))
    }
}
