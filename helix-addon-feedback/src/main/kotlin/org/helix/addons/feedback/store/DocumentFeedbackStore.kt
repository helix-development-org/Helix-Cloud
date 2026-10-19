package org.helix.addons.feedback.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
import org.helix.addons.feedback.ToolLogEntry
import org.helix.api.storage.AddonStorage
import java.util.Base64

/**
 * [FeedbackStore] on the addon's key/value document storage – used when the node runs without PostgreSQL
 * and in tests. One document per report, per comment/event list and per player record.
 *
 * @param storage the addon storage.
 */
class DocumentFeedbackStore(private val storage: AddonStorage) : FeedbackStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Blob(val mime: String, val data: String)

    @Synchronized
    override fun nextNumber(): Long {
        val next = (storage.read(SEQ)?.toLongOrNull() ?: 0L) + 1
        storage.write(SEQ, next.toString())
        return next
    }

    override fun putReport(report: Report) = write("$REPORT${report.nr}", Report.serializer(), report)

    override fun report(nr: Long): Report? = read("$REPORT$nr", Report.serializer())

    override fun reports(): List<Report> =
        storage.keys().filter { it.startsWith(REPORT) }.mapNotNull { read(it, Report.serializer()) }.sortedBy { it.nr }

    @Synchronized
    override fun addComment(comment: Comment) = append("$COMMENTS${comment.nr}", Comment.serializer(), comment)

    override fun comments(nr: Long): List<Comment> = list("$COMMENTS$nr", Comment.serializer())

    @Synchronized
    override fun addEvent(event: ReportEvent) = append("$EVENTS${event.nr}", ReportEvent.serializer(), event)

    override fun events(nr: Long): List<ReportEvent> = list("$EVENTS$nr", ReportEvent.serializer())

    override fun putAttachment(nr: Long, attachment: Attachment, bytes: ByteArray) =
        write("$ATTACHMENT${attachment.id}", Blob.serializer(), Blob(attachment.mime, Base64.getEncoder().encodeToString(bytes)))

    override fun attachment(id: String): Pair<String, ByteArray>? =
        read("$ATTACHMENT$id", Blob.serializer())?.let { it.mime to Base64.getDecoder().decode(it.data) }

    override fun agreement(uuid: String): Agreement? = read("$AGREEMENT$uuid", Agreement.serializer())

    override fun putAgreement(agreement: Agreement) = write("$AGREEMENT${agreement.uuid}", Agreement.serializer(), agreement)

    override fun points(uuid: String): Points? = read("$POINTS$uuid", Points.serializer())

    override fun putPoints(points: Points) = write("$POINTS${points.uuid}", Points.serializer(), points)

    override fun allPoints(): List<Points> =
        storage.keys().filter { it.startsWith(POINTS) }.mapNotNull { read(it, Points.serializer()) }

    @Synchronized
    override fun addNotification(notification: Notification) =
        append("$NOTIFICATIONS${notification.uuid}", Notification.serializer(), notification)

    override fun notifications(uuid: String): List<Notification> = list("$NOTIFICATIONS$uuid", Notification.serializer())

    @Synchronized
    override fun ackNotifications(uuid: String, ids: Set<String>): Int {
        val current = notifications(uuid)
        val keep = if (ids.isEmpty()) emptyList() else current.filterNot { it.id in ids }
        writeList("$NOTIFICATIONS$uuid", Notification.serializer(), keep)
        return current.size - keep.size
    }

    @Synchronized
    override fun putReward(reward: PendingReward) {
        val key = "$REWARDS${reward.uuid}"
        writeList(key, PendingReward.serializer(), list(key, PendingReward.serializer()).filterNot { it.id == reward.id } + reward)
    }

    override fun rewards(uuid: String): List<PendingReward> = list("$REWARDS$uuid", PendingReward.serializer())

    @Synchronized
    override fun addToolLog(entry: ToolLogEntry) {
        val key = "$TOOLLOG${entry.uuid}"
        writeList(key, ToolLogEntry.serializer(), (list(key, ToolLogEntry.serializer()) + entry).takeLast(MAX_TOOLLOG_PER_PLAYER))
    }

    override fun toolLogs(uuid: String?, limit: Int): List<ToolLogEntry> {
        val keys = if (uuid != null) listOf("$TOOLLOG$uuid") else storage.keys().filter { it.startsWith(TOOLLOG) }
        return keys.flatMap { list(it, ToolLogEntry.serializer()) }.sortedBy { it.at }.takeLast(limit)
    }

    override fun settings(): FeedbackSettings? = read(SETTINGS, FeedbackSettings.serializer())

    override fun putSettings(settings: FeedbackSettings) = write(SETTINGS, FeedbackSettings.serializer(), settings)

    private fun <T> read(key: String, serializer: KSerializer<T>): T? =
        storage.read(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T) = storage.write(key, json.encodeToString(serializer, value))

    private fun <T> list(key: String, serializer: KSerializer<T>): List<T> = read(key, ListSerializer(serializer)).orEmpty()

    private fun <T> writeList(key: String, serializer: KSerializer<T>, values: List<T>) = write(key, ListSerializer(serializer), values)

    private fun <T> append(key: String, serializer: KSerializer<T>, value: T) = writeList(key, serializer, list(key, serializer) + value)

    private companion object {
        const val SEQ = "seq"
        const val REPORT = "report:"
        const val COMMENTS = "comments:"
        const val EVENTS = "events:"
        const val ATTACHMENT = "attachment:"
        const val AGREEMENT = "agreement:"
        const val POINTS = "points:"
        const val NOTIFICATIONS = "notifications:"
        const val REWARDS = "rewards:"
        const val SETTINGS = "settings"
        const val TOOLLOG = "toollog:"
        const val MAX_TOOLLOG_PER_PLAYER = 500
    }
}
