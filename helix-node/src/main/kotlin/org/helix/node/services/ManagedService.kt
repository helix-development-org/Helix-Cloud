package org.helix.node.services

import java.nio.file.Path
import org.helix.api.service.ServiceInfo
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition

/**
 * Mutable runtime record of one service inside the node.
 *
 * @property id unique service id, `<task>-<index>`.
 * @property task task the service was created from.
 * @property workspace workspace root on disk.
 * @property port allocated service port.
 */
class ManagedService(
    val id: String,
    val task: TaskDefinition,
    val workspace: Path,
    val port: Int,
) {
    /** Current lifecycle state. */
    @Volatile
    var state: ServiceState = ServiceState.PREPARED

    /** Players currently connected, from the latest heartbeat. */
    @Volatile
    var onlinePlayers: Int = 0

    /** Player slots reported by the latest heartbeat, or the task default. */
    @Volatile
    var maxPlayers: Int = task.maxPlayers

    /** Ticks per second from the latest heartbeat, if reported. */
    @Volatile
    var tps: Double? = null

    /** JVM heap in use (MB) from the last heartbeat; `-1` if unknown. */
    @Volatile
    var memoryUsedMb: Int = -1

    /** Maximum JVM heap (MB) from the last heartbeat; `-1` if unknown. */
    @Volatile
    var memoryMaxMb: Int = -1

    /** Process CPU load (percent) from the last heartbeat; `-1` if unknown. */
    @Volatile
    var cpuPercent: Double = -1.0

    /** Epoch millis of the last start. */
    @Volatile
    var startedAtEpochMs: Long? = null

    /** Epoch millis of the latest heartbeat. */
    @Volatile
    var lastHeartbeatEpochMs: Long? = null

    /** Epoch millis since the service is empty, for idle scale-down. */
    @Volatile
    var emptySinceEpochMs: Long? = null

    /** Whether the current stop was requested by the node. */
    @Volatile
    var stopRequested: Boolean = false

    /**
     * Set by the heartbeat watchdog before killing a stuck/unresponsive
     * service, so [ServiceManager] settles it as FAILED regardless of the
     * exit code or [stopRequested] — feeding the auto-scaler's normal
     * crash-cooldown/replace path instead of a silent STOPPED.
     */
    @Volatile
    var watchdogKilled: Boolean = false

    /** Executor handle, present while the service runs. */
    @Volatile
    var handle: ServiceHandle? = null

    /** Last log lines captured when the service terminated. */
    @Volatile
    var lastLogs: List<String> = emptyList()

    /**
     * Whether the service occupies capacity (not yet terminated).
     *
     * @return `true` for every state except `STOPPED` and `FAILED`.
     */
    fun active(): Boolean = state != ServiceState.STOPPED && state != ServiceState.FAILED

    /**
     * Creates an immutable snapshot for API consumers.
     *
     * @return the current state as [ServiceInfo].
     */
    fun toInfo(): ServiceInfo = ServiceInfo(
        id = id,
        taskName = task.name,
        environment = task.environment,
        executor = task.executor,
        state = state,
        port = port,
        static = task.staticServices,
        onlinePlayers = onlinePlayers,
        maxPlayers = maxPlayers,
        startedAtEpochMs = startedAtEpochMs,
        memoryUsedMb = memoryUsedMb,
        memoryMaxMb = memoryMaxMb,
        cpuPercent = cpuPercent,
    )
}
