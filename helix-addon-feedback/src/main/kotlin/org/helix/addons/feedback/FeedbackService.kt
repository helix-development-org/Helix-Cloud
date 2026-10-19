package org.helix.addons.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addons.feedback.store.FeedbackStore
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Result of a service call: either a value or a machine-readable failure.
 *
 * @param T value type.
 */
sealed interface Outcome<out T> {
    /**
     * Success.
     *
     * @property value the value.
     */
    data class Ok<T>(val value: T) : Outcome<T>

    /**
     * Failure.
     *
     * @property code machine code (for example `not_found`).
     * @property detail English detail.
     */
    data class Fail(val code: String, val detail: String) : Outcome<Nothing>
}

/**
 * Who performs a call: staff (dashboard, CLI, other addons, or a player with `feedback.staff`) or a player
 * acting on their own reports.
 *
 * @property ref the acting player or dashboard user, if known.
 * @property staff whether the caller has staff rights.
 */
data class Viewer(val ref: PlayerRef?, val staff: Boolean) {
    /** Display name for history entries. */
    val actorName: String get() = ref?.name?.takeIf { it.isNotBlank() } ?: if (staff) "staff" else "player"

    companion object {
        /** A staff caller without a player identity (dashboard, CLI, addon). */
        val SYSTEM: Viewer = Viewer(null, true)
    }
}

/**
 * Filter of `feedback.list`.
 *
 * @property status allowed statuses (empty = all).
 * @property severity allowed severities (empty = all).
 * @property category allowed categories (empty = all).
 * @property reporter reporter UUID or name (case-insensitive), if set.
 * @property query free-text search in title, description and number.
 * @property limit page size.
 * @property offset page start.
 * @property sort `new`, `updated` or `severity`.
 */
data class ListFilter(
    val status: Set<String> = emptySet(),
    val severity: Set<String> = emptySet(),
    val category: Set<String> = emptySet(),
    val reporter: String? = null,
    val query: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
    val sort: String = "new",
)

/**
 * Data of a new report.
 *
 * @property reporter reporting player.
 * @property severity severity.
 * @property category category.
 * @property title title.
 * @property description description.
 * @property source report source.
 * @property context technical context from the reporting component.
 */
data class NewReport(
    val reporter: PlayerRef?,
    val severity: String,
    val category: String,
    val title: String,
    val description: String,
    val source: String = Sources.INGAME,
    val context: JsonObject = JsonObject(emptyMap()),
)

/**
 * Data of an automatic exception report.
 *
 * @property hash deduplication hash (for example of the stack trace).
 * @property title title.
 * @property description description (stack trace).
 * @property category category.
 * @property severity severity.
 * @property context technical context.
 * @property player affected player, if any.
 */
data class AutoReport(
    val hash: String,
    val title: String,
    val description: String,
    val category: String = "network",
    val severity: String = Severities.ANNOYING,
    val context: JsonObject = JsonObject(emptyMap()),
    val player: PlayerRef? = null,
)

/**
 * Full view of one report.
 *
 * @property report the report.
 * @property comments comments, oldest first.
 * @property events history, oldest first.
 */
data class ReportDetails(val report: Report, val comments: List<Comment>, val events: List<ReportEvent>)

/**
 * Bug report rules: validation, spam limits, visibility, status workflow, points, notifications,
 * attachments, agreements and pending rewards. All mutating calls are serialized.
 *
 * @param store persistence.
 * @param clock time source.
 * @param logs supplier of the newest console lines of a service (oldest first); empty when unknown.
 * @param notify publishes a notification: category (`bugreport`, `bugreport-staff`), message key, parameters.
 * @param events receives a [FeedbackEvent] for every created report, status change, comment and attachment.
 */
class FeedbackService(
    private val store: FeedbackStore,
    private val clock: Clock = Clock.systemUTC(),
    private val logs: (service: String) -> List<String> = { emptyList() },
    private val notify: (category: String, key: String, params: Map<String, String>) -> Unit = { _, _, _ -> },
    private val events: (FeedbackEvent) -> Unit = {},
) {
    private val lock = Any()
    private val uploads = HashMap<String, Upload>()

    private class Upload(val count: Int, val createdAt: Long) {
        val parts = HashMap<Int, ByteArray>()
        val size: Int get() = parts.values.sumOf { it.size }
    }

    /** @return the current settings (defaults before the first write). */
    fun settings(): FeedbackSettings = store.settings() ?: FeedbackSettings()

    /**
     * Merges [patch] into the settings.
     *
     * @param patch JSON object with the fields to change.
     * @return the new settings or `invalid_settings`.
     */
    fun patchSettings(patch: JsonObject): Outcome<FeedbackSettings> = synchronized(lock) {
        val current = JSON.encodeToJsonElement(FeedbackSettings.serializer(), settings()) as JsonObject
        val merged = JsonObject(current + patch)
        val next = runCatching { JSON.decodeFromJsonElement(FeedbackSettings.serializer(), merged) }.getOrElse {
            return Outcome.Fail("invalid_settings", it.message ?: "invalid settings")
        }
        runCatching { ZoneId.of(next.zone) }.getOrElse { return Outcome.Fail("invalid_settings", "unknown zone ${next.zone}") }
        store.putSettings(next)
        Outcome.Ok(next)
    }

    /**
     * Creates a report.
     *
     * @param input report data.
     * @param viewer caller (staff bypasses spam limits and the test phase switch).
     * @return the stored report or a failure (`invalid_*`, `cooldown`, `daily_limit`, `testphase_closed`).
     */
    fun create(input: NewReport, viewer: Viewer): Outcome<Report> = synchronized(lock) {
        val settings = settings()
        validate(input.severity, input.category, input.title, input.description, input.source)?.let { return it }
        if (!viewer.staff && !settings.testphase) return Outcome.Fail("testphase_closed", "the test phase is closed")
        val reporterUuid = input.reporter?.uuid
        if (!viewer.staff && reporterUuid != null) {
            val own = store.reports().filter { it.reporter?.uuid == reporterUuid && it.source != Sources.AUTO }
            val now = clock.millis()
            val last = own.maxOfOrNull { it.createdAt }
            if (last != null && now - last < settings.cooldownSeconds * 1000L) {
                val left = (settings.cooldownSeconds * 1000L - (now - last) + 999) / 1000
                return Outcome.Fail("cooldown", left.toString())
            }
            val dayStart = Instant.ofEpochMilli(now).atZone(ZoneId.of(settings.zone)).toLocalDate()
                .atStartOfDay(ZoneId.of(settings.zone)).toInstant().toEpochMilli()
            if (own.count { it.createdAt >= dayStart } >= settings.maxPerDay) {
                return Outcome.Fail("daily_limit", "at most ${settings.maxPerDay} reports per day")
            }
        }
        val now = clock.millis()
        val report = Report(
            nr = store.nextNumber(),
            status = Statuses.NEW,
            severity = input.severity,
            category = input.category,
            title = input.title.trim(),
            description = input.description.trim(),
            reporter = input.reporter,
            context = withLogs(input.context, settings),
            source = input.source,
            createdAt = now,
            updatedAt = now,
        )
        store.putReport(report)
        store.addEvent(ReportEvent(report.nr, now, viewer.actorName, "created", to = Statuses.NEW))
        announce("notify.new", report)
        emit(FeedbackEvent("created", report.nr, source = report.source, severity = report.severity, actor = viewer.actorName))
        Outcome.Ok(report)
    }

    /**
     * Records an automatic exception report, deduplicated by hash while the previous report is not closed.
     *
     * @param input exception data.
     * @return `(report, isNew)` or a failure.
     */
    fun auto(input: AutoReport): Outcome<Pair<Report, Boolean>> = synchronized(lock) {
        if (input.hash.isBlank()) return Outcome.Fail("invalid_hash", "hash required")
        val severity = input.severity.takeIf { it in Severities.ALL } ?: Severities.ANNOYING
        val category = input.category.takeIf { it in Categories.ALL } ?: "network"
        val now = clock.millis()
        val existing = store.reports().lastOrNull { it.hash == input.hash && it.status != Statuses.CLOSED }
        if (existing != null) {
            val affected = (existing.affected + listOfNotNull(input.player?.uuid)).distinct().take(MAX_AFFECTED)
            val updated = existing.copy(count = existing.count + 1, affected = affected, updatedAt = now)
            store.putReport(updated)
            return Outcome.Ok(updated to false)
        }
        val report = Report(
            nr = store.nextNumber(),
            status = Statuses.NEW,
            severity = severity,
            category = category,
            title = input.title.trim().take(MAX_TITLE).ifBlank { "Exception" },
            description = input.description.take(MAX_DESCRIPTION * 4),
            reporter = null,
            context = withLogs(input.context, settings()),
            source = Sources.AUTO,
            hash = input.hash,
            affected = listOfNotNull(input.player?.uuid),
            createdAt = now,
            updatedAt = now,
        )
        store.putReport(report)
        store.addEvent(ReportEvent(report.nr, now, "system", "auto", to = Statuses.NEW))
        announce("notify.auto", report)
        emit(FeedbackEvent("created", report.nr, source = report.source, severity = report.severity, actor = "system"))
        Outcome.Ok(report to true)
    }

    /**
     * Lists reports visible to [viewer].
     *
     * @param filter filter and paging.
     * @param viewer caller.
     * @return `(total, page)` – the page omits the attached log lines.
     */
    fun list(filter: ListFilter, viewer: Viewer): Pair<Int, List<Report>> {
        val query = filter.query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val matched = store.reports().asSequence()
            .filter { visible(it, viewer) }
            .filter { filter.status.isEmpty() || it.status in filter.status }
            .filter { filter.severity.isEmpty() || it.severity in filter.severity }
            .filter { filter.category.isEmpty() || it.category in filter.category }
            .filter { r ->
                filter.reporter == null || r.reporter?.uuid.equals(filter.reporter, true) || r.reporter?.name.equals(filter.reporter, true)
            }
            .filter { r ->
                query == null || r.title.lowercase().contains(query) || r.description.lowercase().contains(query) ||
                    "#${r.nr}" == query || r.nr.toString() == query
            }
            .toList()
        val sorted = when (filter.sort) {
            "updated" -> matched.sortedByDescending { it.updatedAt }
            "severity" -> matched.sortedWith(compareByDescending<Report> { Severities.ALL.indexOf(it.severity) }.thenByDescending { it.nr })
            else -> matched.sortedByDescending { it.nr }
        }
        val page = sorted.drop(filter.offset.coerceAtLeast(0)).take(filter.limit.coerceIn(1, MAX_PAGE)).map { it.withoutLogs() }
        return matched.size to page
    }

    /**
     * Loads one report with comments and history.
     *
     * @param nr report number.
     * @param viewer caller.
     * @return the details, `not_found` or `forbidden`.
     */
    fun get(nr: Long, viewer: Viewer): Outcome<ReportDetails> {
        val report = store.report(nr) ?: return Outcome.Fail("not_found", "report #$nr not found")
        if (!visible(report, viewer)) return Outcome.Fail("forbidden", "not your report")
        return Outcome.Ok(ReportDetails(report, store.comments(nr), store.events(nr)))
    }

    /**
     * Adds a comment (reporter or staff).
     *
     * @param nr report number.
     * @param text comment text.
     * @param viewer author.
     * @param via originating channel (for example `discord`), echoed in the [FeedbackEvent].
     * @return the comment or a failure.
     */
    fun comment(nr: Long, text: String, viewer: Viewer, via: String? = null): Outcome<Comment> = synchronized(lock) {
        val report = store.report(nr) ?: return Outcome.Fail("not_found", "report #$nr not found")
        if (!visible(report, viewer)) return Outcome.Fail("forbidden", "not your report")
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > MAX_DESCRIPTION) return Outcome.Fail("invalid_text", "1..$MAX_DESCRIPTION characters")
        val now = clock.millis()
        val comment = Comment(UUID.randomUUID().toString(), nr, viewer.ref ?: PlayerRef(null, viewer.actorName), viewer.staff, clean, now)
        store.addComment(comment)
        store.addEvent(ReportEvent(nr, now, viewer.actorName, "comment"))
        store.putReport(report.copy(updatedAt = now))
        emit(FeedbackEvent("comment", nr, severity = report.severity, actor = viewer.actorName, commentId = comment.id, via = via))
        Outcome.Ok(comment)
    }

    /**
     * Changes the status (staff only); grants or revokes points and notifies the reporter.
     *
     * @param nr report number.
     * @param to new status.
     * @param viewer staff caller.
     * @param note optional note for the history.
     * @return the updated report or a failure (`invalid_transition`, `forbidden`).
     */
    fun status(nr: Long, to: String, viewer: Viewer, note: String? = null): Outcome<Report> = synchronized(lock) {
        if (!viewer.staff) return Outcome.Fail("forbidden", "staff only")
        if (to !in Statuses.ALL) return Outcome.Fail("invalid_status", to)
        val report = store.report(nr) ?: return Outcome.Fail("not_found", "report #$nr not found")
        if (!Statuses.allowed(report.status, to)) return Outcome.Fail("invalid_transition", "${report.status} -> $to")
        Outcome.Ok(transition(report, to, viewer, note, report.duplicateOf.takeIf { to == Statuses.DUPLICATE }))
    }

    /**
     * Marks a report as duplicate of another (staff only).
     *
     * @param nr report number.
     * @param of number of the original report.
     * @param viewer staff caller.
     * @return the updated report or a failure.
     */
    fun duplicate(nr: Long, of: Long, viewer: Viewer): Outcome<Report> = synchronized(lock) {
        if (!viewer.staff) return Outcome.Fail("forbidden", "staff only")
        if (nr == of) return Outcome.Fail("invalid_duplicate", "a report cannot duplicate itself")
        val report = store.report(nr) ?: return Outcome.Fail("not_found", "report #$nr not found")
        store.report(of) ?: return Outcome.Fail("not_found", "report #$of not found")
        if (!Statuses.allowed(report.status, Statuses.DUPLICATE)) {
            return Outcome.Fail("invalid_transition", "${report.status} -> ${Statuses.DUPLICATE}")
        }
        Outcome.Ok(transition(report, Statuses.DUPLICATE, viewer, "#$of", of))
    }

    /**
     * Sets or clears the assignee (staff only).
     *
     * @param nr report number.
     * @param assignee new assignee, `null` to clear.
     * @param viewer staff caller.
     * @return the updated report or a failure.
     */
    fun assign(nr: Long, assignee: String?, viewer: Viewer): Outcome<Report> = synchronized(lock) {
        if (!viewer.staff) return Outcome.Fail("forbidden", "staff only")
        val report = store.report(nr) ?: return Outcome.Fail("not_found", "report #$nr not found")
        val now = clock.millis()
        val updated = report.copy(assignee = assignee?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = now)
        store.putReport(updated)
        store.addEvent(ReportEvent(nr, now, viewer.actorName, "assign", report.assignee, updated.assignee))
        Outcome.Ok(updated)
    }

    /**
     * Stores an attachment.
     *
     * @param nr report number.
     * @param name file name.
     * @param mime MIME type.
     * @param bytes content.
     * @param viewer caller (reporter or staff).
     * @param via originating channel (for example `discord`), echoed in the [FeedbackEvent].
     * @return the attachment metadata or a failure (`too_large`, `forbidden`, `not_found`).
     */
    fun attach(nr: Long, name: String, mime: String, bytes: ByteArray, viewer: Viewer, via: String? = null): Outcome<Attachment> = synchronized(lock) {
        val report = store.report(nr) ?: return Outcome.Fail("not_found", "report #$nr not found")
        if (!visible(report, viewer)) return Outcome.Fail("forbidden", "not your report")
        val max = settings().maxAttachmentBytes
        if (bytes.isEmpty()) return Outcome.Fail("invalid_attachment", "empty attachment")
        if (bytes.size > max) return Outcome.Fail("too_large", "max $max bytes")
        val now = clock.millis()
        val attachment = Attachment(UUID.randomUUID().toString(), name.take(MAX_NAME).ifBlank { "attachment" }, mime.ifBlank { "application/octet-stream" }, bytes.size, now)
        store.putAttachment(nr, attachment, bytes)
        store.putReport(report.copy(attachments = report.attachments + attachment, updatedAt = now))
        store.addEvent(ReportEvent(nr, now, viewer.actorName, "attach", to = attachment.name))
        emit(FeedbackEvent("attachment", nr, severity = report.severity, actor = viewer.actorName, attachmentId = attachment.id, via = via))
        Outcome.Ok(attachment)
    }

    /**
     * Receives one chunk of an upload (uploads expire after ten minutes).
     *
     * @param uploadId client-chosen upload id.
     * @param index chunk index (0-based).
     * @param count total number of chunks.
     * @param bytes chunk content.
     * @return number of chunks received so far or a failure (`too_large`, `invalid_chunk`).
     */
    fun chunk(uploadId: String, index: Int, count: Int, bytes: ByteArray): Outcome<Int> = synchronized(lock) {
        expireUploads()
        if (uploadId.isBlank() || count !in 1..MAX_CHUNKS || index !in 0 until count) {
            return Outcome.Fail("invalid_chunk", "index $index of $count")
        }
        val upload = uploads.getOrPut(uploadId) { Upload(count, clock.millis()) }
        if (upload.count != count) return Outcome.Fail("invalid_chunk", "chunk count changed")
        upload.parts[index] = bytes
        if (upload.size > settings().maxAttachmentBytes) {
            uploads.remove(uploadId)
            return Outcome.Fail("too_large", "max ${settings().maxAttachmentBytes} bytes")
        }
        Outcome.Ok(upload.parts.size)
    }

    /**
     * Completes an upload and attaches it.
     *
     * @param uploadId upload id.
     * @param nr report number.
     * @param name file name.
     * @param mime MIME type.
     * @param viewer caller.
     * @param via originating channel, echoed in the [FeedbackEvent].
     * @return the attachment or a failure (`incomplete`, `not_found`, …).
     */
    fun finish(uploadId: String, nr: Long, name: String, mime: String, viewer: Viewer, via: String? = null): Outcome<Attachment> = synchronized(lock) {
        expireUploads()
        val upload = uploads[uploadId] ?: return Outcome.Fail("not_found", "upload $uploadId unknown or expired")
        if (upload.parts.size != upload.count) return Outcome.Fail("incomplete", "${upload.parts.size}/${upload.count} chunks")
        val bytes = (0 until upload.count).fold(ByteArray(0)) { acc, i -> acc + upload.parts.getValue(i) }
        val result = attach(nr, name, mime, bytes, viewer, via)
        if (result is Outcome.Ok) uploads.remove(uploadId)
        result
    }

    /**
     * Loads attachment bytes.
     *
     * @param id attachment id.
     * @return `(mime, bytes)` or `null`.
     */
    fun attachment(id: String): Pair<String, ByteArray>? = store.attachment(id)

    /**
     * Lists the reports of one player (including their own exploits).
     *
     * @param uuid player UUID.
     * @param limit maximum number of reports.
     * @return reports newest first, without log lines.
     */
    fun mine(uuid: String, limit: Int = 50): List<Report> =
        store.reports().filter { it.reporter?.uuid == uuid }.sortedByDescending { it.nr }.take(limit.coerceIn(1, MAX_PAGE)).map { it.withoutLogs() }

    /**
     * @param uuid player UUID.
     * @return unread notifications.
     */
    fun notifications(uuid: String): List<Notification> = store.notifications(uuid)

    /**
     * Marks notifications as read.
     *
     * @param uuid player UUID.
     * @param ids ids to acknowledge, empty for all.
     * @return number acknowledged.
     */
    fun ack(uuid: String, ids: Set<String>): Int = synchronized(lock) { store.ackNotifications(uuid, ids) }

    /**
     * Tester ranking.
     *
     * @param limit number of entries.
     * @return points records with more than zero points, best first.
     */
    fun top(limit: Int = 10): List<Points> =
        store.allPoints().filter { it.total > 0 }.sortedWith(compareByDescending<Points> { it.total }.thenBy { it.name.lowercase() })
            .take(limit.coerceIn(1, MAX_PAGE))

    /**
     * Agreement state of a player.
     *
     * @param uuid player UUID.
     * @return `(currentVersion, acceptedVersion)`.
     */
    fun agreement(uuid: String): Pair<Int, Int?> = settings().agreementVersion to store.agreement(uuid)?.version

    /**
     * Records the acceptance of the tester agreement.
     *
     * @param uuid player UUID.
     * @param version accepted version, default the current one.
     * @return the stored agreement.
     */
    fun accept(uuid: String, version: Int? = null): Agreement = synchronized(lock) {
        val agreement = Agreement(uuid, version ?: settings().agreementVersion, clock.millis())
        store.putAgreement(agreement)
        agreement
    }

    /**
     * Replaces the release reward table.
     *
     * @param table reward tiers.
     * @return the new settings.
     */
    fun setRewardTable(table: List<JsonObject>): FeedbackSettings = synchronized(lock) {
        settings().copy(rewards = table).also { store.putSettings(it) }
    }

    /**
     * Queues a reward for a player.
     *
     * @param uuid recipient.
     * @param reward reward description.
     * @return the pending reward.
     */
    fun grant(uuid: String, reward: JsonObject): PendingReward = synchronized(lock) {
        PendingReward(UUID.randomUUID().toString(), uuid, reward, clock.millis()).also { store.putReward(it) }
    }

    /**
     * @param uuid recipient.
     * @return unclaimed rewards.
     */
    fun pending(uuid: String): List<PendingReward> = store.rewards(uuid).filter { it.claimedAt == null }

    /**
     * Marks a reward as claimed.
     *
     * @param uuid recipient.
     * @param id reward id.
     * @return the claimed reward, `not_found` or `claimed`.
     */
    fun claim(uuid: String, id: String): Outcome<PendingReward> = synchronized(lock) {
        val reward = store.rewards(uuid).firstOrNull { it.id == id } ?: return Outcome.Fail("not_found", "reward $id not found")
        if (reward.claimedAt != null) return Outcome.Fail("claimed", "already claimed")
        val claimed = reward.copy(claimedAt = clock.millis())
        store.putReward(claimed)
        Outcome.Ok(claimed)
    }

    /**
     * Records the use of a tester tool.
     *
     * @param uuid tester UUID.
     * @param body `{name?, action, details?, service?}` from the plugin.
     * @return the stored entry or `invalid_toollog`.
     */
    fun toolLog(uuid: String, body: JsonObject): Outcome<ToolLogEntry> {
        val action = body.str("action")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
            ?: return Outcome.Fail("invalid_toollog", "action required")
        if (uuid.isBlank()) return Outcome.Fail("invalid_toollog", "uuid required")
        val entry = ToolLogEntry(
            uuid = uuid,
            name = body.str("name").orEmpty(),
            action = action,
            details = body["details"] as? JsonObject ?: JsonObject(emptyMap()),
            service = body.str("service"),
            at = clock.millis(),
        )
        store.addToolLog(entry)
        return Outcome.Ok(entry)
    }

    /**
     * Reads tester tool log entries.
     *
     * @param uuid only this tester, or all when `null`.
     * @param limit maximum number of entries.
     * @return entries, oldest first.
     */
    fun toolLogs(uuid: String?, limit: Int = 100): List<ToolLogEntry> = store.toolLogs(uuid, limit.coerceIn(1, MAX_TOOLLOG_PAGE))

    /** @return all reports (for summaries). */
    fun all(): List<Report> = store.reports()

    /**
     * Links a report to the Discord thread mirroring it (staff/addon only).
     *
     * @param nr report number.
     * @param threadId Discord thread id, blank to clear.
     * @return the updated report or `not_found`.
     */
    fun linkDiscord(nr: Long, threadId: String): Outcome<Report> = synchronized(lock) {
        val report = store.report(nr) ?: return Outcome.Fail("not_found", "report #$nr not found")
        val updated = report.copy(discordThread = threadId.trim().takeIf { it.isNotEmpty() })
        store.putReport(updated)
        Outcome.Ok(updated)
    }

    /**
     * Finds the report mirrored by a Discord thread.
     *
     * @param threadId Discord thread id.
     * @return the report or `null`.
     */
    fun byDiscordThread(threadId: String): Report? =
        threadId.takeIf { it.isNotBlank() }?.let { id -> store.reports().firstOrNull { it.discordThread == id } }

    private fun emit(event: FeedbackEvent) {
        runCatching { events(event) }
    }

    /**
     * Whether [viewer] may see [report]: staff see everything, players only their own reports.
     *
     * @param report the report.
     * @param viewer caller.
     * @return true when visible.
     */
    fun visible(report: Report, viewer: Viewer): Boolean =
        viewer.staff || (viewer.ref?.uuid != null && report.reporter?.uuid == viewer.ref.uuid)

    private fun transition(report: Report, to: String, viewer: Viewer, note: String?, duplicateOf: Long?): Report {
        val now = clock.millis()
        val (points, granted) = Statuses.pointsAfter(report, to, settings().points)
        val updated = report.copy(
            status = to,
            points = points,
            pointsGranted = granted,
            duplicateOf = if (to == Statuses.DUPLICATE) duplicateOf else report.duplicateOf,
            updatedAt = now,
        )
        store.putReport(updated)
        store.addEvent(ReportEvent(report.nr, now, viewer.actorName, "status", report.status, to, note?.take(MAX_DESCRIPTION)))
        val reporter = report.reporter
        if (reporter?.uuid != null) {
            if (points != report.points) {
                val current = store.points(reporter.uuid) ?: Points(reporter.uuid, reporter.name, 0)
                store.putPoints(current.copy(name = reporter.name.ifBlank { current.name }, total = (current.total + points - report.points).coerceAtLeast(0)))
            }
            if (reporter.uuid != viewer.ref?.uuid) {
                store.addNotification(Notification(UUID.randomUUID().toString(), reporter.uuid, report.nr, report.title, to, now))
            }
        }
        announce("notify.status", updated)
        emit(FeedbackEvent("status", report.nr, severity = report.severity, from = report.status, to = to, actor = viewer.actorName, note = note))
        return updated
    }

    private fun announce(key: String, report: Report) {
        val category = if (report.severity == Severities.EXPLOIT) "bugreport-staff" else "bugreport"
        notify(
            category,
            key,
            mapOf(
                "nr" to report.nr.toString(),
                "title" to report.title,
                "status" to report.status,
                "severity" to report.severity,
                "category" to report.category,
                "reporter" to (report.reporter?.name ?: "system"),
                "count" to report.count.toString(),
            ),
        )
    }

    private fun withLogs(context: JsonObject, settings: FeedbackSettings): JsonObject {
        val service = (context["service"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return context
        val raw = runCatching { logs(service) }.getOrDefault(emptyList())
        if (raw.isEmpty()) return context
        val now = (context["serverTime"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: LocalTime.now(clock.withZone(ZoneId.of(settings.zone)))
        val excerpt = LogExcerpt.filter(raw, now, settings.logMinutes, settings.logMaxLines)
        return JsonObject(context + ("logs" to JsonArray(excerpt.map { JsonPrimitive(it) })))
    }

    private fun validate(severity: String, category: String, title: String, description: String, source: String): Outcome.Fail? = when {
        severity !in Severities.ALL -> Outcome.Fail("invalid_severity", severity)
        category !in Categories.ALL -> Outcome.Fail("invalid_category", category)
        source !in Sources.ALL -> Outcome.Fail("invalid_source", source)
        title.isBlank() || title.trim().length > MAX_TITLE -> Outcome.Fail("invalid_title", "1..$MAX_TITLE characters")
        description.trim().length > MAX_DESCRIPTION -> Outcome.Fail("invalid_description", "at most $MAX_DESCRIPTION characters")
        else -> null
    }

    private fun expireUploads() {
        val cutoff = clock.millis() - UPLOAD_TTL_MILLIS
        uploads.entries.removeIf { it.value.createdAt < cutoff }
    }

    private fun Report.withoutLogs(): Report =
        if (context.containsKey("logs")) copy(context = JsonObject(context - "logs")) else this

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        const val MAX_TITLE = 80
        const val MAX_DESCRIPTION = 4000
        const val MAX_NAME = 120
        const val MAX_PAGE = 200
        const val MAX_TOOLLOG_PAGE = 1000
        const val MAX_AFFECTED = 200
        const val MAX_CHUNKS = 256
        const val UPLOAD_TTL_MILLIS = 10 * 60 * 1000L
    }
}

/**
 * Reads a string field of a JSON object.
 *
 * @param key field name.
 * @return the string value or `null`.
 */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull

/**
 * Reads a JSON array of strings.
 *
 * @param key field name.
 * @return the strings (empty when missing).
 */
fun JsonObject.strings(key: String): Set<String> =
    (this[key] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.toSet().orEmpty()
