package org.helix.addons.moderation

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext

class ModerationAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("moderation"))
    private val addon = ModerationAddon().also { it.onEnable(context) }

    @Test
    fun `all commands are permission gated player commands`() {
        val commands = context.handlers.values.map { it.first }.filter { it.playerCommand }

        assertEquals(
            setOf("kick", "warn", "warns", "announce", "tempban"),
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
        assertEquals(listOf("Griefer", "7d", "cheating"), ban.arguments)
    }

    @Test
    fun `kick and warn publish moderation notifications`() {
        context.run("kick", "Mod", "Griefer", "spamming")
        context.run("warn", "Mod", "Steve", "language")

        assertEquals(2, context.notifications.size)
        assertTrue(context.notifications[0].second.contains("[Kick]"))
        assertTrue(context.notifications[1].second.contains("[Warn]"))
        assertTrue(context.notifications.all { it.first == "moderation" })
    }

    @Test
    fun `missing arguments yield usage errors`() {
        assertFalse(context.run("kick", "Mod").success)
        assertFalse(context.run("warn", "Mod", "Steve").success)
        assertFalse(context.run("tempban", "Mod", "Griefer").success)
    }
}
