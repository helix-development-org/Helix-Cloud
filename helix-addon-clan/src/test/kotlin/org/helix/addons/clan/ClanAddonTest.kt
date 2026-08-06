package org.helix.addons.clan

import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `clan chat reaches online members only and requires a clan`() {
        createClan()
        context.run("clan", "Steve", "invite", "Alex")
        context.run("clan", "Alex", "accept", "STV")
        context.online += org.helix.api.player.OnlinePlayer(name = "Steve")
        context.online += org.helix.api.player.OnlinePlayer(name = "Alex")
        context.invocations.clear()

        assertTrue(context.run("cc", "Steve", "hello", "clan").success)
        val delivered = context.invocations.filter { it.action == "player.message" }
        assertEquals(setOf("steve", "alex"), delivered.map { it.arguments.first() }.toSet())
        assertTrue(delivered.all { it.arguments[1].contains("hello clan") && it.arguments[1].contains("STV") })

        assertFalse(context.run("cc", "Steve").success, "empty message shows usage")
        assertFalse(context.run("cc", "Nobody", "hi").success, "clanless players cannot use clan chat")
    }

    @Test
    fun `panel admin actions manage a clan end to end`() {
        createClan()
        context.run("clan", "Steve", "invite", "Alex")
        context.run("clan", "Alex", "accept", "STV")

        // detail: members with roles as JSON
        val detail = context.run("clan.detail", "STV")
        assertTrue(detail.success)
        assertTrue(detail.lines.single().contains("\"owner\": \"steve\""))
        assertTrue(detail.lines.single().contains("\"alex\""))

        // settag: validation + republished bridge values
        assertFalse(context.run("clan.settag", "STV", "TOOLONG").success)
        assertTrue(context.run("clan.settag", "STV", "NEW").success)
        assertEquals("NEW", context.bridgeValues["clan.tag.steve"])

        // transfer: new owner, old owner demoted; owner cannot be removed
        assertTrue(context.run("clan.transfer", "NEW", "Alex").success)
        assertFalse(context.run("clan.remove", "Alex").success, "owner is protected")
        assertTrue(context.run("clan.remove", "Steve").success)
        assertEquals("", context.bridgeValues["clan.tag.steve"])

        // disband clears the remaining member's tag value
        assertTrue(context.run("clan.disband", "NEW").success)
        assertEquals("", context.bridgeValues["clan.tag.alex"])
        assertFalse(context.run("clan.detail", "NEW").success)
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
    fun `clan name format is validated`() {
        assertFalse(context.run("clan", "Steve", "create", "STV", "Hi").success, "too short")
        assertFalse(
            context.run("clan", "Steve", "create", "STV", "A".repeat(25)).success,
            "too long",
        )
        assertFalse(
            context.run("clan", "Steve", "create", "STV", "&cRed Team").success,
            "color codes are rejected",
        )
        assertFalse(
            context.run("clan", "Steve", "create", "STV", " Padded").success,
            "leading whitespace is rejected",
        )
        assertFalse(
            context.run("clan", "Steve", "create", "STV", "Padded ").success,
            "trailing whitespace is rejected",
        )
        assertTrue(context.run("clan", "Steve", "create", "STV", "The Steverians").success)
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
    fun `withdraw never pays out when the bank debit fails`() {
        createClan()
        stubEco("eco.give", ActionResult.ok())

        val result = context.run("clan", "Steve", "bank", "withdraw", "9000")

        assertFalse(result.success)
        assertTrue(context.invocations.none { it.action == "eco.give" }, "coins must never reach the player")
    }

    @Test
    fun `withdraw refunds the clan bank when the payout fails`() {
        createClan()
        stubEco("eco.take", ActionResult.ok())
        context.run("clan", "Steve", "bank", "deposit", "500")
        stubEco("eco.give", ActionResult.error("economy unavailable"))

        val result = context.run("clan", "Steve", "bank", "withdraw", "200")

        assertFalse(result.success)
        assertEquals(500L, ClanStore(context.storage).clanById("stevers")!!.bank, "the debit must be undone")
    }

    @Test
    fun `deposit refunds the player when crediting the bank fails`() {
        createClan()
        // Simulates the clan being disbanded by another command between the
        // player's coins being taken and the bank actually being credited —
        // routed through the addon's own admin action so it mutates the same
        // live ClanStore instance the bank command uses.
        context.registerAction(ActionDescriptor("eco.take", "take", "eco.take")) {
            context.run("clan.disband", "stevers")
            ActionResult.ok()
        }
        context.registerAction(ActionDescriptor("eco.give", "give", "eco.give")) { ActionResult.ok() }

        val result = context.run("clan", "Steve", "bank", "deposit", "500")

        assertFalse(result.success)
        assertTrue(
            context.invocations.any { it.action == "eco.give" && it.arguments == listOf("Steve", "500") },
            "the player must be refunded instead of the coins being destroyed",
        )
    }

    @Test
    fun `display resolver returns the tag for members and null otherwise`() {
        createClan()
        val resolver = context.displayResolvers.single()

        assertNull(resolver.resolve("Steve"), "unverified tags stay invisible")
        assertTrue(context.run("clan.verify", "STV").success)
        assertEquals(" &8[&bSTV&8]", resolver.resolve("Steve")?.suffix)
        assertNull(resolver.resolve("Nobody"))
    }

    @Test
    fun `verification gates the tag lifecycle`() {
        createClan()
        context.online += org.helix.api.player.OnlinePlayer(name = "Steve")

        // fresh clan: pending — bridge value empty, create response carries the hint
        assertEquals("", context.bridgeValues["clan.tag.steve"])

        // verify: tag visible, members notified
        context.invocations.clear()
        assertTrue(context.run("clan.verify", "STV").success)
        assertEquals("STV", context.bridgeValues["clan.tag.steve"])
        assertTrue(
            context.invocations.any { it.action == "player.message" && it.arguments.first() == "steve" },
            "online members are notified about the approval",
        )

        // player-driven tag change resets the verification and hides the tag again
        assertTrue(context.run("clan", "Steve", "tag", "ABC").success)
        assertEquals("", context.bridgeValues["clan.tag.steve"])
        assertNull(context.displayResolvers.single().resolve("Steve"))

        // admin settag counts as approved
        assertTrue(context.run("clan.settag", "ABC", "XYZ").success)
        assertEquals("XYZ", context.bridgeValues["clan.tag.steve"])

        // unverify hides it again
        assertTrue(context.run("clan.unverify", "XYZ").success)
        assertEquals("", context.bridgeValues["clan.tag.steve"])
    }

    @Test
    fun `player-data provider exports membership, but refuses to delete an owner`() {
        createClan()
        context.run("clan", "Steve", "invite", "Alex")
        context.run("clan", "Alex", "accept", "STV")
        val provider = context.playerDataProviders.single()

        assertTrue(provider.export("alex")!!.contains("STV"))
        assertFalse(provider.delete("steve"), "an owner must transfer or disband first")
        assertTrue(provider.delete("alex"))
        assertNull(ClanStore(context.storage).clanOf("Alex"))
    }
}
