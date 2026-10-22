package org.helix.addons.citybuild.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.helix.addons.citybuild.economy.MoneyResult
import org.helix.addons.citybuild.model.ListingStatus
import org.helix.addons.citybuild.model.PlotListing

/**
 * Plot market per instance: players offer whole plots (with buildings) for Kronen.
 *
 * Purchase flow: `active → reserved` (one buyer wins), transfer buyer → seller minus the plot market
 * fee, then `pending` until the instance has handed the plot over via PlotSquared and reports
 * `complete`. Pending purchases survive crashes; the instance asks for them on start (`pending`).
 *
 * @property ctx shared dependencies.
 */
class PlotMarketService(private val ctx: CityContext) {
    /**
     * Offers a plot.
     *
     * @param seller seller uuid.
     * @param shard instance.
     * @param body `{sellerName, plotId, price, world, x, z, size}`.
     * @return the stored listing.
     */
    @Synchronized
    fun offer(seller: String, shard: String, body: JsonObject): PlotListing {
        val plotId = body.string("plotId") ?: Results.fail("invalid_plot", "plotId missing")
        val price = body["price"]?.jsonPrimitive?.longOrNull ?: Results.fail("invalid_price", "price missing")
        if (price <= 0) Results.fail("invalid_price", "price must be positive")
        val open = ctx.repository.listings(shard, setOf(ListingStatus.ACTIVE, ListingStatus.RESERVED, ListingStatus.PENDING))
        if (open.any { it.plotId == plotId }) Results.fail("already_listed", "plot $plotId is already on the market")
        val max = ctx.int("plotmarket.maxOffers", 3)
        if (open.count { it.seller == seller && it.status == ListingStatus.ACTIVE } >= max) Results.fail("limit", "plot market limit $max reached")
        val stored = ctx.repository.insertListing(
            PlotListing(
                shard = shard, plotId = plotId, seller = seller, sellerName = body.string("sellerName").orEmpty(), price = price,
                world = body.string("world") ?: "plots", x = body["x"]?.jsonPrimitive?.intOrNull ?: 0, z = body["z"]?.jsonPrimitive?.intOrNull ?: 0,
                size = body["size"]?.jsonPrimitive?.intOrNull ?: 1, createdAt = ctx.now(),
            ),
        )
        ctx.log("plotmarket.offer", shard, seller, ref = "plot:${stored.id}", amount = price, data = jsonOf("plotId" to plotId))
        return stored
    }

    /**
     * Withdraws an offer.
     *
     * @param actor seller uuid (or any uuid when [admin]).
     * @param shard instance.
     * @param id listing id.
     * @param admin team removal.
     * @return the withdrawn listing.
     */
    fun withdraw(actor: String, shard: String, id: Long, admin: Boolean = false): PlotListing {
        val listing = ctx.repository.listing(id)?.takeIf { it.shard == shard } ?: Results.fail("not_found", "no listing $id")
        if (!admin && listing.seller != actor) Results.fail("not_owner", "not your listing")
        return ctx.repository.transitionListing(id, setOf(ListingStatus.ACTIVE), ListingStatus.WITHDRAWN, closedAt = ctx.now())
            ?.also { ctx.log("plotmarket.withdraw", shard, actor, it.seller, ref = "plot:$id") }
            ?: Results.fail("not_available", "listing $id is not active")
    }

    /**
     * Buys a plot; afterwards the instance hands it over and calls [complete].
     *
     * @param buyer buyer uuid (the plot limit is checked by the instance before).
     * @param shard instance.
     * @param id listing id.
     * @return the pending listing.
     */
    fun buy(buyer: String, shard: String, id: Long): PlotListing {
        val listing = ctx.repository.listing(id)?.takeIf { it.shard == shard } ?: Results.fail("not_found", "no listing $id")
        if (listing.seller == buyer) Results.fail("own_listing", "cannot buy own plot")
        ctx.repository.transitionListing(id, setOf(ListingStatus.ACTIVE), ListingStatus.RESERVED) ?: Results.fail("not_available", "listing $id is not available")
        val fee = ctx.int("fees.plotMarketPermille", 50)
        val money = runCatching {
            ctx.kronen.transfer(buyer, listing.seller, listing.price, "plotmarket.sale", fee, "plot:$id")
        }.getOrElse { MoneyResult.fail("error") }
        if (!money.ok) {
            ctx.repository.transitionListing(id, setOf(ListingStatus.RESERVED), ListingStatus.ACTIVE)
            Results.fail(money.code ?: "error", "payment failed")
        }
        val pending = try {
            ctx.repository.transitionListing(id, setOf(ListingStatus.RESERVED), ListingStatus.PENDING, buyer = buyer)
        } catch (failure: Exception) {
            null
        }
        if (pending == null) {
            // Paid but not recorded: undo the payment and offer the plot again.
            ctx.refundSale(buyer, listing.seller, listing.price, fee, shard, "plot:$id")
            runCatching { ctx.repository.transitionListing(id, setOf(ListingStatus.RESERVED), ListingStatus.ACTIVE) }
            Results.fail("error", "listing state changed during purchase")
        }
        ctx.log("plotmarket.sale", shard, buyer, listing.seller, listing.price, "plot:$id", jsonOf("plotId" to listing.plotId))
        return pending
    }

    /**
     * Confirms the hand-over of a purchased plot.
     *
     * @param shard instance.
     * @param id listing id.
     * @return the sold listing.
     */
    fun complete(shard: String, id: Long): PlotListing {
        ctx.repository.listing(id)?.takeIf { it.shard == shard } ?: Results.fail("not_found", "no listing $id")
        return ctx.repository.transitionListing(id, setOf(ListingStatus.PENDING), ListingStatus.SOLD, closedAt = ctx.now())
            ?: Results.fail("not_pending", "listing $id is not pending")
    }

    /**
     * Registers the plot market actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        val json = CityContext.json
        r.register("citybuild.plotmarket.list", "citybuild.plotmarket.list <shard>", "Active plot offers of an instance.", null) { a ->
            Results.ok(json.encodeToJsonElement(ctx.repository.listings(a.str(0, "shard"), setOf(ListingStatus.ACTIVE))))
        }
        r.register("citybuild.plotmarket.of", "citybuild.plotmarket.of <shard> <plotId>", "Active offer of one plot or null.", null) { a ->
            val shard = a.str(0, "shard")
            val plot = a.str(1, "plotId")
            val listing = ctx.repository.listings(shard, setOf(ListingStatus.ACTIVE)).firstOrNull { it.plotId == plot }
            Results.ok(listing?.let { json.encodeToJsonElement(it) } ?: kotlinx.serialization.json.JsonNull)
        }
        r.register("citybuild.plotmarket.offer", "citybuild.plotmarket.offer <uuid> <shard> <json>", "Offers a plot.", null) { a ->
            Results.ok(json.encodeToJsonElement(offer(a.str(0, "uuid"), a.str(1, "shard"), a.json(2))))
        }
        r.register("citybuild.plotmarket.withdraw", "citybuild.plotmarket.withdraw <uuid> <shard> <id>", "Withdraws an own offer.", null) { a ->
            Results.ok(json.encodeToJsonElement(withdraw(a.str(0, "uuid"), a.str(1, "shard"), a.long(2, "id"))))
        }
        r.register("citybuild.plotmarket.remove", "citybuild.plotmarket.remove <actorUuid> <shard> <id>", "Team removal of an offer.", "citybuild.admin") { a ->
            Results.ok(json.encodeToJsonElement(withdraw(a.str(0, "actorUuid"), a.str(1, "shard"), a.long(2, "id"), admin = true)))
        }
        r.register("citybuild.plotmarket.buy", "citybuild.plotmarket.buy <uuid> <shard> <id>", "Buys a plot (state pending until complete).", null) { a ->
            Results.ok(json.encodeToJsonElement(buy(a.str(0, "uuid"), a.str(1, "shard"), a.long(2, "id"))))
        }
        r.register("citybuild.plotmarket.complete", "citybuild.plotmarket.complete <shard> <id>", "Confirms the plot hand-over.", null) { a ->
            Results.ok(json.encodeToJsonElement(complete(a.str(0, "shard"), a.long(1, "id"))))
        }
        r.register("citybuild.plotmarket.pending", "citybuild.plotmarket.pending <shard>", "Paid purchases still to hand over.", null) { a ->
            Results.ok(json.encodeToJsonElement(ctx.repository.listings(a.str(0, "shard"), setOf(ListingStatus.PENDING))))
        }
    }
}
