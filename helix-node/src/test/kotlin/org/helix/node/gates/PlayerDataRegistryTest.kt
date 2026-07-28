package org.helix.node.gates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.addon.PlayerDataProvider

class PlayerDataRegistryTest {
    private val registry = PlayerDataRegistry()

    @Test
    fun `export aggregates every owner that holds data`() {
        registry.register("bans", exporter { player -> if (player == "steve") "{\"reason\":\"griefing\"}" else null })
        registry.register("economy", exporter { "{\"balance\":10}" })

        assertEquals(mapOf("bans" to "{\"reason\":\"griefing\"}", "economy" to "{\"balance\":10}"), registry.export("steve"))
        assertEquals(mapOf("economy" to "{\"balance\":10}"), registry.export("alex"))
    }

    @Test
    fun `delete reports every owner that actually removed data`() {
        registry.register("bans", provider(deletes = true))
        registry.register("friends", provider(deletes = false))

        assertEquals(listOf("bans"), registry.delete("steve"))
    }

    @Test
    fun `unregistering an owner drops its providers`() {
        registry.register("bans", provider(deletes = true))
        registry.unregisterOwner("bans")

        assertTrue(registry.delete("steve").isEmpty())
    }

    @Test
    fun `a throwing provider is skipped instead of failing the whole export`() {
        registry.register("broken", object : PlayerDataProvider {
            override fun export(player: String): String? = error("boom")
            override fun delete(player: String): Boolean = error("boom")
        })
        registry.register("economy", exporter { "{\"balance\":10}" })

        assertEquals(mapOf("economy" to "{\"balance\":10}"), registry.export("steve"))
        assertTrue(registry.delete("steve").isEmpty())
    }

    private fun provider(deletes: Boolean) = object : PlayerDataProvider {
        override fun export(player: String): String? = null
        override fun delete(player: String): Boolean = deletes
    }

    private fun exporter(export: (String) -> String?) = object : PlayerDataProvider {
        override fun export(player: String): String? = export(player)
        override fun delete(player: String): Boolean = false
    }
}
