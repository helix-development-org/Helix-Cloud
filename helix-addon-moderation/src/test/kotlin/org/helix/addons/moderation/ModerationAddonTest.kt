package org.helix.addons.moderation

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult

class ModerationAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("moderation"))
    private val addon = ModerationAddon().also { it.onEnable(context) }

    @Test
    fun `all commands are permission gated player commands`() {
        val commands = context.handlers.values.map { it.first }.filter { it.playerCommand }

        assertEquals(
            setOf("kick", "warn", "warns", "announce", "tempban", "mute", "unmute", "mutes", "blocklist", "modlookup"),
            commands.map { it.name }.toSet(),
        )
        assertTrue(commands.all { it.permission!!.startsWith("helix.mod.") })
    }

    @Test
    fun `kick delegates to the generic kick action with moderator context`() {
        val result = context.run("kick", "Mod", "Griefer", "spamming")

        assertTrue(result.success)
        val kick = context.invocations.single { it.action == "player.kick" }
        assertEquals("Griefer", kick.arguments.first())
        assertTrue(kick.arguments[1].contains("spamming"))
        assertTrue(kick.arguments[1].contains("Mod"))
    }

    @Test
    fun `warn records history and messages the target`() {
        assertTrue(context.run("warn", "Mod", "Steve", "bad", "language").success)
        assertTrue(context.run("warn", "Mod", "Steve", "again").success)

        val warns = context.run("warns", "Mod", "steve")
        assertEquals(2, warns.lines.size)
        assertTrue(warns.lines.any { it.contains("bad language") })
        assertEquals(
            listOf("Steve", "Steve"),
            context.invocations.filter { it.action == "player.message" }.map { it.arguments.first() },
        )
    }

    @Test
    fun `announce broadcasts network wide`() {
        assertTrue(context.run("announce", "Mod", "Wartung", "um", "20", "Uhr").success)

        val broadcast = context.invocations.single { it.action == "player.broadcast" }
        assertTrue(broadcast.arguments.first().contains("Wartung um 20 Uhr"))
    }

    @Test
    fun `tempban delegates to the bans addon`() {
        context.run("tempban", "Mod", "Griefer", "7d", "cheating")

        val ban = context.invocations.single { it.action == "ban.set" }
        assertEquals(listOf("Griefer", "Mod", "7d", "cheating"), ban.arguments)
    }

    @Test
    fun `kick and warn publish moderation notifications`() {
        context.run("kick", "Mod", "Griefer", "spamming")
        context.run("warn", "Mod", "Steve", "language")

        assertEquals(2, context.notifications.size)
        assertTrue(context.notifications[0].second.contains("was kicked by"))
        assertTrue(context.notifications[1].second.contains("was warned by"))
        assertTrue(context.notifications.all { it.first == "moderation" })
    }

    @Test
    fun `missing arguments yield usage errors`() {
        assertFalse(context.run("kick", "Mod").success)
        assertFalse(context.run("warn", "Mod", "Steve").success)
        assertFalse(context.run("tempban", "Mod", "Griefer").success)
    }

    @Test
    fun `mute and unmute message the target and publish the bridge mute map`() {
        assertTrue(context.run("mute", "Mod", "Steve", "spamming").success)
        assertTrue(
            context.invocations.single { it.action == "player.message" }.arguments[1].contains("muted"),
        )
        assertTrue(context.bridgeValues["moderation.mutes"]!!.contains("steve"))

        assertTrue(context.run("unmute", "Mod", "Steve").success)
        assertFalse(context.bridgeValues["moderation.mutes"]!!.contains("steve"))
    }

    @Test
    fun `unmute without an active mute fails`() {
        assertFalse(context.run("unmute", "Mod", "Steve").success)
    }

    @Test
    fun `blocklist add remove and list round-trip and publish to the bridge`() {
        assertTrue(context.run("blocklist", "Mod", "add", "badword").success)
        assertTrue(context.bridgeValues["moderation.blocklist"]!!.contains("badword"))
        assertTrue(context.run("blocklist", "Mod", "list").lines.first().contains("badword"))

        assertTrue(context.run("blocklist", "Mod", "remove", "badword").success)
        assertFalse(context.bridgeValues["moderation.blocklist"]!!.contains("badword"))
        assertFalse(context.run("blocklist", "Mod", "remove", "badword").success)
    }

    @Test
    fun `modlookup aggregates ban mute warn and incident status across addons`() {
        context.registerAction(ActionDescriptor("ban.check", "d", "u")) { invocation ->
            ActionResult.ok("${invocation.arguments.first().lowercase()} — griefing (permanent) — by Admin")
        }
        context.registerAction(ActionDescriptor("guard.query.incidents", "d", "u")) {
            ActionResult.ok("""{"incidents":[{"name":"steve"},{"name":"steve"},{"name":"alex"}]}""")
        }
        context.run("warn", "Mod", "Steve", "test")
        context.run("mute", "Mod", "Steve", "spam")

        val result = context.run("modlookup", "Mod", "Steve")

        assertTrue(result.success)
        assertTrue(result.lines.any { it.contains("griefing") }, "ban status missing")
        assertTrue(result.lines.any { it.contains("spam") }, "mute status missing")
        assertTrue(result.lines.any { it.contains("Active warns") && it.contains("1") }, "warn count missing")
        assertTrue(result.lines.any { it.contains("Guard incidents") && it.contains("2") }, "incident count missing")
    }

    @Test
    fun `modlookup reports addons that are not installed instead of guessing`() {
        val result = context.run("modlookup", "Mod", "Ghost")

        assertTrue(result.success)
        assertTrue(result.lines.any { it.contains("not installed") }, "ban.check absence must not read as clean")
        assertTrue(result.lines.any { it.contains("none", ignoreCase = true) }, "mute status should be none")
    }

    @Test
    fun `player-data provider exports and clears warn history`() {
        val provider = context.playerDataProviders.single()
        assertEquals(null, provider.export("steve"))

        context.run("warn", "Mod", "Steve", "language")

        assertTrue(provider.export("steve")!!.contains("language"))
        assertTrue(provider.delete("steve"))
        assertTrue(context.run("warns", "Mod", "steve").lines.first().contains("no warnings"))
        assertFalse(provider.delete("steve"))
    }
}
