package de.tytoss.iguard.trace

import de.tytoss.iguard.api.ExemptionGrant
import de.tytoss.iguard.api.IGuardTraceListener
import de.tytoss.iguard.api.IGuardTraceSession
import de.tytoss.iguard.check.ConfidenceModel
import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.config.TraceConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * Hub of everything that observes the check engine: trace recordings ([TraceSession]), API frame
 * listeners ([IGuardTraceListener]) and `/iguard debug` viewers ([DebugRouter]). The engine only
 * builds a [FrameTrace] for a player while at least one of them is interested ([wants]).
 *
 * @property directory directory receiving the `.jsonl` recordings (`plugins/IGuard/traces`).
 * @property config recorder limits.
 * @property serverId server id written into trace headers.
 * @property nameOf player name lookup for headers.
 * @property clientVersionOf client version lookup for headers.
 * @property logger plugin logger.
 * @property clock epoch millis source.
 */
class TraceHub(
    private val directory: Path,
    private val config: TraceConfig,
    dynamic: AtomicReference<DynamicConfig>,
    private val serverId: String,
    private val nameOf: (UUID) -> String?,
    private val clientVersionOf: (UUID) -> String?,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
) : TraceControl, FrameObserver {
    private val listeners = CopyOnWriteArrayList<IGuardTraceListener>()
    private val sessions = ConcurrentHashMap<UUID, TraceSession>()
    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "iguard-trace-writer").apply { isDaemon = true }
    }

    /** `/iguard debug` routing. */
    val debug = DebugRouter(dynamic)

    init {
        writer.scheduleAtFixedRate(::housekeeping, 1, 1, TimeUnit.SECONDS)
    }

    override fun wants(playerId: UUID): Boolean =
        listeners.isNotEmpty() || sessions.containsKey(playerId) || debug.watching(playerId)

    override fun observe(trace: FrameTrace) {
        if (listeners.isNotEmpty()) {
            val report = trace.toReport()
            listeners.forEach { listener ->
                runCatching { listener.onFrame(report) }
                    .onFailure { logger.warning("IGuard trace listener ${listener.javaClass.name} failed: ${it.message}") }
            }
        }
        sessions[trace.frame.playerId]?.record(trace)
        debug.route(trace)
    }

    override fun start(playerId: UUID, label: String): IGuardTraceSession {
        sessions[playerId]?.stop("restarted")
        val now = clock()
        val safeLabel = sanitize(label)
        val stamp = STAMP.format(Instant.ofEpochMilli(now))
        var file = directory.resolve("$safeLabel-$stamp.jsonl")
        var suffix = 2
        while (Files.exists(file)) file = directory.resolve("$safeLabel-$stamp-${suffix++}.jsonl")
        val session = TraceSession(
            playerId, safeLabel, file, Instant.ofEpochMilli(now), config.maxBytes, config.maxSeconds * 1000L,
            config.queueCapacity, writer, logger, clock,
        ) { closed -> sessions.remove(closed.playerId, closed) }
        session.control(
            TraceCodec.headerLine(
                playerId, nameOf(playerId) ?: playerId.toString(), safeLabel, serverId, now,
                clientVersionOf(playerId), ConfidenceModel.RECIPE_VERSION,
            ),
        )
        sessions[playerId] = session
        return session
    }

    override fun stop(playerId: UUID): Path? = sessions[playerId]?.stop()

    override fun marker(playerId: UUID, text: String): Boolean = sessions[playerId]?.marker(text) ?: false

    override fun active(playerId: UUID): IGuardTraceSession? = sessions[playerId]

    /** Every running recording. */
    fun activeSessions(): List<IGuardTraceSession> = sessions.values.toList()

    override fun addListener(listener: IGuardTraceListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    /** Logs an external exemption grant into the player's running recording (replays re-apply it). */
    fun exemptionGranted(grant: ExemptionGrant) {
        sessions[grant.playerId]?.control(TraceCodec.exemptionLine(grant))
    }

    /** Ends the player's recording and debug watches (on quit). */
    fun remove(playerId: UUID) {
        sessions[playerId]?.stop("quit")
        debug.removeTarget(playerId)
        debug.removeViewer(playerId.toString())
    }

    /** Stops every recording and drains the writer. */
    fun shutdown() {
        sessions.values.toList().forEach { it.stop("shutdown") }
        writer.shutdown()
        if (!writer.awaitTermination(3, TimeUnit.SECONDS)) writer.shutdownNow()
    }

    private fun housekeeping() {
        val now = clock()
        sessions.values.toList().forEach { session ->
            if (now - session.startedAt.toEpochMilli() > config.maxSeconds * 1000L) session.stop("max-seconds") else session.flush()
        }
    }

    private companion object {
        /** File-name timestamp. */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

        /** Keeps labels file-name safe: `a-z A-Z 0-9 . _ -`, at most 48 characters. */
        fun sanitize(label: String): String =
            label.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(48).ifEmpty { "trace" }
    }
}
