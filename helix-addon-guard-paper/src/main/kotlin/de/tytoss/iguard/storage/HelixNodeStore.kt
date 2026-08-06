package de.tytoss.iguard.storage

import de.tytoss.iguard.config.HistoryConfig
import de.tytoss.iguard.model.EvidenceFamily
import de.tytoss.iguard.model.IncidentRecord
import de.tytoss.iguard.model.IncidentSnapshot
import de.tytoss.iguard.model.OutboxEvent
import de.tytoss.iguard.model.ReplayRecord
import de.tytoss.iguard.model.SanctionRecord
import de.tytoss.iguard.model.ViolationRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bukkit.Bukkit
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import org.helix.api.action.ActionInvocation
import org.helix.wire.ServiceNodeApi
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One node action waiting in the async write queue: `POST {action, arguments}`. */
private data class NodeAction(val action: String, val arguments: List<String>)

/**
 * Database-less [GuardStore] backend for Helix-Cloud deployments: every write becomes a `guard.store.*`
 * node action and every read a `guard.query.*` action, both invoked over the node's control HTTP API
 * (`POST <url>/api/v1/internal/action` with the per-service bearer token; the first response line carries a
 * compact-JSON payload for queries). This bridge endpoint — not `/api/v1/actions`, which only ever
 * accepts the admin token or a `helix.admin` session — is what a per-service token can actually call;
 * the node only lets it reach actions explicitly marked `bridgeInvocable` (see
 * `GuardStoreActions.register`). Writes go through a bounded queue drained by a virtual-thread flusher
 * that retries the current action until the node is reachable again.
 *
 * Intentional gaps, because the node owns enforcement and analytics:
 *  - outbox events, sanction records and network-ban rows are no-ops (logged once) — the node enforces
 *    bans network-wide (kick + join gate) itself, so no Velocity outbox polling is needed;
 *  - [activeBans], [banHistory] and [findPlayer] return empty results — the
 *    Helix panel takes over those views and the node contract exposes no matching query;
 *  - [incidentWorld] resolves through the incident's recorded world uuid; replays rebuild without
 *    the terrain paste.
 */
class HelixNodeStore(
    controlUrl: String,
    private val controlToken: String,
    private val history: HistoryConfig,
    private val logger: Logger
) : GuardStore {
    private val api = ServiceNodeApi(
        controlUrl,
        System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl,
        System.getenv("HELIX_SERVICE_ID").orEmpty(),
        controlToken,
    ).also { it.start() }
    private val json = Json { ignoreUnknownKeys = true }
    private val queue = ArrayBlockingQueue<NodeAction>(history.queueCapacity)
    private val available = AtomicBoolean(true)
    private val dropped = AtomicLong()
    private val written = AtomicLong()
    private val queued = AtomicInteger()
    private val writerRunning = AtomicBoolean(false)
    private var writerThread: Thread? = null
    private val outboxNoticed = AtomicBoolean(false)
    private val sanctionNoticed = AtomicBoolean(false)
    private val networkBanNoticed = AtomicBoolean(false)
    private val banListNoticed = AtomicBoolean(false)

    init {
        require(controlUrl.isNotBlank()) { "Helix storage requires the HELIX_CONTROL_URL environment variable" }
    }

    override fun start() {
        writerRunning.set(true)
        writerThread = Thread.ofVirtual().name("iguard-helix-writer").start {
            try {
                writerLoop()
            } catch (error: Throwable) {
                logger.severe("Helix node writer stopped unexpectedly: ${error.stackTraceToString()}")
            }
        }
    }

    override suspend fun stopAndFlush(timeoutMillis: Long) {
        writerRunning.set(false)
        writerThread?.interrupt()
        withContext(Dispatchers.IO) { writerThread?.join(timeoutMillis) }
    }

    override fun close() {
        api.close()
    }

    // --- Async writes (queued node actions) ---

    override fun enqueue(record: ViolationRecord, incident: IncidentRecord?): Boolean {
        val incidentAccepted = incident?.let { enqueueIncident(it) } ?: true
        return enqueueAction("guard.store.violation", listOf(violationJson(record))) && incidentAccepted
    }

    override fun enqueueIncident(record: IncidentRecord): Boolean =
        enqueueAction("guard.store.incident", listOf(incidentJson(record)))

    override fun enqueueReplay(record: ReplayRecord, incident: IncidentRecord?): Boolean {
        val incidentAccepted = incident?.let { enqueueIncident(it) } ?: true
        val payload = if (record.compression == "gzip") record.payload else gzip(record.payload)
        val encoded = Base64.getEncoder().encodeToString(payload)
        return enqueueAction("guard.store.replay", listOf(record.incidentId.toString(), encoded)) && incidentAccepted
    }

    /** No-op: proxy outbox polling is replaced by node-side enforcement/broadcasts in helix mode. */
    override fun enqueueOutbox(event: OutboxEvent): Boolean {
        if (outboxNoticed.compareAndSet(false, true)) logger.info("Helix storage: outbox events are handled node-side; skipping local outbox writes")
        return true
    }

    /** No-op: the node action contract has no sanction log; sanction context ships via incidents/punishments. */
    override fun enqueueSanction(record: SanctionRecord): Boolean {
        if (sanctionNoticed.compareAndSet(false, true)) logger.info("Helix storage: sanction records are covered by incident/punishment actions; skipping")
        return true
    }

    /** No-op: network bans are issued through guard.store.ban and enforced by the node itself. */
    override fun enqueueNetworkBan(playerId: UUID, playerName: String, reason: String, expiresAt: Long?): Boolean {
        if (networkBanNoticed.compareAndSet(false, true)) logger.info("Helix storage: network bans are enforced node-side via guard.store.ban; skipping local ban table")
        return true
    }

    override fun enqueuePunishment(playerId: UUID, playerName: String, type: String, hours: Int?, reason: String, actor: String): Boolean {
        val payload = buildJsonObject {
            put("uuid", playerId.toString())
            put("name", playerName.lowercase())
            put("type", type.lowercase())
            put("reason", reason)
            put("actor", actor)
            put("hours", hours ?: 0)
            put("epochMs", System.currentTimeMillis())
        }
        return enqueueAction("guard.store.punishment", listOf(payload.toString()))
    }

    /** Queues a network-wide ban on the node (guard.store.ban; 0 hours = permanent). Node kicks + gates. */
    fun submitBan(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String): Boolean {
        val payload = buildJsonObject {
            put("uuid", playerId.toString())
            put("name", playerName.lowercase())
            put("reason", reason)
            put("actor", actor)
            put("hours", hours)
            put("epochMs", System.currentTimeMillis())
        }
        return enqueueAction("guard.store.ban", listOf(payload.toString()))
    }

    /** Queues a network-wide unban on the node (guard.store.unban). */
    fun submitUnban(playerId: UUID, playerName: String): Boolean =
        enqueueAction("guard.store.unban", listOf(playerId.toString(), playerName.lowercase()))

    // --- Ban reads / lifts ---

    /** Posts the unban synchronously so the caller's success/failure result is real. */
    override fun revokeBanBlocking(playerId: UUID): Boolean {
        val name = runCatching { Bukkit.getOfflinePlayer(playerId).name }.getOrNull().orEmpty()
        return runCatching { invoke("guard.store.unban", listOf(playerId.toString(), name.lowercase())) }.isSuccess
    }

    override fun activeBan(playerId: UUID): BanRow? {
        val payload = firstLineObject(invoke("guard.query.activeban", listOf(playerId.toString()))) ?: return null
        if (payload["active"]?.jsonPrimitive?.booleanOrNull != true) return null
        val expires = payload["expiresAtEpochMs"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
        val name = runCatching { Bukkit.getOfflinePlayer(playerId).name }.getOrNull().orEmpty()
        return BanRow(playerId, name, payload["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(), 0L, expires)
    }

    /** Not exposed by the node contract; the Helix panel lists bans. Returns an empty list. */
    override suspend fun activeBans(limit: Int): List<BanRow> {
        if (banListNoticed.compareAndSet(false, true)) logger.info("Helix storage: ban listings/history live in the Helix panel; returning empty results in-game")
        return emptyList()
    }

    /** Not exposed by the node contract; the Helix panel shows punishment history. Returns an empty list. */
    override suspend fun banHistory(name: String, limit: Int): List<PunishmentRow> = emptyList()

    /** Not exposed by the node contract; callers fall back to Bukkit's offline-player lookup. */
    override suspend fun findPlayer(name: String): Pair<UUID, String>? = null

    // --- History / incident / replay reads ---

    override suspend fun history(playerName: String, page: Int, serverId: String?): List<HistoryEntry> = withContext(Dispatchers.IO) {
        val playerId = resolveUuid(playerName) ?: return@withContext emptyList()
        // The node paginates by a flat limit; fetch enough rows to slice the requested page locally.
        // A server filter is applied client-side, so fetch a larger window when one is requested.
        val safePage = page.coerceAtLeast(1)
        val limit = if (serverId == null) safePage * 10 else 500
        val lines = invoke("guard.query.history", listOf(playerId.toString(), limit.toString()))
        val violations = firstLineObject(lines)?.get("violations")?.jsonArray ?: return@withContext emptyList()
        violations.mapNotNull { element ->
            val row = element.jsonObject
            HistoryEntry(
                Instant.ofEpochMilli(row.long("epochMs")),
                row.string("serverId"),
                row.uuid("uuid") ?: playerId,
                row.string("name"),
                row.string("check"),
                row.double("vl"),
                row.string("details")
            )
        }.filter { serverId == null || it.serverId == serverId }
            .drop((safePage - 1) * 10)
            .take(10)
    }

    override suspend fun incidents(playerName: String, page: Int, serverId: String?): List<IncidentSnapshot> = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val limit = if (serverId == null) safePage * 10 else 500
        queryIncidents(playerName.lowercase(), limit)
            .filter { serverId == null || it.serverId == serverId }
            .drop((safePage - 1) * 10)
            .take(10)
    }

    /** Best-effort by-id lookup: the node only queries per player or "all", so scan the recent window. */
    override suspend fun incident(incidentId: UUID): IncidentSnapshot? = withContext(Dispatchers.IO) {
        queryIncidents("all", 200).firstOrNull { it.incidentId == incidentId }
    }

    override suspend fun incidentPlayer(incidentId: UUID): Pair<UUID, String>? =
        incident(incidentId)?.let { it.playerId to it.playerName }

    /** World uuids are not part of the node contract; replays rebuild without the terrain paste. */
    override suspend fun incidentWorld(incidentId: UUID): UUID? = withContext(Dispatchers.IO) {
        val lines = invoke("guard.query.incidents", listOf("all", "200")) ?: return@withContext null
        val payload = lines.firstOrNull() ?: return@withContext null
        runCatching {
            val incidents = json.parseToJsonElement(payload).jsonObject["incidents"]?.jsonArray ?: return@runCatching null
            incidents.asSequence()
                .map { it.jsonObject }
                .firstOrNull { it["id"]?.jsonPrimitive?.content == incidentId.toString() }
                ?.get("world")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let(UUID::fromString)
        }.getOrNull()
    }

    override suspend fun replayFrames(incidentId: UUID): List<ReplayFrameRow> = withContext(Dispatchers.IO) {
        val payload = firstLineObject(invoke("guard.query.replay", listOf(incidentId.toString())))
            ?.get("payload")?.jsonPrimitive?.contentOrNull
            ?: return@withContext emptyList()
        val bytes = Base64.getDecoder().decode(payload)
        val text = GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }
        parseReplayFrames(text)
    }

    // --- Health / queue metrics ---

    override fun isAvailable(): Boolean = available.get()
    override fun queueSize(): Int = queued.get()
    override fun droppedRecords(): Long = dropped.get()
    override fun writtenRecords(): Long = written.get()

    // --- Internals ---

    private fun enqueueAction(action: String, arguments: List<String>): Boolean {
        val accepted = queue.offer(NodeAction(action, arguments))
        if (accepted) queued.incrementAndGet()
        if (!accepted) {
            val total = dropped.incrementAndGet()
            if (total == 1L || total % 1000L == 0L) logger.warning("Helix action queue full; dropped $total records")
        }
        return accepted
    }

    private fun writerLoop() {
        var pending: NodeAction? = null
        while (writerRunning.get() || pending != null || queue.isNotEmpty()) {
            val action = pending ?: try {
                queue.poll(history.flushMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            pending = null
            try {
                invoke(action.action, action.arguments)
                written.incrementAndGet()
                queued.decrementAndGet()
                available.set(true)
            } catch (error: Exception) {
                if (available.getAndSet(false)) logger.warning("Helix node unavailable; writer will retry: ${error.message}")
                if (!writerRunning.get()) {
                    dropped.incrementAndGet()
                    queued.decrementAndGet()
                    continue
                }
                pending = action
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    // Re-check shutdown state and retry the same action.
                }
            }
        }
    }

    /** Invokes one node action and returns its output lines; throws when the node rejects or is down. */
    private fun invoke(action: String, arguments: List<String>): List<String> {
        val result = api.action(ActionInvocation(action, arguments))
            ?: throw IllegalStateException("node action $action failed: node unreachable")
        check(result.success) { "node action $action rejected: ${result.lines.firstOrNull() ?: "no detail"}" }
        return result.lines
    }

    /** The first response line parsed as a compact-JSON object (the query payload), or null. */
    private fun firstLineObject(lines: List<String>): JsonObject? =
        lines.firstOrNull()?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun queryIncidents(playerOrAll: String, limit: Int): List<IncidentSnapshot> {
        val lines = invoke("guard.query.incidents", listOf(playerOrAll, limit.toString()))
        val incidents = firstLineObject(lines)?.get("incidents")?.jsonArray ?: return emptyList()
        return incidents.mapNotNull { element ->
            val row = element.jsonObject
            val id = row.uuid("id") ?: return@mapNotNull null
            val playerId = row.uuid("uuid") ?: return@mapNotNull null
            val updatedAt = row.long("epochMs")
            // check/summary round-trip the fields the node contract has no dedicated columns for.
            val summary = summaryFields(row.string("summary"))
            IncidentSnapshot(
                id,
                playerId,
                row.string("name"),
                row.string("serverId"),
                Instant.ofEpochMilli(summary["opened"]?.toLongOrNull() ?: updatedAt),
                Instant.ofEpochMilli(updatedAt),
                row.double("confidence"),
                summary["calibrated"]?.toBoolean() ?: false,
                row.string("check").split(',').mapNotNull { family ->
                    runCatching { EvidenceFamily.valueOf(family.trim()) }.getOrNull()
                }.toSet(),
                summary["evidence"]?.toIntOrNull() ?: 0,
                summary["shadow"]?.takeUnless { it == "none" },
                summary["recipe"].orEmpty()
            )
        }
    }

    private fun violationJson(record: ViolationRecord): String = buildJsonObject {
        put("serverId", record.serverId)
        put("uuid", record.playerId.toString())
        put("name", record.playerName.lowercase())
        put("check", record.checkId)
        put("vl", record.violationLevel)
        put("confidence", record.confidence)
        put("epochMs", record.createdAt)
        put("details", detailsJson(record.evidence))
    }.toString()

    private fun incidentJson(record: IncidentRecord): String = buildJsonObject {
        put("id", record.incidentId.toString())
        put("serverId", record.serverId)
        put("world", org.bukkit.Bukkit.getPlayer(record.playerId)?.world?.uid?.toString() ?: "")
        put("uuid", record.playerId.toString())
        put("name", record.playerName.lowercase())
        put("check", record.families.joinToString(",") { it.name })
        put("confidence", record.confidence)
        put("epochMs", record.updatedAt)
        put(
            "summary",
            "opened=${record.openedAt} evidence=${record.evidenceCount} shadow=${record.shadowAction ?: "none"} " +
                "recipe=${record.recipeVersion} calibrated=${record.calibrated}"
        )
    }.toString()

    private fun detailsJson(evidence: Map<String, Any>): String = buildJsonObject {
        evidence.forEach { (key, value) ->
            when (value) {
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Number -> put(key, value.toDouble())
                else -> put(key, value.toString())
            }
        }
    }.toString()

    /** Splits `key=value key=value ...` summaries back into fields (values contain no spaces). */
    private fun summaryFields(summary: String): Map<String, String> =
        summary.split(' ').mapNotNull { token ->
            token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()

    /** Resolves an in-game name to a uuid (online player first, then Bukkit's offline cache). */
    private fun resolveUuid(playerName: String): UUID? =
        Bukkit.getPlayerExact(playerName)?.uniqueId
            ?: runCatching { Bukkit.getOfflinePlayer(playerName).uniqueId }.getOrNull()

    private fun gzip(payload: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(payload) }
        output.toByteArray()
    }
}

private fun JsonObject.string(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.long(key: String): Long = get(key)?.jsonPrimitive?.longOrNull ?: 0L
private fun JsonObject.double(key: String): Double = get(key)?.jsonPrimitive?.doubleOrNull ?: 0.0
private fun JsonObject.uuid(key: String): UUID? =
    get(key)?.jsonPrimitive?.contentOrNull?.let { runCatching { UUID.fromString(it) }.getOrNull() }
