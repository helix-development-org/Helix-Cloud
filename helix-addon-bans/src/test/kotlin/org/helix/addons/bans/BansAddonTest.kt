package org.helix.addons.bans

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.JoinGate
import org.helix.api.proxy.JoinRequest

/**
 * Fake context capturing everything the addon registers.
 */
private class FakeContext(override val dataDirectory: Path) : AddonContext {
    val handlers = mutableMapOf<String, ActionHandler>()
    val gates = mutableListOf<JoinGate>()
    val invoked = mutableListOf<ActionInvocation>()

    override val actions: ActionInvoker = object : ActionInvoker {
        override fun invoke(invocation: ActionInvocation): ActionResult {
            invoked += invocation
            return ActionResult.ok("kick for ${invocation.arguments.first()} queued")
        }

        override fun descriptors() = emptyList<org.helix.api.action.ActionDescriptor>()
    }

    override fun registerAction(descriptor: ActionDescriptor, handler: ActionHandler) {
        handlers[descriptor.name] = handler
    }

    override fun registerJoinGate(gate: JoinGate) {
        gates += gate
    }

    fun run(action: String, vararg args: String): ActionResult =
        handlers.getValue(action).execute(ActionInvocation(action, args.toList()))
}

class BansAddonTest {
    private val context = FakeContext(createTempDirectory("bans"))
    private val addon = BansAddon().also { it.onEnable(context) }

    @Test
    fun `ban blocks join and pardon unblocks`() {
        context.run("ban.set", "Steve", "griefing")

        val denied = context.gates.single().check(JoinRequest("steve"))
        assertFalse(denied.allowed)
        assertTrue(denied.message!!.contains("griefing"))

        assertTrue(context.run("ban.pardon", "STEVE").success)
        assertTrue(context.gates.single().check(JoinRequest("Steve")).allowed)
    }

    @Test
    fun `ban kicks online player through generic action`() {
        context.run("ban.set", "Alex", "7d", "cheating")

        val kick = context.invoked.single()
        assertEquals("player.kick", kick.action)
        assertEquals("Alex", kick.arguments.first())
        assertTrue(kick.arguments[1].contains("cheating"))
    }

    @Test
    fun `temp ban parses duration and lists with expiry`() {
        val result = context.run("ban.set", "Alex", "7d", "cheating")

        assertTrue(result.success)
        assertTrue(result.lines.first().contains("expires in 6d 23h") || result.lines.first().contains("expires in 7d"))
        assertTrue(context.run("ban.list").lines.single().contains("alex"))
        assertTrue(context.run("ban.check", "alex").lines.single().contains("cheating"))
    }

    @Test
    fun `expired temp ban no longer blocks`() {
        var now = 1_000L
        val store = BanStore(createTempDirectory("bans").resolve("bans.json"), clock = { now })
        store.set("steve", "bye", durationMs = 60_000)

        assertEquals("bye", store.activeBan("steve")?.reason)
        now = 100_000
        assertNull(store.activeBan("steve"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `store persists across instances`() {
        val file = createTempDirectory("bans").resolve("bans.json")
        BanStore(file).set("steve", "griefing")

        assertEquals("griefing", BanStore(file).activeBan("Steve")?.reason)
    }

    @Test
    fun `duration tokens parse and validate`() {
        assertEquals(30L * 60_000, BanDuration.parseMillis("30m"))
        assertEquals(7L * 86_400_000, BanDuration.parseMillis("7d"))
        assertNull(BanDuration.parseMillis("perm"))
        assertTrue(BanDuration.isDurationToken("12h"))
        assertFalse(BanDuration.isDurationToken("cheating"))
        assertFailsWith<IllegalArgumentException> { BanDuration.parseMillis("7x") }
    }

    @Test
    fun `ban without duration is permanent`() {
        val result = context.run("ban.set", "Steve")

        assertTrue(result.lines.first().contains("permanent"))
    }
}
