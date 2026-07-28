package org.helix.addons.guard

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.AddonContext
import org.helix.api.message.Messages
import org.helix.api.storage.AddonStorage

/**
 * One anticheat violation reported by an IGuard Paper service.
 *
 * @property serverId Helix service id the violation happened on.
 * @property uuid player uuid.
 * @property name player name.
 * @property check failed check id, for example `movement.fly.a`.
 * @property vl violation level after this violation.
 * @property confidence cheat confidence, `0.0` to `1.0`.
 * @property epochMs violation timestamp, epoch milliseconds.
 * @property details free-form debug details of the check.
 */
@Serializable
data class GuardViolation(
    val serverId: String,
    val uuid: String,
    val name: String,
    val check: String,
    val vl: Double,
    val confidence: Double,
    val epochMs: Long,
    val details: String,
)

/**
 * One aggregated cheating incident (a burst of correlated violations)
 * reported by an IGuard Paper service.
 *
 * @property id unique incident id.
 * @property serverId Helix service id the incident happened on.
 * @property uuid player uuid.
 * @property name player name.
 * @property check dominant check id of the incident.
 * @property confidence cheat confidence, `0.0` to `1.0`.
 * @property epochMs incident timestamp, epoch milliseconds.
 * @property summary human readable one-line summary.
 */
@Serializable
data class GuardIncident(
    val id: String,
    val serverId: String,
    val uuid: String,
    val name: String,
    val check: String,
    val confidence: Double,
    val epochMs: Long,
    val summary: String,
    /** World uuid of the incident scene, for the replay terrain rebuild. */
    val world: String = "",
)

/**
 * One entry of the punishment audit log (bans, unbans and everything else
 * IGuard reports through `guard.store.punishment`).
 *
 * @property uuid player uuid.
 * @property name player name.
 * @property type punishment type, for example `ban` or `unban`.
 * @property reason punishment reason.
 * @property actor who issued the punishment, for example `IGuard` or a
 *   staff name.
 * @property hours duration in hours, `0` for permanent or not applicable.
 * @property epochMs punishment timestamp, epoch milliseconds.
 */
@Serializable
data class GuardPunishment(
    val uuid: String,
    val name: String,
    val type: String,
    val reason: String,
    val actor: String,
    val hours: Long,
    val epochMs: Long,
)

/**
 * One active network ban issued through `guard.store.ban`.
 *
 * @property uuid player uuid.
 * @property name player name at ban time.
 * @property reason ban reason.
 * @property actor who issued the ban.
 * @property epochMs ban timestamp, epoch milliseconds.
 * @property expiresAtEpochMs expiry timestamp, epoch milliseconds; `0` for
 *   a permanent ban.
 */
@Serializable
data class GuardBan(
    val uuid: String,
    val name: String,
    val reason: String,
    val actor: String,
    val epochMs: Long,
    val expiresAtEpochMs: Long,
)

/**
 * One tracked `replay.<incidentId>` write, backing retention pruning.
 *
 * @property incidentId the incident id (matches the `replay.<id>` document key).
 * @property epochMs when the replay was stored on the node.
 */
@Serializable
private data class ReplayIndexEntry(val incidentId: String, val epochMs: Long)

/** Wire payload of `guard.store.ban` — duration in hours, not yet an expiry. */
@Serializable
private data class GuardBanRequest(
    val uuid: String,
    val name: String,
    val reason: String,
    val actor: String,
    val hours: Long,
    val epochMs: Long,
)

/**
 * Anticheat persistence backed by the addon's document storage — the node
 * replacement for IGuard's former direct PostgreSQL access.
 *
 * Document layout (all values kotlinx-serialization JSON):
 * - `violations.<uuid>` — list of [GuardViolation], oldest first, capped at
 *   the newest [VIOLATION_CAP] entries per player AND pruned of anything
 *   older than [violationRetentionDays] (`history.retention-days`) —
 *   enforced lazily on every write, the same way ban expiry already is.
 * - `incidents.<uuid>` — list of [GuardIncident] per player, capped at
 *   [INCIDENT_CAP].
 * - `incidents.recent` — network-global ring of the newest incidents,
 *   capped at [RECENT_CAP].
 * - `replay.<incidentId>` — raw base64 replay payload of one incident.
 *   Unbounded per-entry storage is a real disk-exhaustion risk on a live
 *   server, so `replay.index` (a [ReplayIndexEntry] list, see [writeReplay])
 *   tracks every write and prunes both by [replayRetentionDays]
 *   (`detection.replay-retention-days`) and by [REPLAY_INDEX_CAP] as a hard
 *   backstop, deleting the pruned payload documents themselves (not just
 *   their index entry) so nothing leaks.
 * - `punishments` — list of [GuardPunishment], capped at [PUNISHMENT_CAP].
 * - `bans` — map of lowercase uuid to [GuardBan]; expired entries are
 *   pruned lazily on every ban lookup.
 *
 * All methods are synchronized because actions may be invoked concurrently.
 *
 * @property storage addon-scoped document store.
 * @property violationRetentionDays effective `history.retention-days`,
 *   read live so a config change applies without a restart.
 * @property replayRetentionDays effective `detection.replay-retention-days`,
 *   read live so a config change applies without a restart.
 * @property clock epoch millis source, injectable for tests.
 */
class GuardStore(
    private val storage: AddonStorage,
    private val violationRetentionDays: () -> Int = { DEFAULT_VIOLATION_RETENTION_DAYS },
    private val replayRetentionDays: () -> Int = { DEFAULT_REPLAY_RETENTION_DAYS },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Appends a violation to the player's violation log, pruning entries
     * older than [violationRetentionDays] in addition to the count cap.
     *
     * @param violation the reported violation.
     */
    @Synchronized
    fun addViolation(violation: GuardViolation) {
        val key = "violations.${violation.uuid.lowercase()}"
        val cutoff = clock() - violationRetentionDays().toLong() * DAY_MS
        val entries = (readList<GuardViolation>(key) + violation)
            .filter { it.epochMs >= cutoff }
            .takeLast(VIOLATION_CAP)
        storage.write(key, json.encodeToString(entries))
    }

    /**
     * Reads a player's newest violations.
     *
     * @param uuid player uuid, any case.
     * @param limit maximum number of entries, capped at [QUERY_LIMIT].
     * @return newest first, empty when the player has no violations.
     */
    @Synchronized
    fun violations(uuid: String, limit: Int): List<GuardViolation> =
        readList<GuardViolation>("violations.${uuid.lowercase()}")
            .asReversed()
            .take(limit.coerceIn(1, QUERY_LIMIT))

    /**
     * Appends an incident to the player's incident log and the global
     * recent-incidents ring.
     *
     * @param incident the reported incident.
     */
    @Synchronized
    fun addIncident(incident: GuardIncident) {
        val key = "incidents.${incident.uuid.lowercase()}"
        val entries = readList<GuardIncident>(key) + incident
        storage.write(key, json.encodeToString(entries.takeLast(INCIDENT_CAP)))
        val recent = readList<GuardIncident>(RECENT_DOCUMENT) + incident
        storage.write(RECENT_DOCUMENT, json.encodeToString(recent.takeLast(RECENT_CAP)))
    }

    /**
     * Reads a player's newest incidents.
     *
     * @param uuid player uuid, any case.
     * @param limit maximum number of entries, capped at [QUERY_LIMIT].
     * @return newest first, empty when the player has no incidents.
     */
    @Synchronized
    fun incidents(uuid: String, limit: Int): List<GuardIncident> =
        readList<GuardIncident>("incidents.${uuid.lowercase()}")
            .asReversed()
            .take(limit.coerceIn(1, QUERY_LIMIT))

    /**
     * Reads the newest incidents across the whole network.
     *
     * @param limit maximum number of entries, capped at [QUERY_LIMIT].
     * @return newest first, empty when nothing was reported yet.
     */
    @Synchronized
    fun recentIncidents(limit: Int): List<GuardIncident> =
        readList<GuardIncident>(RECENT_DOCUMENT)
            .asReversed()
            .take(limit.coerceIn(1, QUERY_LIMIT))

    /**
     * Stores (creates or replaces) the replay payload of an incident, then
     * prunes replays outside [replayRetentionDays]/[REPLAY_INDEX_CAP].
     *
     * @param incidentId the incident id.
     * @param payload base64 encoded replay data.
     */
    @Synchronized
    fun writeReplay(incidentId: String, payload: String) {
        storage.write("replay.$incidentId", payload)
        pruneReplays(incidentId)
    }

    /**
     * Reads the replay payload of an incident.
     *
     * @param incidentId the incident id.
     * @return the base64 payload, or `null` when no replay is stored.
     */
    @Synchronized
    fun replay(incidentId: String): String? = storage.read("replay.$incidentId")

    /**
     * Appends an entry to the punishment audit log.
     *
     * @param punishment the log entry.
     */
    @Synchronized
    fun addPunishment(punishment: GuardPunishment) {
        val entries = readList<GuardPunishment>(PUNISHMENTS_DOCUMENT) + punishment
        storage.write(PUNISHMENTS_DOCUMENT, json.encodeToString(entries.takeLast(PUNISHMENT_CAP)))
    }

    /**
     * Stores (creates or replaces) a player's network ban.
     *
     * @param ban the ban entry.
     */
    @Synchronized
    fun putBan(ban: GuardBan) {
        val bans = readBans().toMutableMap()
        bans[ban.uuid.lowercase()] = ban
        writeBans(bans)
    }

    /**
     * Removes a player's network ban.
     *
     * @param uuid player uuid, any case.
     * @return the removed ban, or `null` when the player was not banned.
     */
    @Synchronized
    fun removeBan(uuid: String): GuardBan? {
        val bans = readBans().toMutableMap()
        val removed = bans.remove(uuid.lowercase()) ?: return null
        writeBans(bans)
        return removed
    }

    /**
     * Looks up a player's unexpired ban, pruning expired entries.
     *
     * Matches by uuid first; when no uuid is known (offline proxies), falls
     * back to a case-insensitive name match.
     *
     * @param uuid player uuid, any case; `null` when unknown.
     * @param name player name, any case; `null` when only the uuid matters.
     * @return the active ban, or `null` when the player is not banned.
     */
    @Synchronized
    fun activeBan(uuid: String?, name: String?): GuardBan? {
        val bans = readBans()
        val now = System.currentTimeMillis()
        val active = bans.filterValues { it.expiresAtEpochMs == 0L || it.expiresAtEpochMs > now }
        if (active.size != bans.size) {
            writeBans(active)
        }
        uuid?.let { active[it.lowercase()] }?.let { return it }
        name ?: return null
        return active.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private inline fun <reified T> readList(key: String): List<T> =
        storage.read(key)?.let { json.decodeFromString<List<T>>(it) } ?: emptyList()

    private fun readBans(): Map<String, GuardBan> =
        storage.read(BANS_DOCUMENT)?.let { json.decodeFromString<Map<String, GuardBan>>(it) } ?: emptyMap()

    private fun writeBans(bans: Map<String, GuardBan>) {
        if (bans.isEmpty()) {
            storage.delete(BANS_DOCUMENT)
        } else {
            storage.write(BANS_DOCUMENT, json.encodeToString(bans))
        }
    }

    /**
     * Prunes `replay.<id>` payload documents outside [replayRetentionDays]
     * or beyond [REPLAY_INDEX_CAP], deleting both the index entry and the
     * payload document itself — the count cap alone (as every other capped
     * list here uses) would silently orphan payload documents forever,
     * since a replay isn't a JSON array element but its own keyed document.
     *
     * @param newId the incident id just written, indexed alongside the rest.
     */
    private fun pruneReplays(newId: String) {
        val now = clock()
        val cutoff = now - replayRetentionDays().toLong() * DAY_MS
        val index = readList<ReplayIndexEntry>(REPLAY_INDEX_DOCUMENT) + ReplayIndexEntry(newId, now)
        val fresh = index.filter { it.epochMs >= cutoff }
        val overflow = (fresh.size - REPLAY_INDEX_CAP).coerceAtLeast(0)
        val kept = fresh.drop(overflow)
        (index - kept.toSet()).forEach { storage.delete("replay.${it.incidentId}") }
        storage.write(REPLAY_INDEX_DOCUMENT, json.encodeToString(kept))
    }

    private companion object {
        /** Maximum violations kept per player, newest win. */
        const val VIOLATION_CAP = 500

        /** Maximum incidents kept per player, newest win. */
        const val INCIDENT_CAP = 100

        /** Maximum incidents kept in the global recent ring, newest win. */
        const val RECENT_CAP = 200

        /** Maximum punishment log entries, newest win. */
        const val PUNISHMENT_CAP = 500

        /** Maximum entries a single query returns. */
        const val QUERY_LIMIT = 100

        /** Document holding the global recent-incidents ring. */
        const val RECENT_DOCUMENT = "incidents.recent"

        /** Document holding the punishment audit log. */
        const val PUNISHMENTS_DOCUMENT = "punishments"

        /** Document holding the active bans, lowercase uuid to entry. */
        const val BANS_DOCUMENT = "bans"

        /** Document holding the [ReplayIndexEntry] list backing replay retention. */
        const val REPLAY_INDEX_DOCUMENT = "replay.index"

        /** Hard cap on tracked replay entries regardless of configured retention days. */
        const val REPLAY_INDEX_CAP = 500

        /** Milliseconds in a day, for retention-day math. */
        const val DAY_MS = 86_400_000L

        /** Fallback violation retention when the addon config carries no override. */
        const val DEFAULT_VIOLATION_RETENTION_DAYS = 30

        /** Fallback replay retention when the addon config carries no override. */
        const val DEFAULT_REPLAY_RETENTION_DAYS = 7
    }
}

/**
 * The `guard.store.*` / `guard.query.*` control-API actions consumed by the
 * IGuard Paper plugin, which persists through the node instead of talking
 * to PostgreSQL itself.
 *
 * Store actions answer `{"ok":true}`; query actions answer one compact JSON
 * line. Incidents additionally alert every online staff member holding
 * `iguard.alerts` via the generic `player.message` action, and bans kick
 * the player network-wide via `player.kick`.
 *
 * @property context node facilities of the guard addon.
 * @property store anticheat persistence.
 * @property messages localized guard messages (`alert`, `ban.screen`, …).
 */
class GuardStoreActions(
    private val context: AddonContext,
    private val store: GuardStore,
    private val messages: Messages,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Registers all `guard.store.*` and `guard.query.*` actions.
     */
    fun register() {
        register(
            "guard.store.violation",
            "Persists one anticheat violation reported by an IGuard service.",
            "guard.store.violation <json>",
        ) { invocation -> storeViolation(invocation) }
        register(
            "guard.store.incident",
            "Persists one anticheat incident and alerts online staff network-wide.",
            "guard.store.incident <json>",
        ) { invocation -> storeIncident(invocation) }
        register(
            "guard.store.replay",
            "Persists the base64 replay payload of an incident.",
            "guard.store.replay <incidentId> <base64>",
        ) { invocation -> storeReplay(invocation) }
        register(
            "guard.store.punishment",
            "Appends one entry to the anticheat punishment log.",
            "guard.store.punishment <json>",
        ) { invocation -> storePunishment(invocation) }
        register(
            "guard.store.ban",
            "Stores a network ban issued by IGuard and kicks the player.",
            "guard.store.ban <json>",
        ) { invocation -> storeBan(invocation) }
        register(
            "guard.store.unban",
            "Lifts an IGuard network ban.",
            "guard.store.unban <uuid> <name>",
        ) { invocation -> storeUnban(invocation) }
        register(
            "guard.query.activeban",
            "Answers whether a player has an unexpired IGuard ban.",
            "guard.query.activeban <uuid>",
        ) { invocation -> queryActiveBan(invocation) }
        register(
            "guard.query.history",
            "Reads a player's newest violations.",
            "guard.query.history <uuid> <limit>",
        ) { invocation -> queryHistory(invocation) }
        register(
            "guard.query.incidents",
            "Reads the newest incidents of a player or of the whole network.",
            "guard.query.incidents <uuid|all> <limit>",
        ) { invocation -> queryIncidents(invocation) }
        register(
            "guard.query.replay",
            "Reads the base64 replay payload of an incident.",
            "guard.query.replay <incidentId>",
        ) { invocation -> queryReplay(invocation) }
    }

    /**
     * Renders the localized disconnect screen of a ban.
     *
     * @param player receiving player name, used for language resolution.
     * @param ban the ban to render.
     * @return the MiniMessage disconnect screen text.
     */
    fun banScreen(player: String, ban: GuardBan): String {
        val expiry = if (ban.expiresAtEpochMs == 0L) {
            messages.formatFor(player, "ban.expiry.never")
        } else {
            formatDate(ban.expiresAtEpochMs)
        }
        return messages.formatFor(player, "ban.screen", "reason" to ban.reason, "expiry" to expiry)
    }

    private fun register(name: String, description: String, usage: String, handler: ActionHandler) {
        context.registerAction(ActionDescriptor(name, description, usage, bridgeInvocable = true), handler)
    }

    private fun storeViolation(invocation: ActionInvocation): ActionResult {
        val violation = parse<GuardViolation>(invocation)
            ?: return ActionResult.error("usage: guard.store.violation <json>")
        store.addViolation(violation)
        return ActionResult.ok(OK)
    }

    private fun storeIncident(invocation: ActionInvocation): ActionResult {
        val incident = parse<GuardIncident>(invocation)
            ?: return ActionResult.error("usage: guard.store.incident <json>")
        store.addIncident(incident)
        alertStaff(incident)
        return ActionResult.ok(OK)
    }

    private fun storeReplay(invocation: ActionInvocation): ActionResult {
        val incidentId = invocation.arguments.getOrNull(0)
        val payload = invocation.arguments.getOrNull(1)
        if (incidentId == null || payload == null) {
            return ActionResult.error("usage: guard.store.replay <incidentId> <base64>")
        }
        store.writeReplay(incidentId, payload)
        return ActionResult.ok(OK)
    }

    private fun storePunishment(invocation: ActionInvocation): ActionResult {
        val punishment = parse<GuardPunishment>(invocation)
            ?: return ActionResult.error("usage: guard.store.punishment <json>")
        store.addPunishment(punishment)
        return ActionResult.ok(OK)
    }

    private fun storeBan(invocation: ActionInvocation): ActionResult {
        val request = parse<GuardBanRequest>(invocation)
            ?: return ActionResult.error("usage: guard.store.ban <json>")
        val ban = GuardBan(
            uuid = request.uuid,
            name = request.name,
            reason = request.reason,
            actor = request.actor,
            epochMs = request.epochMs,
            expiresAtEpochMs = if (request.hours <= 0) 0 else request.epochMs + request.hours * 3_600_000,
        )
        store.putBan(ban)
        store.addPunishment(
            GuardPunishment(
                uuid = request.uuid,
                name = request.name,
                type = "ban",
                reason = request.reason,
                actor = request.actor,
                hours = request.hours,
                epochMs = request.epochMs,
            ),
        )
        context.actions.invoke(
            ActionInvocation("player.kick", listOf(ban.name, banScreen(ban.name, ban)), ActionSource.ADDON),
        )
        return ActionResult.ok(OK)
    }

    private fun storeUnban(invocation: ActionInvocation): ActionResult {
        val uuid = invocation.arguments.getOrNull(0)
        val name = invocation.arguments.getOrNull(1)
        if (uuid == null || name == null) {
            return ActionResult.error("usage: guard.store.unban <uuid> <name>")
        }
        store.removeBan(uuid)
        store.addPunishment(
            GuardPunishment(
                uuid = uuid,
                name = name,
                type = "unban",
                reason = "",
                actor = "",
                hours = 0,
                epochMs = System.currentTimeMillis(),
            ),
        )
        return ActionResult.ok(OK)
    }

    private fun queryActiveBan(invocation: ActionInvocation): ActionResult {
        val uuid = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: guard.query.activeban <uuid>")
        val ban = store.activeBan(uuid, name = null)
        val response = if (ban == null) {
            buildJsonObject { put("active", false) }
        } else {
            buildJsonObject {
                put("active", true)
                put("reason", ban.reason)
                put("expiresAtEpochMs", ban.expiresAtEpochMs)
            }
        }
        return ActionResult.ok(json.encodeToString(response))
    }

    private fun queryHistory(invocation: ActionInvocation): ActionResult {
        val uuid = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: guard.query.history <uuid> <limit>")
        val limit = invocation.arguments.getOrNull(1)?.toIntOrNull() ?: DEFAULT_LIMIT
        val response = buildJsonObject {
            put("violations", json.encodeToJsonElement(store.violations(uuid, limit)))
        }
        return ActionResult.ok(json.encodeToString(response))
    }

    private fun queryIncidents(invocation: ActionInvocation): ActionResult {
        val target = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: guard.query.incidents <uuid|all> <limit>")
        val limit = invocation.arguments.getOrNull(1)?.toIntOrNull() ?: DEFAULT_LIMIT
        val incidents = if (target.equals("all", ignoreCase = true)) {
            store.recentIncidents(limit)
        } else {
            store.incidents(target, limit)
        }
        val response = buildJsonObject {
            put("incidents", json.encodeToJsonElement(incidents))
        }
        return ActionResult.ok(json.encodeToString(response))
    }

    private fun queryReplay(invocation: ActionInvocation): ActionResult {
        val incidentId = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: guard.query.replay <incidentId>")
        val response = buildJsonObject { put("payload", store.replay(incidentId)) }
        return ActionResult.ok(json.encodeToString(response))
    }

    /**
     * Alerts every online staff member holding [ALERT_PERMISSION] about an
     * incident, in each receiver's own language.
     */
    private fun alertStaff(incident: GuardIncident) {
        val confidence = (incident.confidence * 100).roundToInt().toString()
        context.onlinePlayers()
            .filter { context.hasPermission(it.name, ALERT_PERMISSION) }
            .forEach { staff ->
                val text = messages.formatFor(
                    staff.name,
                    "alert",
                    "player" to incident.name,
                    "check" to incident.check,
                    "confidence" to confidence,
                    "server" to incident.serverId,
                )
                context.actions.invoke(
                    ActionInvocation("player.message", listOf(staff.name, text), ActionSource.ADDON),
                )
            }
    }

    private fun <T> parse(invocation: ActionInvocation, deserialize: (String) -> T): T? {
        val payload = invocation.arguments.joinToString(" ").trim()
        if (payload.isEmpty()) {
            return null
        }
        return runCatching { deserialize(payload) }.getOrNull()
    }

    private inline fun <reified T> parse(invocation: ActionInvocation): T? =
        parse(invocation) { json.decodeFromString<T>(it) }

    private fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private companion object {
        /** Compact response of every successful store action. */
        const val OK = """{"ok":true}"""

        /** Permission staff needs to receive incident alerts. */
        const val ALERT_PERMISSION = "iguard.alerts"

        /** Query limit used when the caller sends none. */
        const val DEFAULT_LIMIT = 100
    }
}
