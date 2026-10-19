package org.helix.addons.discord

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Player reference of the feedback addon (`{uuid, name}`).
 *
 * @property uuid player UUID, `null` for system or dashboard actors.
 * @property name display name.
 */
@Serializable
data class BugPlayer(val uuid: String? = null, val name: String = "")

/**
 * Attachment metadata of a bug report.
 *
 * @property id attachment id (load the bytes with `feedback.attachment`).
 * @property name file name.
 * @property mime MIME type.
 * @property size size in bytes.
 */
@Serializable
data class BugAttachment(val id: String, val name: String = "attachment", val mime: String = "application/octet-stream", val size: Int = 0)

/**
 * The part of a feedback-addon report the Discord mirror needs.
 *
 * @property nr report number.
 * @property status workflow status.
 * @property severity severity key.
 * @property category category key.
 * @property title title.
 * @property description description.
 * @property reporter reporting player, `null` for automatic reports.
 * @property context technical context (service, world, coordinates …).
 * @property attachments attachment metadata.
 * @property discordThread id of the mirroring thread, if any.
 * @property source report source (`ingame`, `discord`, `panel`, `auto`).
 * @property count occurrences of an automatic report.
 */
@Serializable
data class BugReport(
    val nr: Long,
    val status: String = "new",
    val severity: String = "minor",
    val category: String = "other",
    val title: String = "",
    val description: String = "",
    val reporter: BugPlayer? = null,
    val context: JsonObject = JsonObject(emptyMap()),
    val attachments: List<BugAttachment> = emptyList(),
    val discordThread: String? = null,
    val source: String = "ingame",
    val count: Int = 1,
)

/**
 * A comment of a bug report.
 *
 * @property id comment id.
 * @property author author.
 * @property staff whether the author is staff.
 * @property text text.
 */
@Serializable
data class BugComment(val id: String, val author: BugPlayer = BugPlayer(), val staff: Boolean = false, val text: String = "")

/**
 * `feedback.get` answer.
 *
 * @property report the report.
 * @property comments its comments.
 */
@Serializable
data class BugDetail(val report: BugReport, val comments: List<BugComment> = emptyList())

/**
 * Machine event of the feedback addon (notification category [CATEGORY]).
 *
 * @property type `created`, `status`, `comment` or `attachment`.
 * @property nr report number.
 * @property source report source (on `created`).
 * @property severity report severity.
 * @property from previous status.
 * @property to new status.
 * @property actor acting player or staff member.
 * @property note history note.
 * @property commentId comment id.
 * @property attachmentId attachment id.
 * @property via originating channel (`discord` for the bot's own changes).
 */
@Serializable
data class BugEvent(
    val type: String,
    val nr: Long,
    val source: String? = null,
    val severity: String? = null,
    val from: String? = null,
    val to: String? = null,
    val actor: String? = null,
    val note: String? = null,
    val commentId: String? = null,
    val attachmentId: String? = null,
    val via: String? = null,
) {
    companion object {
        /** Notification-bus category of the feedback addon's machine events. */
        const val CATEGORY: String = "feedback-event"
    }
}

/**
 * `feedback.top` entry.
 *
 * @property rank rank, starting at 1.
 * @property name tester name.
 * @property points bug points.
 */
@Serializable
data class BugTopEntry(val rank: Int = 0, val uuid: String? = null, val name: String = "", val points: Int = 0)

/**
 * Outcome of a feedback call made by the bot.
 *
 * @property ok whether the call succeeded.
 * @property code failure code (for example `cooldown`), `null` on success.
 * @property detail failure detail.
 * @property nr report number of a created report.
 */
data class BugCallResult(val ok: Boolean, val code: String? = null, val detail: String? = null, val nr: Long? = null)

/**
 * One file received from or sent to Discord.
 *
 * @property name file name.
 * @property mime MIME type.
 * @property bytes content.
 */
class BugFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * A Discord message relevant to the bug forum, decoupled from Kord.
 *
 * @property threadId thread (forum post) the message was sent in.
 * @property messageId message id; equals [threadId] for the starter message of a forum post.
 * @property parentId parent (forum) channel of the thread.
 * @property threadName thread title.
 * @property appliedTags forum tag ids applied to the thread.
 * @property authorId Discord user id of the author.
 * @property authorName Discord name of the author.
 * @property authorIsBot whether the author is a bot (ignored).
 * @property content message text.
 * @property files attachments as `(name, mime, url, size)`.
 */
data class BugMessage(
    val threadId: String,
    val messageId: String,
    val parentId: String,
    val threadName: String,
    val appliedTags: List<String>,
    val authorId: String,
    val authorName: String,
    val authorIsBot: Boolean,
    val content: String,
    val files: List<RemoteFile>,
) {
    /** Whether this is the starter message of a forum post. */
    val isStarter: Boolean get() = messageId == threadId

    /**
     * A file attached to a Discord message.
     *
     * @property name file name.
     * @property mime MIME type, if known.
     * @property url download URL.
     * @property size size in bytes.
     */
    data class RemoteFile(val name: String, val mime: String?, val url: String, val size: Int)
}
