package de.tytoss.iguard.check

import de.tytoss.iguard.config.DetectionConfig
import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.config.SanctionConfig
import de.tytoss.iguard.model.AttackFrame
import de.tytoss.iguard.model.BlockActionFrame
import de.tytoss.iguard.model.ClientActionFrame
import de.tytoss.iguard.model.ClientIdentityFrame
import de.tytoss.iguard.model.EvidenceFamily
import de.tytoss.iguard.model.IncidentRecord
import de.tytoss.iguard.model.IncidentSnapshot
import de.tytoss.iguard.model.InventoryClickFrame
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.OutboxEvent
import de.tytoss.iguard.model.PacketFrame
import de.tytoss.iguard.model.ReplayRecord
import de.tytoss.iguard.model.SanctionRecord
import de.tytoss.iguard.model.TimelineFrame
import de.tytoss.iguard.storage.GuardStore
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/** Executes a real sanction. Implemented by the plugin (main-thread ban/kick); no-op in shadow mode. */
fun interface Enforcement {
    fun ban(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String)
}

internal data class IncidentAssignment(
    val incidentId: UUID,
    val confidence: Double,
    val shadowAction: String?,
    val incident: IncidentRecord,
)

private data class Capture(
    val incidentId: UUID,
    val windowStart: Long,
    val windowEnd: Long,
    val frames: MutableList<PacketFrame>,
    var incident: IncidentRecord,
    val tracked: TrackedIncident,
)

private class TrackedIncident(
    val id: UUID,
    val openedAt: Long,
    var updatedAt: Long,
    val familyScores: MutableMap<EvidenceFamily, Double> = linkedMapOf(),
    var deterministic: Boolean = false,
    var evidenceCount: Int = 0,
    var playerName: String = "unknown",
    var lastOutboxAction: String? = null,
    var worldId: UUID? = null,
    // Write throttling: what the node last received for this incident and whether newer state is pending.
    var writtenAt: Long = Long.MIN_VALUE,
    var writtenConfidence: Double = -1.0,
    var writtenFamilies: Set<EvidenceFamily> = emptySet(),
    var writtenShadow: String? = null,
    var latestRecord: IncidentRecord? = null,
    var dirty: Boolean = false,
)

private class IncidentPlayerState {
    // Raw frames (already allocated by the packet listener) are retained here instead of eagerly
    // formatted strings; describe() is deferred to compression time, off the hot path and off-thread.
    val replay = ArrayDeque<PacketFrame>()
    val captures = LinkedHashMap<UUID, Capture>()
    var incident: TrackedIncident? = null
}

internal class IncidentTracker(
    private val serverId: String,
    private val storage: GuardStore,
    private val config: DetectionConfig,
    private val sanctions: SanctionConfig,
    private val dynamic: AtomicReference<DynamicConfig>,
    private val enforcement: Enforcement,
    private val notifications: de.tytoss.iguard.notify.NotificationService,
) {
    // Players already enforced this session -> escalate to the repeat-offender ban duration.
    private val enforced = ConcurrentHashMap.newKeySet<UUID>()

    // GZIP compression runs here, off the stripe worker: previously a 512KB compress ran inline under
    // synchronized(state), stalling the stripe and filling its channel (drops for all its players).
    private val replayExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "iguard-replay-compressor").apply { isDaemon = true }
    }
    private val states = ConcurrentHashMap<UUID, IncidentPlayerState>()
    private val latest = ConcurrentHashMap<UUID, IncidentSnapshot>()

    /** Records the frame into the rolling replay buffer and completes any elapsed capture windows. */
    fun observe(frame: PacketFrame) {
        val state = states.computeIfAbsent(frame.playerId) { IncidentPlayerState() }
        synchronized(state) {
            state.replay.addLast(frame)
            val cutoff = frame.receivedAt - config.replayPreMillis
            while (state.replay.firstOrNull()?.receivedAt?.let { it < cutoff } == true) state.replay.removeFirst()
            val completed = ArrayList<Capture>()
            state.captures.values.forEach { capture ->
                if (frame.receivedAt <= capture.windowEnd) capture.frames += frame
                if (frame.receivedAt >= capture.windowEnd) completed += capture
            }
            completed.forEach {
                state.captures.remove(it.incidentId)
                persistReplay(it, frame.receivedAt)
            }
        }
    }

    /**
     * Folds a check failure into the player's open incident (or a new one) and decides the action.
     *
     * @param frame the failing frame.
     * @param playerName player name.
     * @param failure the failure.
     * @param evidenceWeight `VL / alert-vl` clamped to 0..1: below the alert level the failure only
     *   contributes proportionally and never opens an incident on its own.
     * @param worldId world of the failure (recorded with the incident for the replay terrain).
     * @return the assignment, or null when the sub-threshold failure was not folded into any incident.
     */
    fun assign(frame: PacketFrame, playerName: String, failure: CheckFailure, evidenceWeight: Double = 1.0, worldId: UUID? = null): IncidentAssignment? {
        val state = states.computeIfAbsent(frame.playerId) { IncidentPlayerState() }
        synchronized(state) {
            var incident = state.incident
            if (incident == null || frame.receivedAt - incident.updatedAt > config.incidentGapMillis) {
                if (evidenceWeight < 1.0) return null
                incident?.let(::flush)
                incident = TrackedIncident(UUID.randomUUID(), frame.receivedAt, frame.receivedAt)
                state.incident = incident
            }
            incident.updatedAt = frame.receivedAt
            incident.playerName = playerName
            if (worldId != null) incident.worldId = worldId
            incident.evidenceCount++
            incident.deterministic = incident.deterministic || (failure.deterministic && evidenceWeight >= 1.0)
            val confidenceConfig = dynamic.get().confidence
            val score = ConfidenceModel.signalConfidence(confidenceConfig, failure.checkId) * evidenceWeight.coerceIn(0.0, 1.0)
            incident.familyScores.merge(failure.family, score, ::maxOf)
            val confidence = ConfidenceModel.provisionalConfidence(confidenceConfig, incident.familyScores.values, incident.deterministic)
            val eligible = confidence >= config.shadowThreshold &&
                (incident.familyScores.size >= config.minimumIndependentFamilies || incident.deterministic)
            // A recipe is "calibrated" only when it matches the operator-approved recipe; enforcement
            // is gated on it so a hot-reloaded/experimental recipe can never auto-ban.
            val calibrated = ConfidenceModel.RECIPE_VERSION == sanctions.calibratedRecipe
            val enforce = eligible && calibrated && sanctions.mode == "enforce"
            val banHours = if (frame.playerId in enforced) sanctions.repeatBanHours else sanctions.firstBanHours
            val shadowAction = when {
                enforce -> "BAN_${banHours}H"
                eligible -> "WOULD_BAN_${banHours}H"
                else -> null
            }
            val snapshot = IncidentSnapshot(
                incident.id,
                frame.playerId,
                playerName,
                serverId,
                Instant.ofEpochMilli(incident.openedAt),
                Instant.ofEpochMilli(incident.updatedAt),
                confidence,
                calibrated,
                incident.familyScores.keys.toSet(),
                incident.evidenceCount,
                shadowAction,
                ConfidenceModel.RECIPE_VERSION,
            )
            latest[frame.playerId] = snapshot
            val incidentRecord = snapshot.toRecord(incident.worldId)
            incident.latestRecord = incidentRecord
            // One upsert per meaningful change instead of one per failure: new incident, new action,
            // new family, a real confidence step, or at most every few seconds while it keeps growing.
            val changed = incident.writtenAt == Long.MIN_VALUE ||
                shadowAction != incident.writtenShadow ||
                incident.familyScores.keys != incident.writtenFamilies ||
                confidence >= incident.writtenConfidence + CONFIDENCE_WRITE_STEP ||
                frame.receivedAt - incident.writtenAt >= INCIDENT_WRITE_INTERVAL_MILLIS
            if (changed) write(incident, incidentRecord, frame.receivedAt) else incident.dirty = true
            if (incident.id !in state.captures) {
                state.captures[incident.id] = Capture(
                    incident.id,
                    frame.receivedAt - config.replayPreMillis,
                    frame.receivedAt + config.replayPostMillis,
                    state.replay.filterTo(ArrayList()) { it.receivedAt >= frame.receivedAt - config.replayPreMillis },
                    incidentRecord,
                    incident,
                )
            } else {
                state.captures[incident.id]?.incident = incidentRecord
            }
            if (shadowAction != null && incident.lastOutboxAction != shadowAction) {
                incident.lastOutboxAction = shadowAction
                val reason = if (enforce) {
                    "IGuard ${ConfidenceModel.RECIPE_VERSION}: ${"%.0f".format(confidence * 100)}% confidence, families=${incident.familyScores.keys.joinToString(",")}"
                } else {
                    "Shadow decision from ${ConfidenceModel.RECIPE_VERSION} (calibrated=$calibrated); no player action executed"
                }
                storage.enqueueSanction(
                    SanctionRecord(
                        UUID.randomUUID(), incident.id, frame.playerId, shadowAction, !enforce, frame.receivedAt,
                        frame.receivedAt + banHours * 60L * 60L * 1000L, reason,
                    ),
                )
                // Shadow alerts go to the proxy here; the enforce outbox+network-ban is written by
                // enforcement.ban() (below) so it is shared with manual bans and never double-emitted.
                if (!enforce) {
                    storage.enqueueOutbox(
                        OutboxEvent(
                            UUID.randomUUID(), 1, frame.receivedAt, serverId, frame.playerId, playerName,
                            incident.id, "shadow.sanction",
                                mapOf(
                                "action" to shadowAction,
                                "confidence" to confidence,
                                "calibrated" to calibrated,
                                "families" to incident.familyScores.keys.joinToString(","),
                            ),
                        ),
                    )
                }
                if (enforce) {
                    enforced.add(frame.playerId)
                    enforcement.ban(frame.playerId, playerName, banHours, reason, "IGuard")
                }
                // Notify staff of the flagged incident (webhook applies its own confidence gate + cooldown).
                notifications.incident(
                    frame.playerId, playerName, confidence, incident.familyScores.keys.toSet(),
                    incident.evidenceCount, shadowAction, ConfidenceModel.RECIPE_VERSION,
                )
            }
            return IncidentAssignment(incident.id, confidence, shadowAction, incidentRecord)
        }
    }

    /** The player's most recent incident while it is still within the incident gap, or null. */
    fun latest(playerId: UUID): IncidentSnapshot? = latest[playerId]?.takeIf {
        System.currentTimeMillis() - it.updatedAt.toEpochMilli() <= config.incidentGapMillis
    }

    /** Recently-updated incidents (for the admin panel), strongest first. */
    fun recent(withinMillis: Long): List<IncidentSnapshot> {
        val cutoff = System.currentTimeMillis() - withinMillis
        return latest.values.filter { it.updatedAt.toEpochMilli() >= cutoff }.sortedByDescending { it.confidence }
    }

    /** Flushes any open captures and drops the player's incident state (on quit). */
    fun remove(playerId: UUID) {
        val state = states.remove(playerId) ?: return
        synchronized(state) {
            state.captures.values.forEach { persistReplay(it, System.currentTimeMillis()) }
            state.captures.clear()
            state.incident?.let(::flush)
        }
        latest.remove(playerId)
    }

    private fun write(incident: TrackedIncident, record: IncidentRecord, now: Long) {
        storage.enqueueIncident(record)
        incident.writtenAt = now
        incident.writtenConfidence = record.confidence
        incident.writtenFamilies = record.families
        incident.writtenShadow = record.shadowAction
        incident.dirty = false
    }

    // Writes the final state of an incident whose last updates were throttled away.
    private fun flush(incident: TrackedIncident) {
        val record = incident.latestRecord ?: return
        if (incident.dirty) write(incident, record, incident.updatedAt)
    }

    private fun persistReplay(capture: Capture, capturedAt: Long) {
        // Snapshot the mutable capture under the caller's lock; format (describe) + compress + enqueue
        // off the worker so neither string building nor GZIP runs on the stripe. The incident row only
        // rides along when throttling left newer state unwritten (otherwise it is already stored).
        val frames = capture.frames.toList()
        val incidentId = capture.incidentId
        val windowStart = capture.windowStart
        val windowEnd = capture.windowEnd
        val tracked = capture.tracked
        val incident = if (tracked.dirty) {
            tracked.dirty = false
            tracked.writtenAt = capture.incident.updatedAt
            tracked.writtenConfidence = capture.incident.confidence
            tracked.writtenFamilies = capture.incident.families
            tracked.writtenShadow = capture.incident.shadowAction
            capture.incident
        } else {
            null
        }
        replayExecutor.execute {
            val raw = frames.joinToString("\n") { "${it.receivedAt}\t${describe(it)}" }.encodeToByteArray()
            val compressed = ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { it.write(raw) }
                output.toByteArray()
            }
            val truncated = compressed.size > config.replayMaxBytes
            storage.enqueueReplay(
                ReplayRecord(
                    incidentId,
                    capturedAt,
                    windowStart,
                    windowEnd,
                    "gzip",
                    if (truncated) compressed.copyOf(config.replayMaxBytes) else compressed,
                    truncated,
                    capturedAt + config.replayRetentionDays * 86_400_000L,
                ),
                incident,
            )
        }
    }

    /** Drains the replay compressor so pending captures reach the storage queue before shutdown. */
    fun shutdown() {
        // Flush pending compressions into the storage queue before the storage writer stops.
        replayExecutor.shutdown()
        if (!replayExecutor.awaitTermination(2, TimeUnit.SECONDS)) replayExecutor.shutdownNow()
    }

    private fun IncidentSnapshot.toRecord(worldId: UUID?) = IncidentRecord(
        incidentId, openedAt.toEpochMilli(), updatedAt.toEpochMilli(), serverId, playerId, playerName,
        confidence, calibrated, families, evidenceCount, shadowAction, recipeVersion, worldId,
    )

    private fun describe(frame: PacketFrame): String = when (frame) {
        is MovementFrame -> "move p=${frame.positionChanged} r=${frame.rotationChanged} x=${frame.position.x.fmt()} y=${frame.position.y.fmt()} z=${frame.position.z.fmt()} yaw=${frame.yaw} pitch=${frame.pitch} ground=${frame.onGround}"
        is AttackFrame -> "attack target=${frame.targetEntityId}"
        is BlockActionFrame -> "block action=${frame.action} pos=${frame.blockX},${frame.blockY},${frame.blockZ} face=${frame.face} seq=${frame.interactionSequence}"
        is InventoryClickFrame -> "inventory window=${frame.windowId} state=${frame.stateId} slot=${frame.slot} button=${frame.button} type=${frame.clickType}"
        is TimelineFrame -> "timeline kind=${frame.kind} id=${frame.id}"
        is ClientActionFrame -> "action value=${frame.action}"
        is ClientIdentityFrame -> "identity channel=${frame.channel} bytes=${frame.payload.size}"
        else -> frame::class.simpleName ?: "frame"
    }
}

private fun Double.fmt() = "%.5f".format(java.util.Locale.ROOT, this)

// Minimum confidence increase that justifies another incident upsert on its own.
private const val CONFIDENCE_WRITE_STEP = 0.05

// While an incident keeps growing, its row is refreshed at most this often.
private const val INCIDENT_WRITE_INTERVAL_MILLIS = 5_000L
