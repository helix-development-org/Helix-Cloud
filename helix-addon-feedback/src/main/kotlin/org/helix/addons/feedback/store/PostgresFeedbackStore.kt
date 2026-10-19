package org.helix.addons.feedback.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.helix.addons.feedback.Agreement
import org.helix.addons.feedback.Attachment
import org.helix.addons.feedback.Comment
import org.helix.addons.feedback.FeedbackSettings
import org.helix.addons.feedback.Notification
import org.helix.addons.feedback.PendingReward
import org.helix.addons.feedback.Points
import org.helix.addons.feedback.Report
import org.helix.addons.feedback.ReportEvent
import org.helix.api.addon.StorageConnection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * [FeedbackStore] on the node's PostgreSQL database, schema `feedback`. Every table keeps the columns needed
 * for lookups plus the full record as JSONB (`data`); attachments are stored as BYTEA. Report numbers come
 * from the sequence `feedback.report_nr`.
 *
 * @param connection the node's storage connection settings.
 */
class PostgresFeedbackStore(connection: StorageConnection) : FeedbackStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = connection.url
            username = connection.user
            password = connection.password
            maximumPoolSize = 2
            poolName = "helix-feedback"
        },
    )

    init {
        pool.connection.use { c -> c.createStatement().use { st -> SCHEMA.forEach { st.executeUpdate(it) } } }
    }

    override fun nextNumber(): Long = pool.connection.use { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT nextval('feedback.report_nr')").use { rs -> rs.next(); rs.getLong(1) } }
    }

    override fun putReport(report: Report) {
        update(
            "INSERT INTO feedback.reports (nr, status, severity, category, reporter_uuid, hash, created_at, updated_at, data) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (nr) DO UPDATE SET status = EXCLUDED.status, " +
                "severity = EXCLUDED.severity, category = EXCLUDED.category, reporter_uuid = EXCLUDED.reporter_uuid, " +
                "hash = EXCLUDED.hash, updated_at = EXCLUDED.updated_at, data = EXCLUDED.data",
            report.nr, report.status, report.severity, report.category, report.reporter?.uuid, report.hash,
            report.createdAt, report.updatedAt, encode(Report.serializer(), report),
        )
    }

    override fun report(nr: Long): Report? = queryData("SELECT data FROM feedback.reports WHERE nr = ?", Report.serializer(), nr).firstOrNull()

    override fun reports(): List<Report> = queryData("SELECT data FROM feedback.reports ORDER BY nr", Report.serializer())

    override fun addComment(comment: Comment) {
        update(
            "INSERT INTO feedback.comments (id, nr, at, data) VALUES (?, ?, ?, ?::jsonb)",
            comment.id, comment.nr, comment.at, encode(Comment.serializer(), comment),
        )
    }

    override fun comments(nr: Long): List<Comment> =
        queryData("SELECT data FROM feedback.comments WHERE nr = ? ORDER BY at, id", Comment.serializer(), nr)

    override fun addEvent(event: ReportEvent) {
        update(
            "INSERT INTO feedback.events (nr, at, data) VALUES (?, ?, ?::jsonb)",
            event.nr, event.at, encode(ReportEvent.serializer(), event),
        )
    }

    override fun events(nr: Long): List<ReportEvent> =
        queryData("SELECT data FROM feedback.events WHERE nr = ? ORDER BY at, id", ReportEvent.serializer(), nr)

    override fun putAttachment(nr: Long, attachment: Attachment, bytes: ByteArray) {
        update(
            "INSERT INTO feedback.attachments (id, nr, name, mime, size, at, bytes) VALUES (?, ?, ?, ?, ?, ?, ?)",
            attachment.id, nr, attachment.name, attachment.mime, attachment.size, attachment.at, bytes,
        )
    }

    override fun attachment(id: String): Pair<String, ByteArray>? = pool.connection.use { c ->
        c.prepareStatement("SELECT mime, bytes FROM feedback.attachments WHERE id = ?").use { st ->
            bind(st, listOf(id))
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getBytes(2) else null }
        }
    }

    override fun agreement(uuid: String): Agreement? =
        queryData("SELECT data FROM feedback.agreements WHERE uuid = ?", Agreement.serializer(), uuid).firstOrNull()

    override fun putAgreement(agreement: Agreement) {
        update(
            "INSERT INTO feedback.agreements (uuid, data) VALUES (?, ?::jsonb) ON CONFLICT (uuid) DO UPDATE SET data = EXCLUDED.data",
            agreement.uuid, encode(Agreement.serializer(), agreement),
        )
    }

    override fun points(uuid: String): Points? =
        queryData("SELECT data FROM feedback.points WHERE uuid = ?", Points.serializer(), uuid).firstOrNull()

    override fun putPoints(points: Points) {
        update(
            "INSERT INTO feedback.points (uuid, total, data) VALUES (?, ?, ?::jsonb) " +
                "ON CONFLICT (uuid) DO UPDATE SET total = EXCLUDED.total, data = EXCLUDED.data",
            points.uuid, points.total, encode(Points.serializer(), points),
        )
    }

    override fun allPoints(): List<Points> = queryData("SELECT data FROM feedback.points", Points.serializer())

    override fun addNotification(notification: Notification) {
        update(
            "INSERT INTO feedback.notifications (id, uuid, at, data) VALUES (?, ?, ?, ?::jsonb)",
            notification.id, notification.uuid, notification.at, encode(Notification.serializer(), notification),
        )
    }

    override fun notifications(uuid: String): List<Notification> =
        queryData("SELECT data FROM feedback.notifications WHERE uuid = ? ORDER BY at, id", Notification.serializer(), uuid)

    override fun ackNotifications(uuid: String, ids: Set<String>): Int =
        if (ids.isEmpty()) {
            update("DELETE FROM feedback.notifications WHERE uuid = ?", uuid)
        } else {
            ids.sumOf { update("DELETE FROM feedback.notifications WHERE uuid = ? AND id = ?", uuid, it) }
        }

    override fun putReward(reward: PendingReward) {
        update(
            "INSERT INTO feedback.pending_rewards (id, uuid, data) VALUES (?, ?, ?::jsonb) ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data",
            reward.id, reward.uuid, encode(PendingReward.serializer(), reward),
        )
    }

    override fun rewards(uuid: String): List<PendingReward> =
        queryData("SELECT data FROM feedback.pending_rewards WHERE uuid = ? ORDER BY id", PendingReward.serializer(), uuid)

    override fun settings(): FeedbackSettings? =
        queryData("SELECT data FROM feedback.settings WHERE id = 1", FeedbackSettings.serializer()).firstOrNull()

    override fun putSettings(settings: FeedbackSettings) {
        update(
            "INSERT INTO feedback.settings (id, data) VALUES (1, ?::jsonb) ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data",
            encode(FeedbackSettings.serializer(), settings),
        )
    }

    override fun close() = pool.close()

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private fun <T> queryData(sql: String, serializer: KSerializer<T>, vararg args: Any?): List<T> = pool.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            bind(st, args.toList())
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(json.decodeFromString(serializer, rs.data())) } }
        }
    }

    private fun ResultSet.data(): String = getString(1)

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
                is Boolean -> st.setBoolean(i + 1, v)
                is ByteArray -> st.setBytes(i + 1, v)
                else -> st.setObject(i + 1, v)
            }
        }
    }

    private companion object {
        val SCHEMA = listOf(
            "CREATE SCHEMA IF NOT EXISTS feedback",
            "CREATE SEQUENCE IF NOT EXISTS feedback.report_nr",
            "CREATE TABLE IF NOT EXISTS feedback.reports (nr BIGINT PRIMARY KEY, status TEXT NOT NULL, severity TEXT NOT NULL, " +
                "category TEXT NOT NULL, reporter_uuid TEXT, hash TEXT, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, data JSONB NOT NULL)",
            "CREATE INDEX IF NOT EXISTS reports_reporter ON feedback.reports (reporter_uuid)",
            "CREATE INDEX IF NOT EXISTS reports_hash ON feedback.reports (hash)",
            "CREATE TABLE IF NOT EXISTS feedback.comments (id TEXT PRIMARY KEY, nr BIGINT NOT NULL, at BIGINT NOT NULL, data JSONB NOT NULL)",
            "CREATE INDEX IF NOT EXISTS comments_nr ON feedback.comments (nr)",
            "CREATE TABLE IF NOT EXISTS feedback.events (id BIGSERIAL PRIMARY KEY, nr BIGINT NOT NULL, at BIGINT NOT NULL, data JSONB NOT NULL)",
            "CREATE INDEX IF NOT EXISTS events_nr ON feedback.events (nr)",
            "CREATE TABLE IF NOT EXISTS feedback.attachments (id TEXT PRIMARY KEY, nr BIGINT NOT NULL, name TEXT NOT NULL, " +
                "mime TEXT NOT NULL, size INT NOT NULL, at BIGINT NOT NULL, bytes BYTEA NOT NULL)",
            "CREATE TABLE IF NOT EXISTS feedback.agreements (uuid TEXT PRIMARY KEY, data JSONB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS feedback.points (uuid TEXT PRIMARY KEY, total INT NOT NULL, data JSONB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS feedback.notifications (id TEXT PRIMARY KEY, uuid TEXT NOT NULL, at BIGINT NOT NULL, data JSONB NOT NULL)",
            "CREATE INDEX IF NOT EXISTS notifications_uuid ON feedback.notifications (uuid)",
            "CREATE TABLE IF NOT EXISTS feedback.pending_rewards (id TEXT PRIMARY KEY, uuid TEXT NOT NULL, data JSONB NOT NULL)",
            "CREATE INDEX IF NOT EXISTS rewards_uuid ON feedback.pending_rewards (uuid)",
            "CREATE TABLE IF NOT EXISTS feedback.settings (id INT PRIMARY KEY, data JSONB NOT NULL)",
        )
    }
}
