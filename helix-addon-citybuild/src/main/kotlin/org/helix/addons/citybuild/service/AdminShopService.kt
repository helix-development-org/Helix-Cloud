package org.helix.addons.citybuild.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The merchant (admin shop, collection `adminshop`) with authoritative prices: buying debits Kronen,
 * selling credits Kronen within two network-wide daily limits — items per ware
 * (`dailyLimit`/`adminshop.defaultItemLimit`) and Kronen in total (`adminshop.dailySellCap`).
 * Prices are Kronen per `unit` items; amounts must be multiples of the unit.
 *
 * @property ctx shared dependencies.
 */
class AdminShopService(private val ctx: CityContext) {
    /**
     * Buys from the merchant.
     *
     * @param uuid player.
     * @param shard instance (log only).
     * @param wareId ware id (lower-case material).
     * @param amount item count.
     * @return `{material, amount, paid}`.
     */
    fun buy(uuid: String, shard: String, wareId: String, amount: Int): JsonObject {
        val ware = ware(wareId)
        val unitPrice = ware["buy"]?.jsonPrimitive?.longOrNull ?: Results.fail("not_for_sale", "$wareId is not sold")
        val price = price(ware, unitPrice, amount)
        val money = ctx.kronen.withdraw(uuid, price, "adminshop.buy", "ware:$wareId")
        if (!money.ok) Results.fail(money.code ?: "error", "payment failed")
        ctx.log("adminshop.buy", shard, uuid, amount = price, ref = "ware:$wareId", data = jsonOf("amount" to amount))
        return jsonOf("material" to ware.string("material"), "amount" to amount, "paid" to price, "balance" to money.balance)
    }

    /**
     * Sells to the merchant. The instance removes the items only after this succeeded.
     *
     * @param uuid player.
     * @param shard instance (log only).
     * @param wareId ware id.
     * @param amount item count.
     * @return `{material, amount, paid, itemRemaining, kronenRemaining}`.
     */
    @Synchronized
    fun sell(uuid: String, shard: String, wareId: String, amount: Int): JsonObject {
        val ware = ware(wareId)
        val unitPrice = ware["sell"]?.jsonPrimitive?.longOrNull ?: Results.fail("not_bought", "$wareId is not bought")
        val payout = price(ware, unitPrice, amount)
        val day = ctx.today().toString()
        val itemLimit = itemLimit(ware)
        val cap = ctx.long("adminshop.dailySellCap", 3000)
        val earned = ctx.repository.counters(uuid, day)[KRONEN] ?: 0
        if (earned + payout > cap) Results.fail("limit", (cap - earned).coerceAtLeast(0).toString())
        ctx.repository.addCounter(uuid, day, "sell:$wareId", amount.toLong(), itemLimit) ?: Results.fail("item_limit", "daily limit of $wareId reached")
        val money = ctx.kronen.deposit(uuid, payout, "adminshop.sell", "ware:$wareId", LIMIT_KEY, cap)
        if (!money.ok) {
            ctx.repository.addCounter(uuid, day, "sell:$wareId", -amount.toLong(), Long.MAX_VALUE)
            Results.fail(money.code ?: "error", "payout failed")
        }
        val total = ctx.repository.addCounter(uuid, day, KRONEN, payout, Long.MAX_VALUE) ?: earned + payout
        val sold = ctx.repository.counters(uuid, day)["sell:$wareId"] ?: amount.toLong()
        ctx.log("adminshop.sell", shard, uuid, amount = payout, ref = "ware:$wareId", data = jsonOf("amount" to amount))
        return jsonOf(
            "material" to ware.string("material"), "amount" to amount, "paid" to payout,
            "itemRemaining" to (itemLimit - sold).coerceAtLeast(0), "kronenRemaining" to (cap - total).coerceAtLeast(0),
        )
    }

    /**
     * Today's selling state of a player.
     *
     * @param uuid player.
     * @return `{kronenSold, kronenCap, kronenRemaining, items:{ware: sold}}`.
     */
    fun state(uuid: String): JsonObject {
        val counters = ctx.repository.counters(uuid, ctx.today().toString())
        val cap = ctx.long("adminshop.dailySellCap", 3000)
        val sold = counters[KRONEN] ?: 0
        return jsonOf(
            "kronenSold" to sold,
            "kronenCap" to cap,
            "kronenRemaining" to (cap - sold).coerceAtLeast(0),
            "items" to counters.filterKeys { it.startsWith("sell:") }.mapKeys { it.key.removePrefix("sell:") },
        )
    }

    private fun ware(id: String): JsonObject = ctx.doc("adminshop", id.lowercase()) ?: Results.fail("not_found", "no ware '$id'")

    private fun itemLimit(ware: JsonObject): Long =
        (ware["dailyLimit"]?.jsonPrimitive?.intOrNull ?: ctx.int("adminshop.defaultItemLimit", 512)).toLong()

    private fun price(ware: JsonObject, unitPrice: Long, amount: Int): Long {
        val unit = (ware["unit"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
        if (amount <= 0 || amount % unit != 0) Results.fail("invalid_amount", "amount must be a positive multiple of $unit")
        return unitPrice * (amount / unit)
    }

    /**
     * Registers the merchant actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        r.register("citybuild.adminshop.buy", "citybuild.adminshop.buy <uuid> <shard> <ware> <amount>", "Buys from the merchant.", null) { a ->
            Results.ok(buy(a.str(0, "uuid"), a.str(1, "shard"), a.str(2, "ware"), a.int(3, "amount")))
        }
        r.register("citybuild.adminshop.sell", "citybuild.adminshop.sell <uuid> <shard> <ware> <amount>", "Sells to the merchant within the daily limits.", null) { a ->
            Results.ok(sell(a.str(0, "uuid"), a.str(1, "shard"), a.str(2, "ware"), a.int(3, "amount")))
        }
        r.register("citybuild.adminshop.state", "citybuild.adminshop.state <uuid>", "Today's selling state of a player.", null) { a ->
            Results.ok(state(a.str(0, "uuid")))
        }
    }

    private companion object {
        const val KRONEN = "kronen:sell"
        const val LIMIT_KEY = "citybuild.sell"
    }
}
