package org.helix.addons.citybuild.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
import org.helix.api.storage.AddonStorage

/**
 * Kit claim row of the document store.
 *
 * @property uuid player.
 * @property shard instance.
 * @property kit kit id.
 * @property at claim time.
 */
@Serializable
data class KitClaimRow(val uuid: String, val shard: String, val kit: String, val at: Long)

/**
 * Daily counter row of the document store.
 *
 * @property uuid player.
 * @property day ISO day.
 * @property key counter key.
 * @property value counter value.
 */
@Serializable
data class CounterRow(val uuid: String, val day: String, val key: String, val value: Long)

/**
 * Offer purchase row of the document store.
 *
 * @property offerId offer id.
 * @property shard instance.
 * @property uuid buyer.
 * @property count bought units.
 */
@Serializable
data class OfferRow(val offerId: String, val shard: String, val uuid: String, val count: Int)

/**
 * Profile report row of the document store.
 *
 * @property uuid player.
 * @property shard instance.
 * @property json summary JSON.
 */
@Serializable
data class ReportRow(val uuid: String, val shard: String, val json: String)

/**
 * [CityRepository] on the addon document store, used when the node runs without PostgreSQL
 * (json/mongodb storage mode, tests). Everything is held in memory and each domain is written back
 * as one document on change; every method is `@Synchronized`, which makes all transitions atomic on
 * the single node. Old daily counters (> 3 days) and audit entries beyond [MAX_LOGS] are pruned.
 *
 * @property storage the addon-scoped document store.
 */
class DocumentCityRepository(private val storage: AddonStorage) : CityRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val homes = load("homes", Home.serializer())
    private val warps = load("warps", Warp.serializer())
    private val kitClaims = load("kitclaims", KitClaimRow.serializer())
    private val auctions = load("auctions", Auction.serializer())
    private val pickups = load("pickups", Pickup.serializer())
    private val listings = load("listings", PlotListing.serializer())
    private val counters = load("counters", CounterRow.serializer())
    private val offers = load("offers", OfferRow.serializer())
    private val serials = load("serials", SerialRecord.serializer())
    private val logs = load("logs", LogEntry.serializer())
    private val reports = load("reports", ReportRow.serializer())
    private val jobs = loadMap("jobs", JobProfile.serializer())
    private val rewards = loadMap("rewards", RewardState.serializer())
    private val tutorials = loadMap("tutorials", TutorialState.serializer())
    private val sequences = loadMap("sequences", Long.serializer())

    @Synchronized override fun homes(uuid: String, shard: String): List<Home> =
        homes.filter { it.uuid == uuid && it.shard == shard }.sortedBy { it.name.lowercase() }

    @Synchronized override fun putHome(home: Home) {
        homes.removeAll { it.uuid == home.uuid && it.shard == home.shard && it.name.equals(home.name, true) }
        homes += home
        save("homes", homes, Home.serializer())
    }

    @Synchronized override fun deleteHome(uuid: String, shard: String, name: String): Boolean =
        homes.removeAll { it.uuid == uuid && it.shard == shard && it.name.equals(name, true) }.also { if (it) save("homes", homes, Home.serializer()) }

    @Synchronized override fun warps(shard: String): List<Warp> =
        warps.filter { it.shard == shard }.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))

    @Synchronized override fun putWarp(warp: Warp) {
        warps.removeAll { it.shard == warp.shard && it.name.equals(warp.name, true) }
        warps += warp
        save("warps", warps, Warp.serializer())
    }

    @Synchronized override fun deleteWarp(shard: String, name: String): Boolean =
        warps.removeAll { it.shard == shard && it.name.equals(name, true) }.also { if (it) save("warps", warps, Warp.serializer()) }

    @Synchronized override fun kitClaimedAt(uuid: String, shard: String, kit: String): Long? =
        kitClaims.firstOrNull { it.uuid == uuid && it.shard == shard && it.kit == kit }?.at

    @Synchronized override fun setKitClaim(uuid: String, shard: String, kit: String, at: Long) {
        kitClaims.removeAll { it.uuid == uuid && it.shard == shard && it.kit == kit }
        kitClaims += KitClaimRow(uuid, shard, kit, at)
        save("kitclaims", kitClaims, KitClaimRow.serializer())
    }

    @Synchronized override fun kitClaims(uuid: String, shard: String): Map<String, Long> =
        kitClaims.filter { it.uuid == uuid && it.shard == shard }.associate { it.kit to it.at }

    @Synchronized override fun insertAuction(auction: Auction): Auction {
        val stored = auction.copy(id = next("auction"))
        auctions += stored
        save("auctions", auctions, Auction.serializer())
        return stored
    }

    @Synchronized override fun auction(id: Long): Auction? = auctions.firstOrNull { it.id == id }

    @Synchronized override fun auctions(shard: String, statuses: Set<String>): List<Auction> =
        auctions.filter { it.shard == shard && it.status in statuses }.sortedByDescending { it.id }

    @Synchronized override fun auctionsOf(shard: String, seller: String, statuses: Set<String>): List<Auction> =
        auctions.filter { it.shard == shard && it.seller == seller && it.status in statuses }.sortedByDescending { it.id }

    @Synchronized override fun transitionAuction(id: Long, from: Set<String>, to: String, buyer: String?, closedAt: Long?, notBefore: Long?): Auction? {
        val index = auctions.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = auctions[index]
        if (current.status !in from) return null
        if (notBefore != null && current.expiresAt <= notBefore) return null
        val updated = current.copy(status = to, buyer = buyer ?: current.buyer, closedAt = closedAt ?: current.closedAt)
        auctions[index] = updated
        save("auctions", auctions, Auction.serializer())
        return updated
    }

    @Synchronized override fun markSellerNotified(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        auctions.replaceAll { if (it.id in ids) it.copy(sellerNotified = true) else it }
        save("auctions", auctions, Auction.serializer())
    }

    @Synchronized override fun insertPickup(pickup: Pickup): Pickup {
        val stored = pickup.copy(id = next("pickup"))
        pickups += stored
        save("pickups", pickups, Pickup.serializer())
        return stored
    }

    @Synchronized override fun pickups(uuid: String, shard: String): List<Pickup> =
        pickups.filter { it.uuid == uuid && it.shard == shard }.sortedBy { it.id }

    @Synchronized override fun takePickup(uuid: String, shard: String, id: Long): Pickup? {
        val entry = pickups.firstOrNull { it.id == id && it.uuid == uuid && it.shard == shard } ?: return null
        pickups.remove(entry)
        save("pickups", pickups, Pickup.serializer())
        return entry
    }

    @Synchronized override fun insertListing(listing: PlotListing): PlotListing {
        val stored = listing.copy(id = next("listing"))
        listings += stored
        save("listings", listings, PlotListing.serializer())
        return stored
    }

    @Synchronized override fun listing(id: Long): PlotListing? = listings.firstOrNull { it.id == id }

    @Synchronized override fun listings(shard: String, statuses: Set<String>): List<PlotListing> =
        listings.filter { it.shard == shard && it.status in statuses }.sortedByDescending { it.id }

    @Synchronized override fun transitionListing(id: Long, from: Set<String>, to: String, buyer: String?, closedAt: Long?): PlotListing? {
        val index = listings.indexOfFirst { it.id == id }
        if (index < 0 || listings[index].status !in from) return null
        val updated = listings[index].let { it.copy(status = to, buyer = buyer ?: it.buyer, closedAt = closedAt ?: it.closedAt) }
        listings[index] = updated
        save("listings", listings, PlotListing.serializer())
        return updated
    }

    @Synchronized override fun jobProfile(uuid: String): JobProfile? = jobs[uuid]

    @Synchronized override fun putJobProfile(uuid: String, profile: JobProfile) {
        jobs[uuid] = profile
        saveMap("jobs", jobs, JobProfile.serializer())
    }

    @Synchronized override fun reward(uuid: String): RewardState? = rewards[uuid]

    @Synchronized override fun updateReward(uuid: String, expectedLastDay: String?, state: RewardState): Boolean {
        if (rewards[uuid]?.lastDay != expectedLastDay) return false
        rewards[uuid] = state
        saveMap("rewards", rewards, RewardState.serializer())
        return true
    }

    @Synchronized override fun tutorial(uuid: String): TutorialState? = tutorials[uuid]

    @Synchronized override fun markTutorialBonus(uuid: String, at: Long): Boolean {
        val current = tutorials[uuid] ?: TutorialState()
        if (current.bonusPaidAt != null) return false
        tutorials[uuid] = current.copy(doneAt = current.doneAt ?: at, bonusPaidAt = at)
        saveMap("tutorials", tutorials, TutorialState.serializer())
        return true
    }

    @Synchronized override fun unmarkTutorialBonus(uuid: String) {
        val current = tutorials[uuid] ?: return
        tutorials[uuid] = current.copy(bonusPaidAt = null)
        saveMap("tutorials", tutorials, TutorialState.serializer())
    }

    @Synchronized override fun addCounter(uuid: String, day: String, key: String, delta: Long, max: Long): Long? {
        val index = counters.indexOfFirst { it.uuid == uuid && it.day == day && it.key == key }
        val current = if (index >= 0) counters[index].value else 0L
        val updated = (current + delta).coerceAtLeast(0)
        if (delta > 0 && updated > max) return null
        if (index >= 0) counters[index] = counters[index].copy(value = updated) else counters += CounterRow(uuid, day, key, updated)
        counters.removeAll { it.day < pruneBefore(day) }
        save("counters", counters, CounterRow.serializer())
        return updated
    }

    @Synchronized override fun counters(uuid: String, day: String): Map<String, Long> =
        counters.filter { it.uuid == uuid && it.day == day }.associate { it.key to it.value }

    @Synchronized override fun reserveOffer(offerId: String, shard: String, uuid: String, count: Int, stock: Int?, perPlayer: Int?): Boolean {
        if (stock != null && offerSold(offerId, shard) + count > stock) return false
        if (perPlayer != null && offerBought(offerId, shard, uuid) + count > perPlayer) return false
        val index = offers.indexOfFirst { it.offerId == offerId && it.shard == shard && it.uuid == uuid }
        if (index >= 0) offers[index] = offers[index].copy(count = offers[index].count + count) else offers += OfferRow(offerId, shard, uuid, count)
        save("offers", offers, OfferRow.serializer())
        return true
    }

    @Synchronized override fun releaseOffer(offerId: String, shard: String, uuid: String, count: Int) {
        val index = offers.indexOfFirst { it.offerId == offerId && it.shard == shard && it.uuid == uuid }
        if (index < 0) return
        offers[index] = offers[index].copy(count = (offers[index].count - count).coerceAtLeast(0))
        save("offers", offers, OfferRow.serializer())
    }

    @Synchronized override fun offerSold(offerId: String, shard: String): Int =
        offers.filter { it.offerId == offerId && it.shard == shard }.sumOf { it.count }

    @Synchronized override fun offerBought(offerId: String, shard: String, uuid: String): Int =
        offers.firstOrNull { it.offerId == offerId && it.shard == shard && it.uuid == uuid }?.count ?: 0

    @Synchronized override fun issueSerial(record: SerialRecord): SerialRecord {
        val stored = record.copy(serial = next("serial"))
        serials += stored
        save("serials", serials, SerialRecord.serializer())
        return stored
    }

    @Synchronized override fun serial(serial: Long): SerialRecord? = serials.firstOrNull { it.serial == serial }

    @Synchronized override fun log(entry: LogEntry) {
        logs += entry.copy(id = next("log"))
        while (logs.size > MAX_LOGS) logs.removeAt(0)
        save("logs", logs, LogEntry.serializer())
    }

    @Synchronized override fun logs(actor: String?, ref: String?, kind: String?, limit: Int): List<LogEntry> =
        logs.asReversed().asSequence()
            .filter { actor == null || it.actor == actor || it.target == actor }
            .filter { ref == null || it.ref == ref }
            .filter { kind == null || it.kind == kind }
            .take(limit)
            .toList()

    @Synchronized override fun putProfileReport(uuid: String, shard: String, json: String) {
        reports.removeAll { it.uuid == uuid && it.shard == shard }
        reports += ReportRow(uuid, shard, json)
        save("reports", reports, ReportRow.serializer())
    }

    @Synchronized override fun profileReports(uuid: String): Map<String, String> =
        reports.filter { it.uuid == uuid }.associate { it.shard to it.json }

    @Synchronized override fun deletePlayer(uuid: String): Boolean {
        var changed = false
        if (homes.removeAll { it.uuid == uuid }) { changed = true; save("homes", homes, Home.serializer()) }
        if (kitClaims.removeAll { it.uuid == uuid }) { changed = true; save("kitclaims", kitClaims, KitClaimRow.serializer()) }
        if (pickups.removeAll { it.uuid == uuid }) { changed = true; save("pickups", pickups, Pickup.serializer()) }
        if (counters.removeAll { it.uuid == uuid }) { changed = true; save("counters", counters, CounterRow.serializer()) }
        if (reports.removeAll { it.uuid == uuid }) { changed = true; save("reports", reports, ReportRow.serializer()) }
        if (auctions.removeAll { it.seller == uuid && it.status == "active" }) { changed = true; save("auctions", auctions, Auction.serializer()) }
        if (listings.removeAll { it.seller == uuid && it.status == "active" }) { changed = true; save("listings", listings, PlotListing.serializer()) }
        if (jobs.remove(uuid) != null) { changed = true; saveMap("jobs", jobs, JobProfile.serializer()) }
        if (rewards.remove(uuid) != null) { changed = true; saveMap("rewards", rewards, RewardState.serializer()) }
        if (tutorials.remove(uuid) != null) { changed = true; saveMap("tutorials", tutorials, TutorialState.serializer()) }
        return changed
    }

    @Synchronized override fun wipeGameplay(dryRun: Boolean): Map<String, Int> {
        val counts = linkedMapOf(
            "homes" to homes.size, "kit_claims" to kitClaims.size, "auctions" to auctions.size, "pickup" to pickups.size,
            "plot_market" to listings.size, "jobs" to jobs.size, "rewards" to rewards.size, "tutorial" to tutorials.size,
            "daily_counters" to counters.size, "offer_buys" to offers.size, "forge_serials" to serials.size,
            "profile_reports" to reports.size,
        )
        if (dryRun) return counts
        homes.clear(); save("homes", homes, Home.serializer())
        kitClaims.clear(); save("kitclaims", kitClaims, KitClaimRow.serializer())
        auctions.clear(); save("auctions", auctions, Auction.serializer())
        pickups.clear(); save("pickups", pickups, Pickup.serializer())
        listings.clear(); save("listings", listings, PlotListing.serializer())
        counters.clear(); save("counters", counters, CounterRow.serializer())
        offers.clear(); save("offers", offers, OfferRow.serializer())
        serials.clear(); save("serials", serials, SerialRecord.serializer())
        reports.clear(); save("reports", reports, ReportRow.serializer())
        jobs.clear(); saveMap("jobs", jobs, JobProfile.serializer())
        rewards.clear(); saveMap("rewards", rewards, RewardState.serializer())
        tutorials.clear(); saveMap("tutorials", tutorials, TutorialState.serializer())
        return counts
    }

    /** Nothing to release. */
    override fun close() = Unit

    private fun next(sequence: String): Long {
        val value = (sequences[sequence] ?: 0L) + 1
        sequences[sequence] = value
        saveMap("sequences", sequences, Long.serializer())
        return value
    }

    private fun pruneBefore(day: String): String = runCatching { java.time.LocalDate.parse(day).minusDays(3).toString() }.getOrDefault("")

    private fun <T> load(name: String, serializer: KSerializer<T>): MutableList<T> =
        storage.read(PREFIX + name)?.let { json.decodeFromString(ListSerializer(serializer), it).toMutableList() } ?: mutableListOf()

    private fun <T> loadMap(name: String, serializer: KSerializer<T>): MutableMap<String, T> =
        storage.read(PREFIX + name)?.let { json.decodeFromString(MapSerializer(String.serializer(), serializer), it).toMutableMap() } ?: mutableMapOf()

    private fun <T> save(name: String, rows: List<T>, serializer: KSerializer<T>) =
        storage.write(PREFIX + name, json.encodeToString(ListSerializer(serializer), rows))

    private fun <T> saveMap(name: String, rows: Map<String, T>, serializer: KSerializer<T>) =
        storage.write(PREFIX + name, json.encodeToString(MapSerializer(String.serializer(), serializer), rows))

    private companion object {
        const val PREFIX = "data."
        const val MAX_LOGS = 5000
    }
}
