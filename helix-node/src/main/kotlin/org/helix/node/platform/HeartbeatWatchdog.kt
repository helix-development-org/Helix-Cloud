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
 */
class HeartbeatWatchdog(
    private val manager: ServiceManager,
    private val startDeadlineMillis: Long = 120_000L,
    private val staleThresholdMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(HeartbeatWatchdog::class.java)

    /**
     * Scans all managed services once and reaps any stuck start or stale
     * runner found.
     */
    fun tick() {
        val now = clock()
        manager.managedServices().forEach { service ->
            when (service.state) {
                ServiceState.STARTING -> checkStuckStart(service, now)
                ServiceState.RUNNING -> checkStaleHeartbeat(service, now)
                else -> Unit
            }
        }
    }

    private fun checkStuckStart(service: ManagedService, now: Long) {
        val startedAt = service.startedAtEpochMs ?: return
        val overdueMs = now - startedAt
        if (overdueMs > startDeadlineMillis) {
            reap(service, "did not report its first heartbeat within ${startDeadlineMillis / 1000}s of starting")
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
