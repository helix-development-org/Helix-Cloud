package org.helix.addons.teamutils

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.player.OnlinePlayer

class TeamUtilsAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("team"))
    private val addon = TeamUtilsAddon().also { it.onEnable(context) }

    private fun team(vararg names: String) {
        context.permissionCheck = { player, permission ->
            permission == "helix.team.member" && names.any { it.equals(player, ignoreCase = true) }
        }
    }

    @Test
    fun `team chat reaches every online team member including the sender`() {
        team("Mod1", "Mod2")
        context.online += listOf(OnlinePlayer("Mod1"), OnlinePlayer("Mod2"), OnlinePlayer("Player"))

        assertTrue(context.run("tc", "Mod1", "hallo", "team").success)

        val messages = context.invocations.filter { it.action == "player.message" }
        assertEquals(setOf("Mod1", "Mod2"), messages.map { it.arguments.first() }.toSet())
        assertTrue(messages.all { it.arguments[1].contains("hallo team") })
    }

    @Test
    fun `team list shows only online team members`() {
        team("Mod1")
        context.online += listOf(OnlinePlayer("Mod1"), OnlinePlayer("Player"))

        val result = context.run("team", "Mod1")

        assertTrue(result.lines.single().contains("Mod1"))
        assertTrue(!result.lines.single().contains("Player"))
    }

    @Test
    fun `join and leave notify the rest of the team`() {
        team("Mod1", "Mod2")
        context.online += listOf(OnlinePlayer("Mod1"), OnlinePlayer("Mod2"))

        context.playerListeners.single().onJoin(OnlinePlayer("Mod2"))
        val joinNotified = context.invocations.filter { it.action == "player.message" }
        assertEquals(listOf("Mod1"), joinNotified.map { it.arguments.first() })

        context.invocations.clear()
        context.playerListeners.single().onLeave(OnlinePlayer("Mod2"))
        assertTrue(context.invocations.single().arguments[1].contains("offline"))
    }

    @Test
    fun `non team joins stay silent and team notify counts deliveries`() {
        team("Mod1")
        context.online += OnlinePlayer("Mod1")

        context.playerListeners.single().onJoin(OnlinePlayer("Player"))
        assertTrue(context.invocations.isEmpty())

        val result = context.run("team.notify", "Restart", "in", "5", "Minuten")
        assertTrue(result.lines.single().contains("notified 1"))
    }

    @Test
    fun `commands are gated on the team permission`() {
        val descriptors = context.handlers.values.map { it.first }

        assertTrue(
            descriptors.filter { it.name in setOf("tc", "team") }
                .all { it.permission == "helix.team.member" },
        )
    }
}
