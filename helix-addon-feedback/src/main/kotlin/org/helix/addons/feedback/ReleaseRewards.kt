package org.helix.addons.feedback

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Release rewards of the closed test phase (plan 1, section 8): maps tester points to the reward table and splits
 * a tier into the parts that are paid where they can be paid.
 *
 * Table entry: `{minPoints, kronen?, keys?:[crateId], items?:[ref], permissions?:[node], note?}`. Item references
 * starting with `perm:` or `cosmetic:` are permission grants (cosmetics unlock by permission); all other item
 * references and every key are CityBuild items.
 *
 * Parts (each one [PendingReward]):
 * - [PART_NETWORK] – crowns and permissions, redeemed by the node on the first join anywhere
 *   (`feedback.rewards.redeem`);
 * - [PART_CITYBUILD] – keys and items, delivered by the CityBuild plugin into the pickup box of the first
 *   CityBuild instance the player joins.
 */
object ReleaseRewards {
    /** Marker field of every release reward part. */
    const val RELEASE: String = "release"

    /** Part field name. */
    const val PART: String = "part"

    /** Crowns and permissions, paid by the node. */
    const val PART_NETWORK: String = "network"

    /** Keys and items, delivered by the CityBuild plugin. */
    const val PART_CITYBUILD: String = "citybuild"

    /**
     * Picks the highest tier reached.
     *
     * @param points tester points.
     * @param table reward tiers.
     * @return the tier with the largest `minPoints` ≤ [points], or `null` when none is reached.
     */
    fun tierFor(points: Int, table: List<JsonObject>): JsonObject? =
        table.filter { (it.int("minPoints") ?: Int.MAX_VALUE) <= points }.maxByOrNull { it.int("minPoints") ?: 0 }

    /**
     * Splits a tier into reward parts.
     *
     * @param tier the reached tier.
     * @param points tester points (copied into every part for display).
     * @return one part per payable destination; empty when the tier carries nothing.
     */
    fun parts(tier: JsonObject, points: Int): List<JsonObject> {
        val kronen = (tier["kronen"] as? JsonPrimitive)?.longOrNull ?: 0L
        val items = tier.list("items")
        val permissions = tier.list("permissions") + items.filter { isPermission(it) }.map { it.substringAfter(':') }
        val cityItems = items.filterNot { isPermission(it) }
        val keys = tier.list("keys")
        val common = mapOf(
            RELEASE to JsonPrimitive(true),
            "points" to JsonPrimitive(points),
            "tier" to JsonPrimitive(tier.int("minPoints") ?: 0),
            "note" to JsonPrimitive((tier["note"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
        )
        val parts = mutableListOf<JsonObject>()
        if (kronen > 0 || permissions.isNotEmpty()) {
            parts += JsonObject(
                common + mapOf(
                    PART to JsonPrimitive(PART_NETWORK),
                    "kronen" to JsonPrimitive(kronen),
                    "permissions" to JsonArray(permissions.distinct().map(::JsonPrimitive)),
                ),
            )
        }
        if (keys.isNotEmpty() || cityItems.isNotEmpty()) {
            parts += JsonObject(
                common + mapOf(
                    PART to JsonPrimitive(PART_CITYBUILD),
                    "keys" to JsonArray(keys.map(::JsonPrimitive)),
                    "items" to JsonArray(cityItems.map(::JsonPrimitive)),
                ),
            )
        }
        return parts
    }

    /**
     * Whether a pending reward belongs to the release.
     *
     * @param reward reward JSON.
     * @return `true` for release parts.
     */
    fun isRelease(reward: JsonObject): Boolean = (reward[RELEASE] as? JsonPrimitive)?.booleanOrNull == true

    /**
     * Part of a reward.
     *
     * @param reward reward JSON.
     * @return [PART_NETWORK], [PART_CITYBUILD] or `null` for other rewards.
     */
    fun partOf(reward: JsonObject): String? = (reward[PART] as? JsonPrimitive)?.contentOrNull

    private fun isPermission(ref: String): Boolean = ref.startsWith("perm:") || ref.startsWith("cosmetic:")

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.list(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
}

/**
 * Result of preparing the release rewards.
 *
 * @property holders players with at least one point.
 * @property granted reward parts created (or that would be created in a dry run).
 * @property alreadyPrepared players skipped because they already have release rewards.
 * @property withoutTier players whose points reach no tier.
 */
data class ReleasePreparation(
    val holders: List<Points>,
    val granted: List<PendingReward>,
    val alreadyPrepared: Int,
    val withoutTier: Int,
)
