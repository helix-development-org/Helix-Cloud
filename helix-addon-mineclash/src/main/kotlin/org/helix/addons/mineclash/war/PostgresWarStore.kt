package org.helix.addons.mineclash.war

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import org.helix.api.addon.StorageConnection

/**
 * [WarStore] on the node's PostgreSQL database: table `mineclash.wars` (contract §4) with the whole record as
 * JSONB `body` plus indexed columns for state, clans and service. Driver and HikariCP come from the node
 * classloader.
 *
 * @param connection the node's storage connection settings.
 */
class PostgresWarStore(connection: StorageConnection) : WarStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = connection.url
            username = connection.user
            password = connection.password
            maximumPoolSize = connection.poolSize.coerceIn(1, 2)
            poolName = "mineclash-wars"
        },
    )

    init {
        pool.connection.use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE SCHEMA IF NOT EXISTS mineclash")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS mineclash.wars (
                        war_id TEXT PRIMARY KEY,
                        state TEXT NOT NULL,
                        clan_a TEXT NOT NULL,
                        clan_b TEXT NOT NULL,
                        service_id TEXT,
                        body JSONB NOT NULL,
                        created_at BIGINT NOT NULL,
                        ended_at BIGINT
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS wars_active_idx ON mineclash.wars (state) " +
                        "WHERE state IN ('QUEUED', 'ASSIGNED', 'PREPARE', 'RUNNING')",
                )
            }
        }
    }

    override fun loadActive(): List<WarRecord> =
        pool.connection.use { c ->
            c.prepareStatement(
                "SELECT body FROM mineclash.wars WHERE state IN ('QUEUED', 'ASSIGNED', 'PREPARE', 'RUNNING') ORDER BY created_at",
            ).use { st ->
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) decode(rs.getString(1))?.let(::add) }.sortedBy { it.acceptedAt }
                }
            }
        }

    override fun load(warId: String): WarRecord? =
        pool.connection.use { c ->
            c.prepareStatement("SELECT body FROM mineclash.wars WHERE war_id = ?").use { st ->
                st.setString(1, warId)
                st.executeQuery().use { rs -> if (rs.next()) decode(rs.getString(1)) else null }
            }
        }

    override fun save(record: WarRecord) {
        pool.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO mineclash.wars (war_id, state, clan_a, clan_b, service_id, body, created_at, ended_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT (war_id) DO UPDATE SET state = EXCLUDED.state, " +
                    "service_id = EXCLUDED.service_id, body = EXCLUDED.body, ended_at = EXCLUDED.ended_at",
            ).use { st ->
                st.setString(1, record.warId)
                st.setString(2, record.state)
                st.setString(3, record.teams.getOrNull(0)?.clanId.orEmpty())
                st.setString(4, record.teams.getOrNull(1)?.clanId.orEmpty())
                st.setString(5, record.serviceId)
                st.setString(6, json.encodeToString(WarRecord.serializer(), record))
                st.setLong(7, record.createdAt)
                if (record.endedAt != null) st.setLong(8, record.endedAt) else st.setNull(8, java.sql.Types.BIGINT)
                st.executeUpdate()
            }
        }
    }

    override fun nextId(day: String): String =
        pool.connection.use { c ->
            c.prepareStatement("SELECT war_id FROM mineclash.wars WHERE war_id LIKE ? ORDER BY war_id DESC LIMIT 1").use { st ->
                st.setString(1, "w-$day-%")
                st.executeQuery().use { rs ->
                    val last = if (rs.next()) WarStore.sequenceOf(day, rs.getString(1)) else 0
                    WarStore.id(day, last + 1)
                }
            }
        }

    override fun wipe(dryRun: Boolean): Map<String, Int> =
        pool.connection.use { c ->
            val count = c.prepareStatement("SELECT COUNT(*) FROM mineclash.wars").use { st ->
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            if (!dryRun) c.createStatement().use { it.executeUpdate("DELETE FROM mineclash.wars") }
            linkedMapOf("wars" to count)
        }

    override fun close() {
        pool.close()
    }

    private fun decode(raw: String): WarRecord? = runCatching { json.decodeFromString(WarRecord.serializer(), raw) }.getOrNull()
}
