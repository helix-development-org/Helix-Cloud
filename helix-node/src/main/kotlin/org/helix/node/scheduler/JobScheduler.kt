package org.helix.node.scheduler

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionSource
import org.helix.api.storage.AddonStorage
import org.slf4j.LoggerFactory

/**
 * Runs recurring [ScheduledJob]s: interval-based or daily at a wall-clock time.
 *
 * Jobs are persisted through the central [AddonStorage] (so they survive
 * restarts and work with every storage mode) and executed by invoking their
 * action through the [ActionInvoker]. [tick] is called periodically by the node
 * scheduler; due jobs run at most once per interval / once per day.
 *
 * @property storage backing store for the job list.
 * @property actions action entry point used to run jobs.
 * @property eventSink receives `(category, level, message)` for the event log.
 * @property clock epoch-millis source, injectable for tests.
 * @property zone time zone used for daily schedules.
 */
class JobScheduler(
    private val storage: AddonStorage,
    private val actions: ActionInvoker,
    private val eventSink: (String, String, String) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val logger = LoggerFactory.getLogger(JobScheduler::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lastRun = mutableMapOf<String, Long>()
    private var jobs: List<ScheduledJob> = load()

    /**
     * Returns all configured jobs.
     *
     * @return the current job list.
     */
    @Synchronized
    fun all(): List<ScheduledJob> = jobs

    /**
     * Creates or replaces a job (matched by id) and persists.
     *
     * @param job the job to store.
     */
    @Synchronized
    fun save(job: ScheduledJob) {
        jobs = jobs.filter { it.id != job.id } + job
        persist()
    }

    /**
     * Deletes a job by id and persists.
     *
     * @param id the job id.
     * @return `true` if a job was removed.
     */
    @Synchronized
    fun delete(id: String): Boolean {
        val remaining = jobs.filter { it.id != id }
        if (remaining.size == jobs.size) {
            return false
        }
        jobs = remaining
        lastRun.remove(id)
        persist()
        return true
    }

    /**
     * Runs a job immediately regardless of its schedule.
     *
     * @param id the job id.
     * @return `true` if the job exists and was run.
     */
    @Synchronized
    fun runNow(id: String): Boolean {
        val job = jobs.firstOrNull { it.id == id } ?: return false
        execute(job)
        return true
    }

    /**
     * Evaluates all jobs and runs those that are due.
     */
    @Synchronized
    fun tick() {
        val now = clock()
        jobs.filter { it.enabled }.forEach { job ->
            if (isDue(job, now)) {
                execute(job)
            }
        }
    }

    /**
     * Snapshot of the last-run timestamps, for the restart state.
     *
     * @return job id to epoch millis of the last execution.
     */
    @Synchronized
    fun lastRuns(): Map<String, Long> = lastRun.toMap()

    /**
     * Restores last-run timestamps after a node restart, so daily jobs do
     * not re-fire and intervals do not reset.
     *
     * @param runs job id to epoch millis of the last execution.
     */
    @Synchronized
    fun restoreLastRuns(runs: Map<String, Long>) {
        lastRun.putAll(runs)
    }

    private fun isDue(job: ScheduledJob, now: Long): Boolean {
        val previous = lastRun[job.id]
        if (job.everyMinutes > 0) {
            val intervalMs = job.everyMinutes * 60_000L
            return previous == null || now - previous >= intervalMs
        }
        val at = job.dailyAt?.let(::parseTime) ?: return false
        val target = LocalDate.now(zone).atTime(at).atZone(zone).toInstant().toEpochMilli()
        return now >= target && (previous == null || previous < target)
    }

    private fun execute(job: ScheduledJob) {
        lastRun[job.id] = clock()
        val result = runCatching {
            actions.invoke(ActionInvocation(job.action, job.arguments, ActionSource.SYSTEM))
        }.onFailure { logger.error("scheduled job {} failed", job.id, it) }.getOrNull()
        val ok = result?.success == true
        eventSink("scheduler", if (ok) "info" else "warn", "Ran job ${job.id} (${job.action})")
    }

    private fun parseTime(value: String): LocalTime? =
        runCatching { LocalTime.parse(value.trim()) }.getOrNull()

    private fun load(): List<ScheduledJob> =
        storage.read(KEY)?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() } ?: emptyList()

    private fun persist() {
        storage.write(KEY, json.encodeToString(SERIALIZER, jobs))
    }

    private companion object {
        /** Storage document key holding the job list. */
        const val KEY = "jobs"

        /** Serializer for the persisted job list. */
        val SERIALIZER = ListSerializer(ScheduledJob.serializer())
    }
}
