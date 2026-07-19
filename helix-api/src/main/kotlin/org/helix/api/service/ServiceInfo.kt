package org.helix.api.service

import kotlinx.serialization.Serializable
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType

/**
 * Snapshot of a single service.
 *
 * @property id unique service id, `<task>-<index>`, for example `Lobby-1`.
 * @property taskName task this service was created from.
 * @property environment platform the service runs on.
 * @property executor execution backend the service runs in.
 * @property state current lifecycle state.
 * @property port port the service listens on (host port for docker).
 * @property static whether the service keeps its workspace across restarts.
 * @property onlinePlayers players currently connected, from heartbeats.
 * @property maxPlayers player slots the service offers.
 * @property startedAtEpochMs epoch millis of the last start, if started.
 */
@Serializable
data class ServiceInfo(
    val id: String,
    val taskName: String,
    val environment: Environment,
    val executor: ExecutorType,
    val state: ServiceState,
    val port: Int,
    val static: Boolean,
    val onlinePlayers: Int = 0,
    val maxPlayers: Int = 0,
    val startedAtEpochMs: Long? = null,
)
