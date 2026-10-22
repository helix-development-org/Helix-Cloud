package org.helix.addons.citybuild.vehicle

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.helix.api.addon.StorageConnection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * [VehicleStore] on the node's PostgreSQL database, table `citybuild.vehicles`. Uses its own small pool so the
 * vehicle feature stays independent of the main repository.
 *
 * @param connection the node's storage connection settings.
 */
class PostgresVehicleStore(connection: StorageConnection) : VehicleStore {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = connection.url
            username = connection.user
            password = connection.password
            maximumPoolSize = 2
            poolName = "citybuild-vehicles"
        },
    )

    init {
        pool.connection.use { c -> c.createStatement().use { st -> SCHEMA.forEach { st.executeUpdate(it) } } }
    }

    override fun list(uuid: String, shard: String): List<Vehicle> =
        query("SELECT * FROM citybuild.vehicles WHERE uuid = ? AND shard = ? ORDER BY created_at, id", uuid, shard)

    override fun get(id: String): Vehicle? = query("SELECT * FROM citybuild.vehicles WHERE id = ?", id).firstOrNull()

    override fun put(vehicle: Vehicle) {
        update(
            "INSERT INTO citybuild.vehicles (id, uuid, shard, model, color, locked, access, state, world, x, y, z, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET model = EXCLUDED.model, color = EXCLUDED.color, " +
                "locked = EXCLUDED.locked, access = EXCLUDED.access, state = EXCLUDED.state, world = EXCLUDED.world, x = EXCLUDED.x, " +
                "y = EXCLUDED.y, z = EXCLUDED.z, updated_at = EXCLUDED.updated_at",
            vehicle.id, vehicle.uuid, vehicle.shard, vehicle.model, vehicle.color, vehicle.locked, vehicle.access, vehicle.state,
            vehicle.world, vehicle.x, vehicle.y, vehicle.z, vehicle.createdAt, vehicle.updatedAt,
        )
    }

    override fun delete(id: String): Boolean = update("DELETE FROM citybuild.vehicles WHERE id = ?", id) > 0

    override fun recallAll(shard: String, at: Long): Int =
        update(
            "UPDATE citybuild.vehicles SET state = ?, world = NULL, x = NULL, y = NULL, z = NULL, updated_at = ? WHERE shard = ? AND state = ?",
            VehicleStates.GARAGE, at, shard, VehicleStates.WORLD,
        )

    override fun wipe(dryRun: Boolean): Int = pool.connection.use { c ->
        val count = c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM citybuild.vehicles").use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
        if (!dryRun) c.createStatement().use { st -> st.executeUpdate("DELETE FROM citybuild.vehicles") }
        count
    }

    override fun close() = pool.close()

    private fun query(sql: String, vararg args: Any?): List<Vehicle> = pool.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            bind(st, args.toList())
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.vehicle()) } }
        }
    }

    private fun update(sql: String, vararg args: Any?): Int = pool.connection.use { c ->
        c.prepareStatement(sql).use { st -> bind(st, args.toList()); st.executeUpdate() }
    }

    private fun bind(st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, v ->
            when (v) {
                null -> st.setNull(i + 1, Types.NULL)
                is String -> st.setString(i + 1, v)
                is Int -> st.setInt(i + 1, v)
                is Long -> st.setLong(i + 1, v)
                is Double -> st.setDouble(i + 1, v)
                is Boolean -> st.setBoolean(i + 1, v)
                else -> st.setObject(i + 1, v)
            }
        }
    }

    private fun ResultSet.vehicle(): Vehicle = Vehicle(
        id = getString("id"),
        uuid = getString("uuid"),
        shard = getString("shard"),
        model = getString("model"),
        color = getInt("color"),
        locked = getBoolean("locked"),
        access = getString("access"),
        state = getString("state"),
        world = getString("world"),
        x = getDouble("x").takeUnless { wasNull() },
        y = getDouble("y").takeUnless { wasNull() },
        z = getDouble("z").takeUnless { wasNull() },
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )

    private companion object {
        val SCHEMA = listOf(
            "CREATE SCHEMA IF NOT EXISTS citybuild",
            "CREATE TABLE IF NOT EXISTS citybuild.vehicles (id TEXT PRIMARY KEY, uuid TEXT NOT NULL, shard TEXT NOT NULL, " +
                "model TEXT NOT NULL, color INT NOT NULL DEFAULT 0, locked BOOLEAN NOT NULL DEFAULT FALSE, access TEXT NOT NULL, " +
                "state TEXT NOT NULL, world TEXT, x DOUBLE PRECISION, y DOUBLE PRECISION, z DOUBLE PRECISION, " +
                "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS vehicles_owner ON citybuild.vehicles (uuid, shard)",
        )
    }
}
