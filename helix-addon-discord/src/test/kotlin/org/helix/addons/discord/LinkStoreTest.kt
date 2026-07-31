package org.helix.addons.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.storage.InMemoryAddonStorage

class LinkStoreTest {
    private var now = 1_000_000L
    private var nextCode = 0
    private val storage = InMemoryAddonStorage()
    private val store = LinkStore(
        storage = storage,
        ttlMs = { 300_000L },
        now = { now },
        codeFactory = { "CODE${nextCode++}" },
    )

    @Test
    fun `game code flow links and persists`() {
        val code = store.createGameCode("uuid-1", "Steve")

        val outcome = store.redeemGameCode(code, "42", "steve#dc")

        val link = assertIs<LinkOutcome.Linked>(outcome).link
        assertEquals("Steve", link.playerName)
        assertEquals("42", link.discordId)
        assertEquals("game-code", link.linkedBy)
        assertEquals(link, store.byDiscord("42"))
        assertEquals(link, store.byPlayer("uuid-1"))
        assertTrue(storage.read("links")!!.contains("uuid-1"))

        val reloaded = LinkStore(storage)
        assertEquals("Steve", reloaded.byDiscord("42")?.playerName)
    }

    @Test
    fun `discord code flow links from the game side`() {
        val code = store.createDiscordCode("42", "steve#dc")

        val outcome = store.redeemDiscordCode(code, "uuid-1", "Steve")

        assertIs<LinkOutcome.Linked>(outcome)
        assertEquals("discord-code", store.byDiscord("42")?.linkedBy)
    }

    @Test
    fun `codes are single use case insensitive and expire`() {
        val code = store.createGameCode("uuid-1", "Steve")

        assertIs<LinkOutcome.Linked>(store.redeemGameCode(code.lowercase(), "42", "steve#dc"))
        assertIs<LinkOutcome.InvalidCode>(store.redeemGameCode(code, "43", "alex#dc"))

        val expired = store.createGameCode("uuid-2", "Alex")
        now += 300_001L
        assertIs<LinkOutcome.InvalidCode>(store.redeemGameCode(expired, "43", "alex#dc"))
    }

    @Test
    fun `either side already linked is rejected`() {
        store.setLink("42", "uuid-1", "Steve", "admin")

        val sameDiscord = store.redeemGameCode(store.createGameCode("uuid-2", "Alex"), "42", "steve#dc")
        assertIs<LinkOutcome.AlreadyLinked>(sameDiscord)

        val samePlayer = store.redeemGameCode(store.createGameCode("uuid-1", "Steve"), "43", "alex#dc")
        assertIs<LinkOutcome.AlreadyLinked>(samePlayer)
    }

    @Test
    fun `unlink works from both sides`() {
        store.setLink("42", "uuid-1", "Steve", "admin")

        assertNotNull(store.unlinkPlayer("uuid-1"))
        assertNull(store.byDiscord("42"))

        store.setLink("42", "uuid-1", "Steve", "admin")
        assertNotNull(store.unlinkDiscord("42"))
        assertNull(store.byPlayer("uuid-1"))
        assertNull(store.unlinkDiscord("42"))
    }
}
