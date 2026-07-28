package de.tytoss.igui.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.tytoss.igui.texture.GuiTextureDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.key.Key
import org.postgresql.PGConnection
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [GuiTextureDatabase] backed by PostgreSQL: textures are stored in a
 * `<schema>.igui_textures` table (created on first use) behind a HikariCP
 * connection pool, and [changes] is served by a dedicated `LISTEN`
 * connection subscribed to `pg_notify` events fired by a trigger this class
 * installs alongside the table. This is what enables live texture edits
 * (e.g. from a dashboard) to reach every connected node without a restart.
 *
 * The schema name is baked into the notification channel names (as a
 * SHA-256-derived suffix) so multiple schemas on the same database do not
 * cross-notify each other.
 *
 * @property jdbcUrl PostgreSQL JDBC URL; must start with `jdbc:postgresql:`.
 * @param username database username.
 * @param password database password.
 * @property schema schema the texture table and trigger function live in; must be a valid
 *  SQL identifier.
 * @property poolConfiguration HikariCP pool tuning, see [PostgreSQLPoolConfiguration].
 */
class PostgreSQLGuiTextureDatabase(
    val jdbcUrl: String,
    private val username: String,
    private val password: String,
    val schema: String = "public",
    val poolConfiguration: PostgreSQLPoolConfiguration = PostgreSQLPoolConfiguration(),
) : GuiTextureDatabase {
    private val table: String
    private val upsertChannel: String
    private val deleteChannel: String
    private val dataSource: HikariDataSource
    private val dispatcher = Dispatchers.IO.limitedParallelism(poolConfiguration.maximumPoolSize)
    private val closed = AtomicBoolean()
    private val listenerConnection = AtomicReference<Connection?>()

    init {
        require(jdbcUrl.startsWith("jdbc:postgresql:")) { "A PostgreSQL JDBC URL is required" }
        require(SCHEMA_PATTERN.matches(schema)) { "Invalid PostgreSQL schema '$schema'" }
        require(poolConfiguration.maximumPoolSize >= 1) { "Pool size must be at least one" }
        table = "\"$schema\".igui_textures"
        val channelSuffix = MessageDigest.getInstance("SHA-256")
            .digest(schema.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        upsertChannel = "igui_${channelSuffix}_upsert"
        deleteChannel = "igui_${channelSuffix}_delete"
        val config = HikariConfig().apply {
            this.jdbcUrl = this@PostgreSQLGuiTextureDatabase.jdbcUrl
            this.username = this@PostgreSQLGuiTextureDatabase.username
            this.password = this@PostgreSQLGuiTextureDatabase.password
            maximumPoolSize = poolConfiguration.maximumPoolSize
            minimumIdle = poolConfiguration.maximumPoolSize
            connectionTimeout = poolConfiguration.connectionTimeoutMillis
            validationTimeout = poolConfiguration.validationTimeoutMillis
            keepaliveTime = poolConfiguration.keepaliveTimeMillis
            maxLifetime = poolConfiguration.maxLifetimeMillis
            poolName = "IGui-PostgreSQL"
            addDataSourceProperty("tcpKeepAlive", "true")
            addDataSourceProperty("ApplicationName", "IGui")
        }
        dataSource = HikariDataSource(config)
        try {
            initializeSchema()
        } catch (exception: Exception) {
            dataSource.close()
            throw exception
        }
    }

    override suspend fun textures(): List<GuiTextureDefinition> = withContext(dispatcher) {
        connection { connection ->
            connection.prepareStatement(
                """
                SELECT id, character, font, width_pixels, height_pixels, advance_pixels, client_animated
                FROM $table
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.texture())
                    }
                }
            }
        }
    }

    override suspend fun texture(id: String): GuiTextureDefinition? = withContext(dispatcher) {
        connection { connection ->
            connection.prepareStatement(
                """
                SELECT id, character, font, width_pixels, height_pixels, advance_pixels, client_animated
                FROM $table
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { result -> if (result.next()) result.texture() else null }
            }
        }
    }

    override suspend fun put(texture: GuiTextureDefinition): Unit = withContext(dispatcher) {
        connection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO $table (
                    id, character, font, width_pixels, height_pixels, advance_pixels, client_animated
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    character = excluded.character,
                    font = excluded.font,
                    width_pixels = excluded.width_pixels,
                    height_pixels = excluded.height_pixels,
                    advance_pixels = excluded.advance_pixels,
                    client_animated = excluded.client_animated
                """.trimIndent(),
            ).use { statement ->
                statement.bind(texture)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun put(textures: Iterable<GuiTextureDefinition>): Unit = withContext(dispatcher) {
        connection { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO $table (
                        id, character, font, width_pixels, height_pixels, advance_pixels, client_animated
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        character = excluded.character,
                        font = excluded.font,
                        width_pixels = excluded.width_pixels,
                        height_pixels = excluded.height_pixels,
                        advance_pixels = excluded.advance_pixels,
                        client_animated = excluded.client_animated
                    """.trimIndent(),
                ).use { statement ->
                    textures.forEach { texture ->
                        statement.bind(texture)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun remove(id: String): Boolean = withContext(dispatcher) {
        connection { connection ->
            connection.prepareStatement("DELETE FROM $table WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate() != 0
            }
        }
    }

    override fun changes(): Flow<GuiTextureChange> = callbackFlow {
        checkOpen()
        val running = AtomicBoolean(true)
        val worker = launch(Dispatchers.IO) {
            listenLoop(running) { change -> trySend(change) }
        }
        awaitClose {
            running.set(false)
            listenerConnection.getAndSet(null)?.runCatching { close() }
            worker.cancel()
        }
    }

    override fun poolMetrics(): GuiTexturePoolMetrics {
        val pool = dataSource.hikariPoolMXBean
        return GuiTexturePoolMetrics(pool.activeConnections, pool.idleConnections, pool.threadsAwaitingConnection)
    }

    override suspend fun close(): Unit = withContext(dispatcher) {
        if (!closed.compareAndSet(false, true)) return@withContext
        listenerConnection.getAndSet(null)?.close()
        dataSource.close()
    }

    private fun initializeSchema(): Unit = connection { connection ->
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS $table (
                    id TEXT PRIMARY KEY,
                    character TEXT NOT NULL,
                    font TEXT NOT NULL,
                    width_pixels INTEGER NOT NULL,
                    height_pixels INTEGER NOT NULL,
                    advance_pixels INTEGER NOT NULL,
                    client_animated BOOLEAN NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE OR REPLACE FUNCTION "$schema".igui_notify_texture_change()
                RETURNS trigger AS ${'$'}igui${'$'}
                BEGIN
                    IF TG_OP = 'DELETE' THEN
                        PERFORM pg_notify('$deleteChannel', OLD.id);
                        RETURN OLD;
                    END IF;
                    PERFORM pg_notify('$upsertChannel', NEW.id);
                    RETURN NEW;
                END;
                ${'$'}igui${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            statement.executeUpdate("DROP TRIGGER IF EXISTS igui_texture_change ON $table")
            statement.executeUpdate(
                """
                CREATE TRIGGER igui_texture_change
                AFTER INSERT OR UPDATE OR DELETE ON $table
                FOR EACH ROW EXECUTE FUNCTION "$schema".igui_notify_texture_change()
                """.trimIndent(),
            )
        }
    }

    private suspend fun listenLoop(running: AtomicBoolean, listener: (GuiTextureChange) -> Unit) {
        var retryMillis = 250L
        while (running.get() && !closed.get()) {
            try {
                val connection = DriverManager.getConnection(jdbcUrl, username, password)
                listenerConnection.set(connection)
                connection.createStatement().use { statement ->
                    statement.execute("LISTEN $upsertChannel")
                    statement.execute("LISTEN $deleteChannel")
                }
                val postgres = connection.unwrap(PGConnection::class.java)
                retryMillis = 250L
                while (running.get() && !closed.get()) {
                    postgres.getNotifications(0)?.forEach { notification ->
                        val type = if (notification.name == deleteChannel) {
                            GuiTextureChangeType.DELETE
                        } else {
                            GuiTextureChangeType.UPSERT
                        }
                        listener(GuiTextureChange(type, notification.parameter))
                    }
                }
            } catch (exception: Exception) {
                if (!running.get() || closed.get()) return
                delay(retryMillis)
                retryMillis = minOf(30_000L, retryMillis * 2)
            } finally {
                listenerConnection.getAndSet(null)?.runCatching { close() }
            }
        }
    }

    private fun <T> connection(block: (Connection) -> T): T {
        checkOpen()
        return dataSource.connection.use(block)
    }

    private fun PreparedStatement.bind(texture: GuiTextureDefinition) {
        setString(1, texture.id)
        setString(2, texture.character)
        setString(3, texture.font.asString())
        setInt(4, texture.widthPixels)
        setInt(5, texture.heightPixels)
        setInt(6, texture.advancePixels)
        setBoolean(7, texture.clientAnimated)
    }

    private fun ResultSet.texture(): GuiTextureDefinition = GuiTextureDefinition(
        id = getString("id"),
        character = getString("character"),
        font = Key.key(getString("font")),
        widthPixels = getInt("width_pixels"),
        heightPixels = getInt("height_pixels"),
        advancePixels = getInt("advance_pixels"),
        clientAnimated = getBoolean("client_animated"),
    )

    private fun checkOpen() {
        check(!closed.get()) { "Texture database is closed" }
    }

    private companion object {
        val SCHEMA_PATTERN: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
