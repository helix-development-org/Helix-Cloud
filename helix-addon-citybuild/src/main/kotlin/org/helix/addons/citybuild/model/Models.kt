package org.helix.addons.citybuild.model

import kotlinx.serialization.Serializable

/*
 * Persistent CityBuild records. All timestamps are epoch milliseconds, players are uuids and
 * `shard` is the service id of a city instance (for example `CityBuild-2`). Items are the Base64 of
 * Paper's `ItemStack.serializeAsBytes()` and opaque to the node.
 */

/**
 * A named teleport point of a player on one instance.
 *
 * @property uuid owner.
 * @property shard instance.
 * @property name home name (`[A-Za-z0-9_-]{1,16}`, case-insensitive unique).
 * @property world world name.
 * @property x x coordinate.
 * @property y y coordinate.
 * @property z z coordinate.
 * @property yaw view yaw.
 * @property pitch view pitch.
 */
@Serializable
data class Home(
    val uuid: String,
    val shard: String,
    val name: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

/**
 * A public teleport point of an instance, set by the team.
 *
 * @property shard instance.
 * @property name warp name.
 * @property icon material shown in the warp menu.
 * @property description one line shown in the warp menu.
 * @property world world name.
 * @property x x coordinate.
 * @property y y coordinate.
 * @property z z coordinate.
 * @property yaw view yaw.
 * @property pitch view pitch.
 * @property sortOrder menu order.
 */
@Serializable
data class Warp(
    val shard: String,
    val name: String,
    val icon: String = "ENDER_PEARL",
    val description: String = "",
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val sortOrder: Int = 0,
)

/** Status values of auctions. */
object AuctionStatus {
    /** Listed and buyable. */
    const val ACTIVE: String = "active"

    /** A purchase is in progress (money being booked). */
    const val RESERVED: String = "reserved"

    /** Bought. */
    const val SOLD: String = "sold"

    /** Withdrawn by the seller; the item went to the seller's pickup box. */
    const val CANCELLED: String = "cancelled"

    /** Expired; the item went to the seller's pickup box. */
    const val EXPIRED: String = "expired"
}

/**
 * One auction house listing.
 *
 * @property id listing id.
 * @property shard instance (the auction house is per instance).
 * @property seller seller uuid.
 * @property sellerName seller name at listing time.
 * @property item serialized item stack.
 * @property material item material (filter/search).
 * @property displayName plain display name (search).
 * @property category menu category (`blocks`, `tools`, `armor`, `potions`, `books`, `misc`).
 * @property amount stack size.
 * @property price Kronen.
 * @property createdAt listing time.
 * @property expiresAt expiry time.
 * @property status one of [AuctionStatus].
 * @property buyer buyer uuid once sold.
 * @property closedAt time of sale/cancel/expiry.
 * @property sellerNotified whether the seller saw the sale message.
 */
@Serializable
data class Auction(
    val id: Long = 0,
    val shard: String,
    val seller: String,
    val sellerName: String = "",
    val item: String,
    val material: String = "",
    val displayName: String = "",
    val category: String = "misc",
    val amount: Int = 1,
    val price: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String = AuctionStatus.ACTIVE,
    val buyer: String? = null,
    val closedAt: Long? = null,
    val sellerNotified: Boolean = false,
)

/**
 * An item waiting in a player's pickup box (expired auctions, offline gifts, full inventories).
 *
 * @property id entry id.
 * @property shard instance (items are bound to it).
 * @property uuid owner.
 * @property item serialized item stack.
 * @property source origin, for example `auction.expired` or `give`.
 * @property createdAt creation time.
 */
@Serializable
data class Pickup(val id: Long = 0, val shard: String, val uuid: String, val item: String, val source: String, val createdAt: Long)

/** Status values of plot market listings. */
object ListingStatus {
    /** Offered. */
    const val ACTIVE: String = "active"

    /** A purchase is in progress (money being booked). */
    const val RESERVED: String = "reserved"

    /** Paid; the instance still has to hand the plot over. */
    const val PENDING: String = "pending"

    /** Handed over. */
    const val SOLD: String = "sold"

    /** Withdrawn by the seller or removed by the team. */
    const val WITHDRAWN: String = "withdrawn"
}

/**
 * A plot (or merged plot group) offered on the plot market.
 *
 * @property id listing id.
 * @property shard instance.
 * @property plotId PlotSquared id (`world;x;z` of the base plot).
 * @property seller seller uuid.
 * @property sellerName seller name.
 * @property price Kronen.
 * @property world plot world.
 * @property x block x of the plot home.
 * @property z block z of the plot home.
 * @property size number of merged plots.
 * @property status one of [ListingStatus].
 * @property buyer buyer uuid.
 * @property createdAt offer time.
 * @property closedAt time of sale/withdrawal.
 */
@Serializable
data class PlotListing(
    val id: Long = 0,
    val shard: String,
    val plotId: String,
    val seller: String,
    val sellerName: String = "",
    val price: Long,
    val world: String = "plots",
    val x: Int = 0,
    val z: Int = 0,
    val size: Int = 1,
    val status: String = ListingStatus.ACTIVE,
    val buyer: String? = null,
    val createdAt: Long,
    val closedAt: Long? = null,
)

/**
 * Progress in one job.
 *
 * @property level job level, starting at 1.
 * @property xp XP inside the current level.
 */
@Serializable
data class JobProgress(val level: Int = 1, val xp: Long = 0)

/**
 * Network-wide job state of a player.
 *
 * @property jobs progress per job id.
 * @property active job id per slot (`null` = empty slot).
 * @property switchedAt last switch time per slot (`0` = never).
 * @property milestones claimed milestone keys (`<job>:<level>`).
 */
@Serializable
data class JobProfile(
    val jobs: Map<String, JobProgress> = emptyMap(),
    val active: List<String?> = emptyList(),
    val switchedAt: List<Long> = emptyList(),
    val milestones: Set<String> = emptySet(),
)

/**
 * Daily reward streak of a player.
 *
 * @property streak day of the streak claimed last (1…streak length).
 * @property lastDay ISO date of the last claim in the configured zone.
 */
@Serializable
data class RewardState(val streak: Int = 0, val lastDay: String? = null)

/**
 * Tutorial state of a player (network-wide, once).
 *
 * @property doneAt completion time.
 * @property bonusPaidAt time the completion bonus was paid.
 */
@Serializable
data class TutorialState(val doneAt: Long? = null, val bonusPaidAt: Long? = null)

/**
 * One issued forge item.
 *
 * @property serial serial number.
 * @property forgeId template id (`_oneoff` for items without template).
 * @property version template version at issue time.
 * @property issuedTo receiving player uuid.
 * @property source issue channel (`give`, `kit`, `crate`, `offer`, `reward`, `forge`).
 * @property shard instance.
 * @property issuedAt issue time.
 */
@Serializable
data class SerialRecord(
    val serial: Long,
    val forgeId: String,
    val version: Int,
    val issuedTo: String,
    val source: String,
    val shard: String,
    val issuedAt: Long,
)

/**
 * One audit log entry (shop, trade, auction, crate, forge, offer, plot market, admin shop).
 *
 * @property id entry id.
 * @property kind log kind, for example `trade` or `shop`.
 * @property shard instance.
 * @property actor acting player uuid.
 * @property target other party (uuid) or object, may be empty.
 * @property amount Kronen involved.
 * @property ref reference such as `auction:17` or `serial:42`.
 * @property data free JSON payload.
 * @property at time.
 */
@Serializable
data class LogEntry(
    val id: Long = 0,
    val kind: String,
    val shard: String,
    val actor: String,
    val target: String = "",
    val amount: Long = 0,
    val ref: String = "",
    val data: String = "{}",
    val at: Long,
)
