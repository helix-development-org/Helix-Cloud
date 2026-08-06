package org.helix.api.task

import kotlinx.serialization.Serializable
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType

/**
 * Blueprint for services.
 *
 * A task describes what to run (environment + version), how to run it
 * (executor, memory, JVM arguments) and how many instances to keep alive.
 * Server type and version are configuration, not code.
 *
 * @property name unique task name, also the prefix of service ids.
 * @property environment platform the services run on.
 * @property version platform version, resolved through the version catalog.
 * @property executor execution backend for services of this task.
 * @property staticServices whether services are persistent: static services
 *   keep their workspace across restarts, dynamic services get a fresh
 *   workspace on every start and are deleted after stopping.
 * @property minServiceCount number of services the node always keeps alive.
 * @property maxServiceCount upper bound for auto-scaling and manual starts.
 * @property memoryMb maximum JVM heap per service in megabytes.
 * @property maxPlayers player slots a single service offers.
 * @property startPort first port to try when allocating service ports.
 * @property jvmArgs additional JVM arguments for the server process.
 * @property templates template names copied into new service workspaces.
 * @property fallbackEligible whether backend services of this task may serve
 *   as proxy fallback/lobby targets.
 * @property maintenance whether services of this task reject regular joins.
 * @property paused whether the auto-scaler leaves this task alone: no
 *   minimum-keeping, no scale-up/-down. Stopped services stay stopped, so
 *   a paused task can be edited or deleted without racing the scaler.
 * @property autoScale player-based scaling behaviour.
 * @property disabledAddons addon ids turned off for this task; every other
 *   installed addon is active. Empty means all addons are active.
 */
@Serializable
data class TaskDefinition(
    val name: String,
    val environment: Environment,
    val version: String,
    val executor: ExecutorType = ExecutorType.PROCESS,
    val staticServices: Boolean = false,
    val minServiceCount: Int = 1,
    val maxServiceCount: Int = 1,
    val memoryMb: Int = 1024,
    val maxPlayers: Int = 100,
    val startPort: Int = 25565,
    val jvmArgs: List<String> = emptyList(),
    val templates: List<String> = listOf("default"),
    val fallbackEligible: Boolean = false,
    val maintenance: Boolean = false,
    val paused: Boolean = false,
    val autoScale: AutoScaleSettings = AutoScaleSettings(),
    val disabledAddons: List<String> = emptyList(),
) {
    /**
     * Whether an addon is active for this task.
     *
     * @param addonId the addon id.
     * @return `true` unless the addon is explicitly disabled here.
     */
    fun isAddonActive(addonId: String): Boolean = addonId !in disabledAddons

    init {
        require(name.isNotBlank()) { "task name must not be blank" }
        require(name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "task name may only contain letters, digits, '-' and '_': $name"
        }
        require(version.isNotBlank()) { "task version must not be blank" }
        require(minServiceCount >= 0) { "minServiceCount must be >= 0" }
        require(maxServiceCount >= minServiceCount) {
            "maxServiceCount must be >= minServiceCount"
        }
        require(memoryMb >= 128) { "memoryMb must be >= 128" }
        require(startPort in 1..65535) { "startPort must be a valid port" }
    }
}
