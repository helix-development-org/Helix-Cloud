package org.helix.node.services

import org.helix.api.task.TaskDefinition
import java.nio.file.Path

/**
 * Everything an executor needs to start one prepared service.
 *
 * @property serviceId id of the service to start.
 * @property task task the service belongs to.
 * @property workspace prepared workspace containing `Wrapper.jar`,
 *   `wrapper.properties` and the server jar.
 * @property port port the service listens on (host port for docker).
 * @property environmentVariables variables exported to the wrapper and,
 *   through it, to the server and bridge.
 */
data class ServiceStartSpec(
    val serviceId: String,
    val task: TaskDefinition,
    val workspace: Path,
    val port: Int,
    val environmentVariables: Map<String, String>,
)
