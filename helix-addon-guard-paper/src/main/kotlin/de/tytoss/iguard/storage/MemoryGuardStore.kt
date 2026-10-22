package de.tytoss.iguard.storage

import de.tytoss.iguard.model.IncidentRecord
import de.tytoss.iguard.model.IncidentSnapshot
import de.tytoss.iguard.model.OutboxEvent
import de.tytoss.iguard.model.ReplayRecord
import de.tytoss.iguard.model.SanctionRecord
import de.tytoss.iguard.model.ViolationRecord
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [GuardStore] that only records what IGuard writes — used by trace replays (which must
 * never reach a node) and by tests. Reads answer empty.
 */
class MemoryGuardStore : GuardStore {
    /** Every violation written, in order. */
    val violations: MutableList<ViolationRecord> = CopyOnWriteArrayList()

    /** Every incident upsert written, in order (including the ones riding along with replays). */
    val incidents: MutableList<IncidentRecord> = CopyOnWriteArrayList()

    /** Every replay written, in order. */
    val replays: MutableList<ReplayRecord> = CopyOnWriteArrayList()

    /** Every alert-toggle mirror written, in order. */
    val alertPreferences: MutableList<Pair<String, Boolean>> = CopyOnWriteArrayList()

    override fun start() = Unit

    override suspend fun stopAndFlush(timeoutMillis: Long) = Unit

    override fun close() = Unit

    override fun enqueue(record: ViolationRecord, incident: IncidentRecord?): Boolean {
        incident?.let(incidents::add)
        violations += record
        return true
    }

    override fun enqueueIncident(record: IncidentRecord): Boolean = incidents.add(record)

    override fun enqueueReplay(record: ReplayRecord, incident: IncidentRecord?): Boolean {
        incident?.let(incidents::add)
        return replays.add(record)
    }

    override fun enqueueOutbox(event: OutboxEvent): Boolean = true

    override fun enqueueSanction(record: SanctionRecord): Boolean = true

    override fun enqueueNetworkBan(playerId: UUID, playerName: String, reason: String, expiresAt: Long?): Boolean = true

    override fun enqueuePunishment(playerId: UUID, playerName: String, type: String, hours: Int?, reason: String, actor: String): Boolean = true

    override fun enqueueAlertPreference(playerName: String, enabled: Boolean): Boolean = alertPreferences.add(playerName to enabled)

    override fun revokeBanBlocking(playerId: UUID): Boolean = true

    override fun activeBan(playerId: UUID): BanRow? = null

    override suspend fun activeBans(limit: Int): List<BanRow> = emptyList()

    override suspend fun banHistory(name: String, limit: Int): List<PunishmentRow> = emptyList()

    override suspend fun findPlayer(name: String): Pair<UUID, String>? = null

    override suspend fun history(playerName: String, page: Int, serverId: String?): List<HistoryEntry> = emptyList()

    override suspend fun incidents(playerName: String, page: Int, serverId: String?): List<IncidentSnapshot> = emptyList()

    override suspend fun incident(incidentId: UUID): IncidentSnapshot? = null

    override suspend fun incidentPlayer(incidentId: UUID): Pair<UUID, String>? = null

    override suspend fun incidentWorld(incidentId: UUID): UUID? = null

    override suspend fun replayFrames(incidentId: UUID): List<ReplayFrameRow> = emptyList()

    override fun isAvailable(): Boolean = true

    override fun queueSize(): Int = 0

    override fun droppedRecords(): Long = 0

    override fun writtenRecords(): Long = violations.size.toLong()
}
