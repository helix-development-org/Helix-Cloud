package org.helix.node.platform

import org.helix.api.service.ServiceState
import org.helix.node.services.ManagedService
import org.helix.node.services.ServiceManager
import org.slf4j.LoggerFactory

/**
 * Acts on the heartbeat staleness [NodeHealthService] only ever reported for
 * display: a service stuck in `STARTING` past [startDeadlineMillis], or a
 * `RUNNING` service silent past [staleThresholdMillis], is killed and settled
 * as `FAILED` via [ServiceManager.watchdogFail] — routing already excludes it
 * the moment it stops being `RUNNING`, and the crash then runs through the
 * normal auto-scaler cooldown/replace path.
 *
 * @property manager service lifecycle owner.
 * @property startDeadlineMillis max time a service may stay `STARTING`
 *   without its first heartbeat before it is reaped.
 * @property staleThresholdMillis max time a `RUNNING` service may miss
 *   heartbeats before it is reaped.
 * @property clock epoch millis source, injectable for tests.
 * @property startProgress progress marker of a starting service (default: bytes
 *   Paperclip has downloaded into the workspace). While it keeps changing the
 *   start deadline is measured from the last change, so a first start on a slow
 *   connection is not reaped mid-download (which deleted the temp workspace and
 *   started the whole download again).
 */
class HeartbeatWatchdog(
    private val manager: ServiceManager,
    private val startDeadlineMillis: Long = 120_000L,
    private val staleThresholdMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val startProgress: (ManagedService) -> Long = ::downloadedBytes,
) {
    private val logger = LoggerFactory.getLogger(HeartbeatWatchdog::class.java)

    /** Last progress marker per starting service and when it last changed. */
    private val progress = HashMap<ManagedService, Pair<Long, Long>>()

    /**
     * Scans all managed services once and reaps any stuck start or stale
     * runner found.
     */
    fun tick() {
        val now = clock()
        val services = manager.managedServices()
        progress.keys.retainAll { it in services && it.state == ServiceState.STARTING }
        services.forEach { service ->
            when (service.state) {
                ServiceState.STARTING -> checkStuckStart(service, now)
                ServiceState.RUNNING -> checkStaleHeartbeat(service, now)
                else -> Unit
            }
        }
    }

    private fun checkStuckStart(service: ManagedService, now: Long) {
        val startedAt = service.startedAtEpochMs ?: return
        val marker = runCatching { startProgress(service) }.getOrDefault(0L)
        val previous = progress[service]
        // first look: no progress known yet → counts from the start; afterwards every change resets the clock
        val lastChange = when {
            previous == null -> startedAt
            previous.first != marker -> now
            else -> previous.second
        }
        progress[service] = marker to lastChange
        val overdueMs = now - maxOf(startedAt, lastChange)
        if (overdueMs > startDeadlineMillis) {
            reap(service, "did not report its first heartbeat within ${startDeadlineMillis / 1000}s of starting (or of its last download progress)")
        }
    }

    private fun checkStaleHeartbeat(service: ManagedService, now: Long) {
        val last = service.lastHeartbeatEpochMs ?: return
        val overdueMs = now - last
        if (overdueMs > staleThresholdMillis) {
            reap(service, "missed heartbeats for over ${staleThresholdMillis / 1000}s")
        }
    }

    private fun reap(service: ManagedService, reason: String) {
        logger.warn("Heartbeat watchdog reaping {}: {}", service.id, reason)
        manager.watchdogFail(service.id, reason)
    }
}

/**
 * Bytes below the workspace's Paperclip download directories (`cache/`, `libraries/`) – grows while a first
 * start downloads the Mojang jar and libraries.
 *
 * @param service the starting service.
 * @return total size in bytes (0 if nothing was downloaded yet).
 */
internal fun downloadedBytes(service: ManagedService): Long =
    listOf("cache", "libraries").sumOf { dir ->
        val root = service.workspace.resolve(dir)
        if (!java.nio.file.Files.isDirectory(root)) 0L
        else java.nio.file.Files.walk(root).use { files ->
            files.filter { java.nio.file.Files.isRegularFile(it) }.mapToLong { runCatching { java.nio.file.Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }
