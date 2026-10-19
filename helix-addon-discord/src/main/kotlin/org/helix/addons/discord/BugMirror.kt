package org.helix.addons.discord

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors bug reports of the feedback addon into Discord threads and turns forum posts of linked testers back into
 * reports (plan 1 section 5).
 *
 * - New report → thread in [DiscordConfig.bugForumChannelId] (title `#123 Title`, severity/category/status tags,
 *   context, reporter mention, screenshots as files); exploits go to [DiscordConfig.bugStaffChannelId] only.
 * - Status change → message in the thread, tags updated, closing statuses archive and lock the thread.
 * - Comments and attachments made in game or in the panel → message in the thread.
 * - New forum post of a linked tester → `feedback.create` (severity/category from the post's tags), files are
 *   attached; replies of the reporter or staff → `feedback.comment`. Unlinked users get a `/helix link` hint.
 *
 * The thread id is stored on the report (`feedback.discord.link`), so the mapping survives restarts and is shared
 * with the dashboard. Changes the bot itself made carry `via = discord` and are not echoed back.
 *
 * @property config current bot configuration.
 * @property feedback the feedback addon.
 * @property forum Discord operations.
 * @property playerOf linked player of a Discord user id, `null` when unlinked.
 * @property discordOf linked Discord user id of a player UUID, `null` when unlinked.
 * @property text translated text of a message key with `{placeholder}` values.
 */
class BugMirror(
    private val config: () -> DiscordConfig,
    private val feedback: FeedbackPort,
    private val forum: ForumPort,
    private val playerOf: (discordId: String) -> BugPlayer?,
    private val discordOf: (uuid: String) -> String?,
    private val text: (key: String, params: Map<String, String>) -> String,
) {
    private val hinted = ConcurrentHashMap.newKeySet<String>()

    /**
     * Handles a machine event of the feedback addon.
     *
     * @param event the event.
     */
    suspend fun onEvent(event: BugEvent) {
        if (!config().bugMirror()) return
        when (event.type) {
            "created" -> if (event.source != "discord") feedback.get(event.nr)?.let { ensureThread(it.report) }
            "status" -> onStatus(event)
            "comment" -> if (event.via != "discord") onComment(event)
            "attachment" -> if (event.via != "discord") onAttachment(event)
        }
    }

    /**
     * Handles a message posted in a thread of the bug forum (or the exploit channel).
     *
     * @param message the message.
     */
    suspend fun onMessage(message: BugMessage) {
        val cfg = config()
        if (!cfg.bugMirror() || message.authorIsBot) return
        if (message.parentId != cfg.bugForumChannelId && message.parentId != cfg.bugStaffChannelId) return
        val existing = feedback.byThread(message.threadId)
        if (message.isStarter && existing == null && message.parentId == cfg.bugForumChannelId) {
            newPost(message, cfg)
        } else if (existing != null) {
            reply(message, existing)
        }
    }

    /**
     * Posts the weekly leaderboard when due.
     *
     * @param now current instant.
     * @param lastWeek week key of the last post, if any.
     * @return the week key that was posted, or `null` when nothing was posted.
     */
    suspend fun postBoardIfDue(now: Instant, lastWeek: String?): String? {
        val cfg = config()
        if (cfg.bugBoardChannelId.isBlank()) return null
        val zone = runCatching { ZoneId.of(cfg.bugBoardZone) }.getOrDefault(ZoneId.of("Europe/Berlin"))
        if (!BugBoard.due(now, zone, cfg.bugBoardDay, cfg.bugBoardHour, lastWeek)) return null
        val board = BugBoard.render(feedback.top(BOARD_SIZE), text("bug.board.title", emptyMap()), text("bug.board.empty", emptyMap()))
        forum.send(cfg.bugBoardChannelId, board)
        return BugBoard.weekKey(now, zone)
    }

    /**
     * Opens the thread of a report unless it already has one.
     *
     * @param report the report.
     * @return the thread id, or `null` when the report has no target channel or opening failed.
     */
    suspend fun ensureThread(report: BugReport): String? {
        report.discordThread?.takeIf { it.isNotBlank() }?.let { return it }
        val channel = channelFor(report) ?: return null
        val tags = if (forum.isForum(channel)) BugTags.forReport(report, config().bugTags, forum.tags(channel)) else emptyList()
        val files = report.attachments.take(MAX_FILES).mapNotNull(feedback::attachment)
        val thread = forum.openThread(channel, threadTitle(report.nr, report.title), starter(report), tags, files) ?: return null
        feedback.link(report.nr, thread)
        return thread
    }

    /**
     * The channel a report is mirrored to: exploits only to the staff channel, everything else to the forum.
     *
     * @param report the report.
     * @return the channel id or `null` when the report is dropped.
     */
    fun channelFor(report: BugReport): String? {
        val cfg = config()
        val channel = if (report.severity == "exploit") cfg.bugStaffChannelId else cfg.bugForumChannelId
        return channel.takeIf { it.isNotBlank() }
    }

    /**
     * Renders the starter message of a report thread.
     *
     * @param report the report.
     * @return markdown text.
     */
    fun starter(report: BugReport): String {
        val reporter = when {
            report.reporter == null -> text("bug.start.auto", mapOf("count" to report.count.toString()))
            else -> {
                val mention = report.reporter.uuid?.let(discordOf)?.let { "<@$it>" }
                mention ?: report.reporter.name
            }
        }
        val ctx = report.context
        /** A non-blank context field as text. */
        fun field(name: String): String? = (ctx[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val place = listOfNotNull(field("service"), field("world")).joinToString(" · ")
        val coords = listOf("x", "y", "z").mapNotNull { field(it)?.toDoubleOrNull()?.toInt()?.toString() }
        val where = (place + if (coords.size == 3) " (${coords.joinToString(", ")})" else "").trim()
        val lines = mutableListOf(
            text(
                "bug.start.meta",
                mapOf(
                    "severity" to label("severity", report.severity),
                    "category" to label("category", report.category),
                    "status" to label("status", report.status),
                ),
            ),
            text("bug.start.reporter", mapOf("reporter" to reporter)),
        )
        if (where.isNotBlank()) lines += text("bug.start.place", mapOf("place" to where))
        lines += ""
        lines += DiscordMessages.truncate(report.description.ifBlank { report.title }, DESCRIPTION_LIMIT)
        return lines.joinToString("\n")
    }

    private suspend fun onStatus(event: BugEvent) {
        val report = feedback.get(event.nr)?.report ?: return
        val thread = ensureThread(report) ?: return
        val closing = report.status in BugTags.CLOSING
        if (!closing && event.from in BugTags.CLOSING) forum.update(thread, closed = false)
        val key = if (event.note.isNullOrBlank()) "bug.status" else "bug.status.note"
        forum.post(
            thread,
            text(
                key,
                mapOf(
                    "from" to label("status", event.from.orEmpty()),
                    "to" to label("status", event.to ?: report.status),
                    "actor" to (event.actor ?: "Team"),
                    "note" to event.note.orEmpty(),
                ),
            ),
        )
        val channel = channelFor(report)
        val tags = if (channel != null && forum.isForum(channel)) BugTags.forReport(report, config().bugTags, forum.tags(channel)) else null
        forum.update(thread, tags = tags, closed = if (closing) true else null)
    }

    private suspend fun onComment(event: BugEvent) {
        val detail = feedback.get(event.nr) ?: return
        val comment = detail.comments.firstOrNull { it.id == event.commentId } ?: return
        val thread = ensureThread(detail.report) ?: return
        val key = if (comment.staff) "bug.comment.staff" else "bug.comment"
        forum.post(thread, text(key, mapOf("name" to comment.author.name, "text" to DiscordMessages.truncate(comment.text, DESCRIPTION_LIMIT))))
    }

    private suspend fun onAttachment(event: BugEvent) {
        val report = feedback.get(event.nr)?.report ?: return
        val meta = report.attachments.firstOrNull { it.id == event.attachmentId } ?: return
        val thread = ensureThread(report) ?: return
        val file = feedback.attachment(meta) ?: return
        forum.post(thread, text("bug.attachment", mapOf("name" to meta.name, "actor" to (event.actor ?: ""))), listOf(file))
    }

    private suspend fun newPost(message: BugMessage, cfg: DiscordConfig) {
        val player = playerOf(message.authorId)
        if (player == null) {
            forum.post(message.threadId, text("bug.link_required", mapOf("user" to "<@${message.authorId}>")))
            return
        }
        val (severity, category) = BugTags.classify(message.appliedTags, cfg.bugTags, forum.tags(cfg.bugForumChannelId))
        val title = message.threadName.trim().take(TITLE_LIMIT).ifBlank { "Discord" }
        val result = feedback.create(player, severity, category, title, message.content.trim().ifBlank { title })
        val nr = result.nr
        if (!result.ok || nr == null) {
            forum.post(message.threadId, error(result))
            return
        }
        feedback.link(nr, message.threadId)
        forum.update(message.threadId, name = threadTitle(nr, title))
        attachFiles(nr, player, message)
        forum.post(message.threadId, text("bug.created", mapOf("nr" to nr.toString())))
    }

    private suspend fun reply(message: BugMessage, report: BugReport) {
        val player = playerOf(message.authorId)
        if (player == null) {
            if (hinted.add("${message.threadId}:${message.authorId}")) {
                forum.post(message.threadId, text("bug.link_required", mapOf("user" to "<@${message.authorId}>")))
            }
            return
        }
        if (message.content.isNotBlank()) {
            val result = feedback.comment(report.nr, player, message.content.trim())
            if (!result.ok) {
                forum.post(message.threadId, error(result))
                return
            }
        }
        attachFiles(report.nr, player, message)
    }

    private suspend fun attachFiles(nr: Long, player: BugPlayer, message: BugMessage) {
        message.files.take(MAX_FILES).forEach { remote ->
            if (remote.size > MAX_FILE_BYTES) return@forEach
            val bytes = forum.download(remote.url, MAX_FILE_BYTES) ?: return@forEach
            feedback.attach(nr, player, BugFile(remote.name, remote.mime ?: "application/octet-stream", bytes))
        }
    }

    private fun error(result: BugCallResult): String {
        val code = result.code ?: "other"
        val key = if (code in KNOWN_ERRORS) "bug.error.$code" else "bug.error.other"
        return text(key, mapOf("detail" to result.detail.orEmpty()))
    }

    private fun label(kind: String, key: String): String = BugTags.LABELS["$kind.$key"] ?: key

    /**
     * Builds the thread title `#nr Title` within Discord's 100-character limit.
     *
     * @param nr report number.
     * @param title report title.
     * @return the title.
     */
    fun threadTitle(nr: Long, title: String): String {
        val prefix = "#$nr "
        val clean = title.removePrefix(prefix).trim()
        return (prefix + clean).take(TITLE_LIMIT)
    }

    private companion object {
        const val TITLE_LIMIT = 100
        const val DESCRIPTION_LIMIT = 1800
        const val MAX_FILES = 10
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val BOARD_SIZE = 10
        val KNOWN_ERRORS = setOf("cooldown", "daily_limit", "testphase_closed", "forbidden", "not_found", "too_large", "unavailable")
    }
}
