package org.helix.addons.mineclash.queue

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A queued player.
 *
 * @property uuid player uuid.
 * @property name player name.
 * @property mode requested match mode (`1V1`, `2V2`).
 * @property serviceId service the player is currently on.
 * @property joinedAt epoch millis of the queue entry.
 */
data class QueueEntry(val uuid: String, val name: String, val mode: String, val serviceId: String, val joinedAt: Long)

/**
 * A match a game service has to start.
 *
 * @property matchId unique match id.
 * @property mode match mode.
 * @property teams team index to player uuids.
 * @property names player uuid to name.
 */
@Serializable
data class MatchAssignment(val matchId: String, val mode: String, val teams: List<List<String>>, val names: Map<String, String>)

/**
 * A player that has to be moved to another service before the match can start.
 *
 * @property uuid player uuid.
 * @property target service id to connect to.
 */
@Serializable
data class ConnectInstruction(val uuid: String, val target: String)

/**
 * Everything a service receives from one poll.
 *
 * @property matches matches this service hosts.
 * @property connect players on this service that must be sent elsewhere.
 */
@Serializable
data class PollResult(val matches: List<MatchAssignment> = emptyList(), val connect: List<ConnectInstruction> = emptyList())

/**
 * In-memory matchmaking for the arena modes. Game services announce themselves by polling; a
 * match is hosted on the service of the first queued player when that service is still alive,
 * otherwise on the least loaded live service. Players elsewhere receive connect instructions on
 * their own service's next poll.
 *
 * @property clock time source (epoch millis).
 * @property timeoutMillis how long a player may stay queued.
 * @property serviceTtlMillis how long a service counts as alive after its last poll.
 */
class Matchmaker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 600_000,
    private val serviceTtlMillis: Long = 10_000,
) {
    private val queue = ArrayList<QueueEntry>()
    private val services = ConcurrentHashMap<String, Long>()
    private val pending = ConcurrentHashMap<String, PollResult>()

    /**
     * Adds a player to the queue.
     *
     * @return `null` on success or a reason why the join was refused.
     */
    @Synchronized
    fun join(uuid: String, name: String, mode: String, serviceId: String): String? {
        if (queue.any { it.uuid == uuid }) return "already queued"
        val size = teamSize(mode) ?: return "unknown mode '$mode'"
        if (size < 1) return "unknown mode '$mode'"
        queue += QueueEntry(uuid, name, mode.uppercase(), serviceId, clock())
        services[serviceId] = clock()
        return null
    }

    /**
     * Removes a player from the queue.
     *
     * @return `true` when the player was queued.
     */
    @Synchronized
    fun leave(uuid: String): Boolean = queue.removeAll { it.uuid == uuid }

    /** Number of queued players per mode. */
    @Synchronized
    fun queued(): Map<String, Int> = queue.groupingBy { it.mode }.eachCount()

    /**
     * Poll of a game service: records it as alive, forms new matches and returns what this service
     * has to do (matches to host, players to connect elsewhere).
     *
     * @param serviceId the polling service.
     * @return the instructions for this service.
     */
    @Synchronized
    fun poll(serviceId: String): PollResult {
        val now = clock()
        services[serviceId] = now
        expire(now)
        formMatches(now)
        return pending.remove(serviceId) ?: PollResult()
    }

    private fun expire(now: Long) {
        queue.removeAll { now - it.joinedAt > timeoutMillis }
        services.entries.removeIf { now - it.value > serviceTtlMillis }
    }

    private fun formMatches(now: Long) {
        for (mode in queue.map { it.mode }.distinct()) {
            val size = teamSize(mode) ?: continue
            while (true) {
                val candidates = queue.filter { it.mode == mode }.take(size * 2)
                if (candidates.size < size * 2) break
                val host = hostFor(candidates, now) ?: break
                queue.removeAll { it in candidates }
                val teams = listOf(candidates.take(size).map { it.uuid }, candidates.drop(size).map { it.uuid })
                val assignment = MatchAssignment(UUID.randomUUID().toString(), mode, teams, candidates.associate { it.uuid to it.name })
                addPending(host) { it.copy(matches = it.matches + assignment) }
                candidates.filter { it.serviceId != host }.groupBy { it.serviceId }.forEach { (service, players) ->
                    addPending(service) { it.copy(connect = it.connect + players.map { p -> ConnectInstruction(p.uuid, host) }) }
                }
            }
        }
    }

    private fun hostFor(candidates: List<QueueEntry>, now: Long): String? {
        val alive = services.filterValues { now - it <= serviceTtlMillis }.keys
        if (alive.isEmpty()) return null
        val first = candidates.first().serviceId
        return if (first in alive) first else alive.minByOrNull { service -> queue.count { it.serviceId == service } }
    }

    private fun addPending(service: String, update: (PollResult) -> PollResult) {
        pending[service] = update(pending[service] ?: PollResult())
    }

    private fun teamSize(mode: String): Int? = when (mode.uppercase()) {
        "1V1" -> 1
        "2V2" -> 2
        else -> null
    }
}
