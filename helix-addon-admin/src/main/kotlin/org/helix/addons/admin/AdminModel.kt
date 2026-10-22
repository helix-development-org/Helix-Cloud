package org.helix.addons.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One online player as reported by the Paper component of the server hosting them.
 *
 * @property name Minecraft name.
 * @property uuid player uuid.
 * @property service id of the Paper service the player is on.
 * @property world world name.
 * @property x block x.
 * @property y block y.
 * @property z block z.
 * @property health current health.
 * @property food food level.
 * @property gamemode game mode (`SURVIVAL`, `CREATIVE`, …).
 * @property ping latency in ms.
 * @property frozen whether staff froze the player.
 * @property vanished whether the player is vanished (staff only).
 */
@Serializable
data class AdminPlayer(
    val name: String,
    val uuid: String = "",
    val service: String = "",
    val world: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0,
    val health: Double = 0.0,
    val food: Int = 0,
    val gamemode: String = "",
    val ping: Int = 0,
    val frozen: Boolean = false,
    val vanished: Boolean = false,
)

/**
 * Presence report of one Paper service (`admin.presence`).
 *
 * @property service the reporting service id.
 * @property tps current ticks per second.
 * @property players everyone online there.
 */
@Serializable
data class PresenceReport(
    val service: String,
    val tps: Double = 20.0,
    val players: List<AdminPlayer> = emptyList(),
)

/**
 * An in-game job for a specific Paper service (cross-server actions such as "go to a player on another
 * server"). The payload is interpreted by the Paper component.
 *
 * @property seq monotonic sequence number, used by pollers to fetch only new jobs.
 * @property service id of the service that must execute the job.
 * @property type job type (`arrive`, `connect`, `freeze`, …).
 * @property payload type-specific arguments.
 * @property createdAtMs epoch millis of enqueueing.
 */
@Serializable
data class AdminJob(
    val seq: Long,
    val service: String,
    val type: String,
    val payload: JsonObject,
    val createdAtMs: Long,
)

/**
 * Result of `admin.jobs`: the jobs newer than the poller's cursor and the new cursor.
 *
 * @property seq highest sequence number handed out so far (the next cursor).
 * @property jobs pending jobs for the polling service.
 */
@Serializable
data class JobBatch(val seq: Long, val jobs: List<AdminJob>)

/**
 * Network-wide presence: the latest report of every Paper service, dropped when a service stops reporting.
 *
 * @property ttlMs age after which a service's report is considered stale.
 * @property clock epoch-millis source, injectable for tests.
 */
class Presence(private val ttlMs: Long = 15_000, private val clock: () -> Long = System::currentTimeMillis) {
    private val reports = linkedMapOf<String, Pair<PresenceReport, Long>>()

    /**
     * Stores a service's report, replacing its previous one.
     *
     * @param report the report.
     */
    @Synchronized
    fun update(report: PresenceReport) {
        reports[report.service] = report.copy(players = report.players.map { it.copy(service = report.service) }) to clock()
    }

    /** All players of all fresh reports, sorted by name. */
    @Synchronized
    fun players(): List<AdminPlayer> = fresh().flatMap { it.players }.sortedBy { it.name.lowercase() }

    /** Fresh service reports (tps, player count). */
    @Synchronized
    fun services(): List<PresenceReport> = fresh()

    /**
     * Finds the service a player is on.
     *
     * @param name player name (case-insensitive).
     * @return the service id, or `null` if the player is on no reporting service.
     */
    @Synchronized
    fun serviceOf(name: String): String? = players().firstOrNull { it.name.equals(name, ignoreCase = true) }?.service

    private fun fresh(): List<PresenceReport> {
        val now = clock()
        reports.entries.removeIf { now - it.value.second > ttlMs }
        return reports.values.map { it.first }
    }
}

/**
 * Job queue per Paper service. Jobs expire after [ttlMs] so a service that was down does not replay
 * stale teleports; the queue keeps at most [capacity] jobs.
 *
 * @property ttlMs job lifetime.
 * @property capacity maximum queued jobs.
 * @property clock epoch-millis source, injectable for tests.
 */
class JobQueue(
    private val ttlMs: Long = 30_000,
    private val capacity: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val jobs = ArrayDeque<AdminJob>()
    private var seq = 0L

    /**
     * Enqueues a job.
     *
     * @param service target service id.
     * @param type job type.
     * @param payload job arguments.
     * @return the queued job.
     */
    @Synchronized
    fun push(service: String, type: String, payload: JsonObject): AdminJob {
        prune()
        val job = AdminJob(++seq, service, type, payload, clock())
        jobs.addLast(job)
        while (jobs.size > capacity) jobs.removeFirst()
        return job
    }

    /**
     * Jobs for [service] newer than [after].
     *
     * @param service polling service id.
     * @param after the poller's cursor (`-1` on the first poll returns nothing but the current cursor, so a
     *  restarted service does not replay old jobs).
     * @return the batch and the next cursor.
     */
    @Synchronized
    fun poll(service: String, after: Long): JobBatch {
        prune()
        if (after < 0) return JobBatch(seq, emptyList())
        return JobBatch(seq, jobs.filter { it.service == service && it.seq > after })
    }

    private fun prune() {
        val now = clock()
        while (jobs.isNotEmpty() && now - jobs.first().createdAtMs > ttlMs) jobs.removeFirst()
    }
}
