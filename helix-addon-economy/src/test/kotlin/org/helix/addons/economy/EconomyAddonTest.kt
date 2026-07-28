package org.helix.addons.economy

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext

class EconomyAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("economy"))
    private val addon = EconomyAddon().also { it.onEnable(context) }

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

        val result = context.run("pay", "Steve", "Alex", "40")

        assertTrue(result.success, result.lines.joinToString())
        assertTrue(context.run("eco.get", "Steve").lines.single().contains("60"))
        assertTrue(context.run("eco.get", "Alex").lines.single().contains("40"))
        val notification = context.invocations.single { it.action == "player.message" }
        assertEquals("Alex", notification.arguments.first())
    }

    @Test
    fun `pay validates funds amount and self`() {
        context.run("eco.set", "Steve", "10")

        assertFalse(context.run("pay", "Steve", "Alex", "50").success)
        assertFalse(context.run("pay", "Steve", "Alex", "-5").success)
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
