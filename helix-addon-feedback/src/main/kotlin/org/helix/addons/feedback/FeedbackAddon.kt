package org.helix.addons.feedback

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.helix.addon.sdk.AddonBase
import org.helix.addons.feedback.store.DocumentFeedbackStore
import org.helix.addons.feedback.store.FeedbackStore
import org.helix.addons.feedback.store.PostgresFeedbackStore
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.message.Messages
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.Base64

/**
 * Closed test phase of KronlandMC: bug reports with context, logs and attachments, a staff workflow, tester
 * points and pending release rewards. The action contract is documented in `ACTIONS.md` of this module.
 *
 * @param storeFactory optional store override (tests); by default PostgreSQL when the node uses it,
 *   otherwise the addon document storage.
 * @param clock time source.
 */
class FeedbackAddon(
    private val storeFactory: (() -> FeedbackStore)? = null,
    private val clock: Clock = Clock.systemUTC(),
) : AddonBase() {
    private val logger = LoggerFactory.getLogger(FeedbackAddon::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var store: FeedbackStore
    private lateinit var messages: Messages

    /** The service behind the actions (exposed for tests and sibling components). */
    lateinit var service: FeedbackService
        private set

    override fun enable() {
        messages = loadMessages()
        store = storeFactory?.invoke() ?: openStore()
        service = FeedbackService(
            store = store,
            clock = clock,
            logs = ::serviceLogs,
            notify = { category, key, params ->
                runCatching { context.publishNotification(category, messages.format(key, params)) }
            },
            events = { event ->
                runCatching { context.publishNotification(FeedbackEvent.CATEGORY, json.encodeToString(FeedbackEvent.serializer(), event)) }
            },
        )
        registerActions()
        publishSettings(service.settings())
        panel("feedback", "Bugs", "/panel.html")
    }

    override fun onDisable() {
        if (::store.isInitialized) runCatching { store.close() }
    }

    private fun openStore(): FeedbackStore {
        val connection = context.storageConnection()
        if (connection != null && connection.mode.equals("postgres", ignoreCase = true)) {
            return runCatching { PostgresFeedbackStore(connection) }
                .onFailure { logger.warn("PostgreSQL feedback store unavailable, using document store: {}", it.message) }
                .getOrElse { DocumentFeedbackStore(context.storage()) }
        }
        return DocumentFeedbackStore(context.storage())
    }

    private fun serviceLogs(service: String): List<String> {
        val result = runCatching { context.actions.invoke(ActionInvocation("service.logs", listOf(service, LOG_TAIL.toString()), ActionSource.ADDON)) }
            .getOrNull() ?: return emptyList()
        if (!result.success) return emptyList()
        return result.lines.filterNot { it.startsWith("no logs for ") }
    }

    private fun registerActions() {
        action("feedback.create", "Creates a bug report.", "feedback.create <json>", bridgeInvocable = true) { inv ->
            val body = body(inv, 0) ?: return@action bad("usage: feedback.create <json>")
            val reporter = ref(body["reporter"])
            val viewer = viewer(inv, reporter, asPlayer(body))
            val input = NewReport(
                reporter = reporter,
                severity = body.str("severity").orEmpty(),
                category = body.str("category").orEmpty(),
                title = body.str("title").orEmpty(),
                description = body.str("description").orEmpty(),
                source = body.str("source") ?: if (inv.source == ActionSource.BRIDGE) Sources.INGAME else Sources.PANEL,
                context = body["context"] as? JsonObject ?: JsonObject(emptyMap()),
            )
            reply(service.create(input, viewer)) { encode(Report.serializer(), it) }
        }
        action("feedback.auto", "Records an automatic exception report.", "feedback.auto <json>", bridgeInvocable = true) { inv ->
            val body = body(inv, 0) ?: return@action bad("usage: feedback.auto <json>")
            val input = AutoReport(
                hash = body.str("hash").orEmpty(),
                title = body.str("title").orEmpty(),
                description = body.str("description").orEmpty(),
                category = body.str("category") ?: "network",
                severity = body.str("severity") ?: Severities.ANNOYING,
                context = (body["context"] as? JsonObject ?: JsonObject(emptyMap())).let { ctx ->
                    body.str("service")?.let { JsonObject(ctx + ("service" to JsonPrimitive(it))) } ?: ctx
                },
                player = ref(body["player"]),
            )
            reply(service.auto(input)) { (report, new) ->
                buildJsonObject { put("nr", report.nr); put("new", new); put("count", report.count) }.toString()
            }
        }
        action("feedback.list", "Lists bug reports.", "feedback.list [json]", bridgeInvocable = true) { inv ->
            val body = body(inv, 0) ?: JsonObject(emptyMap())
            val viewer = viewer(inv, ref(body["viewer"]))
            if (inv.source == ActionSource.BRIDGE && viewer.ref?.uuid == null && !viewer.staff) return@action fail("forbidden", "viewer required")
            val filter = ListFilter(
                status = body.strings("status"),
                severity = body.strings("severity"),
                category = body.strings("category"),
                reporter = body.str("reporter"),
                query = body.str("query"),
                limit = (body["limit"] as? JsonPrimitive)?.intOrNull ?: 50,
                offset = (body["offset"] as? JsonPrimitive)?.intOrNull ?: 0,
                sort = body.str("sort") ?: "new",
            )
            val (total, items) = service.list(filter, viewer)
            ActionResult.ok(
                buildJsonObject {
                    put("total", total)
                    put("items", json.encodeToJsonElement(ListSerializer(Report.serializer()), items))
                }.toString(),
            )
        }
        action("feedback.get", "Shows one bug report.", "feedback.get <nr> [viewerJson]", bridgeInvocable = true) { inv ->
            val nr = number(inv, 0) ?: return@action bad("usage: feedback.get <nr> [viewerJson]")
            val viewer = viewer(inv, ref(body(inv, 1)))
            reply(service.get(nr, viewer)) { d ->
                buildJsonObject {
                    put("report", json.encodeToJsonElement(Report.serializer(), d.report))
                    put("comments", json.encodeToJsonElement(ListSerializer(Comment.serializer()), d.comments))
                    put("events", json.encodeToJsonElement(ListSerializer(ReportEvent.serializer()), d.events))
                }.toString()
            }
        }
        action("feedback.comment", "Comments on a bug report.", "feedback.comment <json>", bridgeInvocable = true) { inv ->
            val body = body(inv, 0) ?: return@action bad("usage: feedback.comment <json>")
            val nr = (body["nr"] as? JsonPrimitive)?.longOrNull ?: return@action bad("nr required")
            val viewer = viewer(inv, ref(body["author"]), asPlayer(body))
            reply(service.comment(nr, body.str("text").orEmpty(), viewer, body.str("via"))) { encode(Comment.serializer(), it) }
        }
        action("feedback.status", "Changes the status of a bug report.", "feedback.status <json>", bridgeInvocable = true) { inv ->
            val body = body(inv, 0) ?: return@action bad("usage: feedback.status <json>")
            val nr = (body["nr"] as? JsonPrimitive)?.longOrNull ?: return@action bad("nr required")
            reply(service.status(nr, body.str("status").orEmpty(), viewer(inv, ref(body["actor"])), body.str("note"))) {
                encode(Report.serializer(), it)
            }
        }
        action("feedback.assign", "Assigns a bug report.", "feedback.assign <json>", bridgeInvocable = true) { inv ->
            val body = body(inv, 0) ?: return@action bad("usage: feedback.assign <json>")
            val nr = (body["nr"] as? JsonPrimitive)?.longOrNull ?: return@action bad("nr required")
            reply(service.assign(nr, body.str("assignee"), viewer(inv, ref(body["actor"])))) { encode(Report.serializer(), it) }
        }
        action("feedback.duplicate", "Marks a bug report as duplicate.", "feedback.duplicate <json>", bridgeInvocable = true) { inv ->
            val body = body(inv, 0) ?: return@action bad("usage: feedback.duplicate <json>")
            val nr = (body["nr"] as? JsonPrimitive)?.longOrNull ?: return@action bad("nr required")
            val of = (body["of"] as? JsonPrimitive)?.longOrNull ?: return@action bad("of required")
            reply(service.duplicate(nr, of, viewer(inv, ref(body["actor"])))) { encode(Report.serializer(), it) }
        }
        action("feedback.attach", "Attaches a file to a bug report.", "feedback.attach <nr> <name> <mime> <base64> [viewerJson]", bridgeInvocable = true) { inv ->
            val nr = number(inv, 0) ?: return@action bad("usage: feedback.attach <nr> <name> <mime> <base64> [viewerJson]")
            val bytes = decode(inv.arguments.getOrNull(3)) ?: return@action fail("invalid_attachment", "base64 expected")
            val extra = body(inv, 4)
            val viewer = viewer(inv, ref(extra), extra?.let(::asPlayer) ?: false)
            reply(service.attach(nr, inv.arguments.getOrNull(1).orEmpty(), inv.arguments.getOrNull(2).orEmpty(), bytes, viewer, extra?.str("via"))) {
                encode(Attachment.serializer(), it)
            }
        }
        action("feedback.attach.chunk", "Receives one chunk of an attachment upload.", "feedback.attach.chunk <uploadId> <index> <count> <base64>", bridgeInvocable = true) { inv ->
            val index = inv.arguments.getOrNull(1)?.toIntOrNull()
            val count = inv.arguments.getOrNull(2)?.toIntOrNull()
            val bytes = decode(inv.arguments.getOrNull(3))
            if (index == null || count == null || bytes == null) return@action bad("usage: feedback.attach.chunk <uploadId> <index> <count> <base64>")
            reply(service.chunk(inv.arguments[0], index, count, bytes)) { buildJsonObject { put("received", it) }.toString() }
        }
        action("feedback.attach.finish", "Completes a chunked attachment upload.", "feedback.attach.finish <uploadId> <nr> <name> <mime> [viewerJson]", bridgeInvocable = true) { inv ->
            val nr = number(inv, 1) ?: return@action bad("usage: feedback.attach.finish <uploadId> <nr> <name> <mime> [viewerJson]")
            val extra = body(inv, 4)
            val viewer = viewer(inv, ref(extra), extra?.let(::asPlayer) ?: false)
            reply(service.finish(inv.arguments[0], nr, inv.arguments.getOrNull(2).orEmpty(), inv.arguments.getOrNull(3).orEmpty(), viewer, extra?.str("via"))) {
                encode(Attachment.serializer(), it)
            }
        }
        action("feedback.attachment", "Returns an attachment (mime, base64).", "feedback.attachment <id>") { inv ->
            val id = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.attachment <id>")
            val (mime, bytes) = service.attachment(id) ?: return@action fail("not_found", "attachment $id not found")
            ActionResult.ok(mime, Base64.getEncoder().encodeToString(bytes))
        }
        action("feedback.discord.link", "Links a bug report to its Discord thread.", "feedback.discord.link <nr> <threadId|->") { inv ->
            val nr = number(inv, 0) ?: return@action bad("usage: feedback.discord.link <nr> <threadId|->")
            val thread = inv.arguments.getOrNull(1)?.takeIf { it != "-" }.orEmpty()
            reply(service.linkDiscord(nr, thread)) { encode(Report.serializer(), it) }
        }
        action("feedback.discord.find", "Finds the bug report mirrored by a Discord thread.", "feedback.discord.find <threadId>") { inv ->
            val thread = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.discord.find <threadId>")
            val report = service.byDiscordThread(thread) ?: return@action fail("not_found", "no report for thread $thread")
            ActionResult.ok(encode(Report.serializer(), report))
        }
        action("feedback.mine", "Lists the reports of a player.", "feedback.mine <uuid> [limit]", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.mine <uuid> [limit]")
            val limit = inv.arguments.getOrNull(1)?.toIntOrNull() ?: 50
            ActionResult.ok(json.encodeToString(ListSerializer(Report.serializer()), service.mine(uuid, limit)))
        }
        action("feedback.notifications", "Lists unread report updates of a player.", "feedback.notifications <uuid>", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.notifications <uuid>")
            ActionResult.ok(json.encodeToString(ListSerializer(Notification.serializer()), service.notifications(uuid)))
        }
        action("feedback.notifications.ack", "Marks report updates as read.", "feedback.notifications.ack <uuid> [id ...]", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.notifications.ack <uuid> [id ...]")
            ActionResult.ok(buildJsonObject { put("acked", service.ack(uuid, inv.arguments.drop(1).toSet())) }.toString())
        }
        action("feedback.top", "Tester ranking by points.", "feedback.top [limit]", bridgeInvocable = true) { inv ->
            val limit = inv.arguments.getOrNull(0)?.toIntOrNull() ?: 10
            val rows = service.top(limit).mapIndexed { i, p ->
                buildJsonObject { put("rank", i + 1); put("uuid", p.uuid); put("name", p.name); put("points", p.total) }
            }
            ActionResult.ok(JsonArray(rows).toString())
        }
        action("feedback.agreement.get", "Shows the tester agreement state of a player.", "feedback.agreement.get <uuid>", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.agreement.get <uuid>")
            val (version, accepted) = service.agreement(uuid)
            ActionResult.ok(
                buildJsonObject {
                    put("version", version)
                    put("acceptedVersion", accepted)
                    put("accepted", accepted != null && accepted >= version)
                }.toString(),
            )
        }
        action("feedback.agreement.accept", "Records the acceptance of the tester agreement.", "feedback.agreement.accept <uuid> [version]", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.agreement.accept <uuid> [version]")
            val agreement = service.accept(uuid, inv.arguments.getOrNull(1)?.toIntOrNull())
            ActionResult.ok(buildJsonObject { put("accepted", true); put("version", agreement.version) }.toString())
        }
        action("feedback.settings", "Shows or patches the feedback settings.", "feedback.settings [json]", permission = "feedback.admin") { inv ->
            val patch = body(inv, 0)
            val settings = if (patch == null) service.settings() else when (val r = service.patchSettings(patch)) {
                is Outcome.Ok -> r.value.also { publishSettings(it) }
                is Outcome.Fail -> return@action fail(r.code, r.detail)
            }
            ActionResult.ok(encode(FeedbackSettings.serializer(), settings))
        }
        action("feedback.rewards.table", "Shows or replaces the release reward table.", "feedback.rewards.table [json]", permission = "feedback.admin") { inv ->
            val raw = inv.arguments.joinToString(" ").trim()
            val settings = if (raw.isEmpty()) {
                service.settings()
            } else {
                val table = runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull()
                    ?.mapNotNull { it as? JsonObject } ?: return@action fail("invalid_table", "JSON array of objects expected")
                service.setRewardTable(table)
            }
            ActionResult.ok(JsonArray(settings.rewards).toString())
        }
        action("feedback.rewards.pending", "Lists unclaimed rewards of a player.", "feedback.rewards.pending <uuid>", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.rewards.pending <uuid>")
            ActionResult.ok(json.encodeToString(ListSerializer(PendingReward.serializer()), service.pending(uuid)))
        }
        action("feedback.rewards.grant", "Queues a reward for a player.", "feedback.rewards.grant <uuid> <json>", permission = "feedback.admin") { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.rewards.grant <uuid> <json>")
            val reward = body(inv, 1) ?: return@action bad("reward JSON object required")
            ActionResult.ok(encode(PendingReward.serializer(), service.grant(uuid, reward)))
        }
        action("feedback.rewards.claim", "Marks a reward as claimed.", "feedback.rewards.claim <uuid> <id>", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0)
            val id = inv.arguments.getOrNull(1)
            if (uuid == null || id == null) return@action bad("usage: feedback.rewards.claim <uuid> <id>")
            reply(service.claim(uuid, id)) { encode(PendingReward.serializer(), it) }
        }
        action("feedback.toollog", "Records the use of a tester tool.", "feedback.toollog <uuid> <json>", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action bad("usage: feedback.toollog <uuid> <json>")
            val body = body(inv, 1) ?: return@action bad("usage: feedback.toollog <uuid> <json>")
            reply(service.toolLog(uuid, body)) { encode(ToolLogEntry.serializer(), it) }
        }
        action("feedback.toollog.list", "Lists tester tool usage.", "feedback.toollog.list [uuid|-] [limit]") { inv ->
            val uuid = inv.arguments.getOrNull(0)?.takeIf { it != "-" && it.isNotBlank() }
            val limit = inv.arguments.getOrNull(1)?.toIntOrNull() ?: 100
            ActionResult.ok(json.encodeToString(ListSerializer(ToolLogEntry.serializer()), service.toolLogs(uuid, limit)))
        }
        action("feedback.export", "Summary of reports and points for the panel.", "feedback.export") { _ ->
            val all = service.all()
            ActionResult.ok(
                buildJsonObject {
                    put("total", all.size)
                    put("byStatus", JsonObject(all.groupingBy { it.status }.eachCount().mapValues { JsonPrimitive(it.value) }))
                    put("bySeverity", JsonObject(all.groupingBy { it.severity }.eachCount().mapValues { JsonPrimitive(it.value) }))
                    put("top", json.encodeToJsonElement(ListSerializer(Points.serializer()), service.top(50)))
                }.toString(),
            )
        }
    }

    /**
     * Publishes the settings Paper components read (everything except the reward table) as bridge value
     * `feedback.settings`.
     */
    private fun publishSettings(settings: FeedbackSettings) {
        val full = json.encodeToJsonElement(FeedbackSettings.serializer(), settings) as JsonObject
        runCatching { context.publishBridgeValue(SETTINGS_KEY, JsonObject(full - "rewards").toString()) }
    }

    /**
     * Builds the caller of a bridge call from the payload; every other source is staff.
     */
    private fun viewer(inv: ActionInvocation, ref: PlayerRef?, asPlayer: Boolean = false): Viewer {
        if (inv.source != ActionSource.BRIDGE && !(asPlayer && ref != null)) {
            return Viewer(ref ?: PlayerRef(null, inv.actor ?: "dashboard"), true)
        }
        val staff = ref?.name?.takeIf { it.isNotBlank() }?.let { runCatching { context.hasPermission(it, STAFF) }.getOrDefault(false) } ?: false
        return Viewer(ref, staff)
    }

    /** `asPlayer: true` lets an addon (the Discord bot) act with the rights and limits of the named player. */
    private fun asPlayer(body: JsonObject): Boolean = (body["asPlayer"] as? JsonPrimitive)?.booleanOrNull == true

    private fun ref(element: kotlinx.serialization.json.JsonElement?): PlayerRef? {
        val obj = element as? JsonObject ?: return null
        val uuid = obj.str("uuid")
        val name = obj.str("name").orEmpty()
        return if (uuid == null && name.isBlank()) null else PlayerRef(uuid, name)
    }

    private fun body(inv: ActionInvocation, index: Int): JsonObject? {
        val raw = inv.arguments.drop(index).joinToString(" ")
        if (raw.isBlank()) return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun number(inv: ActionInvocation, index: Int): Long? = inv.arguments.getOrNull(index)?.removePrefix("#")?.toLongOrNull()

    private fun decode(raw: String?): ByteArray? = raw?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    private fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private fun <T> reply(outcome: Outcome<T>, render: (T) -> String): ActionResult = when (outcome) {
        is Outcome.Ok -> ActionResult.ok(render(outcome.value))
        is Outcome.Fail -> fail(outcome.code, outcome.detail)
    }

    private fun fail(code: String, detail: String) = ActionResult.error(code, detail)

    private fun bad(usage: String) = ActionResult.error("usage", usage)

    private companion object {
        const val STAFF = "feedback.staff"
        const val LOG_TAIL = 2000
        const val SETTINGS_KEY = "feedback.settings"
    }
}
