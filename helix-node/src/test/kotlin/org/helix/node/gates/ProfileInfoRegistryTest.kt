package org.helix.node.gates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.addon.ProfileInfoEntry
import org.helix.api.addon.ProfileInfoProvider

class ProfileInfoRegistryTest {
    private val registry = ProfileInfoRegistry()

    @Test
    fun `infoFor aggregates every owner that has lines for a player`() {
        registry.register("stats", info { player -> if (player == "steve") listOf(ProfileInfoEntry("Kills", "42")) else emptyList() })
        registry.register("clan", info { listOf(ProfileInfoEntry("Clan", "STV")) })

        assertEquals(
            mapOf("stats" to listOf(ProfileInfoEntry("Kills", "42")), "clan" to listOf(ProfileInfoEntry("Clan", "STV"))),
            registry.infoFor("steve"),
        )
        assertEquals(mapOf("clan" to listOf(ProfileInfoEntry("Clan", "STV"))), registry.infoFor("alex"))
    }

    @Test
    fun `an owner with no lines for a player is omitted`() {
        registry.register("stats", info { emptyList() })

        assertTrue(registry.infoFor("steve").isEmpty())
    }

    @Test
    fun `unregistering an owner drops its providers`() {
        registry.register("stats", info { listOf(ProfileInfoEntry("Kills", "42")) })
        registry.unregisterOwner("stats")

        assertTrue(registry.infoFor("steve").isEmpty())
    }

    @Test
    fun `a throwing provider is skipped instead of failing the whole lookup`() {
        registry.register("broken", object : ProfileInfoProvider {
            override fun infoFor(player: String): List<ProfileInfoEntry> = error("boom")
        })
        registry.register("stats", info { listOf(ProfileInfoEntry("Kills", "42")) })

        assertEquals(mapOf("stats" to listOf(ProfileInfoEntry("Kills", "42"))), registry.infoFor("steve"))
    }

    private fun info(entries: (String) -> List<ProfileInfoEntry>) = object : ProfileInfoProvider {
        override fun infoFor(player: String): List<ProfileInfoEntry> = entries(player)
    }
}
