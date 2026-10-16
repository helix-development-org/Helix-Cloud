package org.helix.addons.citybuild.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.helix.addons.citybuild.config.ConfigDocument
import kotlin.random.Random

/** Rarity order of crate prizes (DESIGN §11, without „Episch“). */
val RARITIES: List<String> = listOf("NORMAL", "SPECIAL", "ELITE", "TITAN", "BOSS")

/**
 * One prize of a crate.
 *
 * @property id prize id, unique within the crate.
 * @property type `forge` (ref = template id), `item` (data = Base64 item stack), `material`
 *   (ref = Bukkit material), `kronen` (amount = Kronen, paid by the node) or `key` (ref = crate id
 *   whose key is won).
 * @property ref reference according to [type].
 * @property data Base64 item stack for type `item`.
 * @property amount stack size or Kronen.
 * @property weight relative chance (> 0).
 * @property rarity one of [RARITIES].
 */
@Serializable
data class CrateEntry(
    val id: String,
    val type: String,
    val ref: String = "",
    val data: String? = null,
    val amount: Long = 1,
    val weight: Int = 1,
    val rarity: String = "NORMAL",
)

/**
 * A crate as stored in the config collection `crates` (document id = crate id).
 *
 * @property name display name.
 * @property rarity colour of the crate fittings.
 * @property look `CHEST`, `BARREL`, `ENDER_CHEST` or a Blockbench model key.
 * @property key forge template id of the key item (by convention `key_<crateId>`; the instance
 *   creates the template when missing).
 * @property announceFrom lowest prize rarity announced network-wide, `null` = never.
 * @property entries the prizes.
 */
@Serializable
data class CrateSpec(
    val name: String,
    val rarity: String = "NORMAL",
    val look: String = "CHEST",
    val key: String = "",
    val announceFrom: String? = "TITAN",
    val entries: List<CrateEntry> = emptyList(),
)

/** Weighted prize draw, pure and testable with a seeded [Random]. */
object CrateRoll {
    /**
     * Draws one entry with probability `weight / sum(weights)`.
     *
     * @param entries prizes; entries with weight ≤ 0 never win.
     * @param random randomness source.
     * @return the drawn entry.
     * @throws IllegalArgumentException when no entry has a positive weight.
     */
    fun draw(entries: List<CrateEntry>, random: Random): CrateEntry {
        val pool = entries.filter { it.weight > 0 }
        require(pool.isNotEmpty()) { "crate has no prize with positive weight" }
        var roll = random.nextLong(pool.sumOf { it.weight.toLong() })
        for (entry in pool) {
            roll -= entry.weight
            if (roll < 0) return entry
        }
        return pool.last()
    }

    /**
     * Chance of every entry in percent (for the preview).
     *
     * @param entries prizes.
     * @return entry id to percent.
     */
    fun chances(entries: List<CrateEntry>): Map<String, Double> {
        val total = entries.filter { it.weight > 0 }.sumOf { it.weight.toDouble() }
        return entries.associate { it.id to if (total == 0.0 || it.weight <= 0) 0.0 else it.weight * 100.0 / total }
    }
}

/**
 * Crates: specs in the config collection `crates`, server-side draws, Kronen prizes paid directly.
 * The instance consumes the key before calling `open` and returns it when `open` fails.
 *
 * @property ctx shared dependencies.
 * @property random randomness of the draws.
 */
class CrateService(private val ctx: CityContext, private val random: Random = Random.Default) {
    /**
     * Loads a crate spec.
     *
     * @param id crate id.
     * @return the spec.
     */
    fun spec(id: String): CrateSpec {
        val doc = ctx.config.document(COLLECTION, id) ?: Results.fail("not_found", "no crate '$id'")
        return CityContext.json.decodeFromJsonElement(CrateSpec.serializer(), doc.body)
    }

    /**
     * Opens a crate for a player.
     *
     * @param uuid player.
     * @param shard instance.
     * @param crateId crate id.
     * @return `{crate, entry, announce, paid}`.
     */
    fun open(uuid: String, shard: String, crateId: String): JsonObject {
        val spec = spec(crateId)
        val entry = CrateRoll.draw(spec.entries, random)
        var paid = 0L
        if (entry.type == "kronen" && entry.amount > 0) {
            val money = ctx.kronen.deposit(uuid, entry.amount, "crate:$crateId", "crate:$crateId:${entry.id}")
            if (!money.ok) Results.fail(money.code ?: "error", "crate payout failed")
            paid = entry.amount
        }
        val announceFrom = spec.announceFrom?.let { RARITIES.indexOf(it) } ?: -1
        val announce = announceFrom >= 0 && RARITIES.indexOf(entry.rarity) >= announceFrom
        ctx.log("crate", shard, uuid, amount = paid, ref = "crate:$crateId", data = jsonOf("entry" to entry.id, "rarity" to entry.rarity))
        return jsonOf("crate" to crateId, "entry" to CityContext.json.encodeToJsonElement(entry), "announce" to announce, "paid" to paid)
    }

    /**
     * Validates and stores a crate.
     *
     * @param id crate id.
     * @param spec the spec.
     * @return the stored spec.
     */
    fun save(id: String, spec: CrateSpec): CrateSpec {
        if (!Regex("[a-z0-9_]{1,32}").matches(id)) Results.fail("invalid_id", "crate id must match [a-z0-9_]{1,32}")
        if (spec.entries.none { it.weight > 0 }) Results.fail("invalid", "a crate needs at least one prize with positive weight")
        if (spec.entries.map { it.id }.toSet().size != spec.entries.size) Results.fail("invalid", "prize ids must be unique")
        spec.entries.firstOrNull { it.type !in TYPES || it.rarity !in RARITIES }?.let { Results.fail("invalid", "invalid prize ${it.id}") }
        val previous = ctx.config.document(COLLECTION, id)
        val stored = spec.copy(key = spec.key.ifBlank { "key_$id" })
        ctx.config.putDocument(COLLECTION, ConfigDocument(id, previous?.sortOrder ?: ctx.config.documents(COLLECTION).size, CityContext.json.encodeToJsonElement(stored) as JsonObject))
        return stored
    }

    /**
     * Registers the crate actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        val json = CityContext.json
        r.register("citybuild.crate.list", "citybuild.crate.list", "All crates [{id, spec, chances}].", null) { _ ->
            Results.ok(
                JsonArray(
                    ctx.config.documents(COLLECTION).map { doc ->
                        val spec = json.decodeFromJsonElement(CrateSpec.serializer(), doc.body)
                        jsonOf("id" to doc.id, "spec" to doc.body, "chances" to CrateRoll.chances(spec.entries))
                    },
                ),
            )
        }
        r.register("citybuild.crate.save", "citybuild.crate.save <uuid> <id> <json CrateSpec>", "Creates or replaces a crate.", "citybuild.admin.crates") { a ->
            val spec = runCatching { json.decodeFromJsonElement(CrateSpec.serializer(), a.json(2)) }.getOrElse { Results.fail("invalid", it.message ?: "invalid spec") }
            val stored = save(a.str(1, "id"), spec)
            ctx.log("crate.save", "", a.str(0, "uuid"), ref = "crate:${a.str(1, "id")}")
            Results.ok(json.encodeToJsonElement(stored))
        }
        r.register("citybuild.crate.delete", "citybuild.crate.delete <uuid> <id>", "Deletes a crate.", "citybuild.admin.crates") { a ->
            if (!ctx.config.removeDocument(COLLECTION, a.str(1, "id"))) Results.fail("not_found", "no such crate")
            Results.ok(jsonOf("deleted" to true))
        }
        r.register("citybuild.crate.open", "citybuild.crate.open <uuid> <shard> <crateId>", "Draws a prize (key already consumed by the instance).", null) { a ->
            Results.ok(open(a.str(0, "uuid"), a.str(1, "shard"), a.str(2, "crateId")))
        }
    }

    /** Collection and prize types. */
    companion object {
        /** Config collection of the crates. */
        const val COLLECTION: String = "crates"

        /** Allowed prize types. */
        val TYPES: Set<String> = setOf("forge", "item", "material", "kronen", "key")
    }
}
