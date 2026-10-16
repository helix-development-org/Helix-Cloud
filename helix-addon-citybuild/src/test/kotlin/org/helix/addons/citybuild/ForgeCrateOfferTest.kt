package org.helix.addons.citybuild

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addons.citybuild.service.CityException
import org.helix.addons.citybuild.service.CrateEntry
import org.helix.addons.citybuild.service.CrateRoll
import org.helix.addons.citybuild.service.CrateService
import org.helix.addons.citybuild.service.CrateSpec
import org.helix.addons.citybuild.service.ForgeService
import org.helix.addons.citybuild.service.OfferService
import org.helix.addons.citybuild.service.OfferSpec
import org.helix.addons.citybuild.service.OfferWare
import org.helix.addons.citybuild.service.jsonOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ForgeCrateOfferTest {
    private val clock = TestClock()
    private val kronen = FakeKronen()
    private val ctx = testContext(clock, kronen)

    @Test
    fun `forge templates are versioned and serials are unique and tracked`() {
        val forge = ForgeService(ctx)
        val spec = jsonOf("material" to "DIAMOND_SWORD", "enchants" to mapOf("sharpness" to 10))
        assertEquals("1", forge.save("schwert_x", "Schwert X", ALICE, spec)["version"]!!.jsonPrimitive.content)
        val versionBefore = ctx.config.version()
        assertEquals("2", forge.save("schwert_x", "Schwert X", ALICE, spec)["version"]!!.jsonPrimitive.content)
        assertTrue(ctx.config.version() > versionBefore, "template changes bump the config version (live update)")
        val first = forge.issue(BOB, SHARD, "schwert_x", "give", 3)
        val serials = first["serials"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }
        assertEquals(listOf(1L, 2L, 3L), serials)
        assertEquals("2", first["version"]!!.jsonPrimitive.content)
        assertEquals("DIAMOND_SWORD", first["spec"]!!.jsonObject["material"]!!.jsonPrimitive.content)
        val oneOff = forge.issue(BOB, SHARD, ForgeService.ONE_OFF, "forge", 1)
        assertEquals(4L, oneOff["serials"]!!.jsonArray.single().jsonPrimitive.content.toLong())
        assertEquals(BOB, ctx.repository.serial(2)!!.issuedTo)
        assertEquals("not_found", assertFailsWith<CityException> { forge.issue(BOB, SHARD, "missing", "give", 1) }.code)
        assertEquals("invalid_id", assertFailsWith<CityException> { forge.save("Bad Id", "x", ALICE, spec) }.code)
    }

    @Test
    fun `crate draws follow the weights`() {
        val entries = listOf(CrateEntry("a", "kronen", amount = 1, weight = 75), CrateEntry("b", "kronen", amount = 1, weight = 25), CrateEntry("never", "kronen", weight = 0))
        val random = Random(42)
        val counts = (1..20_000).map { CrateRoll.draw(entries, random).id }.groupingBy { it }.eachCount()
        assertEquals(null, counts["never"])
        val share = counts.getValue("a") / 20_000.0
        assertTrue(share in 0.73..0.77, "share of a was $share")
        assertEquals(75.0, CrateRoll.chances(entries)["a"])
        assertFailsWith<IllegalArgumentException> { CrateRoll.draw(listOf(CrateEntry("x", "kronen", weight = 0)), random) }
    }

    @Test
    fun `opening a crate pays Kronen prizes and flags announcements`() {
        val crates = CrateService(ctx, Random(1))
        crates.save("boss", CrateSpec("Boss", entries = listOf(CrateEntry("jackpot", "kronen", amount = 5000, weight = 1, rarity = "BOSS"))))
        val result = crates.open(ALICE, SHARD, "boss")
        assertEquals("5000", result["paid"]!!.jsonPrimitive.content)
        assertEquals("true", result["announce"]!!.jsonPrimitive.content)
        assertEquals(5000, kronen.balance(ALICE))
        assertEquals("key_boss", crates.spec("boss").key)
        val standard = crates.open(ALICE, SHARD, "standard")
        assertTrue((standard["entry"] as JsonObject)["id"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals("invalid", assertFailsWith<CityException> { crates.save("bad", CrateSpec("Bad", entries = listOf(CrateEntry("x", "nope", weight = 1)))) }.code)
    }

    @Test
    fun `offer stock and player limits hold under concurrency`() {
        val offers = OfferService(ctx)
        ctx.config.putDocument(
            OfferService.COLLECTION,
            org.helix.addons.citybuild.config.ConfigDocument("elytra", 0, org.helix.addons.citybuild.service.CityContext.json.encodeToJsonElement(OfferSpec.serializer(), OfferSpec("Elytra", OfferWare("material", "ELYTRA"), 5000, stock = 1)) as JsonObject),
        )
        kronen.balances[ALICE] = 10_000
        kronen.balances[BOB] = 10_000
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val outcomes = listOf(ALICE, BOB).map { buyer ->
            pool.submit<String> {
                start.await()
                runCatching { offers.buy(buyer, SHARD, "elytra", 1, 0); "ok" }.getOrElse { (it as CityException).code }
            }
        }.also { start.countDown() }.map { it.get(5, TimeUnit.SECONDS) }.sorted()
        pool.shutdown()
        assertEquals(listOf("ok", "sold_out"), outcomes)
        assertEquals(15_000, kronen.balance(ALICE) + kronen.balance(BOB))
        // other instance has its own stock
        offers.buy(ALICE, "CityBuild-2", "elytra", 1, 0)
        assertEquals("0", offers.visible(SHARD, ALICE, 0).single().jsonObject["stockLeft"]!!.jsonPrimitive.content)
    }

    @Test
    fun `offer payment failure releases the reservation, windows and tiers apply`() {
        val offers = OfferService(ctx)
        val now = clock.millis()
        fun put(id: String, spec: OfferSpec) = ctx.config.putDocument(
            OfferService.COLLECTION,
            org.helix.addons.citybuild.config.ConfigDocument(id, 0, org.helix.addons.citybuild.service.CityContext.json.encodeToJsonElement(OfferSpec.serializer(), spec) as JsonObject),
        )
        put("keys", OfferSpec("Schlüssel", OfferWare("key", "standard"), 100, perPlayer = 2))
        put("later", OfferSpec("Später", OfferWare("material", "BEACON"), 100, startsAt = now + 60_000))
        put("premium", OfferSpec("Premium", OfferWare("material", "BEACON"), 100, minTier = 2))
        kronen.balances[ALICE] = 150
        offers.buy(ALICE, SHARD, "keys", 1, 0)
        assertEquals("insufficient_funds", assertFailsWith<CityException> { offers.buy(ALICE, SHARD, "keys", 1, 0) }.code)
        kronen.balances[ALICE] = 1000
        offers.buy(ALICE, SHARD, "keys", 1, 0)
        assertEquals("sold_out", assertFailsWith<CityException> { offers.buy(ALICE, SHARD, "keys", 1, 0) }.code)
        assertEquals("not_available", assertFailsWith<CityException> { offers.buy(ALICE, SHARD, "later", 1, 0) }.code)
        assertEquals("tier", assertFailsWith<CityException> { offers.buy(ALICE, SHARD, "premium", 1, 1) }.code)
        assertEquals(listOf("keys"), offers.visible(SHARD, ALICE, 0).map { it.jsonObject["id"]!!.jsonPrimitive.content })
        clock.advanceSeconds(61)
        assertEquals(setOf("keys", "later", "premium"), offers.visible(SHARD, ALICE, 3).map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
    }
}
