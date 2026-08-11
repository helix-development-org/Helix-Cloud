package org.helix.addons.parties

import org.helix.addon.sdk.testing.RecordingAddonContext
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartiesAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("parties"))
    private val addon = PartiesAddon().also { it.onEnable(context) }

    @Test
    fun `create invite accept builds a party with notifications`() {
        assertTrue(context.run("party", "Steve", "create").success)

        val invited = context.run("party", "Steve", "invite", "Alex")
        assertTrue(invited.success)
        assertEquals(
            listOf("Alex"),
            context.invocations.filter { it.action == "player.message" }.map { it.arguments.first() },
        )

        val accepted = context.run("party", "Alex", "accept", "Steve")
        assertTrue(accepted.success)
        assertTrue(context.run("party", "Steve", "list").lines.any { it.contains("alex") })
    }

    @Test
    fun `only the leader can invite or kick`() {
        context.run("party", "Steve", "create")
        context.run("party", "Steve", "invite", "Alex")
        context.run("party", "Alex", "accept", "Steve")

        assertFalse(context.run("party", "Alex", "invite", "Bob").success)
        assertFalse(context.run("party", "Alex", "kick", "Steve").success)
        assertTrue(context.run("party", "Steve", "kick", "Alex").success)
    }

    @Test
    fun `kicked member is removed and notified`() {
        context.run("party", "Steve", "create")
        context.run("party", "Steve", "invite", "Alex")
        context.run("party", "Alex", "accept", "Steve")
        context.invocations.clear()

        val result = context.run("party", "Steve", "kick", "Alex")

        assertTrue(result.success)
        val toAlex = context.invocations.first { it.action == "player.message" && it.arguments[0] == "Alex" }
        assertTrue(toAlex.arguments[1].contains("kicked"))
        assertTrue(context.run("party", "Alex", "list").lines.first().contains("not in a party"))
    }

    @Test
    fun `leader leaving transfers leadership to the next member`() {
        context.run("party", "Steve", "create")
        context.run("party", "Steve", "invite", "Alex")
        context.run("party", "Alex", "accept", "Steve")

        assertTrue(context.run("party", "Steve", "leave").success)

        assertTrue(context.run("party", "Alex", "list").lines.first().contains("alex"))
        assertFalse(context.run("party", "Alex", "kick", "Steve").success)
    }

    @Test
    fun `last member leaving dissolves the party`() {
        context.run("party", "Steve", "create")
        assertTrue(context.run("party", "Steve", "leave").success)
        assertTrue(context.run("party", "Steve", "list").lines.first().contains("not in a party"))
    }

    @Test
    fun `cannot invite a player who is already in a party`() {
        context.run("party", "Steve", "create")
        context.run("party", "Bob", "create")

        assertFalse(context.run("party", "Steve", "invite", "Bob").success)
    }

    @Test
    fun `party members action resolves the group, defaulting to a solo party`() {
        assertEquals(listOf("steve"), context.run("party.members", "Steve").lines)

        context.run("party", "Steve", "create")
        context.run("party", "Steve", "invite", "Alex")
        context.run("party", "Alex", "accept", "Steve")

        assertEquals(listOf("steve", "alex"), context.run("party.members", "Alex").lines)
    }
}
