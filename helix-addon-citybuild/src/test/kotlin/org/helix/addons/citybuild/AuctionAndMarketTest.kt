package org.helix.addons.citybuild

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.helix.addons.citybuild.model.AuctionStatus
import org.helix.addons.citybuild.model.ListingStatus
import org.helix.addons.citybuild.service.AuctionService
import org.helix.addons.citybuild.service.CityException
import org.helix.addons.citybuild.service.PlotMarketService
import org.helix.addons.citybuild.service.jsonOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuctionAndMarketTest {
    private val clock = TestClock()
    private val kronen = FakeKronen()
    private val ctx = testContext(clock, kronen)
    private val auctions = AuctionService(ctx)
    private val market = PlotMarketService(ctx)

    private fun listing(price: Long = 1000, hours: Int = 24): JsonObject =
        jsonOf("sellerName" to "Alice", "item" to "AAEC", "material" to "DIAMOND_SWORD", "displayName" to "Schwert", "category" to "tools", "price" to price, "hours" to hours)

    @Test
    fun `listing fee is charged and the sale fee leaves the economy`() {
        kronen.balances[ALICE] = 100
        kronen.balances[BOB] = 5000
        val created = auctions.create(ALICE, SHARD, 5, listing(price = 1000))
        assertEquals(80, kronen.balance(ALICE)) // 2 % of 1000 = 20
        val sold = auctions.buy(BOB, SHARD, created.id)
        assertEquals(AuctionStatus.SOLD, sold.status)
        assertEquals("AAEC", sold.item)
        assertEquals(4000, kronen.balance(BOB))
        assertEquals(80 + 950, kronen.balance(ALICE)) // 5 % sale fee
        assertEquals(listOf(created.id), auctions.soldNotifications(ALICE, SHARD).map { it.id })
        assertTrue(auctions.soldNotifications(ALICE, SHARD).isEmpty())
    }

    @Test
    fun `minimum listing fee, limits and validation`() {
        kronen.balances[ALICE] = 1000
        auctions.create(ALICE, SHARD, 1, listing(price = 50))
        assertEquals(990, kronen.balance(ALICE)) // min fee 10
        assertEquals("limit", assertFailsWith<CityException> { auctions.create(ALICE, SHARD, 1, listing()) }.code)
        assertEquals("invalid_duration", assertFailsWith<CityException> { auctions.create(ALICE, SHARD, 5, listing(hours = 5)) }.code)
        assertEquals("invalid_price", assertFailsWith<CityException> { auctions.create(ALICE, SHARD, 5, listing(price = 0)) }.code)
        kronen.balances[BOB] = 0
        assertEquals("insufficient_funds", assertFailsWith<CityException> { auctions.create(BOB, SHARD, 5, listing()) }.code)
    }

    @Test
    fun `two concurrent buyers - only one wins`() {
        kronen.balances[ALICE] = 100
        kronen.balances[BOB] = 5000
        kronen.balances[CAROL] = 5000
        val created = auctions.create(ALICE, SHARD, 5, listing())
        kronen.transferDelayMillis = 100
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val results = listOf(BOB, CAROL).map { buyer ->
            pool.submit<String> {
                start.await()
                runCatching { auctions.buy(buyer, SHARD, created.id); "ok" }.getOrElse { (it as CityException).code }
            }
        }
        start.countDown()
        val outcomes = results.map { it.get(5, TimeUnit.SECONDS) }.sorted()
        pool.shutdown()
        assertEquals(listOf("not_available", "ok"), outcomes)
        assertEquals(9000, kronen.balance(BOB) + kronen.balance(CAROL))
    }

    @Test
    fun `failed payment releases the listing and wrong instance is refused`() {
        kronen.balances[ALICE] = 100
        val created = auctions.create(ALICE, SHARD, 5, listing())
        kronen.balances[BOB] = 10
        assertEquals("insufficient_funds", assertFailsWith<CityException> { auctions.buy(BOB, SHARD, created.id) }.code)
        assertEquals(AuctionStatus.ACTIVE, ctx.repository.auction(created.id)!!.status)
        assertEquals("wrong_shard", assertFailsWith<CityException> { auctions.buy(BOB, "CityBuild-2", created.id) }.code)
        assertEquals("own_listing", assertFailsWith<CityException> { auctions.buy(ALICE, SHARD, created.id) }.code)
    }

    @Test
    fun `expired and cancelled listings land in the pickup box`() {
        kronen.balances[ALICE] = 1000
        val first = auctions.create(ALICE, SHARD, 5, listing(hours = 12))
        val second = auctions.create(ALICE, SHARD, 5, listing(hours = 48))
        val pickup = auctions.cancel(ALICE, SHARD, second.id)
        assertEquals("auction.cancelled", pickup.source)
        clock.advanceSeconds(13 * 3600)
        assertEquals(0, (auctions.list(SHARD, 0, 10, null)["entries"]!!.jsonArray.size))
        kronen.balances[BOB] = 5000
        assertEquals("not_available", assertFailsWith<CityException> { auctions.buy(BOB, SHARD, first.id) }.code)
        val expired = auctions.expire(SHARD)
        assertEquals(1, expired.size)
        assertEquals(2, ctx.repository.pickups(ALICE, SHARD).size)
        val taken = ctx.repository.takePickup(ALICE, SHARD, pickup.id)!!
        assertEquals("AAEC", taken.item)
        assertNull(ctx.repository.takePickup(ALICE, SHARD, pickup.id))
        assertNull(ctx.repository.takePickup(BOB, SHARD, expired.single()["pickupId"].toString().toLong()))
    }

    @Test
    fun `list filters sorts and pages`() {
        kronen.balances[ALICE] = 10_000
        auctions.create(ALICE, SHARD, 10, listing(price = 300))
        auctions.create(ALICE, SHARD, 10, listing(price = 100).plus("category" to kotlinx.serialization.json.JsonPrimitive("blocks")).let(::JsonObject))
        auctions.create(ALICE, SHARD, 10, listing(price = 200))
        val cheap = auctions.list(SHARD, 0, 2, jsonOf("sort" to "price_asc"))
        assertEquals(2, cheap["pages"].toString().toInt())
        assertEquals("100", cheap["entries"]!!.jsonArray[0].let { (it as JsonObject)["price"].toString() })
        assertEquals(2, auctions.list(SHARD, 0, 10, jsonOf("category" to "tools"))["entries"]!!.jsonArray.size)
        assertEquals(3, auctions.list(SHARD, 0, 10, jsonOf("search" to "schwert"))["entries"]!!.jsonArray.size)
    }

    @Test
    fun `plot market purchase goes pending until the instance completes it`() {
        kronen.balances[BOB] = 30_000
        val offer = market.offer(ALICE, SHARD, jsonOf("sellerName" to "Alice", "plotId" to "plots;3;4", "price" to 20_000, "x" to 10, "z" to 20))
        assertEquals("already_listed", assertFailsWith<CityException> { market.offer(ALICE, SHARD, jsonOf("plotId" to "plots;3;4", "price" to 1)) }.code)
        val pending = market.buy(BOB, SHARD, offer.id)
        assertEquals(ListingStatus.PENDING, pending.status)
        assertEquals(BOB, pending.buyer)
        assertEquals(10_000, kronen.balance(BOB))
        assertEquals(19_000, kronen.balance(ALICE)) // 5 % fee
        assertEquals(listOf(offer.id), ctx.repository.listings(SHARD, setOf(ListingStatus.PENDING)).map { it.id })
        assertEquals(ListingStatus.SOLD, market.complete(SHARD, offer.id).status)
        assertEquals("not_pending", assertFailsWith<CityException> { market.complete(SHARD, offer.id) }.code)
    }

    @Test
    fun `plot market limit, failed payment and withdrawal`() {
        repeat(3) { market.offer(ALICE, SHARD, jsonOf("plotId" to "plots;$it;0", "price" to 100)) }
        assertEquals("limit", assertFailsWith<CityException> { market.offer(ALICE, SHARD, jsonOf("plotId" to "plots;9;0", "price" to 100)) }.code)
        val first = ctx.repository.listings(SHARD, setOf(ListingStatus.ACTIVE)).last()
        kronen.balances[BOB] = 5
        assertEquals("insufficient_funds", assertFailsWith<CityException> { market.buy(BOB, SHARD, first.id) }.code)
        assertEquals(ListingStatus.ACTIVE, ctx.repository.listing(first.id)!!.status)
        assertEquals("not_owner", assertFailsWith<CityException> { market.withdraw(BOB, SHARD, first.id) }.code)
        assertEquals(ListingStatus.WITHDRAWN, market.withdraw(ALICE, SHARD, first.id).status)
        market.offer(ALICE, SHARD, jsonOf("plotId" to "plots;9;0", "price" to 100))
    }
}
