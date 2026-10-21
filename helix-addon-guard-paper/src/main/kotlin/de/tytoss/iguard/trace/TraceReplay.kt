package de.tytoss.iguard.trace

import de.tytoss.iguard.api.ExemptionManager
import de.tytoss.iguard.api.IGuardFrameReport
import de.tytoss.iguard.check.CheckEngine
import de.tytoss.iguard.check.Enforcement
import de.tytoss.iguard.check.SetbackSink
import de.tytoss.iguard.config.IGuardConfig
import de.tytoss.iguard.model.SafePosition
import de.tytoss.iguard.notify.NotificationService
import de.tytoss.iguard.snapshot.SnapshotStore
import de.tytoss.iguard.storage.MemoryGuardStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * Outcome of replaying one trace.
 *
 * @property playerId traced player (from the header).
 * @property label recording label (from the header).
 * @property reports one report per replayed frame, in order.
 * @property markers the trace's markers (time, text).
 * @property setbacks how many setbacks the checks requested.
 * @property violationLevels the player's violation levels after the last frame.
 */
data class TraceReplayResult(
    val playerId: UUID,
    val label: String,
    val reports: List<IGuardFrameReport>,
    val markers: List<Pair<Long, String>>,
    val setbacks: Int,
    val violationLevels: Map<String, Double>,
) {
    /** Every check that failed at least once (counted or held back). */
    val failedChecks: Set<String> get() = reports.flatMapTo(sortedSetOf()) { report -> report.failures.map { it.checkId } }

    /** How many failures of [checkId] actually raised its violation level. */
    fun countedFailures(checkId: String): Int = reports.sumOf { report -> report.results.count { it.checkId == checkId && it.counted } }

    /** The highest violation level [checkId] reached during the replay. */
    fun peakViolation(checkId: String): Double =
        reports.maxOfOrNull { report -> report.results.filter { it.checkId == checkId }.maxOfOrNull { it.violationLevel } ?: 0.0 } ?: 0.0

    /** Frames whose checks were skipped (exempt), for coverage assertions. */
    val exemptFrames: Int get() = reports.count { it.exempt && it.frameType == "movement" }
}

/**
 * Replays a recorded JSONL trace (see [TraceCodec]) through a fresh [CheckEngine] with the given
 * configuration — no Bukkit, no node: environment lines feed the snapshot store, exemption lines the
 * exemption manager, frame lines the engine (synchronously, with the recorded timestamps as clock).
 * Golden-trace regression tests and offline analysis both build on this.
 *
 * @property config configuration to judge with (defaults: the bundled defaults).
 */
class TraceReplay(private val config: IGuardConfig = IGuardConfig.defaults("replay")) {
    /** Replays the trace file at [path]. */
    fun replay(path: Path): TraceReplayResult = Files.newBufferedReader(path).use { replay(it.lineSequence()) }

    /** Replays trace [lines] (header first). */
    fun replay(lines: Sequence<String>): TraceReplayResult {
        var now = 0L
        val snapshots = SnapshotStore()
        val exemptions = ExemptionManager { now }
        val dynamic = AtomicReference(config.dynamic)
        val reports = ArrayList<IGuardFrameReport>()
        val markers = ArrayList<Pair<Long, String>>()
        var setbacks = 0
        val logger = Logger.getLogger("IGuard-Replay")
        val observer = object : FrameObserver {
            override fun wants(playerId: UUID): Boolean = true

            override fun observe(trace: FrameTrace) {
                reports += trace.toReport()
            }
        }
        val setbackSink = object : SetbackSink {
            override fun request(playerId: UUID, safe: SafePosition, reason: String) {
                setbacks++
            }

            override fun recentSetback(playerId: UUID, now: Long): Boolean = false
        }
        val engine = CheckEngine(
            1, 1024, snapshots, exemptions, config.exemptions, config.detection, config.sanctions, dynamic,
            config.serverId, MemoryGuardStore(), { }, setbackSink, Enforcement { _, _, _, _, _ -> },
            NotificationService(dynamic, logger), logger, observer,
        )
        var playerId: UUID? = null
        var playerName = "replay"
        var label = ""
        try {
            for (raw in lines) {
                val line = TraceCodec.parseLine(raw) ?: continue
                val type = line.text("type")
                if (type == "header") {
                    playerId = UUID.fromString(line.text("player"))
                    playerName = line.text("name").ifEmpty { playerName }
                    label = line.text("label")
                    continue
                }
                val player = playerId ?: error("trace has no header line")
                when (type) {
                    "env" -> snapshots.update(player, playerName, PLAYER_ENTITY_ID, TraceCodec.decodeEnvironment(line))
                    "target" -> {
                        val targetId = UUID.fromString(line.text("player"))
                        val entityId = line.text("entityId").toInt()
                        line["frames"]?.jsonArray?.forEach { frame ->
                            snapshots.update(targetId, line.text("name").ifEmpty { "target" }, entityId, TraceCodec.decodeEnvironment(frame.jsonObject))
                        }
                    }
                    "exempt" -> {
                        val grant = TraceCodec.decodeExemption(line, player)
                        now = grant.grantedAt
                        exemptions.exempt(player, Duration.ofMillis(grant.millis), grant.reason, grant.scope, grant.grantedAt, external = false)
                    }
                    "marker" -> markers += (line.text("t").toLongOrNull() ?: now) to line.text("text")
                    "frame" -> {
                        val frame = TraceCodec.decodeFrame(line, player) ?: continue
                        now = frame.receivedAt
                        engine.processNow(frame)
                    }
                }
            }
        } finally {
            engine.shutdown()
        }
        val player = playerId ?: error("trace has no header line")
        return TraceReplayResult(
            player,
            label,
            reports,
            markers,
            setbacks,
            engine.violationLevels(player).filterValues { it > 0.0 },
        )
    }

    private companion object {
        /** Entity id the replayed player is registered under in the snapshot store. */
        const val PLAYER_ENTITY_ID = Int.MAX_VALUE
    }
}

private fun JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
