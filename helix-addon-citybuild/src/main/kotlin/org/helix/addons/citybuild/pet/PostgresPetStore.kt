package org.helix.addons.citybuild.pet

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.helix.api.addon.StorageConnection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * [PetStore] on the node's PostgreSQL database, table `citybuild.pets`. Uses its own small pool so the pet feature
 * stays independent of the main repository.
 *
 * @param connection the node's storage connection settings.
 */
class PostgresPetStore(connection: StorageConnection) : PetStore {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = connection.url
            username = connection.user
            password = connection.password
            maximumPoolSize = 2
            poolName = "citybuild-pets"
        },
    )

    init {
        pool.connection.use { c -> c.createStatement().use { st -> SCHEMA.forEach { st.executeUpdate(it) } } }
    }

    override fun list(uuid: String, shard: String): List<Pet> =
        query("SELECT * FROM citybuild.pets WHERE uuid = ? AND shard = ? ORDER BY created_at, id", uuid, shard)

    override fun get(id: String): Pet? = query("SELECT * FROM citybuild.pets WHERE id = ?", id).firstOrNull()

    override fun put(pet: Pet) {
        update(
            "INSERT INTO citybuild.pets (id, uuid, shard, type, name, variant, mood, last_fed, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, " +
                "variant = EXCLUDED.variant, mood = EXCLUDED.mood, last_fed = EXCLUDED.last_fed, updated_at = EXCLUDED.updated_at",
            pet.id, pet.uuid, pet.shard, pet.type, pet.name, pet.variant, pet.mood, pet.lastFed, pet.createdAt, pet.updatedAt,
        )
    }

    override fun delete(id: String): Boolean = update("DELETE FROM citybuild.pets WHERE id = ?", id) > 0

    override fun wipe(dryRun: Boolean): Int = pool.connection.use { c ->
        val count = c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM citybuild.pets").use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
        if (!dryRun) c.createStatement().use { st -> st.executeUpdate("DELETE FROM citybuild.pets") }
        count
    }

    override fun close() = pool.close()

    private fun query(sql: String, vararg args: Any?): List<Pet> = pool.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            bind(st, args.toList())
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.pet()) } }
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
                else -> st.setObject(i + 1, v)
            }
        }
    }

    private fun ResultSet.pet(): Pet = Pet(
        id = getString("id"),
        uuid = getString("uuid"),
        shard = getString("shard"),
        type = getString("type"),
        name = getString("name"),
        variant = getString("variant"),
        mood = getInt("mood"),
        lastFed = getLong("last_fed"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )

    private companion object {
        val SCHEMA = listOf(
            "CREATE SCHEMA IF NOT EXISTS citybuild",
            "CREATE TABLE IF NOT EXISTS citybuild.pets (id TEXT PRIMARY KEY, uuid TEXT NOT NULL, shard TEXT NOT NULL, " +
                "type TEXT NOT NULL, name TEXT, variant TEXT, mood INT NOT NULL DEFAULT 80, last_fed BIGINT NOT NULL DEFAULT 0, " +
                "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS pets_owner ON citybuild.pets (uuid, shard)",
        )
    }
}
