package org.helix.addons.citybuild.store

import org.helix.addons.citybuild.model.Auction
import org.helix.addons.citybuild.model.Home
import org.helix.addons.citybuild.model.JobProfile
import org.helix.addons.citybuild.model.LogEntry
import org.helix.addons.citybuild.model.Pickup
import org.helix.addons.citybuild.model.PlotListing
import org.helix.addons.citybuild.model.RewardState
import org.helix.addons.citybuild.model.SerialRecord
import org.helix.addons.citybuild.model.TutorialState
import org.helix.addons.citybuild.model.Warp

/**
 * Persistence of all stateful CityBuild data. Implementations must make every state transition
 * atomic ([transitionAuction], [transitionListing], [takePickup], [addCounter], [reserveOffer],
 * [updateReward], [markTutorialBonus]) so concurrent requests from several instances never both
 * succeed. Business rules (fees, limits, cooldowns) live in the services, not here.
 */
interface CityRepository : AutoCloseable {
    /**
     * Homes of a player on an instance.
     *
     * @param uuid player.
     * @param shard instance.
     * @return homes sorted by name.
     */
    fun homes(uuid: String, shard: String): List<Home>

    /**
     * Creates or replaces a home (same uuid, shard and name ignoring case).
     *
     * @param home the home.
     */
    fun putHome(home: Home)

    /**
     * Deletes a home.
     *
     * @param uuid player.
     * @param shard instance.
     * @param name home name (case-insensitive).
     * @return `true` when a home was deleted.
     */
    fun deleteHome(uuid: String, shard: String, name: String): Boolean

    /**
     * Warps of an instance.
     *
     * @param shard instance.
     * @return warps by sort order and name.
     */
    fun warps(shard: String): List<Warp>

    /**
     * Creates or replaces a warp (same shard and name ignoring case).
     *
     * @param warp the warp.
     */
    fun putWarp(warp: Warp)

    /**
     * Deletes a warp.
     *
     * @param shard instance.
     * @param name warp name (case-insensitive).
     * @return `true` when a warp was deleted.
     */
    fun deleteWarp(shard: String, name: String): Boolean

    /**
     * Last claim of a kit.
     *
     * @param uuid player.
     * @param shard instance.
     * @param kit kit id.
     * @return claim time or `null`.
     */
    fun kitClaimedAt(uuid: String, shard: String, kit: String): Long?

    /**
     * Records a kit claim.
     *
     * @param uuid player.
     * @param shard instance.
     * @param kit kit id.
     * @param at claim time.
     */
    fun setKitClaim(uuid: String, shard: String, kit: String, at: Long)

    /**
     * All kit claims of a player on an instance.
     *
     * @param uuid player.
     * @param shard instance.
     * @return kit id to claim time.
     */
    fun kitClaims(uuid: String, shard: String): Map<String, Long>

    /**
     * Inserts an auction.
     *
     * @param auction the listing (its id is ignored).
     * @return the stored listing with id.
     */
    fun insertAuction(auction: Auction): Auction

    /**
     * Loads an auction.
     *
     * @param id listing id.
     * @return the listing or `null`.
     */
    fun auction(id: Long): Auction?

    /**
     * Auctions of an instance in the given states.
     *
     * @param shard instance.
     * @param statuses wanted states.
     * @return listings, newest first.
     */
    fun auctions(shard: String, statuses: Set<String>): List<Auction>

    /**
     * Auctions of a seller on an instance in the given states.
     *
     * @param shard instance.
     * @param seller seller uuid.
     * @param statuses wanted states.
     * @return listings, newest first.
     */
    fun auctionsOf(shard: String, seller: String, statuses: Set<String>): List<Auction>

    /**
     * Atomically moves an auction from one of [from] to [to].
     *
     * @param id listing id.
     * @param from allowed current states.
     * @param to new state.
     * @param buyer buyer to store (only when not `null`).
     * @param closedAt close time to store (only when not `null`).
     * @param notBefore when not `null`, the transition only happens if `expiresAt > notBefore`.
     * @return the updated listing, or `null` when the state did not match.
     */
    fun transitionAuction(id: Long, from: Set<String>, to: String, buyer: String? = null, closedAt: Long? = null, notBefore: Long? = null): Auction?

    /**
     * Marks the sale of auctions as seen by their seller.
     *
     * @param ids listing ids.
     */
    fun markSellerNotified(ids: Collection<Long>)

    /**
     * Inserts a pickup entry.
     *
     * @param pickup the entry (its id is ignored).
     * @return the stored entry with id.
     */
    fun insertPickup(pickup: Pickup): Pickup

    /**
     * Pickup box of a player on an instance.
     *
     * @param uuid player.
     * @param shard instance.
     * @return entries, oldest first.
     */
    fun pickups(uuid: String, shard: String): List<Pickup>

    /**
     * Atomically removes and returns a pickup entry.
     *
     * @param uuid owner (must match).
     * @param shard instance (must match).
     * @param id entry id.
     * @return the entry, or `null` when it does not exist (anymore).
     */
    fun takePickup(uuid: String, shard: String, id: Long): Pickup?

    /**
     * Inserts a plot market listing.
     *
     * @param listing the listing (its id is ignored).
     * @return the stored listing with id.
     */
    fun insertListing(listing: PlotListing): PlotListing

    /**
     * Loads a plot market listing.
     *
     * @param id listing id.
     * @return the listing or `null`.
     */
    fun listing(id: Long): PlotListing?

    /**
     * Plot market listings of an instance in the given states.
     *
     * @param shard instance.
     * @param statuses wanted states.
     * @return listings, newest first.
     */
    fun listings(shard: String, statuses: Set<String>): List<PlotListing>

    /**
     * Atomically moves a listing from one of [from] to [to].
     *
     * @param id listing id.
     * @param from allowed current states.
     * @param to new state.
     * @param buyer buyer to store (only when not `null`).
     * @param closedAt close time to store (only when not `null`).
     * @return the updated listing, or `null` when the state did not match.
     */
    fun transitionListing(id: Long, from: Set<String>, to: String, buyer: String? = null, closedAt: Long? = null): PlotListing?

    /**
     * Job state of a player.
     *
     * @param uuid player.
     * @return the state or `null`.
     */
    fun jobProfile(uuid: String): JobProfile?

    /**
     * Stores the job state of a player.
     *
     * @param uuid player.
     * @param profile the state.
     */
    fun putJobProfile(uuid: String, profile: JobProfile)

    /**
     * Daily reward state.
     *
     * @param uuid player.
     * @return the state or `null` (never claimed).
     */
    fun reward(uuid: String): RewardState?

    /**
     * Atomically replaces the reward state if its last day is still [expectedLastDay].
     *
     * @param uuid player.
     * @param expectedLastDay last day the caller saw (`null` = never claimed).
     * @param state the new state.
     * @return `true` when replaced.
     */
    fun updateReward(uuid: String, expectedLastDay: String?, state: RewardState): Boolean

    /**
     * Tutorial state.
     *
     * @param uuid player.
     * @return the state or `null`.
     */
    fun tutorial(uuid: String): TutorialState?

    /**
     * Atomically marks the tutorial as done and its bonus as paid, only the first time.
     *
     * @param uuid player.
     * @param at time.
     * @return `true` when this call marked it (the bonus is due), `false` when already paid.
     */
    fun markTutorialBonus(uuid: String, at: Long): Boolean

    /**
     * Reverts [markTutorialBonus] after a failed payout.
     *
     * @param uuid player.
     */
    fun unmarkTutorialBonus(uuid: String)

    /**
     * Atomically adds to a per-player daily counter, refusing to exceed [max].
     *
     * @param uuid player.
     * @param day ISO day.
     * @param key counter key, for example `sell:diamond`.
     * @param delta amount to add (may be negative to roll back).
     * @param max upper bound (inclusive); ignored for negative deltas.
     * @return the new value, or `null` when it would exceed [max].
     */
    fun addCounter(uuid: String, day: String, key: String, delta: Long, max: Long): Long?

    /**
     * All counters of a player on a day.
     *
     * @param uuid player.
     * @param day ISO day.
     * @return key to value.
     */
    fun counters(uuid: String, day: String): Map<String, Long>

    /**
     * Atomically reserves [count] units of an offer: total sold on the instance stays within [stock]
     * and the player's purchases within [perPlayer].
     *
     * @param offerId offer id.
     * @param shard instance.
     * @param uuid buyer.
     * @param count units.
     * @param stock total stock per instance, `null` = unlimited.
     * @param perPlayer limit per player, `null` = unlimited.
     * @return `true` when reserved.
     */
    fun reserveOffer(offerId: String, shard: String, uuid: String, count: Int, stock: Int?, perPlayer: Int?): Boolean

    /**
     * Rolls a reservation back.
     *
     * @param offerId offer id.
     * @param shard instance.
     * @param uuid buyer.
     * @param count units.
     */
    fun releaseOffer(offerId: String, shard: String, uuid: String, count: Int)

    /**
     * Units of an offer sold on an instance.
     *
     * @param offerId offer id.
     * @param shard instance.
     * @return sold units.
     */
    fun offerSold(offerId: String, shard: String): Int

    /**
     * Units of an offer a player bought on an instance.
     *
     * @param offerId offer id.
     * @param shard instance.
     * @param uuid buyer.
     * @return bought units.
     */
    fun offerBought(offerId: String, shard: String, uuid: String): Int

    /**
     * Issues the next forge serial number.
     *
     * @param record the record (its serial is ignored).
     * @return the stored record with serial.
     */
    fun issueSerial(record: SerialRecord): SerialRecord

    /**
     * Looks a serial up.
     *
     * @param serial serial number.
     * @return the record or `null`.
     */
    fun serial(serial: Long): SerialRecord?

    /**
     * Appends an audit log entry.
     *
     * @param entry the entry (its id is ignored).
     */
    fun log(entry: LogEntry)

    /**
     * Searches the audit log.
     *
     * @param actor player uuid matched against actor or target, `null` = any.
     * @param ref reference to match exactly, `null` = any.
     * @param kind log kind, `null` = any.
     * @param limit maximum entries.
     * @return entries, newest first.
     */
    fun logs(actor: String?, ref: String?, kind: String?, limit: Int): List<LogEntry>

    /**
     * Stores the profile summary an instance reported for a player (for example the plot count).
     *
     * @param uuid player.
     * @param shard instance.
     * @param json summary JSON object.
     */
    fun putProfileReport(uuid: String, shard: String, json: String)

    /**
     * Profile summaries of a player.
     *
     * @param uuid player.
     * @return instance to summary JSON.
     */
    fun profileReports(uuid: String): Map<String, String>

    /**
     * Deletes every record of a player (GDPR), except audit log entries other players depend on.
     *
     * @param uuid player.
     * @return `true` when something was deleted.
     */
    fun deletePlayer(uuid: String): Boolean

    /**
     * Counts or deletes the gameplay data of the release reset (plan 1, section 9): homes, kit claims, auctions,
     * pickup boxes, plot market, jobs, daily rewards, tutorial state, daily counters, offer purchases, forge serials
     * and profile reports. Warps (spawn points of the team) and the audit log are kept.
     *
     * @param dryRun `true` only counts.
     * @return rows per data set (counted before deletion).
     */
    fun wipeGameplay(dryRun: Boolean): Map<String, Int>
}
