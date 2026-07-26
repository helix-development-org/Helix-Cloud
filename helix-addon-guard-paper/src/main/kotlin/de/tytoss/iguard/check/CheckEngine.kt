package de.tytoss.iguard.check

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import de.tytoss.iguard.alert.AlertService
import de.tytoss.iguard.api.ExemptionManager
import de.tytoss.iguard.api.IGuardPlayerSnapshot
import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.config.ExemptionConfig
import de.tytoss.iguard.config.DetectionConfig
import de.tytoss.iguard.config.SanctionConfig
import de.tytoss.iguard.model.AttackFrame
import de.tytoss.iguard.model.BlockAction
import de.tytoss.iguard.model.BlockActionFrame
import de.tytoss.iguard.model.ClientAbilitiesFrame
import de.tytoss.iguard.model.ClientAction
import de.tytoss.iguard.model.ClientActionFrame
import de.tytoss.iguard.model.ClientIdentityFrame
import de.tytoss.iguard.model.ClientTickFrame
import de.tytoss.iguard.model.InventoryClickFrame
import de.tytoss.iguard.model.EnvironmentFrame
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.PacketFrame
import de.tytoss.iguard.model.SafePosition
import de.tytoss.iguard.model.ResetFrame
import de.tytoss.iguard.model.ServerAbilitiesFrame
import de.tytoss.iguard.model.ServerTeleportFrame
import de.tytoss.iguard.model.ServerVelocityFrame
import de.tytoss.iguard.model.SwingFrame
import de.tytoss.iguard.model.TeleportConfirmFrame
import de.tytoss.iguard.model.TimelineFrame
import de.tytoss.iguard.model.TimelineKind
import de.tytoss.iguard.model.Vec3
import de.tytoss.iguard.model.ViolationRecord
import de.tytoss.iguard.profile.VersionProfile
import de.tytoss.iguard.profile.VersionProfiles
import de.tytoss.iguard.setback.SetbackService
import de.tytoss.iguard.snapshot.SnapshotStore
import de.tytoss.iguard.storage.GuardStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.round

internal data class CheckFailure(
    val checkId: String,
    val weight: Double,
    val evidence: Map<String, Any>,
    val family: de.tytoss.iguard.model.EvidenceFamily = ConfidenceModel.family(checkId),
    val deterministic: Boolean = false
)

internal class PlayerState {
    var lastSequence = 0L
    var lastMovement: MovementFrame? = null
    var lastVerticalDelta = 0.0
    var lastHorizontalDelta = 0.0
    var airTicks = 0
    var clientTick = 0L
    var attackTick = -1L
    var attackTarget = -1
    var rotationMisses = 0
    var lastAttackAt = 0L
    var sprinting = false
    var sneaking = false
    var clientFlying = false
    // Bedrock (Geyser/Floodgate) players run different movement physics than Java, so the Java
    // prediction would false-positive them. Resolved once from the uuid; movement checks are skipped
    // for Bedrock while deterministic/combat/world checks stay active.
    var bedrock: Boolean? = null

    var clientBrand: String? = null
    val clientChannels = LinkedHashSet<String>()
    var clientFingerprint = ClientFingerprint.UNKNOWN
    var identityReported = false
    var brandSpoofReported = false
    var identityLogKey = ""
    var serverFlying = false
    var serverFlightAllowed = false
    var serverCreative = false
    var movementScale = 0.1f
    var explicitClientTicks = false
    var pendingTeleportId: Int? = null
    var pendingTeleportTarget: Vec3? = null
    var serverVelocity = Vec3(0.0, 0.0, 0.0)
    var velocityAt = 0L
    var predictedHorizontal = 0.0
    var predictedVertical = 0.0
    var lastSpeedFailureTick = -1000L
    var speedFailureStreak = 0

    // Decaying knockback residual (Grim-style velocity uncertainty): after a server velocity or
    // explosion the possible-movement interval must widen by the knockback the client still carries,
    // decaying over the following ticks instead of a fixed time exemption. Added to the speed limit
    // and the fly ceiling so a legit knocked-back player is never flagged.
    var horizontalUncertainty = 0.0
    var verticalUncertainty = 0.0
    var positionGapTicks = 0
    var physicalSupporting = true
    val clientTickTimes = ArrayDeque<Long>()
    val pendingPings = LinkedHashMap<Long, Long>()
    val pendingKeepAlives = LinkedHashMap<Long, Long>()
    var confirmedRttMillis = 0L
    var lastInteractionSequence = -1
    val digStarts = HashMap<String, Long>()
    val digTimes = ArrayDeque<Long>()
    val placementTimes = ArrayDeque<Long>()
    val attackTimes = ArrayDeque<Long>()
    val inventoryMoveTimes = ArrayDeque<Long>()
    val inventoryAttackTimes = ArrayDeque<Long>()
    var lastInventoryClickAt = 0L
    var lastInventoryStateId: Int? = null
    var pendingVelocity: Vec3? = null
    var pendingVelocityTick = 0L
    var velocityObservedHorizontal = 0.0
    var velocityObservedVertical = 0.0
    var lastTimerFailureAt = 0L
    var safePosition: SafePosition? = null
    var droppedPackets = 0L
    var underloadUntil = 0L
    var lastPublishAt = 0L
    var lastSwingAt = 0L
    var lastRotationYaw = Float.NaN
    var lastYawDelta = 0.0
    var lastRotationAt = 0L
    var lastWorldId: UUID? = null
    val violations = HashMap<String, Double>()
    val lastFailureAt = HashMap<String, Long>()

    /** Clears the motion-tracking fields after a teleport/respawn so stale deltas cannot flag. */
    fun resetMotion() {
        lastMovement = null
        lastVerticalDelta = 0.0
        lastHorizontalDelta = 0.0
        airTicks = 0
        attackTick = -1
        attackTarget = -1
        rotationMisses = 0
        pendingTeleportTarget = null
        predictedHorizontal = 0.0
        predictedVertical = 0.0
        lastSpeedFailureTick = -1000L
        speedFailureStreak = 0
        horizontalUncertainty = 0.0
        verticalUncertainty = 0.0
        positionGapTicks = 0
        physicalSupporting = true
    }
}

/**
 * The asynchronous detection core: packet frames are striped per player onto a dedicated worker pool,
 * evaluated against the movement/combat/world/protocol checks and turned into violations, incidents
 * and (shadow or enforced) sanctions. Never touches Bukkit from the workers.
 */
class CheckEngine(
    private val workerCount: Int,
    queueCapacity: Int,
    private val snapshots: SnapshotStore,
    private val exemptions: ExemptionManager,
    private val exemptionConfig: ExemptionConfig,
    private val detectionConfig: DetectionConfig,
    private val sanctionConfig: SanctionConfig,
    private val dynamic: AtomicReference<DynamicConfig>,
    private val serverId: String,
    private val storage: GuardStore,
    private val alerts: AlertService,
    private val setbacks: SetbackService,
    private val enforcement: Enforcement,
    private val notifications: de.tytoss.iguard.notify.NotificationService,
    private val logger: Logger
) {
    // Dedicated worker pool so detection never competes with Dispatchers.Default (storage cleanup,
    // command coroutines). One daemon thread per stripe; per-player ordering is preserved because a
    // player always hashes to the same stripe (see submit).
    private val workerThreads = AtomicInteger()
    private val workerExecutor = Executors.newFixedThreadPool(workerCount) { runnable ->
        Thread(runnable, "iguard-check-worker-${workerThreads.incrementAndGet()}").apply { isDaemon = true }
    }
    private val engineJob = SupervisorJob()
    private val engineScope = CoroutineScope(
        engineJob + workerExecutor.asCoroutineDispatcher() +
            CoroutineExceptionHandler { _, error -> logger.severe("IGuard check worker failed: ${error.stackTraceToString()}") }
    )
    private val channels = List(workerCount) { Channel<PacketFrame>(queueCapacity) }
    private val queued = List(workerCount) { AtomicInteger() }
    private val states = ConcurrentHashMap<UUID, PlayerState>()
    private val published = ConcurrentHashMap<UUID, IGuardPlayerSnapshot>()
    private val pendingDrops = ConcurrentHashMap<UUID, AtomicLong>()
    private val totalDropped = AtomicLong()
    private val warningTimes = ConcurrentHashMap<UUID, Long>()
    private val movementEvaluator = MovementEvaluator()
    private val incidentTracker = IncidentTracker(serverId, storage, detectionConfig, sanctionConfig, dynamic, enforcement, notifications)
    private val processedFrames = AtomicLong()
    private val unevaluatedFrames = AtomicLong()
    private val processingNanos = AtomicLong()
    private val maximumProcessingNanos = AtomicLong()

    /** Starts one consumer coroutine per stripe channel. */
    fun start() {
        channels.forEachIndexed { index, channel ->
            engineScope.launch {
                for (frame in channel) {
                    queued[index].decrementAndGet()
                    val started = System.nanoTime()
                    process(frame)
                    val elapsed = System.nanoTime() - started
                    processedFrames.incrementAndGet()
                    processingNanos.addAndGet(elapsed)
                    maximumProcessingNanos.accumulateAndGet(elapsed, ::maxOf)
                }
            }
        }
    }

    /** Offers a frame to the player's stripe; false (and counted as dropped) when the queue is full. */
    fun submit(frame: PacketFrame): Boolean {
        val index = (frame.playerId.hashCode() and Int.MAX_VALUE) % channels.size
        queued[index].incrementAndGet()
        val accepted = channels[index].trySend(frame).isSuccess
        if (!accepted) queued[index].decrementAndGet()
        if (!accepted) {
            pendingDrops.computeIfAbsent(frame.playerId) { AtomicLong() }.incrementAndGet()
            totalDropped.incrementAndGet()
        }
        return accepted
    }

    /** The player's last published API snapshot, or null. */
    fun snapshot(playerId: UUID) = published[playerId]
    /** The player's most recent open incident, or null. */
    fun incidentSnapshot(playerId: UUID) = incidentTracker.latest(playerId)
    /** Incidents updated within the given window (panel grid). */
    fun recentIncidents(withinMillis: Long = 60_000) = incidentTracker.recent(withinMillis)
    /** Frames dropped because a stripe queue was full. */
    fun totalDroppedPackets() = totalDropped.get()
    /** Frames processed without an evaluable environment snapshot. */
    fun unevaluatedFrameCount() = unevaluatedFrames.get()
    /** Players with a published snapshot. */
    fun trackedPlayers() = published.size
    /** Current per-stripe queue depths. */
    fun queueSizes() = queued.map(AtomicInteger::get)
    /** (processed frames, average micros, maximum micros) since startup. */
    fun processingMetrics(): Triple<Long, Double, Double> {
        val count = processedFrames.get()
        val averageMicros = if (count == 0L) 0.0 else processingNanos.get() / count / 1_000.0
        return Triple(count, averageMicros, maximumProcessingNanos.get() / 1_000.0)
    }

    /** Drops all per-player engine state (on quit). */
    fun remove(playerId: UUID) {
        incidentTracker.remove(playerId)
        states.remove(playerId)
        published.remove(playerId)
        pendingDrops.remove(playerId)
        warningTimes.remove(playerId)
    }

    /** Closes the stripe channels so no new frames are accepted (first shutdown phase). */
    fun stopAccepting() {
        channels.forEach(Channel<PacketFrame>::close)
    }

    /** Cancels the workers, stops the pool and flushes the incident tracker. */
    fun shutdown() {
        engineJob.cancel()
        workerExecutor.shutdown()
        if (!workerExecutor.awaitTermination(2, TimeUnit.SECONDS)) workerExecutor.shutdownNow()
        incidentTracker.shutdown()
    }

    private fun process(frame: PacketFrame) {
        incidentTracker.observe(frame)
        val state = states.computeIfAbsent(frame.playerId) { PlayerState() }
        if (state.bedrock == null) state.bedrock = ClientClassifier.isBedrock(frame.playerId)
        val dropped = pendingDrops.remove(frame.playerId)?.get() ?: 0L
        val sequenceGap = state.lastSequence != 0L && frame.sequence != state.lastSequence + 1
        if (dropped > 0 || sequenceGap) {
            // A frame we created was dropped before reaching a worker (queue overload). Reset the
            // motion predictor so the next delta is not compared against a stale prediction (that
            // would false-positive a legit player). Crucially: do NOT grant an exemption here — a
            // self-induced overload must never buy a detection-free window, and a dropped frame is
            // "we could not check", not "the player is clean". Count it so metrics stay honest.
            state.droppedPackets += max(dropped, (frame.sequence - state.lastSequence - 1).coerceAtLeast(0))
            state.underloadUntil = frame.receivedAt + exemptionConfig.overloadMillis
            state.resetMotion()
            unevaluatedFrames.incrementAndGet()
            warnOverload(frame.playerId)
        }
        state.lastSequence = frame.sequence
        val version = runCatching { ClientVersion.valueOf(frame.clientVersion) }.getOrNull()
        val view = snapshots.view(frame.playerId)
        val profile = version?.let(VersionProfiles::forClient)
        when (frame) {
            is MovementFrame -> if (view != null && profile != null) processMovement(frame, state, view.current, profile, view.playerName)
            is AttackFrame -> if (view != null && profile != null) processAttack(frame, state, view.current, profile, view.playerName)
            is SwingFrame -> state.lastSwingAt = frame.receivedAt
            is ClientActionFrame -> processAction(frame, state)
            is ClientAbilitiesFrame -> processClientAbilities(frame, state)
            is ClientTickFrame -> processClientTick(frame, state, view?.current, view?.playerName)
            is ClientIdentityFrame -> processIdentity(frame, state, view?.playerName)
            is ServerVelocityFrame -> processVelocity(frame, state)
            is ServerTeleportFrame -> processTeleport(frame, state)
            is TeleportConfirmFrame -> processTeleportConfirm(frame, state)
            is ServerAbilitiesFrame -> processServerAbilities(frame, state)
            is ResetFrame -> processReset(frame, state)
            is TimelineFrame -> processTimeline(frame, state)
            is BlockActionFrame -> if (view != null) processBlockAction(frame, state, view.current, view.playerName)
            is InventoryClickFrame -> if (view != null) processInventoryClick(frame, state, view.current, view.playerName)
        }
        if (view != null) reportIdentity(frame, state, view.current, view.playerName)
        // Throttle the observability snapshot: it is consumed only by /iguard info|clients and the API,
        // so rebuilding it (with map/set copies) every packet was pure hot-path waste. Always publish
        // the first frame so a newly tracked player appears immediately.
        if (!published.containsKey(frame.playerId) || frame.receivedAt - state.lastPublishAt >= PUBLISH_INTERVAL_MILLIS) {
            state.lastPublishAt = frame.receivedAt
            publish(frame, state, view?.playerName ?: frame.playerId.toString(), profile != null)
        }
    }

    private fun warnOverload(playerId: UUID) {
        val now = System.currentTimeMillis()
        val previous = warningTimes.putIfAbsent(playerId, now)
        if (previous == null || now - previous >= 10000) {
            warningTimes[playerId] = now
            logger.warning("Worker queue overloaded for $playerId; total dropped packets: ${totalDropped.get()}")
        }
    }

    private fun processMovement(
        incoming: MovementFrame,
        state: PlayerState,
        environment: EnvironmentFrame,
        profile: VersionProfile,
        playerName: String
    ) {
        val invalid = movementEvaluator.badPacketFailure(incoming)
        if (invalid != null) {
            applyResults(incoming, state, environment, playerName, listOf(invalid), setOf("protocol.badpackets.a"))
            state.resetMotion()
            return
        }
        val previous = state.lastMovement
        val frame = incoming.withFallback(previous, environment)
        if (!state.explicitClientTicks) {
            state.positionGapTicks++
            state.clientTick++
        }
        val stale = incoming.receivedAt - environment.capturedAt > exemptionConfig.snapshotMaxAgeMillis
        val worldChanged = state.lastWorldId != null && state.lastWorldId != environment.worldId
        val exemptEnvironment = environment.exemptEnvironment && "liquid" !in environment.environmentTags
        // Grossly stale snapshot / unloaded chunk = "we could not check", not "clean". Skip is still
        // FP-safe, but we count it so metrics never mistake a load blind spot for a clean player.
        if ((stale || !environment.chunkLoaded) && incoming.positionChanged) unevaluatedFrames.incrementAndGet()
        val laggy = environment.tps < exemptionConfig.lowTpsThreshold
        // Support-dependent checks (nofall/ground-spoof, fly-hover) rely on collision boxes sampled for
        // the player's position. Under budgeted sampling a moving player can outrun its last sample, so
        // supports() goes stale-false and would false-positive. Only trust support when the snapshot is
        // fresh (sampled within ~1 tick); otherwise freeze airborne accounting instead of guessing.
        val supportReliable = incoming.receivedAt - environment.capturedAt <= SUPPORT_FRESH_MILLIS
        val exempt = exemptions.isExempt(incoming.playerId, incoming.receivedAt) || exemptEnvironment ||
            stale || !environment.chunkLoaded || previous == null || worldChanged || state.bedrock == true
        val safeCandidate = if (environment.supportingCollision && !environment.colliding) {
            SafePosition(environment.worldId, environment.position, environment.yaw, environment.pitch, environment.tick)
        } else null
        var physicalSupporting = movementEvaluator.supports(frame.position, environment)
        if (!exempt && incoming.positionChanged) {
            val delta = frame.position - previous.position
            state.pendingVelocity?.let {
                state.velocityObservedHorizontal += sqrt(delta.horizontalLengthSquared())
                state.velocityObservedVertical = max(state.velocityObservedVertical, delta.y)
            }
            val evaluation = movementEvaluator.evaluate(frame, delta, environment, profile, state, laggy)
            physicalSupporting = evaluation.supporting
            val failures = evaluation.failures
            applyResults(
                incoming, state, environment, playerName, failures,
                setOf("movement.fly.a", "movement.speed.a", "movement.nofall.a", "movement.phase.a", "movement.step.a", "movement.spider.a", "movement.jesus.a")
            )
            val extra = ArrayList<CheckFailure>(2)
            // Air-jump: a jump-sized upward impulse while airborne and previously descending — a second
            // jump with no ground contact (support-reliable guard prevents lag FP).
            if (supportReliable && !evaluation.supporting && state.airTicks >= 4 && state.lastVerticalDelta < -0.02 && delta.y > 0.33) {
                extra += CheckFailure("movement.airjump.a", 1.5, mapOf("dy" to delta.y.rounded(), "prevDy" to state.lastVerticalDelta.rounded(), "airTicks" to state.airTicks))
            }
            // Omni-sprint: sprinting while the movement vector points opposite the look direction; the
            // vanilla client only sprints roughly forward.
            val horizontal = sqrt(delta.horizontalLengthSquared())
            if (state.sprinting && horizontal > 0.15) {
                val yawRad = Math.toRadians(frame.yaw.toDouble())
                val dot = (delta.x * -sin(yawRad) + delta.z * cos(yawRad)) / horizontal
                if (dot < -0.4) extra += CheckFailure("movement.sprintbackwards.a", 1.0, mapOf("lookDot" to dot.rounded(), "horizontal" to horizontal.rounded()))
            }
            // Fast-ladder: climbing a ladder/vine faster than the vanilla climb speed (~0.118/tick up).
            // Gated on the climbable environment tag, so legit ground movement can never trip it.
            if ("climbable" in environment.environmentTags && delta.y > 0.235) {
                extra += CheckFailure("movement.fastladder.a", 1.0, mapOf("dy" to delta.y.rounded()))
            }
            // High-jump: leaving the ground with an upward impulse beyond even Jump Boost II (~0.62/tick).
            // Requires a fresh takeoff (previous tick roughly level) so descending/apex frames don't fire.
            if (supportReliable && state.airTicks <= 1 && state.lastVerticalDelta <= 0.02 &&
                delta.y > 0.72 && "climbable" !in environment.environmentTags) {
                extra += CheckFailure("movement.highjump.a", 1.5, mapOf("dy" to delta.y.rounded(), "airTicks" to state.airTicks))
            }
            if (extra.isNotEmpty()) applyResults(incoming, state, environment, playerName, extra, setOf("movement.airjump.a", "movement.sprintbackwards.a", "movement.fastladder.a", "movement.highjump.a"))
            if (failures.none { it.checkId == "movement.speed.a" }) {
                movementEvaluator.acceptHorizontal(delta, environment, state, evaluation.supporting)
            } else {
                movementEvaluator.rejectHorizontal(environment, state, evaluation.supporting)
            }
            if (failures.none { it.checkId == "movement.fly.a" }) {
                movementEvaluator.acceptVertical(delta, environment, state, evaluation.supporting)
            } else {
                movementEvaluator.rejectVertical(environment, state, evaluation.supporting)
            }
            if (failures.none { it.checkId == "movement.fly.a" || it.checkId == "movement.speed.a" }) {
                safeCandidate?.let { state.safePosition = it }
            }
            state.lastVerticalDelta = delta.y
            state.lastHorizontalDelta = sqrt(delta.horizontalLengthSquared())
            state.positionGapTicks = 0
            // Decay the knockback-uncertainty window. Horizontal decays with air friction (slower than
            // ground on purpose — a wider window a bit longer is FP-safe); vertical decays like gravity.
            state.horizontalUncertainty = (state.horizontalUncertainty * 0.91).let { if (it < 0.005) 0.0 else it }
            state.verticalUncertainty = ((state.verticalUncertainty - environment.gravity.coerceIn(0.01, 0.2)) * 0.98)
                .let { if (it < 0.005) 0.0 else it }
            movementEvaluator.velocityFailure(state, environment)?.let { velocityFailure ->
                applyResults(incoming, state, environment, playerName, listOf(velocityFailure), setOf("movement.velocity.a"))
            }
        } else {
            safeCandidate?.let { state.safePosition = it }
            if (incoming.positionChanged && previous != null) {
                val delta = frame.position - previous.position
                if (supportReliable && state.bedrock != true && state.airTicks >= (if (laggy) 18 else 6)) {
                    val failure = movementEvaluator.groundSpoofFailure(frame.onGround, frame.position, environment, state, laggy)
                    applyResults(incoming, state, environment, playerName, listOfNotNull(failure), setOf("movement.nofall.a"))
                }
                movementEvaluator.baseline(delta, environment, state)
                state.lastVerticalDelta = delta.y
                state.lastHorizontalDelta = sqrt(delta.horizontalLengthSquared())
                state.positionGapTicks = 0
            } else {
                if (supportReliable && state.bedrock != true && (!exempt || state.airTicks >= 10) && (!laggy || state.airTicks >= 18)) {
                    val failure = movementEvaluator.groundSpoofFailure(frame.onGround, frame.position, environment, state, laggy)
                    applyResults(incoming, state, environment, playerName, listOfNotNull(failure), setOf("movement.nofall.a"))
                }
                movementEvaluator.idle(environment, state)
                state.positionGapTicks = 0
            }
        }
        if (!state.explicitClientTicks && supportReliable) {
            // Only accumulate/clear airborne ticks when the support snapshot is trustworthy; with a
            // stale snapshot we neither confirm airborne nor reset, so a moving legit player is not
            // counted as hovering while a genuinely airborne cheater still accrues on fresh ticks.
            state.airTicks = if (physicalSupporting) 0 else state.airTicks + 1
        }
        if (incoming.rotationChanged) {
            if (!state.lastRotationYaw.isNaN()) {
                state.lastYawDelta = abs(yawDelta(frame.yaw, state.lastRotationYaw))
                state.lastRotationAt = incoming.receivedAt
            }
            state.lastRotationYaw = frame.yaw
        }
        state.physicalSupporting = physicalSupporting
        state.lastMovement = frame
        state.lastWorldId = environment.worldId
    }

    private fun processAction(frame: ClientActionFrame, state: PlayerState) {
        when (frame.action) {
            ClientAction.START_SPRINTING -> state.sprinting = true
            ClientAction.STOP_SPRINTING -> state.sprinting = false
            ClientAction.START_SNEAKING -> state.sneaking = true
            ClientAction.STOP_SNEAKING -> state.sneaking = false
            ClientAction.START_ELYTRA -> {
                state.resetMotion()
                exemptions.exempt(frame.playerId, Duration.ofMillis(exemptionConfig.teleportMillis), "elytra-action")
            }
            ClientAction.OTHER -> Unit
        }
    }

    private fun processIdentity(frame: ClientIdentityFrame, state: PlayerState, playerName: String?) {
        val channel = frame.channel.lowercase().take(128)
        state.clientChannels += channel
        if (channel == "minecraft:brand" || channel == "mc|brand") {
            ClientClassifier.decodeBrand(frame.payload)?.let { state.clientBrand = it }
        }
        if (channel == "minecraft:register" || channel == "register") {
            state.clientChannels += ClientClassifier.decodeRegisteredChannels(frame.payload)
        }
        while (state.clientChannels.size > 128) state.clientChannels.remove(state.clientChannels.first())
        state.clientFingerprint = ClientClassifier.classify(state.clientBrand, state.clientChannels)
        val logKey = "${state.clientFingerprint.family}|${state.clientBrand}|${state.clientChannels.size}"
        if (logKey != state.identityLogKey && state.clientFingerprint.family != "Unknown") {
            state.identityLogKey = logKey
            logger.info(
                "Client identity ${playerName ?: frame.playerId}: ${state.clientFingerprint.family} " +
                    "(${state.clientFingerprint.confidence}), brand=${state.clientBrand ?: "unknown"}, " +
                    "channels=${state.clientChannels.size}"
            )
        }
    }

    private fun reportIdentity(
        frame: PacketFrame,
        state: PlayerState,
        environment: EnvironmentFrame,
        playerName: String
    ) {
        val fingerprint = state.clientFingerprint
        val failures = buildList {
            if (fingerprint.suspicious && !state.identityReported) {
                state.identityReported = true
                add(
                    CheckFailure(
                        "client.identity.a",
                        5.0,
                        mapOf(
                            "family" to fingerprint.family,
                            "confidence" to fingerprint.confidence,
                            "brand" to (state.clientBrand ?: "unknown"),
                            "signals" to fingerprint.signals.joinToString(","),
                            "channels" to state.clientChannels.take(12).joinToString(",")
                        )
                    )
                )
            }
            if (fingerprint.brandSpoofed && !state.brandSpoofReported) {
                state.brandSpoofReported = true
                add(
                    CheckFailure(
                        "client.brand_spoof.a",
                        1.0,
                        mapOf(
                            "brand" to (state.clientBrand ?: "unknown"),
                            "conflict" to fingerprint.brandSpoofSignals.joinToString(","),
                            "assessment" to "mod-loader traffic conflicts with declared vanilla brand; suspicion only"
                        )
                    )
                )
            }
        }
        if (failures.isEmpty()) return
        applyResults(frame, state, environment, playerName, failures, failures.mapTo(HashSet(), CheckFailure::checkId))
    }

    private fun processClientAbilities(frame: ClientAbilitiesFrame, state: PlayerState) {
        state.clientFlying = frame.flying
        if (frame.flying || state.serverFlightAllowed) {
            state.resetMotion()
            exemptions.exempt(frame.playerId, Duration.ofMillis(exemptionConfig.teleportMillis), "flight-ability")
        }
    }

    private fun processClientTick(frame: ClientTickFrame, state: PlayerState, environment: EnvironmentFrame?, playerName: String?) {
        state.explicitClientTicks = true
        state.clientTick++
        state.positionGapTicks = (state.positionGapTicks + 1).coerceAtMost(3)
        if (environment != null) state.airTicks = if (state.physicalSupporting) 0 else state.airTicks + 1
        state.clientTickTimes.addLast(frame.receivedAt)
        while (state.clientTickTimes.firstOrNull()?.let { frame.receivedAt - it > 10_000 } == true) state.clientTickTimes.removeFirst()
        val first = state.clientTickTimes.firstOrNull()
        if (environment != null && playerName != null && first != null && frame.receivedAt - first >= 9_000 &&
            state.clientTickTimes.size >= 190 && frame.receivedAt - state.lastTimerFailureAt >= 1_000
        ) {
            val rate = (state.clientTickTimes.size - 1) * 1000.0 / (frame.receivedAt - first).coerceAtLeast(1)
            if (rate > 21.2 && state.confirmedRttMillis < 500 && environment.tps >= 19.0) {
                state.lastTimerFailureAt = frame.receivedAt
                applyResults(
                    frame, state, environment, playerName,
                    listOf(CheckFailure("movement.timer.a", 1.0, mapOf("clientTps" to rate.rounded(), "samples" to state.clientTickTimes.size, "rtt" to state.confirmedRttMillis))),
                    setOf("movement.timer.a")
                )
            }
        }
    }

    private fun processVelocity(frame: ServerVelocityFrame, state: PlayerState) {
        state.serverVelocity = frame.velocity
        state.velocityAt = frame.receivedAt
        state.lastVerticalDelta = frame.velocity.y
        state.pendingVelocity = frame.velocity
        state.pendingVelocityTick = state.clientTick
        state.velocityObservedHorizontal = 0.0
        state.velocityObservedVertical = 0.0
        // Open a decaying possible-movement window for the knockback the client will carry for the
        // next ticks; the short exemption still covers the immediate impact tick.
        state.horizontalUncertainty = max(state.horizontalUncertainty, sqrt(frame.velocity.horizontalLengthSquared()))
        state.verticalUncertainty = max(state.verticalUncertainty, max(0.0, frame.velocity.y))
        exemptions.exempt(frame.playerId, Duration.ofMillis(exemptionConfig.velocityMillis), frame.source)
    }

    private fun processTeleport(frame: ServerTeleportFrame, state: PlayerState) {
        val previous = state.lastMovement
        val basePosition = previous?.position ?: Vec3(0.0, 0.0, 0.0)
        state.resetMotion()
        state.pendingTeleportTarget = Vec3(
            if (frame.relativeX) basePosition.x + frame.position.x else frame.position.x,
            if (frame.relativeY) basePosition.y + frame.position.y else frame.position.y,
            if (frame.relativeZ) basePosition.z + frame.position.z else frame.position.z
        )
        state.pendingTeleportId = frame.teleportId
        state.serverVelocity = frame.deltaMovement
        state.velocityAt = frame.receivedAt
        exemptions.exempt(frame.playerId, Duration.ofMillis(exemptionConfig.teleportMillis), "server-teleport")
    }

    private fun processTeleportConfirm(frame: TeleportConfirmFrame, state: PlayerState) {
        if (state.pendingTeleportId == frame.teleportId) {
            state.pendingTeleportId = null
            exemptions.exempt(frame.playerId, Duration.ofMillis(250), "teleport-confirm")
        }
    }

    private fun processServerAbilities(frame: ServerAbilitiesFrame, state: PlayerState) {
        state.serverFlying = frame.flying
        state.serverFlightAllowed = frame.flightAllowed
        state.serverCreative = frame.creative
        state.movementScale = frame.movementScale
        if (frame.flying || frame.flightAllowed || frame.creative) {
            state.resetMotion()
            exemptions.exempt(frame.playerId, Duration.ofMillis(exemptionConfig.teleportMillis), "server-abilities")
        }
    }

    private fun processReset(frame: ResetFrame, state: PlayerState) {
        state.resetMotion()
        state.pendingTeleportId = null
        state.serverVelocity = Vec3(0.0, 0.0, 0.0)
        state.sprinting = false
        state.sneaking = false
        state.pendingVelocity = null
        state.clientTickTimes.clear()
        state.lastInteractionSequence = -1
        exemptions.exempt(frame.playerId, Duration.ofMillis(exemptionConfig.respawnMillis), frame.reason)
    }

    private fun processTimeline(frame: TimelineFrame, state: PlayerState) {
        val pending = when (frame.kind) {
            TimelineKind.SERVER_PING -> state.pendingPings.also { it[frame.id] = frame.receivedAt }
            TimelineKind.SERVER_KEEP_ALIVE -> state.pendingKeepAlives.also { it[frame.id] = frame.receivedAt }
            TimelineKind.CLIENT_PONG -> state.pendingPings
            TimelineKind.CLIENT_KEEP_ALIVE -> state.pendingKeepAlives
        }
        if (frame.kind == TimelineKind.CLIENT_PONG || frame.kind == TimelineKind.CLIENT_KEEP_ALIVE) {
            pending.remove(frame.id)?.let { sent -> state.confirmedRttMillis = (frame.receivedAt - sent).coerceAtLeast(0) }
        }
        while (pending.size > 32) pending.remove(pending.keys.first())
    }

    private fun processBlockAction(frame: BlockActionFrame, state: PlayerState, environment: EnvironmentFrame, playerName: String) {
        val failures = ArrayList<CheckFailure>()
        state.attackTimes.addLast(frame.receivedAt)
        trimTimes(state.attackTimes, frame.receivedAt, 3_000)
        if (state.attackTimes.size >= 40) {
            val duration = state.attackTimes.last() - state.attackTimes.first()
            if (duration >= 2_000) {
                val intervals = state.attackTimes.zipWithNext { first, second -> (second - first).toDouble() }
                val mean = intervals.average()
                val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
                val deviation = sqrt(variance)
                val cps = (state.attackTimes.size - 1) * 1000.0 / duration
                if (cps > 13.0 && deviation / mean.coerceAtLeast(1.0) < 0.035) {
                    failures += CheckFailure(
                        "combat.autoclicker.a", 1.0,
                        mapOf("cps" to cps.rounded(), "meanInterval" to mean.rounded(), "deviation" to deviation.rounded(), "samples" to intervals.size)
                    )
                }
            }
        }
        if (frame.interactionSequence >= 0 && state.lastInteractionSequence >= 0 && frame.interactionSequence < state.lastInteractionSequence - 4) {
            failures += CheckFailure(
                "protocol.badpackets.a", 1.0,
                mapOf("reason" to "interaction-sequence-regression", "sequence" to frame.interactionSequence, "previous" to state.lastInteractionSequence)
            )
        }
        if (frame.interactionSequence >= 0) state.lastInteractionSequence = max(state.lastInteractionSequence, frame.interactionSequence)
        if (frame.action == BlockAction.PLACE && listOf(frame.cursorX, frame.cursorY, frame.cursorZ).any { it !in 0.0f..1.0f || !it.isFinite() }) {
            failures += CheckFailure(
                "protocol.badpackets.a", 2.0, mapOf("reason" to "invalid-block-cursor"),
                deterministic = true
            )
        }
        val movement = state.lastMovement
        if (movement != null && frame.action != BlockAction.USE_ITEM) {
            val center = Vec3(frame.blockX + 0.5, frame.blockY + 0.5, frame.blockZ + 0.5)
            val eye = movement.position + Vec3(0.0, environment.eyeHeight, 0.0)
            val distance = sqrt((center - eye).lengthSquared())
            if (distance > 6.5) {
                failures += CheckFailure("world.interactionreach.a", 1.0, mapOf("distance" to distance.rounded(), "action" to frame.action.name))
            }
            if (frame.action == BlockAction.PLACE) {
                state.placementTimes.addLast(frame.receivedAt)
                trimTimes(state.placementTimes, frame.receivedAt, 1_000)
                val direction = Vec3.direction(movement.yaw, movement.pitch)
                val targetDirection = (center - eye).normalized()
                val dot = direction.x * targetDirection.x + direction.y * targetDirection.y + direction.z * targetDirection.z
                if (state.placementTimes.size >= 6 && frame.blockY <= movement.position.y - 0.4 && dot < 0.25) {
                    failures += CheckFailure(
                        "world.scaffold.a", 1.0,
                        mapOf("placementsPerSecond" to state.placementTimes.size, "lookDot" to dot.rounded(), "distance" to distance.rounded())
                    )
                }
                if (state.placementTimes.size >= 9) {
                    failures += CheckFailure("world.fastplace.a", 1.0, mapOf("placementsPerSecond" to state.placementTimes.size))
                }
            }
        }
        val key = "${frame.blockX},${frame.blockY},${frame.blockZ}"
        when (frame.action) {
            BlockAction.START_DIG -> {
                state.digStarts[key] = frame.receivedAt
                state.digTimes.addLast(frame.receivedAt)
                trimTimes(state.digTimes, frame.receivedAt, 1_000)
                if (state.digTimes.size >= 12) failures += CheckFailure("world.nuker.a", 1.0, mapOf("startsPerSecond" to state.digTimes.size))
                // No-facing: starting to dig a block the player is not looking at (nuker/no-rotation tell).
                if (movement != null) {
                    val center = Vec3(frame.blockX + 0.5, frame.blockY + 0.5, frame.blockZ + 0.5)
                    val eye = movement.position + Vec3(0.0, environment.eyeHeight, 0.0)
                    val look = Vec3.direction(movement.yaw, movement.pitch)
                    val toBlock = (center - eye).normalized()
                    val dot = look.x * toBlock.x + look.y * toBlock.y + look.z * toBlock.z
                    if (dot < 0.2) failures += CheckFailure("world.nofacing.a", 1.0, mapOf("lookDot" to dot.rounded(), "block" to key))
                }
            }
            BlockAction.FINISH_DIG -> state.digStarts.remove(key)?.let { started ->
                val duration = frame.receivedAt - started
                if (duration in 0..74) failures += CheckFailure("world.fastbreak.a", 0.5, mapOf("durationMillis" to duration, "block" to key))
            }
            BlockAction.CANCEL_DIG -> state.digStarts.remove(key)
            else -> Unit
        }
        while (state.digStarts.size > 32) state.digStarts.remove(state.digStarts.keys.first())
        applyResults(
            frame, state, environment, playerName, failures,
            setOf("protocol.badpackets.a", "world.interactionreach.a", "world.scaffold.a", "world.fastplace.a", "world.nuker.a", "world.fastbreak.a", "world.nofacing.a")
        )
    }

    private fun processInventoryClick(frame: InventoryClickFrame, state: PlayerState, environment: EnvironmentFrame, playerName: String) {
        val invalid = frame.windowId < 0 || frame.windowId > 127 || frame.slot < -999 || frame.slot > 127 || frame.button !in -1..8
        val regressed = frame.stateId?.let { current -> state.lastInventoryStateId?.let { current < it - 8 } ?: false } ?: false
        frame.stateId?.let { state.lastInventoryStateId = max(state.lastInventoryStateId ?: it, it) }
        val failures = buildList {
            if (invalid || regressed) add(
                CheckFailure(
                    "inventory.impossible.a", if (invalid) 2.0 else 1.0,
                    mapOf("window" to frame.windowId, "state" to (frame.stateId ?: -1), "slot" to frame.slot, "button" to frame.button, "type" to frame.clickType),
                    deterministic = invalid
                )
            )
            state.lastInventoryClickAt = frame.receivedAt
            if (state.sprinting && state.lastHorizontalDelta > 0.12) {
                state.inventoryMoveTimes.addLast(frame.receivedAt)
                trimTimes(state.inventoryMoveTimes, frame.receivedAt, 2_000)
                if (state.inventoryMoveTimes.size >= 5) {
                    add(CheckFailure("inventory.move.a", 1.0, mapOf("clicksWhileMoving" to state.inventoryMoveTimes.size, "horizontal" to state.lastHorizontalDelta.rounded(), "sprinting" to true)))
                }
            }
        }
        applyResults(frame, state, environment, playerName, failures, setOf("inventory.impossible.a", "inventory.move.a"))
    }

    private fun processAttack(
        frame: AttackFrame,
        state: PlayerState,
        environment: EnvironmentFrame,
        profile: VersionProfile,
        playerName: String
    ) {
        if (exemptions.isExempt(frame.playerId, frame.receivedAt) || environment.exemptEnvironment || !environment.chunkLoaded) return
        val movement = state.lastMovement ?: return
        val target = snapshots.target(frame.targetEntityId) ?: return
        if (target.playerId == frame.playerId || target.current.worldId != environment.worldId) return
        val compensatedAt = frame.receivedAt - environment.ping.coerceIn(0, 300) / 2L
        val targetFrame = snapshots.frameAt(target, compensatedAt)
        val attackerFrame = snapshots.view(frame.playerId)?.let { snapshots.frameAt(it, compensatedAt) } ?: environment
        val eyeHeight = if (attackerFrame.entityBox.maxY - attackerFrame.entityBox.minY < 1.0) 0.4 else 1.62
        val origin = movement.position + Vec3(0.0, eyeHeight, 0.0)
        val direction = Vec3.direction(movement.yaw, movement.pitch)
        val tolerance = 0.1 + environment.ping.coerceIn(0, 300) * 0.0005
        val distance = targetFrame.entityBox.expand(tolerance).rayDistance(origin, direction, profile.reach + 1.0)
        val failures = ArrayList<CheckFailure>()
        if (frame.receivedAt - state.lastInventoryClickAt in 0..75) {
            state.inventoryAttackTimes.addLast(frame.receivedAt)
            trimTimes(state.inventoryAttackTimes, frame.receivedAt, 5_000)
            if (state.inventoryAttackTimes.size >= 3) {
                failures += CheckFailure("combat.inventory.a", 1.0, mapOf("attacksAfterInventory" to state.inventoryAttackTimes.size, "delayMillis" to frame.receivedAt - state.lastInventoryClickAt))
            }
        }
        if (distance != null && distance > profile.reach + tolerance) {
            failures += CheckFailure("combat.reach.a", 1.0, mapOf("distance" to distance.rounded(), "limit" to (profile.reach + tolerance).rounded(), "ping" to environment.ping))
        }
        val rotationHit = targetFrame.entityBox.expand(0.22 + tolerance).rayDistance(origin, direction, profile.reach + 1.0) != null
        if (frame.receivedAt - state.lastAttackAt > 500) state.rotationMisses = 0
        state.rotationMisses = if (rotationHit) 0 else state.rotationMisses + 1
        state.lastAttackAt = frame.receivedAt
        if (state.rotationMisses >= 3) {
            failures += CheckFailure("combat.rotation.a", 1.0, mapOf("misses" to state.rotationMisses, "target" to target.playerName))
        }
        if (state.attackTick == state.clientTick && state.attackTarget != -1 && state.attackTarget != frame.targetEntityId) {
            failures += CheckFailure("combat.multitarget.a", 1.5, mapOf("firstTarget" to state.attackTarget, "secondTarget" to frame.targetEntityId, "clientTick" to state.clientTick))
        }
        state.attackTick = state.clientTick
        state.attackTarget = frame.targetEntityId
        // No-swing: a real client sends an arm-swing animation with every hit; killaura often skips it.
        if (frame.receivedAt - state.lastSwingAt > 400) {
            failures += CheckFailure("combat.noswing.a", 1.0, mapOf("sinceSwingMs" to (frame.receivedAt - state.lastSwingAt)))
        }
        // Snap-aim: an attack immediately after an impossibly large single-tick rotation.
        if (frame.receivedAt - state.lastRotationAt <= SNAP_AIM_WINDOW_MILLIS && state.lastYawDelta > SNAP_AIM_DEGREES) {
            failures += CheckFailure("combat.snapaim.a", 1.0, mapOf("yawDelta" to state.lastYawDelta.rounded(), "sinceRotMs" to (frame.receivedAt - state.lastRotationAt)))
        }
        applyResults(frame, state, environment, playerName, failures, setOf("combat.reach.a", "combat.rotation.a", "combat.multitarget.a", "combat.autoclicker.a", "combat.inventory.a", "combat.noswing.a", "combat.snapaim.a"))
    }

    private fun applyResults(
        frame: PacketFrame,
        state: PlayerState,
        environment: EnvironmentFrame,
        playerName: String,
        failures: List<CheckFailure>,
        evaluated: Set<String>
    ) {
        val settings = dynamic.get()
        val failedIds = failures.mapTo(HashSet(), CheckFailure::checkId)
        for (checkId in evaluated - failedIds) {
            val config = settings.checks[checkId] ?: continue
            state.violations.computeIfPresent(checkId) { _, value -> (value - config.decay).coerceAtLeast(0.0) }
        }
        for (failure in failures) {
            val config = settings.checks[failure.checkId] ?: continue
            if (!config.enabled) continue
            val lastFailure = state.lastFailureAt[failure.checkId] ?: Long.MIN_VALUE
            if (lastFailure != Long.MIN_VALUE && frame.receivedAt - lastFailure < detectionConfig.signalCooldownMillis) continue
            state.lastFailureAt[failure.checkId] = frame.receivedAt
            val violationLevel = (state.violations[failure.checkId] ?: 0.0) + failure.weight
            state.violations[failure.checkId] = violationLevel
            val incident = incidentTracker.assign(frame, playerName, failure)
            // Forensic provenance: flag violations raised during/just after a queue-overload window,
            // where dropped frames may have distorted the motion predictor. Stored in existing JSONB.
            val evidence = if (frame.receivedAt < state.underloadUntil) failure.evidence + ("underload" to true) else failure.evidence
            val record = ViolationRecord(
                frame.receivedAt,
                serverId,
                frame.playerId,
                playerName,
                failure.checkId,
                violationLevel,
                environment.worldId,
                environment.position,
                environment.ping,
                environment.tps,
                evidence,
                incident.incidentId,
                incident.confidence,
                incident.shadowAction
            )
            storage.enqueue(record, incident.incident)
            if (violationLevel >= config.alertVl) alerts.record(record)
            // Setback is the primary reaction for every movement check: a movement cheat is neutralised
            // by rewinding the player, so a false positive can never ban anyone. Bans stay for the
            // confidence pipeline (deterministic/multi-family), keeping enforcement conservative.
            if (config.setbackVl >= 0.0 && violationLevel >= config.setbackVl && failure.checkId in SETBACK_CHECKS) {
                state.safePosition?.let { setbacks.request(frame.playerId, it, failure.checkId) }
            }
        }
    }

    private fun publish(frame: PacketFrame, state: PlayerState, playerName: String, supported: Boolean) {
        published[frame.playerId] = IGuardPlayerSnapshot(
            frame.playerId,
            playerName,
            frame.clientVersion,
            state.clientBrand,
            state.clientFingerprint.family,
            state.clientFingerprint.confidence,
            state.clientChannels.toSet(),
            supported,
            exemptions.expiresAt(frame.playerId),
            state.violations.toMap(),
            state.droppedPackets,
            Instant.ofEpochMilli(frame.receivedAt)
        )
    }
}

private fun MovementFrame.withFallback(previous: MovementFrame?, environment: EnvironmentFrame): MovementFrame {
    val fallbackPosition = previous?.position ?: environment.position
    val fallbackYaw = previous?.yaw ?: environment.yaw
    val fallbackPitch = previous?.pitch ?: environment.pitch
    return copy(
        position = if (positionChanged) position else fallbackPosition,
        yaw = if (rotationChanged) yaw else fallbackYaw,
        pitch = if (rotationChanged) pitch else fallbackPitch
    )
}

// Shortest signed angular difference between two yaws in degrees (handles the ±180 wraparound).
private fun yawDelta(a: Float, b: Float): Double {
    var d = (a - b).toDouble() % 360.0
    if (d > 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return d
}

// Attack within this window after a huge single-tick rotation counts as an aim snap (combat.snapaim).
private const val SNAP_AIM_DEGREES = 60.0
private const val SNAP_AIM_WINDOW_MILLIS = 160L

private const val PUBLISH_INTERVAL_MILLIS = 200L

// Max age of a player's sampled environment for its collision/support data to be trusted by
// support-dependent checks (~1 server tick). Beyond this the snapshot may lag a moving player.
private const val SUPPORT_FRESH_MILLIS = 60L

// Movement checks neutralised by a setback instead of a punishment: rewinding the player defeats the
// cheat, so a false positive can never escalate to a ban.
private val SETBACK_CHECKS = setOf(
    "movement.fly.a", "movement.speed.a", "movement.nofall.a", "movement.phase.a",
    "movement.step.a", "movement.spider.a", "movement.jesus.a", "movement.highjump.a",
    "movement.airjump.a"
)

private fun Double.rounded() = round(this * 10000.0) / 10000.0

private fun trimTimes(times: ArrayDeque<Long>, now: Long, windowMillis: Long) {
    while (times.firstOrNull()?.let { now - it > windowMillis } == true) times.removeFirst()
}
