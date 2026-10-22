package org.helix.addons.citybuild

import kotlinx.serialization.json.JsonObject
import org.helix.addons.citybuild.config.ConfigStore
import org.helix.addons.citybuild.model.Auction
import org.helix.addons.citybuild.model.AuctionStatus
import org.helix.addons.citybuild.model.ListingStatus
import org.helix.addons.citybuild.model.PlotListing
import org.helix.addons.citybuild.service.AuctionService
import org.helix.addons.citybuild.service.CityContext
import org.helix.addons.citybuild.service.CityException
import org.helix.addons.citybuild.service.PlotMarketService
import org.helix.addons.citybuild.service.jsonOf
import org.helix.addons.citybuild.store.CityRepository
import org.helix.addons.citybuild.store.DocumentCityRepository
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Repository that fails the state change right after a successful payment. */
private class FailingAfterPayment(private val delegate: CityRepository) : CityRepository by delegate {
    var failSold = false
    var failPending = false

    override fun transitionAuction(id: Long, from: Set<String>, to: String, buyer: String?, closedAt: Long?, notBefore: Long?): Auction? {
        if (failSold && to == AuctionStatus.SOLD) error("database gone")
        return delegate.transitionAuction(id, from, to, buyer, closedAt, notBefore)
    }

    override fun transitionListing(id: Long, from: Set<String>, to: String, buyer: String?, closedAt: Long?): PlotListing? {
        if (failPending && to == ListingStatus.PENDING) return null
        return delegate.transitionListing(id, from, to, buyer, closedAt)
    }
}

class RefundAfterPaymentTest {
    private val kronen = FakeKronen()
    private val storage = InMemoryAddonStorage()
    private val repository = FailingAfterPayment(DocumentCityRepository(storage))
    private val ctx = CityContext(repository, ConfigStore(storage), kronen, TestClock())

    private fun listing(price: Long): JsonObject =
        jsonOf("sellerName" to "Alice", "item" to "AAEC", "material" to "DIAMOND_SWORD", "displayName" to "Schwert", "category" to "tools", "price" to price, "hours" to 24)

    @Test
    fun `auction purchase is refunded when the sale cannot be recorded`() {
        val auctions = AuctionService(ctx)
        kronen.balances[ALICE] = 100
        kronen.balances[BOB] = 5000
        val created = auctions.create(ALICE, SHARD, 5, listing(1000))
        val sellerBefore = kronen.balance(ALICE)
        repository.failSold = true
        assertFailsWith<CityException> { auctions.buy(BOB, SHARD, created.id) }
        assertEquals(5000, kronen.balance(BOB), "buyer gets the full price back")
        assertEquals(sellerBefore, kronen.balance(ALICE), "seller loses what was received")
        assertEquals(AuctionStatus.ACTIVE, repository.auction(created.id)?.status)
    }

    @Test
    fun `plot purchase is refunded when the pending state cannot be stored`() {
        val market = PlotMarketService(ctx)
        kronen.balances[BOB] = 50_000
        val offer = market.offer(ALICE, SHARD, jsonOf("sellerName" to "Alice", "plotId" to "1;1", "price" to 20_000))
        repository.failPending = true
        assertFailsWith<CityException> { market.buy(BOB, SHARD, offer.id) }
        assertEquals(50_000, kronen.balance(BOB))
        assertEquals(0, kronen.balance(ALICE))
        assertEquals(ListingStatus.ACTIVE, repository.listing(offer.id)?.status)
    }
}
