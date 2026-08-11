package org.helix.node.services

import org.helix.api.proxy.ProxyCommand
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Restarts services and whole tasks with a chat countdown.
 *
 * A restart announces itself network-wide at fixed marks before the
 * restart moment (translated per receiving player via the
 * `helix.translations.network.restart.*` keys), then stops each service,
 * waits for its termination and starts a replacement — unless the
 * auto-scaler already brought one up. Task restarts run rolling, one
 * service at a time, so capacity stays available.
 *
 * @property manager service lifecycle owner.
 * @property deliver enqueues a proxy command for all active proxies.
 * @property eventSink records dashboard events.
 * @property stopWaitMillis maximum milliseconds to wait for a stop.
 * @property scalerGraceMillis milliseconds the auto-scaler gets to replace
 *  a stopped service before an explicit start.
 */
class RestartCoordinator(
    private val manager: ServiceManager,
    private val deliver: (ProxyCommand) -> Unit,
    private val eventSink: (category: String, level: String, message: String) -> Unit = { _, _, _ -> },
    private val stopWaitMillis: Long = STOP_WAIT_MILLIS,
    private val scalerGraceMillis: Long = SCALER_GRACE_MILLIS,
) {
    private val logger = LoggerFactory.getLogger(RestartCoordinator::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "helix-restarts").apply { isDaemon = true }
    }

    /**
     * Schedules the restart of one service.
     *
     * @param serviceId the service to restart; must be active.
     * @param delaySeconds countdown length before the restart happens.
     * @return `true` when the restart was scheduled.
     */
    fun restartService(serviceId: String, delaySeconds: Long): Boolean {
        val service = manager.find(serviceId) ?: return false
        if (!service.active()) {
            return false
        }
        schedule(serviceId, delaySeconds) { restartNow(listOf(serviceId)) }
        return true
    }

    /**
     * Schedules a rolling restart of every active service of a task.
     *
     * @param taskName the task whose services restart.
     * @param delaySeconds countdown length before the restart happens.
     * @return the number of services that will restart, `0` if none.
     */
    fun restartTask(taskName: String, delaySeconds: Long): Int {
        val services = manager.managedServices()
            .filter { it.task.name == taskName && it.active() }
            .map { it.id }
        if (services.isEmpty()) {
            return 0
        }
        schedule(taskName, delaySeconds) { restartNow(services) }
        return services.size
    }

    private fun schedule(target: String, delaySeconds: Long, restart: () -> Unit) {
        eventSink("service", "info", "Restart of $target scheduled in ${delaySeconds}s")
        ANNOUNCE_MARKS.filter { it in 1..delaySeconds }.forEach { mark ->
            executor.schedule(
                { announce(WARN_KEY, WARN_FALLBACK, target, mark) },
                delaySeconds - mark,
                TimeUnit.SECONDS,
            )
        }
        executor.schedule(
            {
                announce(NOW_KEY, NOW_FALLBACK, target, 0)
                runCatching(restart).onFailure { failure ->
                    logger.error("Restart of {} failed", target, failure)
                    eventSink("service", "error", "Restart of $target failed: ${failure.message}")
                }
            },
            delaySeconds,
            TimeUnit.SECONDS,
        )
    }

    private fun announce(key: String, fallback: String, target: String, seconds: Long) {
        runCatching {
            deliver(
                ProxyCommand.broadcastKey(
                    key = key,
                    fallback = fallback,
                    params = mapOf("target" to target, "seconds" to seconds.toString()),
                ),
            )
        }.onFailure { logger.warn("Restart announcement failed: {}", it.message) }
    }

    private fun restartNow(serviceIds: List<String>) {
        serviceIds.forEach { serviceId ->
            val service = manager.find(serviceId) ?: return@forEach
            if (!service.active()) {
                return@forEach
            }
            val taskName = service.task.name
            val before = manager.activeCount(taskName)
            manager.stopService(serviceId)
            if (!awaitTermination(serviceId)) {
                logger.warn("Service {} did not stop in time, killing", serviceId)
                manager.killService(serviceId)
                awaitTermination(serviceId)
            }
            // Give the auto-scaler its immediate rebalance pass; only start a
            // replacement when it did not already restore the capacity.
            if (!awaitCapacity(taskName, before)) {
                runCatching { manager.startService(taskName) }
                    .onSuccess { eventSink("service", "info", "Restarted $serviceId as ${it.id}") }
                    .onFailure { failure ->
                        eventSink("service", "error", "Restart of $serviceId failed: ${failure.message}")
                    }
            } else {
                eventSink("service", "info", "Restarted $serviceId (replacement via auto-scaler)")
            }
        }
    }

    private fun awaitTermination(serviceId: String): Boolean {
        val deadline = System.currentTimeMillis() + stopWaitMillis
        while (System.currentTimeMillis() < deadline) {
            val service = manager.find(serviceId) ?: return true
            if (!service.active()) {
                return true
            }
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun awaitCapacity(taskName: String, expected: Int): Boolean {
        val deadline = System.currentTimeMillis() + scalerGraceMillis
        while (System.currentTimeMillis() < deadline) {
            if (manager.activeCount(taskName) >= expected) {
                return true
            }
            Thread.sleep(POLL_MILLIS)
        }
        return manager.activeCount(taskName) >= expected
    }

    private companion object {
        /** Seconds before the restart at which a warning is broadcast. */
        val ANNOUNCE_MARKS = listOf<Long>(600, 300, 120, 60, 30, 10, 5, 3, 2, 1)

        /** Translation key of the countdown warning. */
        const val WARN_KEY = "helix.translations.network.restart.warn"

        /** Fallback template of the countdown warning. */
        const val WARN_FALLBACK = "{prefix} <gray><white>{target}</white> restarts in <white>{seconds}s</white>."

        /** Translation key of the restart-now announcement. */
        const val NOW_KEY = "helix.translations.network.restart.now"

        /** Fallback template of the restart-now announcement. */
        const val NOW_FALLBACK = "{prefix} <gray><white>{target}</white> is restarting now."

        /** Maximum milliseconds to wait for a service to stop. */
        const val STOP_WAIT_MILLIS = 30_000L

        /** Milliseconds the auto-scaler gets to replace a stopped service. */
        const val SCALER_GRACE_MILLIS = 8_000L

        /** Poll interval while waiting. */
        const val POLL_MILLIS = 250L
    }
}
