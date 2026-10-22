package org.helix.addons.economy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.player.OnlinePlayer
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EconomyAddonTest {
    private val steve = "00000000-0000-0000-0000-00000000000a"
    private val alex = "00000000-0000-0000-0000-00000000000b"
    private val storage = InMemoryAddonStorage()
    private val context = RecordingAddonContext(createTempDirectory("economy"), storage).apply {
        recordJoin("Steve", steve)
        recordJoin("Alex", alex)
    }
    private val addon = EconomyAddon().also { it.onEnable(context) }

    private fun balance(player: String) = context.run("eco.api.balance", player).lines.single().toLong()

    @Test
    fun `machine api actions are bridge invocable`() {
        listOf("eco.api.balance", "eco.api.deposit", "eco.api.withdraw", "eco.api.transfer", "eco.api.history", "eco.api.limit")
            .forEach { name -> assertTrue(context.handlers.getValue(name).first.bridgeInvocable, "$name must be bridgeInvocable") }
        listOf("eco.give", "eco.take", "eco.set", "eco.export").forEach { name ->
            assertFalse(context.handlers.getValue(name).first.bridgeInvocable, "$name must stay admin-only")
        }
    }

    @Test
    fun `legacy two-argument deposit and withdraw still answer plain numbers`() {
        assertEquals("1040", context.run("eco.api.deposit", "Steve", "40").lines.single())
        assertEquals("1040", context.bridgeValues["economy.balance.steve"])
        assertEquals("1000", context.run("eco.api.withdraw", "Steve", "40").lines.single())
        assertEquals("1000", context.bridgeValues["economy.balance.steve"])
    }

    @Test
    fun `players resolve by name, uuid and online list`() {
        context.run("eco.api.deposit", steve, "5", "job")
        assertEquals(1005, balance("steve"))
        assertEquals(1005, balance(steve.uppercase()))
        context.online += OnlinePlayer(name = "Neo", uuid = "00000000-0000-0000-0000-00000000000e")
        assertEquals("1001", context.run("eco.api.deposit", "Neo", "1").lines.single())
        val unknown = context.run("eco.api.deposit", "Nobody", "1")
        assertFalse(unknown.success)
        assertEquals("unknown player: Nobody", unknown.lines.single())
    }

    @Test
    fun `withdraw rejects uncovered and invalid amounts`() {
        val uncovered = context.run("eco.api.withdraw", "Steve", "1001")
        assertFalse(uncovered.success)
        assertEquals("insufficient funds", uncovered.lines.single())
        assertFalse(context.run("eco.api.deposit", "Steve", "-5").success)
        assertFalse(context.run("eco.api.deposit", "Steve", "abc").success)
        assertFalse(context.run("eco.api.deposit", "Steve").success)
        assertEquals(1000, balance("Steve"))
    }

    @Test
    fun `daily limit through the machine api`() {
        assertTrue(context.run("eco.api.deposit", "Steve", "2500", "adminshop", "-", "citybuild.sell", "3000").success)
        val rejected = context.run("eco.api.deposit", "Steve", "600", "adminshop", "-", "citybuild.sell", "3000")
        assertFalse(rejected.success)
        assertEquals("limit reached: 500", rejected.lines.single())
        val state = Json.parseToJsonElement(context.run("eco.api.limit", "Steve", "citybuild.sell", "3000").lines.single()).jsonObject
        assertEquals(2500, state.getValue("used").jsonPrimitive.long)
        assertEquals(500, state.getValue("remaining").jsonPrimitive.long)
        assertFalse(context.run("eco.api.deposit", "Steve", "1", "x", "-", "citybuild.sell").success, "limit key needs a max")
    }

    @Test
    fun `transfer answers with a json receipt and withholds the fee`() {
        val result = context.run("eco.api.transfer", "Steve", "Alex", "100", "shop", "30", "chest-1")
        assertTrue(result.success, result.lines.joinToString())
        val receipt = Json.decodeFromString<TransferReceipt>(result.lines.single())
        assertEquals(TransferReceipt(steve, alex, 100, 3, 97, 900, 1097), receipt)
        assertEquals("900", context.bridgeValues["economy.balance.steve"])
        assertEquals("1097", context.bridgeValues["economy.balance.alex"])
        assertEquals("insufficient funds", context.run("eco.api.transfer", "Steve", "Alex", "5000").lines.single())
        assertFalse(context.run("eco.api.transfer", "Steve", "Steve", "1").success)
        assertFalse(context.run("eco.api.transfer", "Steve", "Alex", "1", "x", "1001").success)
    }

    @Test
    fun `history is json newest first`() {
        context.run("eco.api.deposit", "Steve", "5", "job")
        context.run("eco.api.withdraw", "Steve", "2", "shop", "chest-9")
        val history = Json.parseToJsonElement(context.run("eco.api.history", "Steve", "10").lines.single()).jsonArray
        assertEquals(listOf(-2L, 5L), history.map { it.jsonObject.getValue("delta").jsonPrimitive.long })
        assertEquals("chest-9", history.first().jsonObject.getValue("ref").jsonPrimitive.content)
    }

    @Test
    fun `kronen command shows balance and bookings`() {
        assertTrue(context.run("kronen", "Steve").lines.single().contains("1.000"))
        assertTrue(context.run("kronen", "Steve", "verlauf").lines.single().isNotBlank(), "empty history answers with a hint")
        context.run("eco.api.deposit", "Steve", "5", "job")
        val lines = context.run("kronen", "Steve", "verlauf").lines
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("+5"))
    }

    @Test
    fun `admin actions manage balances`() {
        assertTrue(context.run("eco.give", "Steve", "100").success)
        assertTrue(context.run("eco.take", "Steve", "30").success)
        assertTrue(context.run("eco.get", "steve").lines.single().contains("1070"))
        assertTrue(context.run("eco.set", "Steve", "500").success)
        assertTrue(context.run("balance", "Steve").lines.single().contains("500"))
        assertFalse(context.run("eco.take", "Steve", "501").success)
        assertFalse(context.run("eco.give", "Nobody", "1").success)
        val export = Json.parseToJsonElement(context.run("eco.export").lines.single()).jsonObject
        assertEquals(500, export.getValue("Steve").jsonPrimitive.long)
    }

    @Test
    fun `pay transfers crowns and notifies the receiver`() {
        context.online += OnlinePlayer(name = "Alex", uuid = alex)

        val result = context.run("pay", "Steve", "Alex", "40")

        assertTrue(result.success, result.lines.joinToString())
        assertEquals(960, balance("Steve"))
        assertEquals(1040, balance("Alex"))
        val notification = context.invocations.single { it.action == "player.message" }
        assertEquals("Alex", notification.arguments.first())
    }

    @Test
    fun `pay to a name nobody has ever joined with is rejected`() {
        assertFalse(context.run("pay", "Steve", "Ghost", "40").success)
        assertEquals(1000, balance("Steve"))
    }

    @Test
    fun `pay validates funds amount and self`() {
        assertFalse(context.run("pay", "Steve", "Alex", "5000").success)
        assertFalse(context.run("pay", "Steve", "Alex", "-5").success)
        assertFalse(context.run("pay", "Steve", "Alex", "0").success)
        assertFalse(context.run("pay", "Steve", "Steve", "5").success)
        assertFalse(context.run("pay", "Steve", "Alex", "abc").success)
    }

    @Test
    fun `legacy name-keyed balances are migrated on enable`() {
        val legacy = InMemoryAddonStorage().apply { write("balances", """{"steve":250,"ghost":70}""") }
        val ctx = RecordingAddonContext(createTempDirectory("economy-legacy"), legacy).apply { recordJoin("Steve", steve) }
        EconomyAddon().onEnable(ctx)

        assertEquals("250", ctx.run("eco.api.balance", "Steve").lines.single())
        ctx.recordJoin("Ghost", "00000000-0000-0000-0000-00000000000d")
        ctx.playerListeners.forEach { it.onJoin(OnlinePlayer("Ghost", "00000000-0000-0000-0000-00000000000d")) }
        assertEquals("70", ctx.run("eco.api.balance", "Ghost").lines.single())
        assertEquals("70", ctx.bridgeValues["economy.balance.ghost"])
    }

    @Test
    fun `player-data provider exports and deletes an account`() {
        val provider = context.playerDataProviders.single()
        assertEquals(null, provider.export("Steve"))

        context.run("eco.give", "Steve", "100")

        assertTrue(provider.export("Steve")!!.contains("1100"))
        assertTrue(provider.delete("Steve"))
        assertEquals(1000, balance("Steve"))
        assertEquals(null, provider.export("Steve"))
    }
}
