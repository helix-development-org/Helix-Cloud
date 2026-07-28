package org.helix.node.services

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.api.execution.ExecutorType
import org.helix.api.service.ServiceState

/**
 * On-disk mirror of the running-services map.
 *
 * The [ServiceManager] rewrites this file on every lifecycle change, so a
 * restarted node knows which services may have survived headless and how to
 * re-adopt them (process id for process services, deterministic container
 * name for Docker services).
 *
 * @property file path of the registry JSON file.
 */
class ServiceRegistryFile(private val file: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Rewrites the registry from the current live services.
     *
     * @param services all managed services.
     */
    @Synchronized
    fun write(services: List<ManagedService>) {
        val entries = services.map { service ->
            ServiceRegistryEntry(
                id = service.id,
                task = service.task.name,
                workspace = service.workspace.toAbsolutePath().toString(),
                port = service.port,
                executor = service.task.executor,
                state = service.state,
                pid = service.handle?.pid,
                startedAtEpochMs = service.startedAtEpochMs,
                processStartInstantEpochMs = service.handle?.startInstantEpochMs,
            )
        }
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temp, json.encodeToString(entries))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /**
     * Reads the registry left behind by the previous node process.
     *
     * @return the persisted entries, or empty when absent or unreadable.
     */
    @Synchronized
    fun read(): List<ServiceRegistryEntry> {
        if (!Files.exists(file)) {
            return emptyList()
        }
        return runCatching { json.decodeFromString<List<ServiceRegistryEntry>>(Files.readString(file)) }
            .getOrDefault(emptyList())
    }
}

/**
 * One persisted service of the registry file.
 *
 * @property id service id.
 * @property task owning task name.
 * @property workspace absolute workspace path.
 * @property port allocated service port.
 * @property executor execution backend of the service.
 * @property state last known lifecycle state.
 * @property pid wrapper process id for process services.
 * @property startedAtEpochMs epoch millis of the last start.
 * @property processStartInstantEpochMs the wrapper process's OS start
 *  instant, used to confirm a re-attached pid is still the same process
 *  rather than one that reused the pid after a reboot.
 */
@Serializable
data class ServiceRegistryEntry(
    val id: String,
    val task: String,
    val workspace: String,
    val port: Int,
    val executor: ExecutorType,
    val state: ServiceState,
    val pid: Long? = null,
    val startedAtEpochMs: Long? = null,
    val processStartInstantEpochMs: Long? = null,
)
