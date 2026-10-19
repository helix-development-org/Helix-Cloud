package org.helix.addons.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A player reference carried on reports, comments and actions: the UUID identifies, the name is for display.
 *
 * @property uuid player UUID, `null` for system or dashboard actors.
 * @property name display name (player name or dashboard user).
 */
@Serializable
data class PlayerRef(val uuid: String? = null, val name: String = "")

/**
 * Metadata of a file attached to a report; the bytes are stored separately.
 *
 * @property id attachment id.
 * @property name file name.
 * @property mime MIME type, for example `image/jpeg`.
 * @property size size in bytes.
 * @property at upload time (epoch ms).
 */
@Serializable
data class Attachment(val id: String, val name: String, val mime: String, val size: Int, val at: Long)

/**
 * A bug report.
 *
 * @property nr sequential report number (#1234).
 * @property status workflow status, see [Statuses].
 * @property severity severity, see [Severities].
 * @property category category, see [Categories].
 * @property title short title.
 * @property description free text.
 * @property reporter reporting player, `null` for automatic reports.
 * @property context technical context (service, position, client, logs …).
 * @property assignee staff member responsible, if any.
 * @property duplicateOf number of the original report when marked as duplicate.
 * @property points points currently granted to the reporter for this report.
 * @property pointsGranted whether points were ever granted (first confirmation only).
 * @property source where the report came from, see [Sources].
 * @property hash deduplication hash of automatic reports.
 * @property count how often an automatic report occurred.
 * @property affected UUIDs of players affected by an automatic report.
 * @property attachments attached files.
 * @property discordThread id of the Discord thread mirroring the report, if any.
 * @property createdAt creation time (epoch ms).
 * @property updatedAt last change (epoch ms).
 */
@Serializable
data class Report(
    val nr: Long,
    val status: String,
    val severity: String,
    val category: String,
    val title: String,
    val description: String,
    val reporter: PlayerRef? = null,
    val context: JsonObject = JsonObject(emptyMap()),
    val assignee: String? = null,
    val duplicateOf: Long? = null,
    val points: Int = 0,
    val pointsGranted: Boolean = false,
    val source: String = Sources.INGAME,
    val hash: String? = null,
    val count: Int = 1,
    val affected: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val discordThread: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A comment on a report.
 *
 * @property id comment id.
 * @property nr report number.
 * @property author author.
 * @property staff whether the author commented as staff.
 * @property text comment text.
 * @property at time (epoch ms).
 */
@Serializable
data class Comment(val id: String, val nr: Long, val author: PlayerRef, val staff: Boolean, val text: String, val at: Long)

/**
 * One entry of a report's history.
 *
 * @property nr report number.
 * @property at time (epoch ms).
 * @property actor who caused it.
 * @property type event type (`created`, `status`, `assign`, `duplicate`, `comment`, `attach`, `auto`).
 * @property from previous value, if any.
 * @property to new value, if any.
 * @property note optional note.
 */
@Serializable
data class ReportEvent(
    val nr: Long,
    val at: Long,
    val actor: String,
    val type: String,
    val from: String? = null,
    val to: String? = null,
    val note: String? = null,
)

/**
 * An unread status update for a reporter.
 *
 * @property id notification id.
 * @property uuid recipient.
 * @property nr report number.
 * @property title report title.
 * @property status new status.
 * @property at time (epoch ms).
 */
@Serializable
data class Notification(val id: String, val uuid: String, val nr: Long, val title: String, val status: String, val at: Long)

/**
 * A reward waiting to be paid out (for example after the release reset).
 *
 * @property id reward id.
 * @property uuid recipient.
 * @property reward reward description (kronen, keys, items, note).
 * @property createdAt creation time (epoch ms).
 * @property claimedAt claim time, `null` while pending.
 */
@Serializable
data class PendingReward(
    val id: String,
    val uuid: String,
    val reward: JsonObject,
    val createdAt: Long,
    val claimedAt: Long? = null,
)

/**
 * Accumulated tester points.
 *
 * @property uuid player UUID.
 * @property name last known name.
 * @property total total points.
 */
@Serializable
data class Points(val uuid: String, val name: String, val total: Int)

/**
 * Accepted tester agreement.
 *
 * @property uuid player UUID.
 * @property version accepted agreement version.
 * @property acceptedAt time (epoch ms).
 */
@Serializable
data class Agreement(val uuid: String, val version: Int, val acceptedAt: Long)

/** Allowed severities. */
object Severities {
    /** Kleinigkeit. */
    const val MINOR: String = "minor"

    /** Störend. */
    const val ANNOYING: String = "annoying"

    /** Blockierend. */
    const val BLOCKING: String = "blocking"

    /** Absturz. */
    const val CRASH: String = "crash"

    /** Exploit – visible to staff and the reporter only. */
    const val EXPLOIT: String = "exploit"

    /** All severities, mildest first. */
    val ALL: List<String> = listOf(MINOR, ANNOYING, BLOCKING, CRASH, EXPLOIT)
}

/** Allowed categories. */
object Categories {
    /** All categories. */
    val ALL: List<String> = listOf("mineclash", "citybuild", "lobby", "network", "graphics", "other")
}

/** Report sources. */
object Sources {
    /** In-game `/bug`. */
    const val INGAME: String = "ingame"

    /** Discord forum thread. */
    const val DISCORD: String = "discord"

    /** Web panel. */
    const val PANEL: String = "panel"

    /** Automatic exception capture. */
    const val AUTO: String = "auto"

    /** All sources. */
    val ALL: List<String> = listOf(INGAME, DISCORD, PANEL, AUTO)
}
