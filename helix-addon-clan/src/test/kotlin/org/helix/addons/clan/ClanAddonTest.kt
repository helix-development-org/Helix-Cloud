package org.helix.addons.clan

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult

class ClanAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("clan"))
    private val addon = ClanAddon().also { it.onEnable(context) }

    private fun createClan(owner: String = "Steve", tag: String = "STV", name: String = "Stevers") =
        context.run("clan", owner, "create", tag, name)

    /** Registers a fake economy action returning [result] and records its calls. */
    private fun stubEco(name: String, result: ActionResult) {
        context.registerAction(ActionDescriptor(name, name, name)) { result }
    }

    @Test
    fun `create then info round-trips`() {
        assertTrue(createClan().success)

        val info = context.run("clan", "Steve", "info")
        assertTrue(info.success)
        assertTrue(info.lines.any { it.contains("Stevers") })
        assertTrue(info.lines.any { it.contains("STV") })
        assertTrue(info.lines.any { it.contains("Owner") && it.contains("steve") })
    }

    @Test
    fun `tag and name uniqueness and validation are enforced`() {
        assertTrue(createClan().success)

        assertFalse(context.run("clan", "Alex", "create", "STV", "Other").success)
        assertFalse(context.run("clan", "Alex", "create", "ABC", "Stevers").success)
        assertFalse(context.run("clan", "Alex", "create", "X", "TooShort").success)
        assertFalse(context.run("clan", "Alex", "create", "TOOLONG", "Bad").success)
        assertFalse(context.run("clan", "Steve", "create", "NEW", "Again").success)
    }

    @Test
    fun `invite accept joins the clan and notifies members`() {
        createClan()
        val invited = context.run("clan", "Steve", "invite", "Alex")
        assertTrue(invited.success)
        assertEquals(
            "Alex",
            context.invocations.last { it.action == "player.message" }.arguments.first(),
        )

        val accepted = context.run("clan", "Alex", "accept", "STV")
        assertTrue(accepted.success)
        assertEquals("stevers", context.storage.let { ClanStore(it).clanIdOf("Alex") })
        assertEquals(2, ClanStore(context.storage).clanById("stevers")!!.members.size)
    }

    @Test
    fun `accept without invite is rejected`() {
        createClan()
        assertFalse(context.run("clan", "Alex", "accept", "STV").success)
    }

    @Test
    fun `non-officer cannot invite`() {
        createClan()
        context.run("clan", "Steve", "invite", "Alex")
        context.run("clan", "Alex", "accept", "STV")

        assertFalse(context.run("clan", "Alex", "invite", "Bob").success)
    }

    @Test
    fun `leave and owner disband rules`() {
        createClan()
        context.run("clan", "Steve", "invite", "Alex")
        context.run("clan", "Alex", "accept", "STV")

        assertFalse(context.run("clan", "Steve", "leave").success)
        assertTrue(context.run("clan", "Alex", "leave").success)

        val disband = context.run("clan", "Steve", "leave")
        assertTrue(disband.success)
        assertNull(ClanStore(context.storage).clanById("stevers"))
    }

    @Test
    fun `kick promote and demote respect roles`() {
        createClan()
        context.run("clan", "Steve", "invite", "Alex")
        context.run("clan", "Alex", "accept", "STV")
        context.run("clan", "Steve", "invite", "Bob")
        context.run("clan", "Bob", "accept", "STV")

        assertTrue(context.run("clan", "Steve", "promote", "Alex").success)
        assertFalse(context.run("clan", "Alex", "promote", "Bob").success)
        assertFalse(context.run("clan", "Alex", "kick", "Steve").success)
        assertTrue(context.run("clan", "Alex", "kick", "Bob").success)
        assertTrue(context.run("clan", "Steve", "demote", "Alex").success)
        assertNull(ClanStore(context.storage).clanById("stevers")!!.members["bob"])
    }

    @Test
    fun `bank deposit invokes eco take and credits the bank`() {
        createClan()
        stubEco("eco.take", ActionResult.ok())

        val result = context.run("clan", "Steve", "bank", "deposit", "500")

        assertTrue(result.success)
        assertTrue(context.invocations.any { it.action == "eco.take" && it.arguments == listOf("Steve", "500") })
        assertEquals(500L, ClanStore(context.storage).clanById("stevers")!!.bank)
    }

    @Test
    fun `deposit failure does not credit the bank`() {
        createClan()
        stubEco("eco.take", ActionResult.error("insufficient"))

        val result = context.run("clan", "Steve", "bank", "deposit", "500")

        assertFalse(result.success)
        assertEquals(0L, ClanStore(context.storage).clanById("stevers")!!.bank)
    }

    @Test
    fun `withdraw checks role and funds and invokes eco give`() {
        createClan()
        stubEco("eco.take", ActionResult.ok())
        stubEco("eco.give", ActionResult.ok())
        context.run("clan", "Steve", "bank", "deposit", "500")
        context.run("clan", "Steve", "invite", "Alex")
        context.run("clan", "Alex", "accept", "STV")

        assertFalse(context.run("clan", "Alex", "bank", "withdraw", "100").success)
        assertFalse(context.run("clan", "Steve", "bank", "withdraw", "9000").success)

        val ok = context.run("clan", "Steve", "bank", "withdraw", "200")
        assertTrue(ok.success)
        assertTrue(context.invocations.any { it.action == "eco.give" && it.arguments == listOf("Steve", "200") })
        assertEquals(300L, ClanStore(context.storage).clanById("stevers")!!.bank)
    }

    @Test
    fun `display resolver returns the tag for members and null otherwise`() {
        createClan()
        val resolver = context.displayResolvers.single()

        assertEquals(" &8[&bSTV&8]", resolver.resolve("Steve")?.suffix)
        assertNull(resolver.resolve("Nobody"))
    }
}
