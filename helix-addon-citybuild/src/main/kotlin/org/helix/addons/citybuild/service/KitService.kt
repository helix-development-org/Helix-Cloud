package org.helix.addons.citybuild.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Kits (collection `kits`): per player and instance, with cooldown, one-time kits and a minimum
 * tier. The node only books the claim; the Paper plugin hands out the items of the returned body.
 *
 * @property ctx shared dependencies.
 */
class KitService(private val ctx: CityContext) {
    /**
     * Claims a kit.
     *
     * @param uuid player.
     * @param shard instance.
     * @param kitId kit id.
     * @param tier the player's tier (0–3).
     * @param bypass ignore the cooldown (`citybuild.bypass.cooldown`).
     * @return the kit body (items to hand out).
     */
    @Synchronized
    fun claim(uuid: String, shard: String, kitId: String, tier: Int, bypass: Boolean = false): JsonObject {
        val kit = ctx.doc("kits", kitId) ?: Results.fail("not_found", "no kit '$kitId'")
        val minTier = kit["minTier"]?.jsonPrimitive?.intOrNull ?: 0
        if (tier < minTier) Results.fail("tier", "kit '$kitId' needs tier $minTier")
        val remaining = remainingSeconds(kit, ctx.repository.kitClaimedAt(uuid, shard, kitId))
        if (!bypass && remaining != 0L) Results.fail(if (remaining < 0) "already_claimed" else "cooldown", remaining.toString())
        ctx.repository.setKitClaim(uuid, shard, kitId, ctx.now())
        ctx.log("kit", shard, uuid, ref = "kit:$kitId")
        return kit
    }

    /**
     * Cooldown state of every kit for a player.
     *
     * @param uuid player.
     * @param shard instance.
     * @param tier the player's tier.
     * @return kit id to `{available, remainingSeconds, claimedOnce, tierOk}`.
     */
    fun state(uuid: String, shard: String, tier: Int): JsonObject {
        val claims = ctx.repository.kitClaims(uuid, shard)
        return JsonObject(
            ctx.config.documents("kits").associate { doc ->
                val remaining = remainingSeconds(doc.body, claims[doc.id])
                val tierOk = tier >= (doc.body["minTier"]?.jsonPrimitive?.intOrNull ?: 0)
                doc.id to jsonOf(
                    "available" to (tierOk && remaining == 0L),
                    "remainingSeconds" to remaining.coerceAtLeast(0),
                    "claimedOnce" to (remaining < 0),
                    "tierOk" to tierOk,
                )
            },
        )
    }

    /** `0` = available, `> 0` = seconds left, `-1` = one-time kit already claimed. */
    private fun remainingSeconds(kit: JsonObject, claimedAt: Long?): Long {
        claimedAt ?: return 0
        if (kit["once"]?.jsonPrimitive?.booleanOrNull == true) return -1
        val cooldown = kit["cooldownSeconds"]?.jsonPrimitive?.longOrNull ?: 0
        val left = (claimedAt + cooldown * 1000 - ctx.now() + 999) / 1000
        return left.coerceAtLeast(0)
    }

    /**
     * Registers the kit actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        r.register("citybuild.kit.claim", "citybuild.kit.claim <uuid> <shard> <kit> <tier> [bypass]", "Claims a kit; returns its body.", null) { a ->
            Results.ok(claim(a.str(0, "uuid"), a.str(1, "shard"), a.str(2, "kit"), a.int(3, "tier"), a.opt(4).equals("bypass", true)))
        }
        r.register("citybuild.kit.state", "citybuild.kit.state <uuid> <shard> <tier>", "Availability of every kit for a player.", null) { a ->
            Results.ok(state(a.str(0, "uuid"), a.str(1, "shard"), a.int(2, "tier")))
        }
        r.register("citybuild.kit.list", "citybuild.kit.list", "All kits as JSON array.", null) { _ ->
            Results.ok(JsonArray(ctx.config.documents("kits").map { jsonOf("id" to it.id, "body" to it.body) }))
        }
    }
}
