package org.helix.addons.citybuild.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.helix.addons.citybuild.model.Auction
import org.helix.addons.citybuild.model.AuctionStatus
import org.helix.addons.citybuild.model.Pickup
import org.slf4j.LoggerFactory

/**
 * Auction house per instance plus the pickup box.
 *
 * Purchase flow (atomic across instances): the listing is moved `active → reserved` (only one buyer
 * can win), then the Kronen are transferred buyer → seller minus the sale fee; on a money failure the
 * listing goes back to `active`, otherwise to `sold`. Listing fees are paid on creation and never
 * refunded. Expired and cancelled listings move their item into the seller's pickup box.
 *
 * @property ctx shared dependencies.
 */
class AuctionService(private val ctx: CityContext) {
    private val logger = LoggerFactory.getLogger(AuctionService::class.java)

    /**
     * Creates a listing.
     *
     * @param seller seller uuid.
     * @param shard instance.
     * @param limit maximum simultaneous listings of the seller's tier.
     * @param body `{sellerName, item, material, displayName, category, amount, price, hours}`.
     * @return the stored listing.
     */
    fun create(seller: String, shard: String, limit: Int, body: JsonObject): Auction {
        val price = body["price"]?.jsonPrimitive?.longOrNull ?: Results.fail("invalid_price", "price missing")
        val hours = body["hours"]?.jsonPrimitive?.intOrNull ?: ctx.ints("auction.durationsHours").firstOrNull() ?: 24
        if (price < ctx.long("auction.minPrice", 1) || price > ctx.long("auction.maxPrice", 10_000_000)) Results.fail("invalid_price", "price out of range")
        val durations = ctx.ints("auction.durationsHours")
        if (durations.isNotEmpty() && hours !in durations) Results.fail("invalid_duration", "allowed durations: $durations")
        val item = body.string("item") ?: Results.fail("invalid_item", "item missing")
        if (ctx.repository.auctionsOf(shard, seller, setOf(AuctionStatus.ACTIVE, AuctionStatus.RESERVED)).size >= limit) Results.fail("limit", "auction limit $limit reached")
        val fee = listingFee(price)
        val now = ctx.now()
        val paid = ctx.kronen.withdraw(seller, fee, "auction.listing", "auction:new")
        if (!paid.ok) Results.fail(paid.code ?: "error", "listing fee $fee not paid")
        val stored = ctx.repository.insertAuction(
            Auction(
                shard = shard, seller = seller, sellerName = body.string("sellerName").orEmpty(), item = item,
                material = body.string("material").orEmpty(), displayName = body.string("displayName").orEmpty(),
                category = body.string("category") ?: "misc", amount = body["amount"]?.jsonPrimitive?.intOrNull ?: 1,
                price = price, createdAt = now, expiresAt = now + hours * 3_600_000L,
            ),
        )
        ctx.log("auction.create", shard, seller, amount = fee, ref = "auction:${stored.id}", data = jsonOf("price" to price))
        return stored
    }

    /**
     * Listing fee of a price (`fees.auctionListingPermille`, at least `fees.auctionListingMin`).
     *
     * @param price listing price.
     * @return the fee in Kronen.
     */
    fun listingFee(price: Long): Long = maxOf(ctx.long("fees.auctionListingMin", 10), price * ctx.int("fees.auctionListingPermille", 20) / 1000)

    /**
     * Buys a listing.
     *
     * @param buyer buyer uuid.
     * @param shard instance the buyer is on.
     * @param id listing id.
     * @return the sold listing including its item.
     */
    fun buy(buyer: String, shard: String, id: Long): Auction {
        val listing = ctx.repository.auction(id) ?: Results.fail("not_found", "no auction $id")
        if (listing.shard != shard) Results.fail("wrong_shard", "auction belongs to ${listing.shard}")
        if (listing.seller == buyer) Results.fail("own_listing", "cannot buy own listing")
        val now = ctx.now()
        ctx.repository.transitionAuction(id, setOf(AuctionStatus.ACTIVE), AuctionStatus.RESERVED, notBefore = now)
            ?: Results.fail("not_available", "auction $id is not available")
        val fee = ctx.int("fees.auctionSalePermille", 50)
        val money = runCatching {
            ctx.kronen.transfer(buyer, listing.seller, listing.price, "auction.sale", fee, "auction:$id")
        }.getOrElse { org.helix.addons.citybuild.economy.MoneyResult.fail("error") }
        if (!money.ok) {
            ctx.repository.transitionAuction(id, setOf(AuctionStatus.RESERVED), AuctionStatus.ACTIVE)
            Results.fail(money.code ?: "error", "payment failed")
        }
        val sold = try {
            ctx.repository.transitionAuction(id, setOf(AuctionStatus.RESERVED), AuctionStatus.SOLD, buyer = buyer, closedAt = now)
        } catch (failure: Exception) {
            logger.error("auction {} could not be marked sold after payment", id, failure)
            null
        }
        if (sold == null) {
            // Paid but not recorded: undo the payment and put the listing back on sale.
            logger.error("auction {} lost its reservation after payment; refunding {}", id, buyer)
            ctx.refundSale(buyer, listing.seller, listing.price, fee, shard, "auction:$id")
            runCatching { ctx.repository.transitionAuction(id, setOf(AuctionStatus.RESERVED), AuctionStatus.ACTIVE) }
            Results.fail("error", "auction state changed during purchase")
        }
        ctx.log("auction.sale", shard, buyer, listing.seller, listing.price, "auction:$id")
        return sold
    }

    /**
     * Withdraws a listing; the item goes to the seller's pickup box.
     *
     * @param actor seller uuid (or any uuid when [admin]).
     * @param shard instance.
     * @param id listing id.
     * @param admin team removal (any seller).
     * @return the pickup entry.
     */
    fun cancel(actor: String, shard: String, id: Long, admin: Boolean = false): Pickup {
        val listing = ctx.repository.auction(id) ?: Results.fail("not_found", "no auction $id")
        if (listing.shard != shard) Results.fail("wrong_shard", "auction belongs to ${listing.shard}")
        if (!admin && listing.seller != actor) Results.fail("not_owner", "not your auction")
        val cancelled = ctx.repository.transitionAuction(id, setOf(AuctionStatus.ACTIVE), AuctionStatus.CANCELLED, closedAt = ctx.now())
            ?: Results.fail("not_available", "auction $id is not active")
        ctx.log("auction.cancel", shard, actor, cancelled.seller, ref = "auction:$id")
        return ctx.repository.insertPickup(Pickup(shard = shard, uuid = cancelled.seller, item = cancelled.item, source = "auction.cancelled", createdAt = ctx.now()))
    }

    /**
     * Expires every due listing of an instance into the pickup boxes.
     *
     * @param shard instance.
     * @return `{auctionId, seller, pickupId}` per expired listing.
     */
    fun expire(shard: String): List<JsonObject> {
        val now = ctx.now()
        return ctx.repository.auctions(shard, setOf(AuctionStatus.ACTIVE)).filter { it.expiresAt <= now }.mapNotNull { due ->
            val expired = ctx.repository.transitionAuction(due.id, setOf(AuctionStatus.ACTIVE), AuctionStatus.EXPIRED, closedAt = now) ?: return@mapNotNull null
            val pickup = ctx.repository.insertPickup(Pickup(shard = shard, uuid = expired.seller, item = expired.item, source = "auction.expired", createdAt = now))
            jsonOf("auctionId" to expired.id, "seller" to expired.seller, "pickupId" to pickup.id)
        }
    }

    /**
     * Lists buyable listings with filter, sort and paging.
     *
     * @param shard instance.
     * @param page page index from 0.
     * @param pageSize entries per page (1–100).
     * @param filter `{category?, search?, sort?: newest|price_asc|price_desc, seller?}`.
     * @return `{page, pages, total, entries:[listing without item…]}`.
     */
    fun list(shard: String, page: Int, pageSize: Int, filter: JsonObject?): JsonObject {
        val now = ctx.now()
        val category = filter?.string("category")
        val search = filter?.string("search")?.lowercase()
        val seller = filter?.string("seller")
        val all = ctx.repository.auctions(shard, setOf(AuctionStatus.ACTIVE)).asSequence()
            .filter { it.expiresAt > now }
            .filter { category == null || it.category == category }
            .filter { seller == null || it.seller == seller }
            .filter { search == null || it.displayName.lowercase().contains(search) || it.material.lowercase().contains(search) }
            .toList()
        val sorted = when (filter?.string("sort")) {
            "price_asc" -> all.sortedWith(compareBy({ it.price }, { -it.id }))
            "price_desc" -> all.sortedWith(compareBy({ -it.price }, { -it.id }))
            else -> all.sortedByDescending { it.id }
        }
        val size = pageSize.coerceIn(1, 100)
        val pages = maxOf(1, (sorted.size + size - 1) / size)
        val index = page.coerceIn(0, pages - 1)
        return jsonOf(
            "page" to index,
            "pages" to pages,
            "total" to sorted.size,
            "entries" to JsonArray(sorted.drop(index * size).take(size).map { CityContext.json.encodeToJsonElement(it) }),
        )
    }

    /**
     * Sold listings of a seller not yet announced to them; marks them as announced.
     *
     * @param seller seller uuid.
     * @param shard instance.
     * @return the sold listings.
     */
    fun soldNotifications(seller: String, shard: String): List<Auction> {
        val sold = ctx.repository.auctionsOf(shard, seller, setOf(AuctionStatus.SOLD)).filter { !it.sellerNotified }
        ctx.repository.markSellerNotified(sold.map { it.id })
        return sold
    }

    /**
     * Registers the auction and pickup actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        val json = CityContext.json
        r.register("citybuild.auction.list", "citybuild.auction.list <shard> <page> <pageSize> [json filter]", "Buyable listings of an instance.", null) { a ->
            Results.ok(list(a.str(0, "shard"), a.int(1, "page"), a.int(2, "pageSize"), if (a.values.size > 3) a.json(3) else null))
        }
        r.register("citybuild.auction.create", "citybuild.auction.create <uuid> <shard> <limit> <json>", "Lists an item; charges the listing fee.", null) { a ->
            Results.ok(json.encodeToJsonElement(create(a.str(0, "uuid"), a.str(1, "shard"), a.int(2, "limit"), a.json(3))))
        }
        r.register("citybuild.auction.buy", "citybuild.auction.buy <uuid> <shard> <id>", "Buys a listing; returns it with its item.", null) { a ->
            Results.ok(json.encodeToJsonElement(buy(a.str(0, "uuid"), a.str(1, "shard"), a.long(2, "id"))))
        }
        r.register("citybuild.auction.cancel", "citybuild.auction.cancel <uuid> <shard> <id> [admin]", "Withdraws a listing into the pickup box.", null) { a ->
            Results.ok(json.encodeToJsonElement(cancel(a.str(0, "uuid"), a.str(1, "shard"), a.long(2, "id"), a.opt(3).equals("admin", true))))
        }
        r.register("citybuild.auction.expire", "citybuild.auction.expire <shard>", "Moves due listings into the pickup boxes.", null) { a ->
            Results.ok(JsonArray(expire(a.str(0, "shard"))))
        }
        r.register("citybuild.auction.mine", "citybuild.auction.mine <uuid> <shard>", "Active and reserved listings of a seller.", null) { a ->
            Results.ok(json.encodeToJsonElement(ctx.repository.auctionsOf(a.str(1, "shard"), a.str(0, "uuid"), setOf(AuctionStatus.ACTIVE, AuctionStatus.RESERVED))))
        }
        r.register("citybuild.auction.sold", "citybuild.auction.sold <uuid> <shard>", "Unannounced sales of a seller (marks them announced).", null) { a ->
            Results.ok(json.encodeToJsonElement(soldNotifications(a.str(0, "uuid"), a.str(1, "shard"))))
        }
        r.register("citybuild.auction.fee", "citybuild.auction.fee <price>", "Listing fee of a price.", null) { a ->
            Results.ok(jsonOf("fee" to listingFee(a.long(0, "price"))))
        }
        r.register("citybuild.pickup.list", "citybuild.pickup.list <uuid> <shard>", "Pickup box of a player.", null) { a ->
            Results.ok(json.encodeToJsonElement(ctx.repository.pickups(a.str(0, "uuid"), a.str(1, "shard"))))
        }
        r.register("citybuild.pickup.take", "citybuild.pickup.take <uuid> <shard> <id>", "Removes and returns a pickup entry.", null) { a ->
            val entry = ctx.repository.takePickup(a.str(0, "uuid"), a.str(1, "shard"), a.long(2, "id")) ?: Results.fail("not_found", "no such pickup entry")
            Results.ok(json.encodeToJsonElement(entry))
        }
        r.register("citybuild.pickup.give", "citybuild.pickup.give <uuid> <shard> <source> <item>", "Puts an item into a pickup box (offline gifts, full inventories).", null) { a ->
            val entry = ctx.repository.insertPickup(Pickup(shard = a.str(1, "shard"), uuid = a.str(0, "uuid"), item = a.str(3, "item"), source = a.str(2, "source"), createdAt = ctx.now()))
            Results.ok(json.encodeToJsonElement(entry))
        }
    }
}

/**
 * A string field of a JSON object.
 *
 * @param key field name.
 * @return the text, or `null` when missing, null or blank.
 */
fun JsonObject.string(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content?.takeIf { it.isNotBlank() && it != "null" }
