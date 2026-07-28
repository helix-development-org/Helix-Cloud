package org.helix.node.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerDataProvider
import org.helix.node.actions.ActionRegistry
import org.helix.node.gates.PlayerDataRegistry

class PlayerDataActionsTest {
    private val playerData = PlayerDataRegistry()
    private val registry = ActionRegistry().also { PlayerDataActions(playerData).registerAll(it) }

    @Test
    fun `export merges every owner's raw JSON into one document`() {
        playerData.register(
            "bans",
            object : PlayerDataProvider {
                override fun export(player: String) = """{"reason":"griefing"}"""
                override fun delete(player: String) = false
            },
        )

        val result = registry.invoke(ActionInvocation("player.gdpr-export", listOf("steve"), ActionSource.CLI))

        assertTrue(result.success)
        val document = result.lines.first()
        assertTrue(document.contains("\"player\""))
        assertTrue(document.contains("\"griefing\""))
    }

    @Test
    fun `export requires a player argument`() {
        val result = registry.invoke(ActionInvocation("player.gdpr-export", emptyList(), ActionSource.CLI))

        assertTrue(!result.success)
    }

    @Test
    fun `delete reports the owners data was removed from`() {
        playerData.register(
            "bans",
            object : PlayerDataProvider {
                override fun export(player: String): String? = null
                override fun delete(player: String) = true
            },
        )

        val result = registry.invoke(ActionInvocation("player.gdpr-delete", listOf("steve"), ActionSource.CLI))

        assertEquals(listOf("removed steve's data from: bans"), result.lines)
    }

    @Test
    fun `delete reports when nothing was found`() {
        val result = registry.invoke(ActionInvocation("player.gdpr-delete", listOf("steve"), ActionSource.CLI))

        assertEquals(listOf("no data found for steve"), result.lines)
    }
}
