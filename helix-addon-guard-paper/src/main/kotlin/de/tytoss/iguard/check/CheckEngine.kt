package de.tytoss.iguard.check

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import de.tytoss.iguard.api.ExemptionManager
import de.tytoss.iguard.api.IGuardCheckResult
import de.tytoss.iguard.api.IGuardExemption
import de.tytoss.iguard.api.IGuardExemptionScope
import de.tytoss.iguard.api.IGuardPlayerSnapshot
import de.tytoss.iguard.config.CheckConfig
import de.tytoss.iguard.config.DetectionConfig
import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.config.ExemptionConfig
import de.tytoss.iguard.config.SanctionConfig
import de.tytoss.iguard.model.AttackFrame
import de.tytoss.iguard.model.BlockAction
import de.tytoss.iguard.model.BlockActionFrame
import de.tytoss.iguard.model.ClientAbilitiesFrame
import de.tytoss.iguard.model.ClientAction
import de.tytoss.iguard.model.ClientActionFrame
import de.tytoss.iguard.model.ClientIdentityFrame
import de.tytoss.iguard.model.ClientTickFrame
import de.tytoss.iguard.model.EnvironmentFrame
import de.tytoss.iguard.model.EnvironmentTags
import de.tytoss.iguard.model.InventoryClickFrame
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.PacketFrame
import de.tytoss.iguard.model.ResetFrame
import de.tytoss.iguard.model.SafePosition
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
import de.tytoss.iguard.snapshot.SnapshotStore
import de.tytoss.iguard.storage.GuardStore
import de.tytoss.iguard.trace.FrameObserver
import de.tytoss.iguard.trace.FrameTrace
import de.tytoss.iguard.trace.TraceTarget
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

internal data class CheckFailure(
    val checkId: String,
    val weight: Double,
    val evidence: Map<String, Any>,
    val family: de.tytoss.iguard.model.EvidenceFamily = ConfidenceModel.family(checkId),
    val deterministic: Boolean = false,
)

/** A started dig: packet time, client tick and whether that tick came from tick-end packets. */
internal data class DigStart(val at: Long, val tick: Long, val explicitTicks: Boolean)

internal class PlayerState {
    var lastSequence = 0L
    var lastMovement: MovementFrame? = null
    var lastVerticalDelta = 0.0
    var lastHorizontalDelta = 0.0
    var airTicks = 0
    var clientTick = 0L
    var attackTick = -1L
    var attackTarget = -1
    var attackTargetPlayer = false
    var rotationMisses = 0
    var lastAttackAt = 0L
    var noSwingStreak = 0
    var snapAimStreak = 0
    var sprintBackStreak = 0

    /** Consecutive movement frames spent gliding without a net-descending tick; see [MovementEvaluator.elytraFailure]. */
    var elytraLevelStreak = 0

    /** Consecutive frames hovering level right above a liquid (surface Jesus check). */
    var surfaceHoverStreak = 0
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
    // Slipperiness of the block last stood on: vanilla applies the START-of-tick block's friction, which
    // the takeoff frame (already above air) can no longer sample.
    var lastGroundSlipperiness = 0.6
    // Sustained-speed window: observed vs. vanilla-possible horizontal distance over clean frames.
    var speedWindowFrames = 0
    var speedWindowObserved = 0.0
    var speedWindowVanilla = 0.0
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
    val digStarts = HashMap<String, DigStart>()
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

    /** Time of each check's last verdict (pass or fail); decay runs on the time between verdicts. */
    val violationTouchedAt = HashMap<String, Long>()

    /** Whether each check's last verdict was a failure (continuous failing pauses the decay). */
    val lastVerdictFailed = HashMap<String, Boolean>()

    /** The pending server-teleport exemption, cancelled as soon as the client confirms the teleport. */
    var teleportExemption: IGuardExemption? = null

    /** Observation of the frame being processed, null when nobody observes the player. */
    var observation: FrameObservation? = null

    /** Reused observation buffer (allocated on first observation only). */
    var observationBuffer: FrameObservation? = null

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
        sprintBackStreak = 0
        elytraLevelStreak = 0
        surfaceHoverStreak = 0
    }
}

/** Per-frame collection of verdicts and internals while the player is observed (traces, debug, listeners). */
internal class FrameObservation {
    val results = ArrayList<IGuardCheckResult>(8)
    val exemptions = ArrayList<String>(4)
    var exempt = false
    var delta: Vec3? = null
    var physicalSupport: Boolean? = null
    val limits = LinkedHashMap<String, Double>()
    val metrics = LinkedHashMap<String, Any>()
    val targets = ArrayList<TraceTarget>(1)

    /** Resets the buffer for the next frame. */
    fun clear() {
        results.clear()
        exemptions.clear()
        exempt = false
        delta = null
        physicalSupport = null
        limits.clear()
        metrics.clear()
        targets.clear()
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
    private val alerts: AlertSink,
    private val setbacks: SetbackSink,
    private val enforcement: Enforcement,
    private val notifications: de.tytoss.iguard.notify.NotificationService,
    private val logger: Logger,
    private val observer: FrameObserver = FrameObserver.None,
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
            CoroutineExceptionHandler { _, error -> logger.severe("IGuard check worker failed: ${error.stackTraceToString()}") },
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

    /**
     * Processes a frame synchronously on the calling thread (trace replays and tests). Never mix it
     * with [submit] for the same player while the workers run.
     */
    internal fun processNow(frame: PacketFrame) = process(frame)

    /** All current violation levels of the player (tests, replays). */
    internal fun violationLevels(playerId: UUID): Map<String, Double> = states[playerId]?.violations?.toMap().orEmpty()

    /** The current violation level of one check, 0 when the player never failed it (tests, replays). */
    internal fun violationLevel(playerId: UUID, checkId: String): Double = states[playerId]?.violations?.get(checkId) ?: 0.0

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
        val observation = if (observer.wants(frame.playerId)) {
            (state.observationBuffer ?: FrameObservation().also { state.observationBuffer = it }).also(FrameObservation::clear)
        } else {
            null
        }
        state.observation = observation
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
        if (observation != null) {
            state.observation = null
            emitTrace(frame, state, observation, view?.current, view?.playerName ?: frame.playerId.toString())
        }
        // Throttle the observability snapshot: it is consumed only by /iguard info|clients and the API,
        // so rebuilding it (with map/set copies) every packet was pure hot-path waste. Always publish
        // the first frame so a newly tracked player appears immediately.
        if (!published.containsKey(frame.playerId) || frame.receivedAt - state.lastPublishAt >= PUBLISH_INTERVAL_MILLIS) {
            state.lastPublishAt = frame.receivedAt
            publish(frame, state, view?.playerName ?: frame.playerId.toString(), profile != null)
        }
    }

    private fun emitTrace(frame: PacketFrame, state: PlayerState, observation: FrameObservation, environment: EnvironmentFrame?, playerName: String) {
        val trace = FrameTrace(
            frame,
            playerName,
            environment,
            state.clientTick,
            observation.exempt,
            observation.exemptions.toList(),
            observation.results.toList(),
            state.violations.filterValues { it > 0.0 },
            observation.delta,
            observation.physicalSupport,
            linkedMapOf(
                "predictedHorizontal" to state.predictedHorizontal.rounded(),
                "predictedVertical" to state.predictedVertical.rounded(),
                "horizontalUncertainty" to state.horizontalUncertainty.rounded(),
                "verticalUncertainty" to state.verticalUncertainty.rounded(),
                "airTicks" to state.airTicks,
                "positionGapTicks" to state.positionGapTicks,
                "lastVerticalDelta" to state.lastVerticalDelta.rounded(),
                "lastHorizontalDelta" to state.lastHorizontalDelta.rounded(),
                "sprinting" to state.sprinting,
                "sneaking" to state.sneaking,
                "rttMillis" to state.confirmedRttMillis,
            ),
            LinkedHashMap(observation.limits),
            LinkedHashMap(observation.metrics),
            observation.targets.toList(),
        )
        runCatching { observer.observe(trace) }
            .onFailure { logger.warning("IGuard frame observer failed: ${it.message}") }
    }

    private fun warnOverload(playerId: UUID) {
        val now = System.currentTimeMillis()
        val previous = warningTimes.putIfAbsent(playerId, now)
        if (previous == null || now - previous >= 10000) {
            warningTimes[playerId] = now
            logger.warning("Worker queue overloaded for $playerId; total dropped packets: ${totalDropped.get()}")
        }
    }

    /**
     * Whether the elytra-fly check may run this tick: gliding must be the ONLY unusual environment
     * factor. A player who is also flying/creative/riding/etc. legitimately moves outside normal glide
     * physics for that other reason, so piling the elytra check on top there would just re-introduce
     * the false positives the "flight"/"game-mode"/"vehicle" exemptions already exist to prevent.
     */
    private fun elytraCheckable(environment: EnvironmentFrame): Boolean =
        environment.environmentTags.none { it != EnvironmentTags.ELYTRA && it !in EnvironmentTags.NON_EXEMPTING }

    /** Movement-only grace window granted by the engine itself (frame time based, not reported as external). */
    private fun exemptMovement(frame: PacketFrame, millis: Long, reason: String): IGuardExemption =
        exemptions.exempt(frame.playerId, Duration.ofMillis(millis.coerceAtLeast(1)), reason, IGuardExemptionScope.MOVEMENT, frame.receivedAt, external = false)

    private fun processMovement(
        incoming: MovementFrame,
        state: PlayerState,
        environment: EnvironmentFrame,
        profile: VersionProfile,
        playerName: String,
    ) {
        val observation = state.observation
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
            // Clients without the 1.21.2+ tick-end packet (older versions, also behind ViaVersion) send
            // at most one movement packet per client tick, so the movement stream is their tick clock.
            recordClientTick(incoming, state, environment, playerName, "movement")
        }
        val stale = incoming.receivedAt - environment.capturedAt > exemptionConfig.snapshotMaxAgeMillis
        // The environment's collision scan covers a ±2-column neighborhood around the SAMPLED
        // position, which lags the packet stream by 1-2 ticks. A frame outside that neighborhood
        // must be treated as "we could not check" — judging support against a scan that does not
        // even contain the player's block manufactured phantom-airborne ticks and fly/speed flags
        // on plain sprint-jumping.
        val covered = abs(frame.position.x - environment.position.x) <= SCAN_COVERAGE_XZ &&
            abs(frame.position.z - environment.position.z) <= SCAN_COVERAGE_XZ &&
            frame.position.y - environment.position.y in -SCAN_COVERAGE_DOWN..SCAN_COVERAGE_UP
        val worldChanged = state.lastWorldId != null && state.lastWorldId != environment.worldId
        // "liquid" alone is not exempting (the jesus check runs there), but it must never cancel the
        // OTHER exempting tags: a boat ON water is still a vehicle, riptide IN water is still riptide —
        // treating those as checkable produced jesus/highjump false positives for every boat passenger.
        // Context tags (low TPS, liquid surface) never exempt.
        val exemptingTags = EnvironmentTags.movementExempting(environment.environmentTags)
        val exemptEnvironment = exemptingTags.isNotEmpty()
        // Grossly stale snapshot / unloaded chunk = "we could not check", not "clean". Skip is still
        // FP-safe, but we count it so metrics never mistake a load blind spot for a clean player.
        if ((stale || !covered || !environment.chunkLoaded) && incoming.positionChanged) unevaluatedFrames.incrementAndGet()
        val laggy = environment.tps < exemptionConfig.lowTpsThreshold
        // Support-dependent checks (nofall/ground-spoof, fly-hover) rely on collision boxes sampled for
        // the player's position. Under budgeted sampling a moving player can outrun its last sample, so
        // supports() goes stale-false and would false-positive. Only trust support when the snapshot is
        // fresh (sampled within ~1 tick) AND actually covers the frame's position; otherwise freeze
        // airborne accounting instead of guessing.
        val supportReliable = covered && incoming.receivedAt - environment.capturedAt <= SUPPORT_FRESH_MILLIS
        val timedExempt = exemptions.isExempt(incoming.playerId, incoming.receivedAt)
        val blind = stale || !covered || !environment.chunkLoaded || previous == null || worldChanged || state.bedrock == true
        val exempt = timedExempt || exemptEnvironment || blind
        // Climbables exempt the dry-land model (climbing is not modelled there), but fast-ladder and
        // high-jump are built for exactly that situation: run them whenever the climbable is the ONLY
        // reason for the exemption.
        val climbCheckable = !timedExempt && !blind && exemptingTags.size == 1 && EnvironmentTags.CLIMBABLE in exemptingTags
        if (observation != null && exempt) {
            observation.exempt = true
            if (timedExempt) exemptions.activeReasons(incoming.playerId, incoming.receivedAt).forEach { observation.exemptions += "timed:$it" }
            exemptingTags.forEach { observation.exemptions += "tag:$it" }
            if (stale) observation.exemptions += "stale-snapshot"
            if (!covered) observation.exemptions += "outside-scan"
            if (!environment.chunkLoaded) observation.exemptions += "chunk-unloaded"
            if (previous == null) observation.exemptions += "no-previous-position"
            if (worldChanged) observation.exemptions += "world-changed"
            if (state.bedrock == true) observation.exemptions += "bedrock"
        }
        val safeCandidate = if (environment.supportingCollision && !environment.colliding) {
            SafePosition(environment.worldId, environment.position, environment.yaw, environment.pitch, environment.tick)
        } else null
        var physicalSupporting = movementEvaluator.supports(frame.position, environment)
        // Knockback absorption must be observed on EVERY position frame: the velocity packet itself
        // grants an exemption, so accumulating only on non-exempt frames read as "no knockback taken"
        // once the exemption expired and anti-KB-flagged any legit, by then stationary, player.
        if (incoming.positionChanged && previous != null && state.pendingVelocity != null) {
            val observed = frame.position - previous.position
            state.velocityObservedHorizontal += sqrt(observed.horizontalLengthSquared())
            state.velocityObservedVertical = max(state.velocityObservedVertical, observed.y)
        }
        if (!exempt && incoming.positionChanged) {
            val delta = frame.position - previous.position
            observation?.delta = delta
            val evaluation = movementEvaluator.evaluate(frame, delta, environment, profile, state, laggy, observation?.limits)
            physicalSupporting = evaluation.supporting
            val failures = evaluation.failures
            applyResults(
                incoming, state, environment, playerName, failures,
                setOf("movement.fly.a", "movement.speed.a", "movement.nofall.a", "movement.phase.a", "movement.step.a", "movement.spider.a", "movement.jesus.a"),
            )
            val extra = ArrayList<CheckFailure>(2)
            val extraEvaluated = HashSet<String>(4)
            // The impulse heuristics below model dry-land physics only: swimming (dolphin's grace,
            // ascent out of water) legitimately produces jump-sized impulses and reversed vectors.
            if (EnvironmentTags.LIQUID !in environment.environmentTags) {
                extraEvaluated += listOf("movement.airjump.a", "movement.sprintbackwards.a", "movement.highjump.a")
                // Air-jump: a jump-sized upward impulse while airborne and previously descending — a second
                // jump with no ground contact (support-reliable guard prevents lag FP).
                if (supportReliable && !evaluation.supporting && state.airTicks >= 4 && state.lastVerticalDelta < -0.02 && delta.y > 0.33) {
                    extra += CheckFailure("movement.airjump.a", 1.5, mapOf("dy" to delta.y.rounded(), "prevDy" to state.lastVerticalDelta.rounded(), "airTicks" to state.airTicks))
                }
                // Omni-sprint: sprinting while the movement vector points opposite the look direction.
                // Momentum in the AIR is direction-free (a 180° camera turn mid sprint-jump is normal
                // play), so only grounded ticks count — and ground friction kills reverse momentum
                // within ~2 ticks, so a streak of 3 grounded backwards ticks cannot be legit.
                val horizontal = sqrt(delta.horizontalLengthSquared())
                var backwards = false
                if (state.sprinting && horizontal > 0.15 && evaluation.supporting && supportReliable) {
                    val yawRad = Math.toRadians(frame.yaw.toDouble())
                    val dot = (delta.x * -sin(yawRad) + delta.z * cos(yawRad)) / horizontal
                    backwards = dot < -0.4
                    if (backwards && state.sprintBackStreak >= 2) {
                        extra += CheckFailure("movement.sprintbackwards.a", 1.0, mapOf("lookDot" to dot.rounded(), "horizontal" to horizontal.rounded(), "streak" to state.sprintBackStreak + 1))
                    }
                }
                state.sprintBackStreak = if (backwards) state.sprintBackStreak + 1 else 0
                movementEvaluator.highJumpFailure(delta, environment, state, supportReliable, observation?.limits)?.let(extra::add)
            }
            if (extraEvaluated.isNotEmpty()) applyResults(incoming, state, environment, playerName, extra, extraEvaluated)
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
            // Exemptions are absolute for the ground-spoof check too: a mounted or otherwise exempt
            // player is legitimately onGround with no block support (the vehicle carries them), so the
            // old "airTicks >= 10 overrides the exemption" escape hatch nofall-flagged every rider.
            if (incoming.positionChanged && previous != null) {
                val delta = frame.position - previous.position
                observation?.delta = delta
                if (climbCheckable) {
                    val climbFailures = listOfNotNull(
                        movementEvaluator.fastLadderFailure(frame, delta, environment, state, observation?.limits),
                        movementEvaluator.highJumpFailure(delta, environment, state, supportReliable, observation?.limits),
                    )
                    applyResults(incoming, state, environment, playerName, climbFailures, setOf("movement.fastladder.a", "movement.highjump.a"))
                }
                if (!exempt && supportReliable && state.bedrock != true && state.airTicks >= (if (laggy) 18 else 6)) {
                    val failure = movementEvaluator.groundSpoofFailure(frame.onGround, frame.position, environment, state, laggy)
                    applyResults(incoming, state, environment, playerName, listOfNotNull(failure), setOf("movement.nofall.a"))
                }
                movementEvaluator.baseline(delta, environment, state)
                state.lastVerticalDelta = delta.y
                state.lastHorizontalDelta = sqrt(delta.horizontalLengthSquared())
                state.positionGapTicks = 0
            } else {
                if (!exempt && supportReliable && state.bedrock != true && (!laggy || state.airTicks >= 18)) {
                    val failure = movementEvaluator.groundSpoofFailure(frame.onGround, frame.position, environment, state, laggy)
                    applyResults(incoming, state, environment, playerName, listOfNotNull(failure), setOf("movement.nofall.a"))
                }
                movementEvaluator.idle(environment, state)
                state.positionGapTicks = 0
            }
        }
        // Elytra-fly runs independently of the exempt/non-exempt split above: gliding is (correctly)
        // an exemption from every dry-land check — fly/speed/nofall assume ground-relative physics
        // that do not apply mid-glide — but that left glide-hacking (faking "still gliding" while
        // ignoring gravity) with zero coverage. This check never touches the shared motion predictor
        // (state.predictedHorizontal/Vertical), so it cannot destabilize the dry-land checks for the
        // frame after landing. See MovementEvaluator.elytraFailure for the physics reasoning.
        if (EnvironmentTags.ELYTRA in environment.environmentTags && incoming.positionChanged && previous != null &&
            !laggy && elytraCheckable(environment)
        ) {
            val delta = frame.position - previous.position
            val failure = movementEvaluator.elytraFailure(delta, state)
            applyResults(incoming, state, environment, playerName, listOfNotNull(failure), setOf("movement.elytrafly.a"))
        } else if (EnvironmentTags.ELYTRA !in environment.environmentTags) {
            state.elytraLevelStreak = 0
        }
        if (!state.explicitClientTicks && supportReliable) {
            // Only accumulate/clear airborne ticks when the support snapshot is trustworthy; with a
            // stale snapshot we neither confirm airborne nor reset, so a moving legit player is not
            // counted as hovering while a genuinely airborne cheater still accrues on fresh ticks.
            state.airTicks = if (physicalSupporting) 0 else state.airTicks + 1
        }
        observation?.physicalSupport = physicalSupporting
        if (incoming.rotationChanged) {
            if (!state.lastRotationYaw.isNaN()) {
                state.lastYawDelta = abs(yawDelta(frame.yaw, state.lastRotationYaw))
                state.lastRotationAt = incoming.receivedAt
            }
            state.lastRotationYaw = frame.yaw
        }
        // Only adopt the new support verdict when the sample can be trusted; otherwise keep the last
        // reliable one so the per-tick airborne accounting (processClientTick) never counts phantom
        // air ticks from a lagging scan.
        if (supportReliable) state.physicalSupporting = physicalSupporting
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
                exemptMovement(frame, exemptionConfig.teleportMillis, "elytra-action")
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
                    "channels=${state.clientChannels.size}",
            )
        }
    }

    private fun reportIdentity(
        frame: PacketFrame,
        state: PlayerState,
        environment: EnvironmentFrame,
        playerName: String,
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
                            "channels" to state.clientChannels.take(12).joinToString(","),
                        ),
                    ),
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
                            "assessment" to "mod-loader traffic conflicts with declared vanilla brand; suspicion only",
                        ),
                    ),
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
            exemptMovement(frame, exemptionConfig.teleportMillis, "flight-ability")
        }
    }

    private fun processClientTick(frame: ClientTickFrame, state: PlayerState, environment: EnvironmentFrame?, playerName: String?) {
        if (!state.explicitClientTicks) {
            // The first tick-end packet switches the timer from the movement-packet fallback to the
            // explicit tick clock; mixing both sources would double-count the window.
            state.explicitClientTicks = true
            state.clientTickTimes.clear()
        }
        state.clientTick++
        state.positionGapTicks = (state.positionGapTicks + 1).coerceAtMost(3)
        // Freeze the airborne accounting on a stale support sample (same rule as the legacy-client
        // path): counting phantom air ticks here made the NEXT normal jump miss the takeoff gate and
        // fly-flag with a huge offset.
        if (environment != null && frame.receivedAt - environment.capturedAt <= SUPPORT_FRESH_MILLIS) {
            state.airTicks = if (state.physicalSupporting) 0 else state.airTicks + 1
        }
        recordClientTick(frame, state, environment, playerName, "tick-end")
    }

    /**
     * Timer check: one sample per client tick (the explicit tick-end packet, or a movement packet for
     * clients without it), judged over a 10-second window so lag bursts average out. Evaluated on
     * every tick once the window is full, so the timer VL decays while the rate is legit.
     */
    private fun recordClientTick(frame: PacketFrame, state: PlayerState, environment: EnvironmentFrame?, playerName: String?, source: String) {
        state.clientTickTimes.addLast(frame.receivedAt)
        while (state.clientTickTimes.firstOrNull()?.let { frame.receivedAt - it > TIMER_WINDOW_MILLIS } == true) state.clientTickTimes.removeFirst()
        val first = state.clientTickTimes.firstOrNull() ?: return
        if (environment == null || playerName == null) return
        if (frame.receivedAt - first < TIMER_MIN_SPAN_MILLIS || state.clientTickTimes.size < TIMER_MIN_SAMPLES) return
        // Not judgeable: Bedrock clients tick differently, a high RTT means bursty delivery and a
        // lagging server delays (and then bunches) the packets it reads.
        if (state.bedrock == true || state.confirmedRttMillis >= 500 || environment.tps < 19.0) return
        val rate = (state.clientTickTimes.size - 1) * 1000.0 / (frame.receivedAt - first).coerceAtLeast(1)
        state.observation?.metrics?.put("clientTps", rate.rounded())
        if (rate <= TIMER_RATE_LIMIT) {
            applyResults(frame, state, environment, playerName, emptyList(), TIMER_CHECKS)
            return
        }
        if (frame.receivedAt - state.lastTimerFailureAt < 1_000) {
            applyResults(frame, state, environment, playerName, emptyList(), TIMER_CHECKS, held = TIMER_CHECKS)
            return
        }
        state.lastTimerFailureAt = frame.receivedAt
        applyResults(
            frame, state, environment, playerName,
            listOf(
                CheckFailure(
                    "movement.timer.a", 1.0,
                    mapOf("clientTps" to rate.rounded(), "samples" to state.clientTickTimes.size, "rtt" to state.confirmedRttMillis, "source" to source),
                ),
            ),
            TIMER_CHECKS,
        )
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
        // Knockback only affects movement: the hit player stays fully judgeable for reach/killaura.
        exemptMovement(frame, exemptionConfig.velocityMillis, frame.source)
    }

    private fun processTeleport(frame: ServerTeleportFrame, state: PlayerState) {
        val previous = state.lastMovement
        val basePosition = previous?.position ?: Vec3(0.0, 0.0, 0.0)
        state.resetMotion()
        state.pendingTeleportTarget = Vec3(
            if (frame.relativeX) basePosition.x + frame.position.x else frame.position.x,
            if (frame.relativeY) basePosition.y + frame.position.y else frame.position.y,
            if (frame.relativeZ) basePosition.z + frame.position.z else frame.position.z,
        )
        state.pendingTeleportId = frame.teleportId
        state.serverVelocity = frame.deltaMovement
        state.velocityAt = frame.receivedAt
        // The teleport only exempts until the client confirms it (then a short grace for packets in
        // flight). The cap only matters when no confirm arrives; a setback gets a short one so being
        // set back can never buy a check-free window.
        val setback = setbacks.recentSetback(frame.playerId, frame.receivedAt)
        val cap = if (setback) minOf(exemptionConfig.teleportMillis, SETBACK_TELEPORT_CAP_MILLIS) else exemptionConfig.teleportMillis
        state.teleportExemption?.cancel()
        state.teleportExemption = exemptMovement(frame, cap, if (setback) "setback-teleport" else "server-teleport")
    }

    private fun processTeleportConfirm(frame: TeleportConfirmFrame, state: PlayerState) {
        if (state.pendingTeleportId == frame.teleportId) {
            state.pendingTeleportId = null
            state.teleportExemption?.cancel()
            state.teleportExemption = null
            exemptMovement(frame, TELEPORT_CONFIRM_GRACE_MILLIS, "teleport-confirm")
        }
    }

    private fun processServerAbilities(frame: ServerAbilitiesFrame, state: PlayerState) {
        state.serverFlying = frame.flying
        state.serverFlightAllowed = frame.flightAllowed
        state.serverCreative = frame.creative
        state.movementScale = frame.movementScale
        if (frame.flying || frame.flightAllowed || frame.creative) {
            state.resetMotion()
            exemptMovement(frame, exemptionConfig.teleportMillis, "server-abilities")
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
        // Respawns/world changes reset everything; a short full exemption covers the reload.
        exemptions.exempt(
            frame.playerId, Duration.ofMillis(exemptionConfig.respawnMillis.coerceAtLeast(1)), frame.reason,
            IGuardExemptionScope.ALL, frame.receivedAt, external = false,
        )
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
        // The world/interaction heuristics model survival pacing: creative building (instant break,
        // rapid place) and bypass/dead/sleeping states would flood them with FPs. Physics states
        // (flight, vehicle, liquid …) and movement-only exemptions do NOT suspend them.
        if (actionExempt(frame, state, environment)) return
        val failures = ArrayList<CheckFailure>()
        // Sequence 0 means "not predicted": the vanilla client numbers predicted interactions from 1 and sends
        // every unpredicted one (abort digging, item use release, drops …) with 0 – never a regression.
        val sequenced = frame.interactionSequence > 0
        if (sequenced && state.lastInteractionSequence > 0 && frame.interactionSequence < state.lastInteractionSequence - 4) {
            failures += CheckFailure(
                "protocol.badpackets.a", 1.0,
                mapOf("reason" to "interaction-sequence-regression", "sequence" to frame.interactionSequence, "previous" to state.lastInteractionSequence),
            )
        }
        if (sequenced) state.lastInteractionSequence = max(state.lastInteractionSequence, frame.interactionSequence)
        if (frame.action == BlockAction.PLACE && listOf(frame.cursorX, frame.cursorY, frame.cursorZ).any { it !in 0.0f..1.0f || !it.isFinite() }) {
            failures += CheckFailure(
                "protocol.badpackets.a", 2.0, mapOf("reason" to "invalid-block-cursor"),
                deterministic = true,
            )
        }
        val movement = state.lastMovement
        if (movement != null && frame.action != BlockAction.USE_ITEM) {
            val center = Vec3(frame.blockX + 0.5, frame.blockY + 0.5, frame.blockZ + 0.5)
            val eye = actorPosition(movement, environment) + Vec3(0.0, environment.eyeHeight, 0.0)
            val distance = sqrt((center - eye).lengthSquared())
            state.observation?.metrics?.put("distance", distance.rounded())
            if (distance > 6.5) {
                failures += CheckFailure("world.interactionreach.a", 1.0, mapOf("distance" to distance.rounded(), "action" to frame.action.name))
            }
            if (frame.action == BlockAction.PLACE) {
                state.placementTimes.addLast(frame.receivedAt)
                trimTimes(state.placementTimes, frame.receivedAt, 1_000)
                val direction = Vec3.direction(movement.yaw, movement.pitch)
                val targetDirection = (center - eye).normalized()
                val dot = direction.x * targetDirection.x + direction.y * targetDirection.y + direction.z * targetDirection.z
                state.observation?.metrics?.apply {
                    put("lookDot", dot.rounded())
                    put("placementsPerSecond", state.placementTimes.size)
                }
                if (state.placementTimes.size >= 6 && frame.blockY <= movement.position.y - 0.4 && dot < 0.25) {
                    failures += CheckFailure(
                        "world.scaffold.a", 1.0,
                        mapOf("placementsPerSecond" to state.placementTimes.size, "lookDot" to dot.rounded(), "distance" to distance.rounded()),
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
                state.digStarts[key] = DigStart(frame.receivedAt, state.clientTick, state.explicitClientTicks)
                state.digTimes.addLast(frame.receivedAt)
                trimTimes(state.digTimes, frame.receivedAt, 1_000)
                if (state.digTimes.size >= 12) failures += CheckFailure("world.nuker.a", 1.0, mapOf("startsPerSecond" to state.digTimes.size))
                // No-facing: starting to dig a block the player is not looking at (nuker/no-rotation tell).
                if (movement != null) {
                    val center = Vec3(frame.blockX + 0.5, frame.blockY + 0.5, frame.blockZ + 0.5)
                    val eye = actorPosition(movement, environment) + Vec3(0.0, environment.eyeHeight, 0.0)
                    val look = Vec3.direction(movement.yaw, movement.pitch)
                    val toBlock = (center - eye).normalized()
                    val dot = look.x * toBlock.x + look.y * toBlock.y + look.z * toBlock.z
                    state.observation?.metrics?.put("digLookDot", dot.rounded())
                    if (dot < 0.2) failures += CheckFailure("world.nofacing.a", 1.0, mapOf("lookDot" to dot.rounded(), "block" to key))
                }
            }
            BlockAction.FINISH_DIG -> state.digStarts.remove(key)?.let { started ->
                val duration = frame.receivedAt - started.at
                val ticks = if (started.explicitTicks && state.explicitClientTicks) state.clientTick - started.tick else null
                val dig = frame.dig
                state.observation?.metrics?.apply {
                    put("digMillis", duration)
                    ticks?.let { put("digTicks", it) }
                    dig?.let {
                        put("digProgress", it.progressPerTick.rounded())
                        put("digBlock", it.block)
                    }
                }
                if (dig != null) {
                    // Measured by the server while digging: compare with the vanilla break time.
                    DigTiming.judge(dig, ticks, duration, environment.ping, dynamic.get().tuning)?.let { evidence ->
                        failures += CheckFailure("world.fastbreak.a", 1.0, evidence)
                    }
                } else if (duration in 0..24) {
                    // Unmeasured (FINISH before the first server sample): only a FINISH in the same client
                    // flush as its START is evidence. A legit 1-2 tick break sends FINISH 50-100ms later and
                    // jitter can compress that gap — the old 74ms window flagged exactly those players.
                    failures += CheckFailure("world.fastbreak.a", 0.5, mapOf("durationMillis" to duration, "block" to key))
                }
            }
            BlockAction.CANCEL_DIG -> state.digStarts.remove(key)
            else -> Unit
        }
        while (state.digStarts.size > 32) state.digStarts.remove(state.digStarts.keys.first())
        applyResults(
            frame, state, environment, playerName, failures,
            setOf("protocol.badpackets.a", "world.interactionreach.a", "world.scaffold.a", "world.fastplace.a", "world.nuker.a", "world.fastbreak.a", "world.nofacing.a"),
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
                    deterministic = invalid,
                ),
            )
            state.lastInventoryClickAt = frame.receivedAt
            state.observation?.metrics?.apply {
                put("sprinting", state.sprinting)
                put("horizontal", state.lastHorizontalDelta.rounded())
            }
            // Bedrock (Geyser) clients can legitimately keep walking with an open inventory.
            if (state.bedrock != true && state.sprinting && state.lastHorizontalDelta > 0.12) {
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
        playerName: String,
    ) {
        if (actionExempt(frame, state, environment)) return
        if (!environment.chunkLoaded) {
            state.observation?.apply { exempt = true; exemptions += "chunk-unloaded" }
            return
        }
        val movement = state.lastMovement ?: return
        val previousAttackAt = state.lastAttackAt
        state.lastAttackAt = frame.receivedAt
        val failures = ArrayList<CheckFailure>()
        // Autoclicker cadence is measured on real attack packets only: block/dig packets are flushed
        // tick-aligned by the vanilla client, so measuring them (as before) read plain mining as a
        // perfectly regular "autoclicker".
        state.attackTimes.addLast(frame.receivedAt)
        trimTimes(state.attackTimes, frame.receivedAt, 3_000)
        val tuning = dynamic.get().tuning
        if (state.attackTimes.size >= tuning.autoclickerMinSamples) {
            val duration = state.attackTimes.last() - state.attackTimes.first()
            if (duration >= 2_000) {
                val intervals = state.attackTimes.zipWithNext { first, second -> (second - first).toDouble() }
                val mean = intervals.average()
                val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
                val deviation = sqrt(variance)
                val cps = (state.attackTimes.size - 1) * 1000.0 / duration
                state.observation?.metrics?.apply {
                    put("cps", cps.rounded())
                    put("intervalDeviation", (deviation / mean.coerceAtLeast(1.0)).rounded())
                }
                // Machine regularity from min-cps on (a metronome at 10 CPS is as suspicious as at 20). Tick
                // aligned rates such as 12 CPS alternate 50/100ms intervals and stay above the threshold.
                if (cps >= tuning.autoclickerMinCps && deviation / mean.coerceAtLeast(1.0) < tuning.autoclickerMaxDeviation) {
                    failures += CheckFailure(
                        "combat.autoclicker.a", 1.0,
                        mapOf("cps" to cps.rounded(), "meanInterval" to mean.rounded(), "deviation" to deviation.rounded(), "samples" to intervals.size),
                    )
                }
            }
        }
        // No-swing: the vanilla client sends the arm-swing right AFTER the attack packet, so "no swing
        // yet" is normal for the current hit — flagging on that (as before) hit every first attack after
        // idling. Only a sustained series of attacks with no swing arriving in between is killaura
        // evidence.
        if (previousAttackAt > 0 && frame.receivedAt - previousAttackAt <= 2_000 && state.lastSwingAt < previousAttackAt) {
            state.noSwingStreak++
        } else {
            state.noSwingStreak = 0
        }
        if (state.noSwingStreak >= 3) {
            failures += CheckFailure("combat.noswing.a", 1.0, mapOf("attacksWithoutSwing" to state.noSwingStreak, "sinceSwingMs" to (frame.receivedAt - state.lastSwingAt)))
        }
        // Bedrock (Geyser) clients can legitimately attack straight out of an open inventory.
        if (state.bedrock != true && frame.receivedAt - state.lastInventoryClickAt in 0..75) {
            state.inventoryAttackTimes.addLast(frame.receivedAt)
            trimTimes(state.inventoryAttackTimes, frame.receivedAt, 5_000)
            if (state.inventoryAttackTimes.size >= 3) {
                failures += CheckFailure("combat.inventory.a", 1.0, mapOf("attacksAfterInventory" to state.inventoryAttackTimes.size, "delayMillis" to frame.receivedAt - state.lastInventoryClickAt))
            }
        }
        val target = snapshots.target(frame.targetEntityId)
        val playerTarget = target != null && target.playerId != frame.playerId && target.current.worldId == environment.worldId
        // Multi-target: vanilla attacks at most what the crosshair hits, so two different entities in one client
        // tick (any type: players, mobs, armor stands …) are aura evidence. Without tick-end packets the tick
        // clock is the movement stream, which idles for standing players – only player pairs count there.
        if (state.attackTick == state.clientTick && state.attackTarget != -1 && state.attackTarget != frame.targetEntityId &&
            (state.explicitClientTicks || (playerTarget && state.attackTargetPlayer))
        ) {
            failures += CheckFailure("combat.multitarget.a", 1.5, mapOf("firstTarget" to state.attackTarget, "secondTarget" to frame.targetEntityId, "clientTick" to state.clientTick))
        }
        state.attackTick = state.clientTick
        state.attackTarget = frame.targetEntityId
        state.attackTargetPlayer = playerTarget
        if (target == null || !playerTarget) {
            // Cadence/no-swing/inventory/multi-target apply to any attack (mobs included); the positional
            // checks below need a tracked player target.
            applyResults(frame, state, environment, playerName, failures, setOf("combat.autoclicker.a", "combat.noswing.a", "combat.inventory.a", "combat.multitarget.a"))
            return
        }
        // Lag compensation: the attacker acted roughly one-way latency before the packet arrived and
        // aimed at a target the client renders a further interpolation window (~100ms) in the past.
        // Judge reach/rotation against the most favorable target frame across that whole window —
        // compensating only ping/2 (as before) reach-flagged legit hits in every laggy chase fight.
        val ping = environment.ping.coerceIn(0, 400).toLong()
        val sampleTimes = longArrayOf(frame.receivedAt - ping / 2, frame.receivedAt - ping, frame.receivedAt - ping - 100)
        val targetFrames = sampleTimes.map { snapshots.frameAt(target, it) }
        val attackerFrame = snapshots.view(frame.playerId)?.let { snapshots.frameAt(it, sampleTimes[0]) } ?: environment
        val eyeHeight = if (attackerFrame.entityBox.maxY - attackerFrame.entityBox.minY < 1.0) 0.4 else 1.62
        val origin = actorPosition(movement, environment) + Vec3(0.0, eyeHeight, 0.0)
        val direction = Vec3.direction(movement.yaw, movement.pitch)
        val tolerance = 0.1 + environment.ping.coerceIn(0, 300) * 0.0005
        // Where the view ray misses a frame (or hits beyond reach + 1), the closest-point distance stands in: a
        // lower bound of any hit, so a hit from 4+ blocks no longer escapes the check.
        val distance = targetFrames.minOfOrNull { sample ->
            val box = sample.entityBox.expand(tolerance)
            box.rayDistance(origin, direction, profile.reach + 1.0) ?: box.distanceTo(origin)
        }
        state.observation?.apply {
            targets += TraceTarget(frame.targetEntityId, target.playerId, target.playerName, targetFrames.sortedBy(EnvironmentFrame::capturedAt))
            distance?.let { metrics["reachDistance"] = it.rounded() }
            metrics["reachLimit"] = (profile.reach + tolerance).rounded()
            metrics["ping"] = environment.ping
        }
        if (distance != null && distance > profile.reach + tolerance) {
            failures += CheckFailure("combat.reach.a", 1.0, mapOf("distance" to distance.rounded(), "limit" to (profile.reach + tolerance).rounded(), "ping" to environment.ping))
        }
        val rotationHit = targetFrames.any { it.entityBox.expand(0.22 + tolerance).rayDistance(origin, direction, profile.reach + 1.0) != null }
        state.observation?.metrics?.put("rotationHit", rotationHit)
        if (frame.receivedAt - previousAttackAt > 500) state.rotationMisses = 0
        state.rotationMisses = if (rotationHit) 0 else state.rotationMisses + 1
        // Geyser's input translation produces rotations a Java client never would; skip aim heuristics.
        if (state.bedrock != true && state.rotationMisses >= 3) {
            failures += CheckFailure("combat.rotation.a", 1.0, mapOf("misses" to state.rotationMisses, "target" to target.playerName))
        }
        // Snap-aim: attacks right after huge single-tick rotations. High-sensitivity players flick past
        // 60°/tick legitimately, so this needs a larger angle AND a repeat before it becomes evidence.
        val snapped = frame.receivedAt - state.lastRotationAt <= SNAP_AIM_WINDOW_MILLIS && state.lastYawDelta > SNAP_AIM_DEGREES
        state.snapAimStreak = if (snapped) state.snapAimStreak + 1 else 0
        if (state.bedrock != true && snapped && state.snapAimStreak >= 2) {
            failures += CheckFailure("combat.snapaim.a", 1.0, mapOf("yawDelta" to state.lastYawDelta.rounded(), "sinceRotMs" to (frame.receivedAt - state.lastRotationAt), "streak" to state.snapAimStreak))
        }
        applyResults(frame, state, environment, playerName, failures, setOf("combat.reach.a", "combat.rotation.a", "combat.multitarget.a", "combat.autoclicker.a", "combat.inventory.a", "combat.noswing.a", "combat.snapaim.a"))
    }

    /**
     * Whether combat/world/inventory checks must be skipped for this frame: only a FULL exemption or
     * a tag under which the player cannot act normally (bypass, dead, sleeping, creative/spectator).
     */
    private fun actionExempt(frame: PacketFrame, state: PlayerState, environment: EnvironmentFrame): Boolean {
        val timed = exemptions.isFullyExempt(frame.playerId, frame.receivedAt)
        val tagged = EnvironmentTags.actionExempt(environment.environmentTags)
        if (!timed && !tagged) return false
        state.observation?.let { observation ->
            observation.exempt = true
            if (timed) {
                exemptions.activeReasons(frame.playerId, frame.receivedAt).filter { it.startsWith("all:") }.forEach { observation.exemptions += "timed:$it" }
            }
            environment.environmentTags.filter { it in EnvironmentTags.ACTION_EXEMPTING }.forEach { observation.exemptions += "tag:$it" }
        }
        return true
    }

    // Where the player acts from: riding clients send no position packets, so their last movement
    // position is wherever they mounted — the sampled server position is the real seat position.
    private fun actorPosition(movement: MovementFrame, environment: EnvironmentFrame): Vec3 =
        if (EnvironmentTags.VEHICLE in environment.environmentTags) environment.position else movement.position

    /**
     * Folds one evaluation into the violation levels.
     *
     * Decay is time based: on every verdict a check loses `decay-per-second` for the time since its
     * previous verdict, except while it keeps failing (a continuous cheat must accumulate). [held]
     * marks checks that fail this frame without adding weight (their own pacing holds them back).
     * Sub-threshold failures feed incident confidence only in proportion to VL / alert-vl and never
     * open an incident on their own.
     */
    private fun applyResults(
        frame: PacketFrame,
        state: PlayerState,
        environment: EnvironmentFrame,
        playerName: String,
        failures: List<CheckFailure>,
        evaluated: Set<String>,
        held: Set<String> = emptySet(),
    ) {
        val settings = dynamic.get()
        val now = frame.receivedAt
        val observation = state.observation
        val failedIds = failures.mapTo(HashSet(), CheckFailure::checkId)
        for (checkId in evaluated) {
            if (checkId in failedIds) continue
            val config = settings.checks[checkId] ?: continue
            val failing = checkId in held
            decay(state, checkId, config, now, failing)
            observation?.results?.add(
                IGuardCheckResult(checkId, failing, false, 0.0, state.violations[checkId] ?: 0.0, config.alertVl, emptyMap()),
            )
        }
        for (failure in failures) {
            val config = settings.checks[failure.checkId] ?: continue
            if (!config.enabled) {
                observation?.results?.add(
                    IGuardCheckResult(failure.checkId, true, false, 0.0, state.violations[failure.checkId] ?: 0.0, config.alertVl, failure.evidence + ("disabled" to true)),
                )
                continue
            }
            decay(state, failure.checkId, config, now, failing = true)
            val lastFailure = state.lastFailureAt[failure.checkId] ?: Long.MIN_VALUE
            if (lastFailure != Long.MIN_VALUE && now - lastFailure < detectionConfig.signalCooldownMillis) {
                observation?.results?.add(
                    IGuardCheckResult(failure.checkId, true, false, 0.0, state.violations[failure.checkId] ?: 0.0, config.alertVl, failure.evidence),
                )
                continue
            }
            state.lastFailureAt[failure.checkId] = now
            val violationLevel = (state.violations[failure.checkId] ?: 0.0) + failure.weight
            state.violations[failure.checkId] = violationLevel
            observation?.results?.add(
                IGuardCheckResult(failure.checkId, true, true, failure.weight, violationLevel, config.alertVl, failure.evidence),
            )
            // Evidence weight: a check below its alert level is noise-prone, so it only contributes
            // proportionally to an incident and cannot open one by itself.
            val evidenceWeight = if (config.alertVl <= 0.0) 1.0 else (violationLevel / config.alertVl).coerceIn(0.0, 1.0)
            val incident = incidentTracker.assign(frame, playerName, failure, evidenceWeight, environment.worldId)
            // Forensic provenance: flag violations raised during/just after a queue-overload window,
            // where dropped frames may have distorted the motion predictor. Stored in existing JSONB.
            val evidence = if (now < state.underloadUntil) failure.evidence + ("underload" to true) else failure.evidence
            val record = ViolationRecord(
                now,
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
                incident?.incidentId,
                incident?.confidence ?: 0.0,
                incident?.shadowAction,
            )
            // The incident itself is written by the tracker (throttled upserts); the violation row
            // only references it.
            storage.enqueue(record)
            if (violationLevel >= config.alertVl) alerts.record(record)
            // Setback is the primary reaction for every movement check: a movement cheat is neutralised
            // by rewinding the player, so a false positive can never ban anyone. Bans stay for the
            // confidence pipeline (deterministic/multi-family), keeping enforcement conservative.
            if (config.setbackVl >= 0.0 && violationLevel >= config.setbackVl && failure.checkId in SETBACK_CHECKS) {
                state.safePosition?.let { setbacks.request(frame.playerId, it, failure.checkId) }
            }
        }
    }

    // Applies the time-based decay for one verdict of [checkId] at [now].
    private fun decay(state: PlayerState, checkId: String, config: CheckConfig, now: Long, failing: Boolean) {
        val touchedAt = state.violationTouchedAt.put(checkId, now)
        val previousFailed = state.lastVerdictFailed.put(checkId, failing) == true
        if (touchedAt == null || config.decayPerSecond <= 0.0) return
        val elapsed = now - touchedAt
        if (elapsed <= 0) return
        // Failing again right after a failure is one continuous offence: no decay in between.
        if (failing && previousFailed && elapsed <= CONTINUOUS_FAILURE_GAP_MILLIS) return
        val current = state.violations[checkId] ?: return
        if (current <= 0.0) return
        state.violations[checkId] = (current - config.decayPerSecond * elapsed / 1000.0).coerceAtLeast(0.0)
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
            Instant.ofEpochMilli(frame.receivedAt),
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
        pitch = if (rotationChanged) pitch else fallbackPitch,
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
// 90° in one tick is beyond ordinary tracking but reachable by high-sensitivity flicks — which is why
// the check additionally demands a streak of two before it fires.
private const val SNAP_AIM_DEGREES = 90.0
private const val SNAP_AIM_WINDOW_MILLIS = 160L

// Grace after a confirmed teleport for movement packets that were already in flight.
private const val TELEPORT_CONFIRM_GRACE_MILLIS = 250L

// Upper bound of a setback teleport's exemption when the client never confirms it.
private const val SETBACK_TELEPORT_CAP_MILLIS = 1000L

// Two failures of one check closer than this are one continuous offence (no decay in between).
private const val CONTINUOUS_FAILURE_GAP_MILLIS = 1000L

// Timer check window: samples over the last 10 s, judged once 9 s and 190 samples are covered.
private const val TIMER_WINDOW_MILLIS = 10_000L
private const val TIMER_MIN_SPAN_MILLIS = 9_000L
private const val TIMER_MIN_SAMPLES = 190
private const val TIMER_RATE_LIMIT = 21.2
private val TIMER_CHECKS = setOf("movement.timer.a")

private const val PUBLISH_INTERVAL_MILLIS = 200L

// Max age of a player's sampled environment for its collision/support data to be trusted by
// support-dependent checks (~1 server tick). Beyond this the snapshot may lag a moving player.
private const val SUPPORT_FRESH_MILLIS = 60L

// How far a frame position may sit from the sampled environment position and still be covered by
// its ±2-column collision scan (guaranteed scan reach is 2.0 blocks; minus the feet-box half-width).
// Vertically the scan spans -1..+2 rows around the sampled block.
private const val SCAN_COVERAGE_XZ = 1.7
private const val SCAN_COVERAGE_DOWN = 0.9
private const val SCAN_COVERAGE_UP = 1.9

// Movement checks neutralised by a setback instead of a punishment: rewinding the player defeats the
// cheat, so a false positive can never escalate to a ban.
private val SETBACK_CHECKS = setOf(
    "movement.fly.a", "movement.speed.a", "movement.nofall.a", "movement.phase.a",
    "movement.step.a", "movement.spider.a", "movement.jesus.a", "movement.highjump.a",
    "movement.airjump.a",
)

private fun Double.rounded() = round(this * 10000.0) / 10000.0

private fun trimTimes(times: ArrayDeque<Long>, now: Long, windowMillis: Long) {
    while (times.firstOrNull()?.let { now - it > windowMillis } == true) times.removeFirst()
}
