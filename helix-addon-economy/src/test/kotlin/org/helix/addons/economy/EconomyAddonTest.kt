package org.helix.addons.economy

import org.helix.addon.sdk.testing.RecordingAddonContext
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EconomyAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("economy"))
    private val addon = EconomyAddon().also { it.onEnable(context) }

    @Test
    fun `machine api actions are bridge invocable and answer plain numbers`() {
        listOf("eco.api.balance", "eco.api.deposit", "eco.api.withdraw").forEach { name ->
            assertTrue(context.handlers.getValue(name).first.bridgeInvocable, "$name must be bridgeInvocable")
        }

        val start = context.run("eco.api.balance", "Steve").lines.single().toLong()
        assertEquals((start + 40).toString(), context.run("eco.api.deposit", "Steve", "40").lines.single())
        assertEquals((start + 40).toString(), context.bridgeValues["economy.balance.steve"])
        assertEquals(start.toString(), context.run("eco.api.withdraw", "Steve", "40").lines.single())
        assertEquals(start.toString(), context.bridgeValues["economy.balance.steve"])
    }

    @Test
    fun `machine api withdraw rejects uncovered and invalid amounts`() {
        val start = context.run("eco.api.balance", "Steve").lines.single().toLong()

        val uncovered = context.run("eco.api.withdraw", "Steve", (start + 1).toString())
        assertFalse(uncovered.success)
        assertTrue(uncovered.lines.single().contains("insufficient"))

        assertFalse(context.run("eco.api.deposit", "Steve", "-5").success)
        assertFalse(context.run("eco.api.deposit", "Steve", "abc").success)
        assertFalse(context.run("eco.api.deposit", "Steve").success)
        assertEquals(start.toString(), context.run("eco.api.balance", "Steve").lines.single())
    }

    @Test
    fun `admin actions manage balances`() {
        assertTrue(context.run("eco.give", "Steve", "100").success)
        assertTrue(context.run("eco.take", "Steve", "30").success)
        assertTrue(context.run("eco.get", "steve").lines.single().contains("70"))
        assertTrue(context.run("eco.set", "Steve", "500").success)
        assertTrue(context.run("balance", "Steve").lines.single().contains("500"))
    }

    @Test
    fun `overdraft is rejected`() {
        context.run("eco.set", "Steve", "10")

        assertFalse(context.run("eco.take", "Steve", "50").success)
        assertTrue(context.run("eco.get", "Steve").lines.single().contains("10"))
    }

    @Test
    fun `pay transfers coins and notifies the receiver`() {
        context.run("eco.give", "Steve", "100")
        context.online += org.helix.api.player.OnlinePlayer(name = "Alex")

        val result = context.run("pay", "Steve", "Alex", "40")

        assertTrue(result.success, result.lines.joinToString())
        assertTrue(context.run("eco.get", "Steve").lines.single().contains("60"))
        assertTrue(context.run("eco.get", "Alex").lines.single().contains("40"))
        val notification = context.invocations.single { it.action == "player.message" }
        assertEquals("Alex", notification.arguments.first())
    }

    @Test
    fun `pay to an offline but known player still works`() {
        context.run("eco.give", "Steve", "100")
        context.recordJoin("alex", "11111111-1111-1111-1111-111111111111")

        assertTrue(context.run("pay", "Steve", "Alex", "40").success)
        assertTrue(context.run("eco.get", "Alex").lines.single().contains("40"))
    }

    @Test
    fun `pay to a name nobody has ever joined with is rejected`() {
        context.run("eco.give", "Steve", "100")

        val result = context.run("pay", "Steve", "Alex", "40")

        assertFalse(result.success)
        assertTrue(context.run("eco.get", "Steve").lines.single().contains("100"), "the sender must keep the coins")
        assertTrue(context.run("eco.get", "Alex").lines.single().contains("1000"), "no balance record was created")
    }

    @Test
    fun `pay validates funds amount and self`() {
        context.run("eco.set", "Steve", "10")
        context.online += org.helix.api.player.OnlinePlayer(name = "Alex")

        assertFalse(context.run("pay", "Steve", "Alex", "50").success)
        assertFalse(context.run("pay", "Steve", "Alex", "-5").success)
        assertFalse(context.run("pay", "Steve", "Alex", "0").success)
        assertFalse(context.run("pay", "Steve", "Steve", "5").success)
        assertFalse(context.run("pay", "Steve", "Alex", "abc").success)
    }

    @Test
    fun `balances persist across instances`() {
        val storage = org.helix.api.storage.InMemoryAddonStorage()
        BalanceStore(storage).add("steve", 250)

        assertEquals(250, BalanceStore(storage).balance("Steve"))
    }

    @Test
    fun `new players start with the starting balance`() {
        assertTrue(context.run("eco.get", "Newbie").lines.single().contains("1000"))
    }

    @Test
    fun `player-data provider exports and resets a balance`() {
        val provider = context.playerDataProviders.single()
        assertEquals(null, provider.export("newbie"))

        context.run("eco.give", "Steve", "100")

        assertTrue(provider.export("steve")!!.contains("1100"))
        assertTrue(provider.delete("steve"))
        assertTrue(context.run("eco.get", "Steve").lines.single().contains("1000"))
        assertEquals(null, provider.export("newbie"), "players who never transacted stay unexported")
    }
}
