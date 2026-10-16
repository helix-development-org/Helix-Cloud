package org.helix.addons.citybuild.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.helix.addons.citybuild.config.ConfigDocument
import org.helix.addons.citybuild.economy.MoneyResult

/**
 * The ware of a special offer.
 *
 * @property type `forge` (ref = template id), `key` (ref = crate id), `item` (data = Base64 item
 *   stack) or `material` (ref = Bukkit material).
 * @property ref reference according to [type].
 * @property data Base64 item stack for type `item`.
 * @property amount items per purchased unit.
 */
@Serializable
data class OfferWare(val type: String, val ref: String = "", val data: String? = null, val amount: Int = 1)

/**
 * A merchant special offer as stored in the config collection `offers` (document id = offer id).
 *
 * @property name display name.
 * @property ware what is sold.
 * @property price Kronen per unit.
 * @property stock total units per instance, `null` = unlimited.
 * @property perPlayer units per player and instance, `null` = unlimited.
 * @property startsAt start (epoch ms), `null` = immediately.
 * @property endsAt end (epoch ms, exclusive), `null` = open-ended.
 * @property minTier lowest tier that sees the offer.
 * @property shards instances that show it, empty = all.
 */
@Serializable
data class OfferSpec(
    val name: String,
    val ware: OfferWare,
    val price: Long,
    val stock: Int? = null,
    val perPlayer: Int? = null,
    val startsAt: Long? = null,
    val endsAt: Long? = null,
    val minTier: Int = 0,
    val shards: List<String> = emptyList(),
)

/**
 * Special offers: specs in the config collection `offers`; stock and per-player purchases are
 * reserved atomically per instance before the Kronen are debited, and released when payment fails.
 *
 * @property ctx shared dependencies.
 */
class OfferService(private val ctx: CityContext) {
    /**
     * Offers visible to a player right now.
     *
     * @param shard instance.
     * @param uuid player.
     * @param tier the player's tier.
     * @return `[{id, spec, stockLeft, bought}]`.
     */
    fun visible(shard: String, uuid: String, tier: Int): JsonArray {
        val now = ctx.now()
        return JsonArray(
            specs().filter { (_, spec) -> active(spec, shard, now) && tier >= spec.minTier }.map { (id, spec) ->
                jsonOf(
                    "id" to id,
                    "spec" to CityContext.json.encodeToJsonElement(spec),
                    "stockLeft" to spec.stock?.let { (it - ctx.repository.offerSold(id, shard)).coerceAtLeast(0) },
                    "bought" to ctx.repository.offerBought(id, shard, uuid),
                )
            },
        )
    }

    /**
     * Buys units of an offer.
     *
     * @param uuid buyer.
     * @param shard instance.
     * @param offerId offer id.
     * @param count units (1–64).
     * @param tier the buyer's tier.
     * @return `{offer, spec, count, paid}` – the instance hands out `count × ware.amount` items.
     */
    fun buy(uuid: String, shard: String, offerId: String, count: Int, tier: Int): JsonObject {
        val doc = ctx.config.document(COLLECTION, offerId) ?: Results.fail("not_found", "no offer '$offerId'")
        val spec = CityContext.json.decodeFromJsonElement(OfferSpec.serializer(), doc.body)
        if (count !in 1..64) Results.fail("invalid_amount", "count must be 1..64")
        if (!active(spec, shard, ctx.now())) Results.fail("not_available", "offer is not active")
        if (tier < spec.minTier) Results.fail("tier", "offer needs tier ${spec.minTier}")
        if (!ctx.repository.reserveOffer(offerId, shard, uuid, count, spec.stock, spec.perPlayer)) Results.fail("sold_out", "stock or player limit exhausted")
        val price = spec.price * count
        val money = runCatching { ctx.kronen.withdraw(uuid, price, "offer:$offerId", "offer:$offerId") }.getOrElse { MoneyResult.fail("error") }
        if (!money.ok) {
            ctx.repository.releaseOffer(offerId, shard, uuid, count)
            Results.fail(money.code ?: "error", "payment failed")
        }
        ctx.log("offer", shard, uuid, amount = price, ref = "offer:$offerId", data = jsonOf("count" to count))
        return jsonOf("offer" to offerId, "spec" to doc.body, "count" to count, "paid" to price)
    }

    private fun specs(): List<Pair<String, OfferSpec>> = ctx.config.documents(COLLECTION).mapNotNull { doc ->
        runCatching { doc.id to CityContext.json.decodeFromJsonElement(OfferSpec.serializer(), doc.body) }.getOrNull()
    }

    private fun active(spec: OfferSpec, shard: String, now: Long): Boolean =
        (spec.startsAt == null || now >= spec.startsAt) && (spec.endsAt == null || now < spec.endsAt) &&
            (spec.shards.isEmpty() || spec.shards.any { it.equals(shard, true) })

    /**
     * Registers the offer actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        val json = CityContext.json
        r.register("citybuild.offer.list", "citybuild.offer.list <shard> <uuid> <tier>", "Offers visible to a player now.", null) { a ->
            Results.ok(visible(a.str(0, "shard"), a.str(1, "uuid"), a.int(2, "tier")))
        }
        r.register("citybuild.offer.all", "citybuild.offer.all", "All offers [{id, spec}] (admin hub).", null) { _ ->
            Results.ok(JsonArray(ctx.config.documents(COLLECTION).map { jsonOf("id" to it.id, "spec" to it.body) }))
        }
        r.register("citybuild.offer.save", "citybuild.offer.save <uuid> <id> <json OfferSpec>", "Creates or replaces an offer.", "citybuild.admin.offers") { a ->
            val id = a.str(1, "id")
            if (!Regex("[a-z0-9_]{1,48}").matches(id)) Results.fail("invalid_id", "offer id must match [a-z0-9_]{1,48}")
            val spec = runCatching { json.decodeFromJsonElement(OfferSpec.serializer(), a.json(2)) }.getOrElse { Results.fail("invalid", it.message ?: "invalid spec") }
            if (spec.price < 0 || spec.ware.amount < 1 || spec.ware.type !in setOf("forge", "key", "item", "material")) Results.fail("invalid", "invalid offer")
            val previous = ctx.config.document(COLLECTION, id)
            ctx.config.putDocument(COLLECTION, ConfigDocument(id, previous?.sortOrder ?: ctx.config.documents(COLLECTION).size, json.encodeToJsonElement(spec) as JsonObject))
            ctx.log("offer.save", "", a.str(0, "uuid"), ref = "offer:$id")
            Results.ok(json.encodeToJsonElement(spec))
        }
        r.register("citybuild.offer.delete", "citybuild.offer.delete <uuid> <id>", "Deletes an offer.", "citybuild.admin.offers") { a ->
            if (!ctx.config.removeDocument(COLLECTION, a.str(1, "id"))) Results.fail("not_found", "no such offer")
            Results.ok(jsonOf("deleted" to true))
        }
        r.register("citybuild.offer.buy", "citybuild.offer.buy <uuid> <shard> <offerId> <count> <tier>", "Buys units of an offer.", null) { a ->
            Results.ok(buy(a.str(0, "uuid"), a.str(1, "shard"), a.str(2, "offerId"), a.int(3, "count"), a.int(4, "tier")))
        }
    }

    /** Collection name. */
    companion object {
        /** Config collection of the offers. */
        const val COLLECTION: String = "offers"
    }
}
