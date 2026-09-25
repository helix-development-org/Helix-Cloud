package org.helix.addons.mineclash.profile

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import org.helix.api.addon.StorageConnection
import java.sql.Timestamp

/**
 * Profile repository on the node's central PostgreSQL database (schema `mineclash`). Profiles are
 * stored as a JSONB document plus indexed ranking columns; match history is one row per match.
 * The driver and HikariCP come from the node classloader.
 *
 * @param connection the node's storage connection settings.
 */
class PostgresProfileRepository(connection: StorageConnection) : ProfileRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = connection.url
            username = connection.user
            password = connection.password
            maximumPoolSize = connection.poolSize.coerceIn(1, 8)
            poolName = "mineclash-profiles"
        },
    )

    init {
        pool.connection.use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE SCHEMA IF NOT EXISTS mineclash")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS mineclash.profiles (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL DEFAULT '',
                        wins INTEGER NOT NULL DEFAULT 0,
                        losses INTEGER NOT NULL DEFAULT 0,
                        roses BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMPTZ NOT NULL,
                        data JSONB NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS profiles_roses_idx ON mineclash.profiles (roses DESC)")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS mineclash.match_history (
                        id BIGSERIAL PRIMARY KEY,
                        mode TEXT NOT NULL,
                        winner_team INTEGER NOT NULL,
                        players JSONB NOT NULL,
                        roses_delta JSONB NOT NULL,
                        started_at TIMESTAMPTZ NOT NULL,
                        ended_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    override fun load(uuid: String): PlayerProfile? =
        pool.connection.use { c ->
            c.prepareStatement("SELECT data FROM mineclash.profiles WHERE uuid = ?").use { st ->
                st.setString(1, uuid)
                st.executeQuery().use { rs -> if (rs.next()) json.decodeFromString<PlayerProfile>(rs.getString(1)) else null }
            }
        }

    override fun save(profile: PlayerProfile) {
        pool.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO mineclash.profiles (uuid, name, wins, losses, roses, updated_at, data) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) " +
                    "ON CONFLICT (uuid) DO UPDATE SET name = EXCLUDED.name, wins = EXCLUDED.wins, losses = EXCLUDED.losses, " +
                    "roses = EXCLUDED.roses, updated_at = EXCLUDED.updated_at, data = EXCLUDED.data",
            ).use { st ->
                st.setString(1, profile.uuid)
                st.setString(2, profile.name)
                st.setInt(3, profile.wins)
                st.setInt(4, profile.losses)
                st.setLong(5, profile.roses)
                st.setTimestamp(6, Timestamp(if (profile.updatedAt > 0) profile.updatedAt else System.currentTimeMillis()))
                st.setString(7, json.encodeToString(profile))
                st.executeUpdate()
            }
        }
    }

    override fun recordMatch(record: MatchRecord) {
        pool.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO mineclash.match_history (mode, winner_team, players, roses_delta, started_at, ended_at) VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)",
            ).use { st ->
                st.setString(1, record.mode)
                st.setInt(2, record.winnerTeam)
                st.setString(3, json.encodeToString(record.players))
                st.setString(4, json.encodeToString(record.rosesDelta))
                st.setTimestamp(5, Timestamp(if (record.startedAt > 0) record.startedAt else System.currentTimeMillis()))
                st.setTimestamp(6, Timestamp(if (record.endedAt > 0) record.endedAt else System.currentTimeMillis()))
                st.executeUpdate()
            }
        }
    }

    override fun topByRoses(limit: Int): List<Triple<String, String, Long>> =
        pool.connection.use { c ->
            c.prepareStatement("SELECT uuid, name, roses FROM mineclash.profiles ORDER BY roses DESC LIMIT ?").use { st ->
                st.setInt(1, limit)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getLong(3))) }
                }
            }
        }

    override fun close() = pool.close()
}
