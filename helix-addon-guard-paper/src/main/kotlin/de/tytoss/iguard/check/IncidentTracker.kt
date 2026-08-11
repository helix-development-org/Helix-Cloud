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

    /** Folds a check failure into the player's open incident (or a new one) and decides the action. */
    fun assign(frame: PacketFrame, playerName: String, failure: CheckFailure): IncidentAssignment {
        val state = states.computeIfAbsent(frame.playerId) { IncidentPlayerState() }
        synchronized(state) {
            var incident = state.incident
            if (incident == null || frame.receivedAt - incident.updatedAt > config.incidentGapMillis) {
                incident = TrackedIncident(UUID.randomUUID(), frame.receivedAt, frame.receivedAt)
                state.incident = incident
            }
            incident.updatedAt = frame.receivedAt
            incident.playerName = playerName
            incident.evidenceCount++
            incident.deterministic = incident.deterministic || failure.deterministic
            val confidenceConfig = dynamic.get().confidence
            incident.familyScores.merge(failure.family, ConfidenceModel.signalConfidence(confidenceConfig, failure.checkId), ::maxOf)
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
            val incidentRecord = snapshot.toRecord()
            storage.enqueueIncident(incidentRecord)
            if (incident.id !in state.captures) {
                state.captures[incident.id] = Capture(
                    incident.id,
                    frame.receivedAt - config.replayPreMillis,
                    frame.receivedAt + config.replayPostMillis,
                    state.replay.filterTo(ArrayList()) { it.receivedAt >= frame.receivedAt - config.replayPreMillis },
                    incidentRecord,
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
        }
        latest.remove(playerId)
    }

    private fun persistReplay(capture: Capture, capturedAt: Long) {
        // Snapshot the mutable capture under the caller's lock; format (describe) + compress + enqueue
        // off the worker so neither string building nor GZIP runs on the stripe.
        val frames = capture.frames.toList()
        val incidentId = capture.incidentId
        val windowStart = capture.windowStart
        val windowEnd = capture.windowEnd
        val incident = capture.incident
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

    private fun IncidentSnapshot.toRecord() = IncidentRecord(
        incidentId, openedAt.toEpochMilli(), updatedAt.toEpochMilli(), serverId, playerId, playerName,
        confidence, calibrated, families, evidenceCount, shadowAction, recipeVersion,
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
