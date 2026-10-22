package org.helix.addons.citybuild.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import org.helix.api.addon.StorageConnection
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * [CityRepository] on the node's central PostgreSQL database, schema `citybuild`. Atomic transitions
 * are single conditional `UPDATE … RETURNING` / `DELETE … RETURNING` statements; offer reservations
 * take a transaction-scoped advisory lock per offer and instance. Items are stored as their Base64
 * text. The driver and HikariCP come from the node classloader.
 *
 * @param connection the node's storage connection settings.
 */
class PostgresCityRepository(connection: StorageConnection) : CityRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = connection.url
            username = connection.user
            password = connection.password
            maximumPoolSize = connection.poolSize.coerceIn(1, 8)
            poolName = "citybuild"
        },
    )

    init {
        pool.connection.use { c -> c.createStatement().use { st -> SCHEMA.forEach { st.executeUpdate(it) } } }
    }

    override fun homes(uuid: String, shard: String): List<Home> =
        query("SELECT * FROM citybuild.homes WHERE uuid = ? AND shard = ? ORDER BY name_key", uuid, shard) { it.home() }

    override fun putHome(home: Home) {
        update(
            "INSERT INTO citybuild.homes (uuid, shard, name_key, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (uuid, shard, name_key) DO UPDATE SET name = EXCLUDED.name, world = EXCLUDED.world, x = EXCLUDED.x, y = EXCLUDED.y, " +
                "z = EXCLUDED.z, yaw = EXCLUDED.yaw, pitch = EXCLUDED.pitch",
            home.uuid, home.shard, home.name.lowercase(), home.name, home.world, home.x, home.y, home.z, home.yaw, home.pitch,
        )
    }

    override fun deleteHome(uuid: String, shard: String, name: String): Boolean =
        update("DELETE FROM citybuild.homes WHERE uuid = ? AND shard = ? AND name_key = ?", uuid, shard, name.lowercase()) > 0

    override fun warps(shard: String): List<Warp> =
        query("SELECT * FROM citybuild.warps WHERE shard = ? ORDER BY sort_order, name_key", shard) { it.warp() }

    override fun putWarp(warp: Warp) {
        update(
            "INSERT INTO citybuild.warps (shard, name_key, name, icon, description, world, x, y, z, yaw, pitch, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (shard, name_key) DO UPDATE SET name = EXCLUDED.name, icon = EXCLUDED.icon, description = EXCLUDED.description, world = EXCLUDED.world, " +
                "x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z, yaw = EXCLUDED.yaw, pitch = EXCLUDED.pitch, sort_order = EXCLUDED.sort_order",
            warp.shard, warp.name.lowercase(), warp.name, warp.icon, warp.description, warp.world, warp.x, warp.y, warp.z, warp.yaw, warp.pitch, warp.sortOrder,
        )
    }

    override fun deleteWarp(shard: String, name: String): Boolean =
        update("DELETE FROM citybuild.warps WHERE shard = ? AND name_key = ?", shard, name.lowercase()) > 0

    override fun kitClaimedAt(uuid: String, shard: String, kit: String): Long? =
        query("SELECT claimed_at FROM citybuild.kit_claims WHERE uuid = ? AND shard = ? AND kit = ?", uuid, shard, kit) { it.getLong(1) }.firstOrNull()

    override fun setKitClaim(uuid: String, shard: String, kit: String, at: Long) {
        update(
            "INSERT INTO citybuild.kit_claims (uuid, shard, kit, claimed_at) VALUES (?, ?, ?, ?) ON CONFLICT (uuid, shard, kit) DO UPDATE SET claimed_at = EXCLUDED.claimed_at",
            uuid, shard, kit, at,
        )
    }

    override fun kitClaims(uuid: String, shard: String): Map<String, Long> =
        query("SELECT kit, claimed_at FROM citybuild.kit_claims WHERE uuid = ? AND shard = ?", uuid, shard) { it.getString(1) to it.getLong(2) }.toMap()

    override fun insertAuction(auction: Auction): Auction = query(
        "INSERT INTO citybuild.auctions (shard, seller, seller_name, item, material, display_name, category, amount, price, created_at, expires_at, status, buyer, closed_at, seller_notified) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *",
        auction.shard, auction.seller, auction.sellerName, auction.item, auction.material, auction.displayName, auction.category, auction.amount,
        auction.price, auction.createdAt, auction.expiresAt, auction.status, auction.buyer, auction.closedAt, auction.sellerNotified,
    ) { it.auction() }.single()

    override fun auction(id: Long): Auction? = query("SELECT * FROM citybuild.auctions WHERE id = ?", id) { it.auction() }.firstOrNull()

    override fun auctions(shard: String, statuses: Set<String>): List<Auction> =
        query("SELECT * FROM citybuild.auctions WHERE shard = ? AND status = ANY(?) ORDER BY id DESC", shard, statuses) { it.auction() }

    override fun auctionsOf(shard: String, seller: String, statuses: Set<String>): List<Auction> =
        query("SELECT * FROM citybuild.auctions WHERE shard = ? AND seller = ? AND status = ANY(?) ORDER BY id DESC", shard, seller, statuses) { it.auction() }

    override fun transitionAuction(id: Long, from: Set<String>, to: String, buyer: String?, closedAt: Long?, notBefore: Long?): Auction? = query(
        "UPDATE citybuild.auctions SET status = ?, buyer = COALESCE(?, buyer), closed_at = COALESCE(?::bigint, closed_at) " +
            "WHERE id = ? AND status = ANY(?) AND (?::bigint IS NULL OR expires_at > ?::bigint) RETURNING *",
        to, buyer, closedAt, id, from, notBefore, notBefore,
    ) { it.auction() }.firstOrNull()

    override fun markSellerNotified(ids: Collection<Long>) {
        if (ids.isNotEmpty()) update("UPDATE citybuild.auctions SET seller_notified = TRUE WHERE id = ANY(?)", LongIds(ids.toList()))
    }

    override fun insertPickup(pickup: Pickup): Pickup = query(
        "INSERT INTO citybuild.pickup (shard, uuid, item, source, created_at) VALUES (?, ?, ?, ?, ?) RETURNING *",
        pickup.shard, pickup.uuid, pickup.item, pickup.source, pickup.createdAt,
    ) { it.pickup() }.single()

    override fun pickups(uuid: String, shard: String): List<Pickup> =
        query("SELECT * FROM citybuild.pickup WHERE uuid = ? AND shard = ? ORDER BY id", uuid, shard) { it.pickup() }

    override fun takePickup(uuid: String, shard: String, id: Long): Pickup? =
        query("DELETE FROM citybuild.pickup WHERE id = ? AND uuid = ? AND shard = ? RETURNING *", id, uuid, shard) { it.pickup() }.firstOrNull()

    override fun insertListing(listing: PlotListing): PlotListing = query(
        "INSERT INTO citybuild.plot_market (shard, plot_id, seller, seller_name, price, world, x, z, size, status, buyer, created_at, closed_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *",
        listing.shard, listing.plotId, listing.seller, listing.sellerName, listing.price, listing.world, listing.x, listing.z, listing.size,
        listing.status, listing.buyer, listing.createdAt, listing.closedAt,
    ) { it.listing() }.single()

    override fun listing(id: Long): PlotListing? = query("SELECT * FROM citybuild.plot_market WHERE id = ?", id) { it.listing() }.firstOrNull()

    override fun listings(shard: String, statuses: Set<String>): List<PlotListing> =
        query("SELECT * FROM citybuild.plot_market WHERE shard = ? AND status = ANY(?) ORDER BY id DESC", shard, statuses) { it.listing() }

    override fun transitionListing(id: Long, from: Set<String>, to: String, buyer: String?, closedAt: Long?): PlotListing? = query(
        "UPDATE citybuild.plot_market SET status = ?, buyer = COALESCE(?, buyer), closed_at = COALESCE(?::bigint, closed_at) WHERE id = ? AND status = ANY(?) RETURNING *",
        to, buyer, closedAt, id, from,
    ) { it.listing() }.firstOrNull()

    override fun jobProfile(uuid: String): JobProfile? =
        query("SELECT profile FROM citybuild.jobs WHERE uuid = ?", uuid) { json.decodeFromString<JobProfile>(it.getString(1)) }.firstOrNull()

    override fun putJobProfile(uuid: String, profile: JobProfile) {
        update(
            "INSERT INTO citybuild.jobs (uuid, profile) VALUES (?, ?::jsonb) ON CONFLICT (uuid) DO UPDATE SET profile = EXCLUDED.profile",
            uuid, json.encodeToString(profile),
        )
    }

    override fun reward(uuid: String): RewardState? =
        query("SELECT streak, last_day FROM citybuild.rewards WHERE uuid = ?", uuid) { RewardState(it.getInt(1), it.getString(2)) }.firstOrNull()

    override fun updateReward(uuid: String, expectedLastDay: String?, state: RewardState): Boolean =
        if (expectedLastDay == null) {
            update(
                "INSERT INTO citybuild.rewards (uuid, streak, last_day) VALUES (?, ?, ?) ON CONFLICT (uuid) DO UPDATE " +
                    "SET streak = EXCLUDED.streak, last_day = EXCLUDED.last_day WHERE citybuild.rewards.last_day IS NULL",
                uuid, state.streak, state.lastDay,
            ) > 0
        } else {
            update("UPDATE citybuild.rewards SET streak = ?, last_day = ? WHERE uuid = ? AND last_day = ?", state.streak, state.lastDay, uuid, expectedLastDay) > 0
        }

    override fun tutorial(uuid: String): TutorialState? = query("SELECT done_at, bonus_paid_at FROM citybuild.tutorial WHERE uuid = ?", uuid) {
        TutorialState(it.getLong(1).takeUnless { _ -> it.wasNull() }, it.getLong(2).takeUnless { _ -> it.wasNull() })
    }.firstOrNull()

    override fun markTutorialBonus(uuid: String, at: Long): Boolean = update(
        "INSERT INTO citybuild.tutorial (uuid, done_at, bonus_paid_at) VALUES (?, ?, ?) ON CONFLICT (uuid) DO UPDATE " +
            "SET bonus_paid_at = EXCLUDED.bonus_paid_at, done_at = COALESCE(citybuild.tutorial.done_at, EXCLUDED.done_at) WHERE citybuild.tutorial.bonus_paid_at IS NULL",
        uuid, at, at,
    ) > 0

    override fun unmarkTutorialBonus(uuid: String) {
        update("UPDATE citybuild.tutorial SET bonus_paid_at = NULL WHERE uuid = ?", uuid)
    }

    override fun addCounter(uuid: String, day: String, key: String, delta: Long, max: Long): Long? = query(
        "INSERT INTO citybuild.daily_counters (uuid, day, key, value) SELECT ?, ?, ?, GREATEST(0, ?::bigint) WHERE ?::bigint <= ?::bigint OR ?::bigint <= 0 " +
            "ON CONFLICT (uuid, day, key) DO UPDATE SET value = GREATEST(0, citybuild.daily_counters.value + ?::bigint) " +
            "WHERE ?::bigint <= 0 OR citybuild.daily_counters.value + ?::bigint <= ?::bigint RETURNING value",
        uuid, day, key, delta, delta, max, delta, delta, delta, delta, max,
    ) { it.getLong(1) }.firstOrNull()

    override fun counters(uuid: String, day: String): Map<String, Long> =
        query("SELECT key, value FROM citybuild.daily_counters WHERE uuid = ? AND day = ?", uuid, day) { it.getString(1) to it.getLong(2) }.toMap()

    override fun reserveOffer(offerId: String, shard: String, uuid: String, count: Int, stock: Int?, perPlayer: Int?): Boolean = transaction { c ->
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { st -> st.bind(listOf("offer:$offerId:$shard")); st.executeQuery().close() }
        val sold = c.single("SELECT COALESCE(SUM(count), 0) FROM citybuild.offer_buys WHERE offer_id = ? AND shard = ?", offerId, shard)
        val bought = c.single("SELECT COALESCE(SUM(count), 0) FROM citybuild.offer_buys WHERE offer_id = ? AND shard = ? AND uuid = ?", offerId, shard, uuid)
        if (stock != null && sold + count > stock) return@transaction false
        if (perPlayer != null && bought + count > perPlayer) return@transaction false
        c.prepareStatement(
            "INSERT INTO citybuild.offer_buys (offer_id, shard, uuid, count) VALUES (?, ?, ?, ?) ON CONFLICT (offer_id, shard, uuid) DO UPDATE SET count = citybuild.offer_buys.count + EXCLUDED.count",
        ).use { st -> st.bind(listOf(offerId, shard, uuid, count)); st.executeUpdate() }
        true
    }

    override fun releaseOffer(offerId: String, shard: String, uuid: String, count: Int) {
        update("UPDATE citybuild.offer_buys SET count = GREATEST(0, count - ?) WHERE offer_id = ? AND shard = ? AND uuid = ?", count, offerId, shard, uuid)
    }

    override fun offerSold(offerId: String, shard: String): Int =
        query("SELECT COALESCE(SUM(count), 0) FROM citybuild.offer_buys WHERE offer_id = ? AND shard = ?", offerId, shard) { it.getInt(1) }.single()

    override fun offerBought(offerId: String, shard: String, uuid: String): Int =
        query("SELECT COALESCE(SUM(count), 0) FROM citybuild.offer_buys WHERE offer_id = ? AND shard = ? AND uuid = ?", offerId, shard, uuid) { it.getInt(1) }.single()

    override fun issueSerial(record: SerialRecord): SerialRecord = query(
        "INSERT INTO citybuild.forge_serials (forge_id, version, issued_to, source, shard, issued_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING *",
        record.forgeId, record.version, record.issuedTo, record.source, record.shard, record.issuedAt,
    ) { it.serial() }.single()

    override fun serial(serial: Long): SerialRecord? = query("SELECT * FROM citybuild.forge_serials WHERE serial = ?", serial) { it.serial() }.firstOrNull()

    override fun log(entry: LogEntry) {
        update(
            "INSERT INTO citybuild.logs (kind, shard, actor, target, amount, ref, data, at) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
            entry.kind, entry.shard, entry.actor, entry.target, entry.amount, entry.ref, entry.data, entry.at,
        )
    }

    override fun logs(actor: String?, ref: String?, kind: String?, limit: Int): List<LogEntry> = query(
        "SELECT * FROM citybuild.logs WHERE (?::text IS NULL OR actor = ?::text OR target = ?::text) AND (?::text IS NULL OR ref = ?::text) " +
            "AND (?::text IS NULL OR kind = ?::text) ORDER BY id DESC LIMIT ?",
        actor, actor, actor, ref, ref, kind, kind, limit,
    ) { it.logEntry() }

    override fun putProfileReport(uuid: String, shard: String, json: String) {
        update(
            "INSERT INTO citybuild.profile_reports (uuid, shard, data) VALUES (?, ?, ?::jsonb) ON CONFLICT (uuid, shard) DO UPDATE SET data = EXCLUDED.data",
            uuid, shard, json,
        )
    }

    override fun profileReports(uuid: String): Map<String, String> =
        query("SELECT shard, data FROM citybuild.profile_reports WHERE uuid = ?", uuid) { it.getString(1) to it.getString(2) }.toMap()

    override fun deletePlayer(uuid: String): Boolean = transaction { c ->
        var deleted = 0
        listOf(
            "DELETE FROM citybuild.homes WHERE uuid = ?",
            "DELETE FROM citybuild.kit_claims WHERE uuid = ?",
            "DELETE FROM citybuild.pickup WHERE uuid = ?",
            "DELETE FROM citybuild.jobs WHERE uuid = ?",
            "DELETE FROM citybuild.rewards WHERE uuid = ?",
            "DELETE FROM citybuild.tutorial WHERE uuid = ?",
            "DELETE FROM citybuild.daily_counters WHERE uuid = ?",
            "DELETE FROM citybuild.profile_reports WHERE uuid = ?",
            "DELETE FROM citybuild.auctions WHERE seller = ? AND status = 'active'",
            "DELETE FROM citybuild.plot_market WHERE seller = ? AND status = 'active'",
        ).forEach { sql -> c.prepareStatement(sql).use { st -> st.setString(1, uuid); deleted += st.executeUpdate() } }
        deleted > 0
    }

    override fun wipeGameplay(dryRun: Boolean): Map<String, Int> = transaction { c ->
        val counts = linkedMapOf<String, Int>()
        GAMEPLAY_TABLES.forEach { table -> counts[table] = c.single("SELECT COUNT(*) FROM citybuild.$table").toInt() }
        if (!dryRun) {
            c.createStatement().use { st -> st.execute("TRUNCATE ${GAMEPLAY_TABLES.joinToString { "citybuild.$it" }}") }
        }
        counts
    }

    /** Closes the connection pool. */
    override fun close() = pool.close()

    private fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = pool.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            st.bind(args.toList())
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    private fun update(sql: String, vararg args: Any?): Int = pool.connection.use { c ->
        c.prepareStatement(sql).use { st -> st.bind(args.toList()); st.executeUpdate() }
    }

    private fun <T> transaction(block: (Connection) -> T): T = pool.connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    private fun Connection.single(sql: String, vararg args: Any?): Long = prepareStatement(sql).use { st ->
        st.bind(args.toList())
        st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }

    private fun PreparedStatement.bind(args: List<Any?>) {
        args.forEachIndexed { i, value ->
            val index = i + 1
            when (value) {
                null -> setNull(index, Types.NULL)
                is String -> setString(index, value)
                is Int -> setInt(index, value)
                is Long -> setLong(index, value)
                is Double -> setDouble(index, value)
                is Float -> setFloat(index, value)
                is Boolean -> setBoolean(index, value)
                is LongIds -> setArray(index, connection.createArrayOf("bigint", value.ids.toTypedArray()))
                is Set<*> -> setArray(index, connection.createArrayOf("text", value.map { it.toString() }.toTypedArray()))
                else -> setString(index, value.toString())
            }
        }
    }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun ResultSet.home() = Home(
        getString("uuid"), getString("shard"), getString("name"), getString("world"),
        getDouble("x"), getDouble("y"), getDouble("z"), getFloat("yaw"), getFloat("pitch"),
    )

    private fun ResultSet.warp() = Warp(
        getString("shard"), getString("name"), getString("icon"), getString("description"), getString("world"),
        getDouble("x"), getDouble("y"), getDouble("z"), getFloat("yaw"), getFloat("pitch"), getInt("sort_order"),
    )

    private fun ResultSet.auction() = Auction(
        getLong("id"), getString("shard"), getString("seller"), getString("seller_name"), getString("item"), getString("material"),
        getString("display_name"), getString("category"), getInt("amount"), getLong("price"), getLong("created_at"), getLong("expires_at"),
        getString("status"), getString("buyer"), longOrNull("closed_at"), getBoolean("seller_notified"),
    )

    private fun ResultSet.pickup() = Pickup(getLong("id"), getString("shard"), getString("uuid"), getString("item"), getString("source"), getLong("created_at"))

    private fun ResultSet.listing() = PlotListing(
        getLong("id"), getString("shard"), getString("plot_id"), getString("seller"), getString("seller_name"), getLong("price"),
        getString("world"), getInt("x"), getInt("z"), getInt("size"), getString("status"), getString("buyer"), getLong("created_at"), longOrNull("closed_at"),
    )

    private fun ResultSet.serial() = SerialRecord(
        getLong("serial"), getString("forge_id"), getInt("version"), getString("issued_to"), getString("source"), getString("shard"), getLong("issued_at"),
    )

    private fun ResultSet.logEntry() = LogEntry(
        getLong("id"), getString("kind"), getString("shard"), getString("actor"), getString("target"), getLong("amount"),
        getString("ref"), getString("data"), getLong("at"),
    )

    /**
     * Marker for a bigint array parameter.
     *
     * @property ids the values.
     */
    private class LongIds(val ids: List<Long>)

    private companion object {
        /** Tables emptied by the release reset; warps and the audit log are kept. */
        val GAMEPLAY_TABLES = listOf(
            "homes", "kit_claims", "auctions", "pickup", "plot_market", "jobs", "rewards", "tutorial", "daily_counters",
            "offer_buys", "forge_serials", "profile_reports",
        )

        val SCHEMA = listOf(
            "CREATE SCHEMA IF NOT EXISTS citybuild",
            "CREATE TABLE IF NOT EXISTS citybuild.homes (uuid TEXT NOT NULL, shard TEXT NOT NULL, name_key TEXT NOT NULL, name TEXT NOT NULL, world TEXT NOT NULL, " +
                "x DOUBLE PRECISION NOT NULL, y DOUBLE PRECISION NOT NULL, z DOUBLE PRECISION NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, PRIMARY KEY (uuid, shard, name_key))",
            "CREATE TABLE IF NOT EXISTS citybuild.warps (shard TEXT NOT NULL, name_key TEXT NOT NULL, name TEXT NOT NULL, icon TEXT NOT NULL, description TEXT NOT NULL, " +
                "world TEXT NOT NULL, x DOUBLE PRECISION NOT NULL, y DOUBLE PRECISION NOT NULL, z DOUBLE PRECISION NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, " +
                "sort_order INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (shard, name_key))",
            "CREATE TABLE IF NOT EXISTS citybuild.kit_claims (uuid TEXT NOT NULL, shard TEXT NOT NULL, kit TEXT NOT NULL, claimed_at BIGINT NOT NULL, PRIMARY KEY (uuid, shard, kit))",
            "CREATE TABLE IF NOT EXISTS citybuild.auctions (id BIGSERIAL PRIMARY KEY, shard TEXT NOT NULL, seller TEXT NOT NULL, seller_name TEXT NOT NULL, item TEXT NOT NULL, " +
                "material TEXT NOT NULL, display_name TEXT NOT NULL, category TEXT NOT NULL, amount INTEGER NOT NULL, price BIGINT NOT NULL, created_at BIGINT NOT NULL, " +
                "expires_at BIGINT NOT NULL, status TEXT NOT NULL, buyer TEXT, closed_at BIGINT, seller_notified BOOLEAN NOT NULL DEFAULT FALSE)",
            "CREATE INDEX IF NOT EXISTS auctions_shard_status_idx ON citybuild.auctions (shard, status)",
            "CREATE INDEX IF NOT EXISTS auctions_seller_idx ON citybuild.auctions (seller)",
            "CREATE TABLE IF NOT EXISTS citybuild.pickup (id BIGSERIAL PRIMARY KEY, shard TEXT NOT NULL, uuid TEXT NOT NULL, item TEXT NOT NULL, source TEXT NOT NULL, created_at BIGINT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS pickup_owner_idx ON citybuild.pickup (uuid, shard)",
            "CREATE TABLE IF NOT EXISTS citybuild.plot_market (id BIGSERIAL PRIMARY KEY, shard TEXT NOT NULL, plot_id TEXT NOT NULL, seller TEXT NOT NULL, seller_name TEXT NOT NULL, " +
                "price BIGINT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL, size INTEGER NOT NULL, status TEXT NOT NULL, buyer TEXT, " +
                "created_at BIGINT NOT NULL, closed_at BIGINT)",
            "CREATE INDEX IF NOT EXISTS plot_market_shard_status_idx ON citybuild.plot_market (shard, status)",
            "CREATE TABLE IF NOT EXISTS citybuild.jobs (uuid TEXT PRIMARY KEY, profile JSONB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS citybuild.rewards (uuid TEXT PRIMARY KEY, streak INTEGER NOT NULL, last_day TEXT)",
            "CREATE TABLE IF NOT EXISTS citybuild.tutorial (uuid TEXT PRIMARY KEY, done_at BIGINT, bonus_paid_at BIGINT)",
            "CREATE TABLE IF NOT EXISTS citybuild.daily_counters (uuid TEXT NOT NULL, day TEXT NOT NULL, key TEXT NOT NULL, value BIGINT NOT NULL, PRIMARY KEY (uuid, day, key))",
            "CREATE TABLE IF NOT EXISTS citybuild.offer_buys (offer_id TEXT NOT NULL, shard TEXT NOT NULL, uuid TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (offer_id, shard, uuid))",
            "CREATE TABLE IF NOT EXISTS citybuild.forge_serials (serial BIGSERIAL PRIMARY KEY, forge_id TEXT NOT NULL, version INTEGER NOT NULL, issued_to TEXT NOT NULL, " +
                "source TEXT NOT NULL, shard TEXT NOT NULL, issued_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS citybuild.logs (id BIGSERIAL PRIMARY KEY, kind TEXT NOT NULL, shard TEXT NOT NULL, actor TEXT NOT NULL, target TEXT NOT NULL, " +
                "amount BIGINT NOT NULL, ref TEXT NOT NULL, data JSONB NOT NULL, at BIGINT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS logs_actor_idx ON citybuild.logs (actor)",
            "CREATE INDEX IF NOT EXISTS logs_target_idx ON citybuild.logs (target)",
            "CREATE INDEX IF NOT EXISTS logs_ref_idx ON citybuild.logs (ref)",
            "CREATE TABLE IF NOT EXISTS citybuild.profile_reports (uuid TEXT NOT NULL, shard TEXT NOT NULL, data JSONB NOT NULL, PRIMARY KEY (uuid, shard))",
        )
    }
}
