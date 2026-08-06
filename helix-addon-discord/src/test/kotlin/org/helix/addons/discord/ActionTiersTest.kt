package org.helix.addons.discord

import kotlin.test.Test
import kotlin.test.assertEquals

class ActionTiersTest {
    @Test
    fun `read only suffixes are normal and unknown actions default to destructive`() {
        val config = DiscordConfig()

        assertEquals(ActionTier.NORMAL, ActionTiers.classify("service.list", config))
        assertEquals(ActionTier.NORMAL, ActionTiers.classify("platform.overview", config))
        assertEquals(ActionTier.NORMAL, ActionTiers.classify("ban.check", config))
        assertEquals(ActionTier.DESTRUCTIVE, ActionTiers.classify("service.stop", config))
        assertEquals(ActionTier.DESTRUCTIVE, ActionTiers.classify("eco.give", config))
    }

    @Test
    fun `default criticals and explicit configuration win over the heuristic`() {
        val config = DiscordConfig(
            normalActions = listOf("eco.give"),
            destructiveActions = listOf("versions.list"),
            criticalActions = DiscordConfig.DEFAULT_CRITICAL + "ban.set",
        )

        assertEquals(ActionTier.CRITICAL, ActionTiers.classify("platform.stop", config))
        assertEquals(ActionTier.CRITICAL, ActionTiers.classify("service.command", config))
        assertEquals(ActionTier.CRITICAL, ActionTiers.classify("ban.set", config))
        assertEquals(ActionTier.NORMAL, ActionTiers.classify("eco.give", config))
        assertEquals(ActionTier.DESTRUCTIVE, ActionTiers.classify("versions.list", config))
    }
}
