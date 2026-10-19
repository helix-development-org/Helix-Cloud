package org.helix.addons.feedback.store

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

/**
 * Persistence of the feedback addon. Implementations only store and load; all rules live in
 * [org.helix.addons.feedback.FeedbackService], which serializes access, so implementations need no own
 * transaction logic beyond single-statement atomicity.
 */
interface FeedbackStore : AutoCloseable {
    /** @return the next free report number (strictly increasing). */
    fun nextNumber(): Long

    /**
     * Inserts or replaces a report.
     *
     * @param report the report.
     */
    fun putReport(report: Report)

    /**
     * @param nr report number.
     * @return the report or `null`.
     */
    fun report(nr: Long): Report?

    /** @return all reports (the test phase stays small; filtering happens in the service). */
    fun reports(): List<Report>

    /**
     * Stores a comment.
     *
     * @param comment the comment.
     */
    fun addComment(comment: Comment)

    /**
     * @param nr report number.
     * @return comments, oldest first.
     */
    fun comments(nr: Long): List<Comment>

    /**
     * Stores a history entry.
     *
     * @param event the entry.
     */
    fun addEvent(event: ReportEvent)

    /**
     * @param nr report number.
     * @return history, oldest first.
     */
    fun events(nr: Long): List<ReportEvent>

    /**
     * Stores attachment bytes.
     *
     * @param nr report number.
     * @param attachment metadata.
     * @param bytes content.
     */
    fun putAttachment(nr: Long, attachment: Attachment, bytes: ByteArray)

    /**
     * @param id attachment id.
     * @return `(mime, bytes)` or `null`.
     */
    fun attachment(id: String): Pair<String, ByteArray>?

    /**
     * @param uuid player UUID.
     * @return the accepted agreement or `null`.
     */
    fun agreement(uuid: String): Agreement?

    /**
     * Stores an accepted agreement.
     *
     * @param agreement the agreement.
     */
    fun putAgreement(agreement: Agreement)

    /**
     * @param uuid player UUID.
     * @return accumulated points or `null`.
     */
    fun points(uuid: String): Points?

    /**
     * Stores accumulated points.
     *
     * @param points the points.
     */
    fun putPoints(points: Points)

    /** @return all point records. */
    fun allPoints(): List<Points>

    /**
     * Stores a notification.
     *
     * @param notification the notification.
     */
    fun addNotification(notification: Notification)

    /**
     * @param uuid recipient.
     * @return unread notifications, oldest first.
     */
    fun notifications(uuid: String): List<Notification>

    /**
     * Removes notifications.
     *
     * @param uuid recipient.
     * @param ids ids to remove; empty removes all of the recipient.
     * @return number removed.
     */
    fun ackNotifications(uuid: String, ids: Set<String>): Int

    /**
     * Inserts or replaces a pending reward.
     *
     * @param reward the reward.
     */
    fun putReward(reward: PendingReward)

    /**
     * @param uuid recipient.
     * @return all rewards of the recipient (pending and claimed).
     */
    fun rewards(uuid: String): List<PendingReward>

    /**
     * Appends a tester tool log entry.
     *
     * @param entry the entry.
     */
    fun addToolLog(entry: ToolLogEntry)

    /**
     * Loads tester tool log entries.
     *
     * @param uuid only entries of this tester, or all when `null`.
     * @param limit maximum number of entries (newest kept).
     * @return entries, oldest first.
     */
    fun toolLogs(uuid: String?, limit: Int): List<ToolLogEntry>

    /** @return the stored settings or `null` before the first write. */
    fun settings(): FeedbackSettings?

    /**
     * Stores the settings.
     *
     * @param settings the settings.
     */
    fun putSettings(settings: FeedbackSettings)

    /** Releases resources (database pool). */
    override fun close() {}
}
