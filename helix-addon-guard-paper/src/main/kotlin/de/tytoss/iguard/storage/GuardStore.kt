package de.tytoss.iguard.storage

import de.tytoss.iguard.model.IncidentRecord
import de.tytoss.iguard.model.IncidentSnapshot
import de.tytoss.iguard.model.OutboxEvent
import de.tytoss.iguard.model.ReplayRecord
import de.tytoss.iguard.model.SanctionRecord
import de.tytoss.iguard.model.ViolationRecord
import java.time.Instant
import java.util.UUID

/** One movement sample from a decompressed incident replay. */
data class ReplayFrameRow(
    val at: Long, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val onGround: Boolean,
)

/** One row of a player's violation history. */
data class HistoryEntry(
    val createdAt: Instant,
    val serverId: String,
    val playerId: UUID,
    val playerName: String,
    val checkId: String,
    val violationLevel: Double,
    val evidence: String,
)

/** A currently-active network ban. */
data class BanRow(val playerId: UUID, val playerName: String, val reason: String, val createdAt: Long, val expiresAt: Long?)

/** One entry in a player's punishment history (ban or unban). */
data class PunishmentRow(val createdAt: Long, val playerName: String, val type: String, val hours: Int?, val reason: String, val actor: String)

/**
 * Persistence boundary of the plugin: everything the rest of IGuard reads from or writes to durable
 * storage goes through this interface. In Helix-Cloud there is exactly one backend — [HelixNodeStore]
 * (no database; delegates to a Helix-Cloud node over HTTP).
 *
 * Writes are fire-and-forget: `enqueue*` methods only offer to a bounded in-memory queue (returning
 * `false` when full) and a background flusher persists asynchronously. Reads are either suspend
 * functions (dispatched to IO by the implementation) or explicitly-blocking helpers that must never
 * run on the main thread.
 */
interface GuardStore : AutoCloseable {
    /** Starts the background writer(s); call once after construction. */
    fun start()

    /** Stops the background writer and flushes what it can within [timeoutMillis]. */
    suspend fun stopAndFlush(timeoutMillis: Long = 3000)

    // --- Async writes (bounded queue + background flusher) ---

    /** Queues a violation, optionally with the incident it belongs to (persisted first). */
    fun enqueue(record: ViolationRecord, incident: IncidentRecord? = null): Boolean

    /** Queues an incident upsert. */
    fun enqueueIncident(record: IncidentRecord): Boolean

    /** Queues a compressed replay payload, optionally with the incident it belongs to. */
    fun enqueueReplay(record: ReplayRecord, incident: IncidentRecord? = null): Boolean

    /** Queues a proxy outbox event (shadow alerts, sanction broadcasts). */
    fun enqueueOutbox(event: OutboxEvent): Boolean

    /** Queues a sanction decision record. */
    fun enqueueSanction(record: SanctionRecord): Boolean

    /** Records a network-wide ban (proxy-enforced, incident-independent) so the login gate blocks rejoin. */
    fun enqueueNetworkBan(playerId: UUID, playerName: String, reason: String, expiresAt: Long?): Boolean

    /** Appends an audit entry (BAN/UNBAN) to the punishment history log. */
    fun enqueuePunishment(playerId: UUID, playerName: String, type: String, hours: Int?, reason: String, actor: String): Boolean

    // --- Ban reads / lifts ---

    /** Blocking ban revoke for the native provider's unban path (off the main thread). */
    fun revokeBanBlocking(playerId: UUID): Boolean

    /** Blocking active-ban lookup for the async login gate (must not run on the main thread). */
    fun activeBan(playerId: UUID): BanRow?

    /** All currently-active (non-expired) network bans, most recent first. */
    suspend fun activeBans(limit: Int = 100): List<BanRow>

    /** A player's punishment history (bans + unbans), most recent first. */
    suspend fun banHistory(name: String, limit: Int = 20): List<PunishmentRow>

    /** Resolves a player uuid+name for an offline-or-online name from recorded punishments/bans. */
    suspend fun findPlayer(name: String): Pair<UUID, String>?

    // --- History / incident / replay reads ---

    /** One page (10 rows) of a player's violation history, newest first, optionally per server. */
    suspend fun history(playerName: String, page: Int, serverId: String?): List<HistoryEntry>

    /** One page (10 rows) of a player's incidents, newest first, optionally per server. */
    suspend fun incidents(playerName: String, page: Int, serverId: String?): List<IncidentSnapshot>

    /** A single incident by id, or null when unknown. */
    suspend fun incident(incidentId: UUID): IncidentSnapshot?

    /** The recorded player (uuid, name) of an incident — used to skin the replay NPC. */
    suspend fun incidentPlayer(incidentId: UUID): Pair<UUID, String>?

    /** The world a given incident occurred in (from its violations), when known. */
    suspend fun incidentWorld(incidentId: UUID): UUID?

    /** Decompressed movement timeline of an incident replay (for playback). */
    suspend fun replayFrames(incidentId: UUID): List<ReplayFrameRow>

    // --- Health / queue metrics for /iguard status ---

    /** True while the backend is reachable (flips false after a failed write/cleanup). */
    fun isAvailable(): Boolean

    /** Records currently waiting in the write queue. */
    fun queueSize(): Int

    /** Records dropped because the write queue was full or shutdown flushed unwritten work. */
    fun droppedRecords(): Long

    /** Records successfully persisted since startup. */
    fun writtenRecords(): Long
}

/** Parses the decompressed replay text (one `epochMs<TAB>description` line per frame) into movement rows. */
internal fun parseReplayFrames(text: String): List<ReplayFrameRow> {
    val move = Regex("""x=(-?[0-9.]+) y=(-?[0-9.]+) z=(-?[0-9.]+) yaw=(-?[0-9.]+) pitch=(-?[0-9.]+) ground=(true|false)""")
    return text.lineSequence().mapNotNull { line ->
        val at = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
        val m = move.find(line) ?: return@mapNotNull null
        ReplayFrameRow(
            at, m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble(),
            m.groupValues[4].toFloat(), m.groupValues[5].toFloat(), m.groupValues[6].toBoolean(),
        )
    }.toList()
}
