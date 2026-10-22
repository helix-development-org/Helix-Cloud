package org.helix.addons.feedback.paper

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.wire.ServiceNodeApi
import java.util.UUID

/** Action names of the node contract (`helix-addon-feedback/ACTIONS.md`), kept in one place. */
object FeedbackActions {
    /** Create a report. */
    const val CREATE = "feedback.create"

    /** Automatic error report, deduplicated by hash. */
    const val AUTO = "feedback.auto"

    /** A report with comments and events. */
    const val GET = "feedback.get"

    /** Comment on a report. */
    const val COMMENT = "feedback.comment"

    /** Own reports. */
    const val MINE = "feedback.mine"

    /** Unread status notifications. */
    const val NOTIFICATIONS = "feedback.notifications"

    /** Mark notifications read. */
    const val NOTIFICATIONS_ACK = "feedback.notifications.ack"

    /** Points ranking. */
    const val TOP = "feedback.top"

    /** Pending rewards of a player. */
    const val REWARDS_PENDING = "feedback.rewards.pending"

    /** Redeems one pending reward. */
    const val REWARDS_REDEEM = "feedback.rewards.redeem"

    /** Test tool usage log. */
    const val TOOL_LOG = "feedback.toollog"

    /** One part of a chunked attachment upload. */
    const val ATTACH_CHUNK = "feedback.attach.chunk"

    /** Completes a chunked attachment upload. */
    const val ATTACH_FINISH = "feedback.attach.finish"

    /** Agreement state. */
    const val AGREEMENT_GET = "feedback.agreement.get"

    /** Accept the agreement. */
    const val AGREEMENT_ACCEPT = "feedback.agreement.accept"

    /** Economy deposit (test crowns). */
    const val ECO_DEPOSIT = "eco.api.deposit"
}

/**
 * Outcome of a node call.
 *
 * @property ok whether the action succeeded.
 * @property body `lines[0]` (JSON on success, the machine code on failure).
 * @property detail `lines[1]` on failure (English detail, e.g. seconds left for `cooldown`).
 */
data class NodeReply(val ok: Boolean, val body: String?, val detail: String?) {
    /** The machine error code, `unavailable` when the node was not reachable. */
    val code: String get() = if (ok) "" else body ?: "unavailable"

    /** Factory. */
    companion object {
        /** No answer from the node. */
        val UNAVAILABLE = NodeReply(false, "unavailable", null)

        /**
         * @param result the action result, null when the node was unreachable.
         * @return the reply.
         */
        fun of(result: ActionResult?): NodeReply =
            if (result == null) UNAVAILABLE else NodeReply(result.success, result.lines.getOrNull(0), result.lines.getOrNull(1))
    }
}

/**
 * A player reference of the contract.
 *
 * @property uuid player UUID, null for system authors.
 * @property name display name.
 */
@Serializable
data class PlayerRef(val uuid: String? = null, val name: String = "")

/**
 * A report as the plugin shows it (the contract's `Report` without fields the plugin does not need).
 *
 * @property nr report number.
 * @property status status wire id.
 * @property severity severity wire id.
 * @property category category wire id.
 * @property title title.
 * @property description description.
 * @property points points granted so far.
 * @property source `ingame`, `discord`, `panel` or `auto`.
 * @property createdAt creation time (epoch ms).
 * @property updatedAt last change (epoch ms).
 */
@Serializable
data class ReportView(
    val nr: Long,
    val status: String = "new",
    val severity: String = "minor",
    val category: String = "other",
    val title: String = "",
    val description: String = "",
    val points: Int = 0,
    val source: String = "ingame",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * A comment on a report.
 *
 * @property id comment id.
 * @property author author.
 * @property staff whether the author is staff.
 * @property text comment text.
 * @property at time (epoch ms).
 */
@Serializable
data class CommentView(val id: Long = 0, val author: PlayerRef = PlayerRef(), val staff: Boolean = false, val text: String = "", val at: Long = 0)

/**
 * `feedback.get` answer.
 *
 * @property report the report.
 * @property comments its comments, oldest first.
 */
@Serializable
data class ReportDetail(val report: ReportView, val comments: List<CommentView> = emptyList())

/**
 * An unread status notification.
 *
 * @property id notification id.
 * @property nr report number.
 * @property title report title.
 * @property status new status wire id.
 * @property at time (epoch ms).
 */
@Serializable
data class NotificationView(val id: Long, val nr: Long = 0, val title: String = "", val status: String = "new", val at: Long = 0)

/**
 * One ranking line.
 *
 * @property rank rank, 1-based.
 * @property name player name.
 * @property points points.
 */
@Serializable
data class TopEntry(val rank: Int = 0, val name: String = "", val points: Int = 0)

/**
 * Agreement state of a player.
 *
 * @property version current agreement version.
 * @property acceptedVersion the version the player accepted, null for none.
 * @property accepted whether the current version is accepted.
 */
@Serializable
data class AgreementState(val version: Int = 1, val acceptedVersion: Int? = null, val accepted: Boolean = false)

/**
 * Node client of the feedback paper component over the shared [ServiceNodeApi] transport. All calls block and
 * must run off the main thread.
 *
 * @param controlUrl primary control url.
 * @param token per-service bearer token.
 */
class FeedbackNodeClient(controlUrl: String, token: String) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val api = ServiceNodeApi(
        controlUrl,
        System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl,
        System.getenv("HELIX_SERVICE_ID").orEmpty(),
        token,
    ).also { it.start() }

    /**
     * Sends a new report.
     *
     * @param reporter the reporter.
     * @param draft the validated form.
     * @param context the `context` object.
     * @return the reply; on success the body is the created `Report`.
     */
    fun create(reporter: PlayerRef, draft: ReportDraft, context: JsonObject): NodeReply {
        val payload = buildJsonObject {
            putJsonObject("reporter") { put("uuid", reporter.uuid); put("name", reporter.name) }
            put("severity", draft.severity?.wire ?: Severity.MINOR.wire)
            put("category", draft.category.wire)
            put("title", draft.title)
            put("description", draft.description)
            put("source", "ingame")
            put("context", context)
        }
        return call(FeedbackActions.CREATE, payload.toString())
    }

    /**
     * Sends an automatic error report.
     *
     * @param hash stack trace hash.
     * @param title title.
     * @param description description (trace).
     * @param service this server's service id.
     * @param context optional context.
     * @param player optional affected player.
     * @return the reply.
     */
    fun auto(hash: String, title: String, description: String, service: String, context: JsonObject?, player: PlayerRef?): NodeReply {
        val payload = buildJsonObject {
            put("hash", hash)
            put("title", title)
            put("description", description)
            put("category", "other")
            put("service", service)
            if (context != null) put("context", context)
            if (player != null) putJsonObject("player") { put("uuid", player.uuid); put("name", player.name) }
        }
        return call(FeedbackActions.AUTO, payload.toString())
    }

    /**
     * @param uuid the player.
     * @param limit max. reports.
     * @return the player's own reports, newest first; null when the node is unreachable.
     */
    fun mine(uuid: UUID, limit: Int = 50): List<ReportView>? =
        call(FeedbackActions.MINE, uuid.toString(), limit.toString()).decode(ListSerializer(ReportView.serializer()))

    /**
     * @param nr report number.
     * @param viewer the viewing player.
     * @return the report with comments, or the failure reply's code.
     */
    fun get(nr: Long, viewer: PlayerRef): Pair<ReportDetail?, NodeReply> {
        val reply = call(FeedbackActions.GET, nr.toString(), buildJsonObject { put("uuid", viewer.uuid); put("name", viewer.name) }.toString())
        return reply.decode(ReportDetail.serializer()) to reply
    }

    /**
     * Adds a comment as the reporter.
     *
     * @param nr report number.
     * @param author the author.
     * @param text comment text.
     * @return the reply.
     */
    fun comment(nr: Long, author: PlayerRef, text: String): NodeReply = call(
        FeedbackActions.COMMENT,
        buildJsonObject {
            put("nr", nr)
            putJsonObject("author") { put("uuid", author.uuid); put("name", author.name) }
            put("text", text)
        }.toString(),
    )

    /**
     * @param uuid the player.
     * @return unread notifications; null when unreachable.
     */
    fun notifications(uuid: UUID): List<NotificationView>? =
        call(FeedbackActions.NOTIFICATIONS, uuid.toString()).decode(ListSerializer(NotificationView.serializer()))

    /**
     * Marks notifications read.
     *
     * @param uuid the player.
     * @param ids notification ids.
     */
    fun ack(uuid: UUID, ids: List<Long>) {
        if (ids.isNotEmpty()) call(FeedbackActions.NOTIFICATIONS_ACK, uuid.toString(), *ids.map(Long::toString).toTypedArray())
    }

    /**
     * @param limit lines.
     * @return the ranking; null when unreachable.
     */
    fun top(limit: Int = 10): List<TopEntry>? = call(FeedbackActions.TOP, limit.toString()).decode(ListSerializer(TopEntry.serializer()))

    /**
     * @param uuid the player.
     * @return the agreement state; null when unreachable.
     */
    fun agreement(uuid: UUID): AgreementState? = call(FeedbackActions.AGREEMENT_GET, uuid.toString()).decode(AgreementState.serializer())

    /**
     * @param uuid the player.
     * @param version the accepted version.
     * @return the reply.
     */
    fun accept(uuid: UUID, version: Int): NodeReply = call(FeedbackActions.AGREEMENT_ACCEPT, uuid.toString(), version.toString())

    /**
     * Books test crowns with the network-wide daily limit.
     *
     * @param uuid the player.
     * @param amount crowns.
     * @param dailyLimit daily limit of the `feedback.testkronen` counter.
     * @return the reply (body = new balance; failure `limit reached: n`).
     */
    fun testKronen(uuid: UUID, amount: Long, dailyLimit: Long): NodeReply =
        call(FeedbackActions.ECO_DEPOSIT, uuid.toString(), amount.toString(), "feedback.testkronen", "-", "feedback.testkronen", dailyLimit.toString())

    /**
     * Uploads an attachment in parts (`feedback.attach.chunk` …, then `feedback.attach.finish`).
     *
     * @param nr report number.
     * @param viewer the reporter (must own the report).
     * @param name file name.
     * @param mime MIME type.
     * @param bytes the data.
     * @return the reply of the finishing call (or the first failing chunk).
     */
    fun attach(nr: Long, viewer: PlayerRef, name: String, mime: String, bytes: ByteArray): NodeReply {
        val uploadId = UUID.randomUUID().toString()
        val parts = ScreenshotUploads.parts(bytes)
        parts.forEachIndexed { index, part ->
            val reply = call(FeedbackActions.ATTACH_CHUNK, uploadId, index.toString(), parts.size.toString(), part)
            if (!reply.ok) return reply
        }
        val viewerJson = buildJsonObject { put("uuid", viewer.uuid); put("name", viewer.name) }.toString()
        return call(FeedbackActions.ATTACH_FINISH, uploadId, nr.toString(), name, mime, viewerJson)
    }

    /**
     * @param uuid the player.
     * @return the player's unclaimed rewards as raw JSON objects, null when the node was unreachable.
     */
    fun pendingRewards(uuid: UUID): List<JsonObject>? {
        val reply = call(FeedbackActions.REWARDS_PENDING, uuid.toString())
        if (!reply.ok) return null
        return runCatching { json.parseToJsonElement(reply.body ?: "[]") as kotlinx.serialization.json.JsonArray }
            .getOrNull()?.mapNotNull { it as? JsonObject }
    }

    /**
     * Redeems a reward (network part: the node pays crowns and permissions).
     *
     * @param uuid the player.
     * @param id reward id.
     * @param name the player's name (for permission grants).
     * @return the reply.
     */
    fun redeemReward(uuid: UUID, id: String, name: String): NodeReply = call(FeedbackActions.REWARDS_REDEEM, uuid.toString(), id, name)

    /**
     * Records one test tool use on the node (`feedback.toollog`).
     *
     * @param uuid the tester.
     * @param entry JSON `{name, action, details, service}`.
     * @return the reply.
     */
    fun toolLog(uuid: UUID, entry: JsonObject): NodeReply = call(FeedbackActions.TOOL_LOG, uuid.toString(), entry.toString())

    /**
     * @return the published bridge values (for `feedback.settings`), null when unreachable.
     */
    fun bridgeValues(): Map<String, String>? = api.bridgeValues()

    /** Closes the transport. */
    fun close() {
        api.close()
    }

    private fun call(action: String, vararg args: String): NodeReply =
        NodeReply.of(runCatching { api.action(ActionInvocation(action, args.toList())) }.getOrNull())

    private fun <T> NodeReply.decode(serializer: kotlinx.serialization.KSerializer<T>): T? =
        if (!ok) null else body?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
}
