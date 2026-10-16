package org.helix.addons.citybuild.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.helix.api.addon.StorageConnection

/**
 * [RailStore] on the node's PostgreSQL database, table `citybuild.rail_discovered`. A discovery is a single
 * `INSERT … ON CONFLICT DO NOTHING`, so concurrent calls never duplicate rows. Uses its own small pool; the
 * driver and HikariCP come from the node classloader.
 *
 * @param connection the node's storage connection settings.
 */
class PostgresRailStore(connection: StorageConnection) : RailStore {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = connection.url
            username = connection.user
            password = connection.password
            maximumPoolSize = 2
            poolName = "citybuild-rail"
        },
    )

    init {
        pool.connection.use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE SCHEMA IF NOT EXISTS citybuild")
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS citybuild.rail_discovered (uuid TEXT NOT NULL, shard TEXT NOT NULL, station TEXT NOT NULL, " +
                        "discovered_at BIGINT NOT NULL, PRIMARY KEY (uuid, shard, station))",
                )
            }
        }
    }

    override fun discovered(uuid: String, shard: String): List<String> = pool.connection.use { c ->
        c.prepareStatement("SELECT station FROM citybuild.rail_discovered WHERE uuid = ? AND shard = ? ORDER BY station").use { st ->
            st.setString(1, uuid)
            st.setString(2, shard)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    override fun discover(uuid: String, shard: String, station: String, at: Long): Boolean = pool.connection.use { c ->
        c.prepareStatement(
            "INSERT INTO citybuild.rail_discovered (uuid, shard, station, discovered_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { st ->
            st.setString(1, uuid)
            st.setString(2, shard)
            st.setString(3, station)
            st.setLong(4, at)
            st.executeUpdate() > 0
        }
    }

    override fun forget(uuid: String): Boolean = pool.connection.use { c ->
        c.prepareStatement("DELETE FROM citybuild.rail_discovered WHERE uuid = ?").use { st ->
            st.setString(1, uuid)
            st.executeUpdate() > 0
        }
    }

    override fun export(uuid: String): Map<String, List<String>> = pool.connection.use { c ->
        c.prepareStatement("SELECT shard, station FROM citybuild.rail_discovered WHERE uuid = ? ORDER BY shard, station").use { st ->
            st.setString(1, uuid)
            st.executeQuery().use { rs ->
                val out = linkedMapOf<String, MutableList<String>>()
                while (rs.next()) out.getOrPut(rs.getString(1)) { mutableListOf() }.add(rs.getString(2))
                out
            }
        }
    }

    override fun close() = pool.close()
}
