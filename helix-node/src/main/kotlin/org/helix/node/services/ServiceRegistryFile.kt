package org.helix.node.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.api.execution.ExecutorType
import org.helix.api.service.ServiceState
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * On-disk mirror of the running-services map.
 *
 * The [ServiceManager] rewrites this file on every lifecycle change, so a
 * restarted node knows which services may have survived headless and how to
 * re-adopt them (process id for process services, deterministic container
 * name for Docker services).
 *
 * Snapshots are stamped with a monotonically increasing sequence number
 * (see [nextSequence]) taken while the snapshot is captured, so a snapshot
 * that reaches the writer late can never overwrite a newer one.
 *
 * @property file path of the registry JSON file.
 */
class ServiceRegistryFile(private val file: Path) {
    private val logger = LoggerFactory.getLogger(ServiceRegistryFile::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val sequences = AtomicLong()

    /** Highest sequence number already written; guarded by the write lock. */
    private var lastWrittenSequence = 0L

    /**
     * Issues the sequence number for the next snapshot.
     *
     * Callers must draw the number while they capture the services snapshot
     * (under the same lock that guards the services map), so the sequence
     * order matches the snapshot order.
     *
     * @return a monotonically increasing snapshot sequence number.
     */
    fun nextSequence(): Long = sequences.incrementAndGet()

    /**
     * Rewrites the registry from a snapshot of the live services, unless a
     * snapshot with a higher sequence number was already written — a stale
     * snapshot is dropped instead of clobbering a newer one.
     *
     * Write failures are logged, never thrown: a broken registry mirror must
     * not take the service lifecycle down with it.
     *
     * @param sequence snapshot sequence number from [nextSequence].
     * @param services the services snapshot to persist.
     */
    @Synchronized
    fun write(sequence: Long, services: List<ManagedService>) {
        if (sequence <= lastWrittenSequence) {
            return
        }
        lastWrittenSequence = sequence
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
                controlToken = service.controlToken,
            )
        }
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temp, json.encodeToString(entries))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { failure ->
            logger.warn("Could not write the service registry {}: {}", file, failure.message)
        }
    }

    /**
     * Reads the registry left behind by the previous node process.
     *
     * A missing file is a normal cold boot and yields an empty list. An
     * existing but unparsable file is NOT the same thing: it may well
     * describe surviving services, so the error is logged and `null` is
     * returned — callers must treat that as "unknown survivors" and skip
     * destructive cleanup such as the orphan-workspace sweep.
     *
     * @return the persisted entries, empty when the file is absent, or
     *   `null` when the file exists but could not be parsed.
     */
    @Synchronized
    fun read(): List<ServiceRegistryEntry>? {
        if (!Files.exists(file)) {
            return emptyList()
        }
        return runCatching { json.decodeFromString<List<ServiceRegistryEntry>>(Files.readString(file)) }
            .onFailure { failure ->
                logger.error("Could not parse the service registry {}: {}", file, failure.message)
            }
            .getOrNull()
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
 * @property controlToken the bridge control token injected into the
 *  service's process environment, restored into the token registry when the
 *  service is re-adopted after a backend restart.
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
    val controlToken: String? = null,
)
