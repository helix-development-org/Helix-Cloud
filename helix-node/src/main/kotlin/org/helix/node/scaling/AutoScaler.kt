package org.helix.node.scaling

import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import org.helix.node.services.ServiceManager
import org.helix.node.tasks.TaskStore
import org.slf4j.LoggerFactory

/**
 * Keeps service capacity in line with demand.
 *
 * Every [tick] enforces three rules per task:
 * 1. at least `minServiceCount` services are alive,
 * 2. when auto-scale is enabled and the occupied player-slot ratio reaches
 *    the threshold, an additional service starts (up to `maxServiceCount`),
 * 3. surplus dynamic services that stayed empty for `idleStopSeconds` are
 *    stopped again.
 *
 * @property taskStore configured tasks.
 * @property manager service lifecycle owner.
 * @property clock epoch millis source, injectable for tests.
 */
class AutoScaler(
    private val taskStore: TaskStore,
    private val manager: ServiceManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(AutoScaler::class.java)
    private val retryAtEpochMs = mutableMapOf<String, Long>()

    /**
     * Runs one scaling pass over all tasks.
     */
    fun tick() {
        taskStore.all().forEach { task ->
            ensureMinimum(task)
            if (task.autoScale.enabled) {
                scaleUp(task)
                scaleDown(task)
            }
        }
    }

    private fun ensureMinimum(task: TaskDefinition) {
        if (inCooldown(task)) {
            return
        }
        while (manager.activeCount(task.name) < task.minServiceCount) {
            val started = runCatching { manager.startService(task.name) }
            if (started.isFailure) {
                retryAtEpochMs[task.name] = clock() + START_FAILURE_COOLDOWN_MS
                logger.error(
                    "Cannot keep minimum of {} for task {} — retrying in {}s: {}",
                    task.minServiceCount,
                    task.name,
                    START_FAILURE_COOLDOWN_MS / 1000,
                    started.exceptionOrNull()?.message,
                )
                return
            }
            logger.info("Started {} to keep task minimum", started.getOrThrow().id)
        }
    }

    private fun inCooldown(task: TaskDefinition): Boolean {
        val retryAt = retryAtEpochMs[task.name] ?: return false
        if (clock() >= retryAt) {
            retryAtEpochMs.remove(task.name)
            return false
        }
        return true
    }

    private fun scaleUp(task: TaskDefinition) {
        if (inCooldown(task) || manager.activeCount(task.name) >= task.maxServiceCount) {
            return
        }
        val running = manager.managedServices()
            .filter { it.task.name == task.name && it.state == ServiceState.RUNNING }
        val capacity = running.sumOf { it.maxPlayers }
        if (capacity <= 0) {
            return
        }
        val ratio = running.sumOf { it.onlinePlayers }.toDouble() / capacity
        if (ratio >= task.autoScale.playerRatioThreshold) {
            val started = runCatching { manager.startService(task.name) }
            started.onSuccess {
                logger.info(
                    "Scaled up task {} to {} (slot usage {})",
                    task.name,
                    it.id,
                    "%.2f".format(ratio),
                )
            }.onFailure {
                retryAtEpochMs[task.name] = clock() + START_FAILURE_COOLDOWN_MS
                logger.error("Scale-up of task {} failed", task.name, it)
            }
        }
    }

    private fun scaleDown(task: TaskDefinition) {
        if (task.staticServices) {
            return
        }
        val now = clock()
        val surplus = manager.activeCount(task.name) - task.minServiceCount
        if (surplus <= 0) {
            return
        }
        manager.managedServices()
            .filter { it.task.name == task.name && it.state == ServiceState.RUNNING }
            .filter { it.onlinePlayers == 0 }
            .filter { candidate ->
                val emptySince = candidate.emptySinceEpochMs
                emptySince != null && now - emptySince >= task.autoScale.idleStopSeconds * 1000
            }
            .sortedByDescending { it.id }
            .take(surplus)
            .forEach { idle ->
                logger.info("Stopping idle service {} of task {}", idle.id, task.name)
                manager.stopService(idle.id)
            }
    }

    private companion object {
        /** Millis a task is skipped after a failed service start. */
        const val START_FAILURE_COOLDOWN_MS = 60_000L
    }
}
