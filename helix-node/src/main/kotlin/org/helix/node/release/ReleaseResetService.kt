package org.helix.node.release

import org.helix.api.action.ActionResult
import org.helix.api.release.ReleaseReport
import org.helix.api.release.ReleaseReset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * What the release reset needs from the node; [NodeReleaseHost] wires the real services, tests use fakes.
 */
interface ReleaseHost {
    /**
     * @param task task name.
     * @return whether the task exists.
     */
    fun taskExists(task: String): Boolean

    /**
     * @param task task name.
     * @return whether the auto-scaler currently leaves the task alone.
     */
    fun isPaused(task: String): Boolean

    /**
     * Pauses or resumes a task.
     *
     * @param task task name.
     * @param paused new state.
     */
    fun setPaused(task: String, paused: Boolean)

    /**
     * Stops every running service of a task gracefully.
     *
     * @param task task name.
     * @return ids of the services asked to stop.
     */
    fun stopServices(task: String): List<String>

    /**
     * Kills every still running service of a task.
     *
     * @param task task name.
     */
    fun killServices(task: String)

    /**
     * @param task task name.
     * @return number of active services of the task.
     */
    fun activeCount(task: String): Int

    /**
     * @param task task name.
     * @return static service ids of the task with their workspaces.
     */
    fun staticWorkspaces(task: String): List<Pair<String, Path>>

    /**
     * Zips a static workspace.
     *
     * @param serviceId static service id.
     * @return the archive file name.
     */
    fun backupService(serviceId: String): String

    /**
     * Zips the json-mode data directories.
     *
     * @return the archive file name.
     * @throws IllegalStateException in postgres/mongodb mode (no data directories).
     */
    fun backupData(): String

    /** Network maintenance flag. */
    var maintenance: Boolean

    /** @return names of all registered participant actions (`*.release.reset`). */
    fun participants(): List<String>

    /**
     * Invokes an action.
     *
     * @param action action name.
     * @param arguments arguments.
     * @return the result.
     */
    fun invoke(action: String, arguments: List<String>): ActionResult

    /**
     * Records a dashboard event.
     *
     * @param level `info`, `warn` or `error`.
     * @param message event text.
     */
    fun event(level: String, message: String)
}

/**
 * State of the last release reset run.
 *
 * @property state `idle`, `running`, `done` or `failed`.
 * @property startedAt start time (epoch ms), 0 when never started.
 * @property finishedAt end time (epoch ms), `null` while running.
 * @property steps progress log.
 * @property reports participant reports of the run.
 * @property errors problems that did not stop the run.
 */
data class ReleaseStatus(
    val state: String = IDLE,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val steps: List<String> = emptyList(),
    val reports: List<ReleaseReport> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    /** Run states. */
    companion object {
        /** Never started. */
        const val IDLE: String = "idle"

        /** In progress. */
        const val RUNNING: String = "running"

        /** Finished. */
        const val DONE: String = "done"

        /** Aborted. */
        const val FAILED: String = "failed"
    }
}

/**
 * Network release reset (plan 1, section 9): stops the game tasks, backs them up, lets every participating addon
 * reset its own data (`<addon>.release.reset`), wipes player worlds of the static game services, resumes the tasks
 * and finally opens the network.
 *
 * Kept by design: permissions (incl. the beta badge), bans and moderation, feedback data and pending release
 * rewards, configuration and seeds, clans (banks emptied), built spawn worlds.
 *
 * @property host node services.
 * @property config release configuration supplier (re-read for every plan/run).
 * @property clock current time in epoch millis.
 * @property sleep pause between stop polls.
 * @property stopTimeoutMs how long services may take to stop before they are killed.
 * @property launch starts the background run (a daemon thread by default; tests run inline).
 */
class ReleaseResetService(
    private val host: ReleaseHost,
    private val config: () -> ReleaseConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS,
    private val launch: (Runnable) -> Unit = { Thread(it, "helix-release-reset").apply { isDaemon = true }.start() },
) {
    @Volatile
    private var status = ReleaseStatus()

    /** @return the state of the last run. */
    fun status(): ReleaseStatus = status

    /**
     * Dry run: what would be stopped, backed up, deleted and kept.
     *
     * @return human readable lines (one participant report per addon as JSON at the end).
     */
    fun plan(): List<String> {
        val cfg = config()
        val lines = mutableListOf("release reset plan (confirm with: release.reset run ${cfg.phrase} [--remove-testers] [--keep-maintenance])")
        cfg.tasks.forEach { task ->
            if (!host.taskExists(task.task)) {
                lines += "task ${task.task}: not configured on this node – skipped"
                return@forEach
            }
            val workspaces = host.staticWorkspaces(task.task)
            lines += "task ${task.task}: stop ${host.activeCount(task.task)} service(s), back up ${workspaces.size} static workspace(s)"
            workspaces.forEach { (id, root) ->
                val targets = WipeTargets.resolve(root, task.wipe)
                lines += "  $id: wipe ${targets.size} path(s)" +
                    if (targets.isEmpty()) "" else ": " + targets.joinToString { root.relativize(it).toString() }
            }
        }
        host.participants().sorted().forEach { action ->
            val result = runCatching { host.invoke(action, listOf(ReleaseReset.PLAN)) }.getOrElse { ActionResult.error(it.message ?: "failed") }
            val report = ReleaseReset.parse(result.lines.firstOrNull())
            lines += if (result.success && report != null) describe(report) else "$action: plan failed – ${result.lines.joinToString(" ")}"
        }
        lines += "afterwards: maintenance off (unless --keep-maintenance), tester group kept (unless --remove-testers)"
        return lines
    }

    /**
     * Starts the reset in the background.
     *
     * @param phrase confirmation phrase.
     * @param removeTesters also remove every `tester` group membership.
     * @param keepMaintenance leave maintenance on after the reset.
     * @return ok when started; error on a wrong phrase or a run in progress.
     */
    @Synchronized
    fun run(phrase: String?, removeTesters: Boolean, keepMaintenance: Boolean): ActionResult {
        val cfg = config()
        if (phrase != cfg.phrase) return ActionResult.error("wrong_phrase", "confirmation phrase does not match (see config/${ReleaseConfig.FILE_NAME})")
        if (status.state == ReleaseStatus.RUNNING) return ActionResult.error("running", "a release reset is already running")
        status = ReleaseStatus(state = ReleaseStatus.RUNNING, startedAt = clock())
        launch(Runnable { execute(cfg, removeTesters, keepMaintenance) })
        return ActionResult.ok("release reset started – follow it with release.status")
    }

    private fun execute(cfg: ReleaseConfig, removeTesters: Boolean, keepMaintenance: Boolean) {
        val wasPaused = mutableMapOf<String, Boolean>()
        val tasks = cfg.tasks.filter { host.taskExists(it.task) }
        try {
            step("maintenance on")
            host.maintenance = true
            host.event("warn", "Release reset started")
            tasks.forEach { task ->
                wasPaused[task.task] = host.isPaused(task.task)
                host.setPaused(task.task, true)
                val stopping = host.stopServices(task.task)
                step("task ${task.task} paused, stopping ${stopping.size} service(s)")
            }
            tasks.forEach { task -> awaitStopped(task.task) }
            tasks.forEach { task ->
                host.staticWorkspaces(task.task).forEach { (id, _) ->
                    runCatching { host.backupService(id) }
                        .onSuccess { step("backup $id: $it") }
                        .onFailure { error("backup $id failed: ${it.message}") }
                }
            }
            runCatching { host.backupData() }
                .onSuccess { step("data backup: $it") }
                .onFailure { step("data backup skipped (${it.message}) – run backup.sh (pg_dump) before the release reset") }
            val args = buildList {
                add(ReleaseReset.RUN)
                if (removeTesters) add(ReleaseReset.FLAG_REMOVE_TESTERS)
            }
            host.participants().sorted().forEach { action ->
                val result = runCatching { host.invoke(action, args) }.getOrElse { ActionResult.error(it.message ?: "failed") }
                val report = ReleaseReset.parse(result.lines.firstOrNull())
                if (result.success && report != null) {
                    status = status.copy(reports = status.reports + report)
                    step(describe(report))
                } else {
                    error("$action failed: ${result.lines.joinToString(" ")}")
                }
            }
            tasks.forEach { task ->
                host.staticWorkspaces(task.task).forEach { (id, root) ->
                    val targets = WipeTargets.resolve(root, task.wipe)
                    targets.forEach { target ->
                        runCatching { WipeTargets.delete(target) }.onFailure { error("wipe $id/${root.relativize(target)} failed: ${it.message}") }
                    }
                    step("wiped ${targets.size} path(s) of $id")
                }
            }
            status = status.copy(state = ReleaseStatus.DONE)
        } catch (e: Exception) {
            error("aborted: ${e.message}")
            status = status.copy(state = ReleaseStatus.FAILED)
        } finally {
            tasks.forEach { task ->
                host.setPaused(task.task, wasPaused[task.task] ?: false)
                step("task ${task.task} " + if (wasPaused[task.task] == true) "stays paused" else "resumed")
            }
            if (status.state == ReleaseStatus.DONE && !keepMaintenance) {
                host.maintenance = false
                step("maintenance off – the network is open")
            } else {
                step("maintenance stays on")
            }
            status = status.copy(finishedAt = clock())
            host.event(if (status.state == ReleaseStatus.DONE) "info" else "error", "Release reset ${status.state}")
        }
    }

    private fun awaitStopped(task: String) {
        val deadline = clock() + stopTimeoutMs
        while (host.activeCount(task) > 0) {
            if (clock() >= deadline) {
                host.killServices(task)
                step("task $task: services killed after ${stopTimeoutMs / 1000}s")
                sleep(POLL_MS)
                check(host.activeCount(task) == 0) { "services of $task did not stop" }
                return
            }
            sleep(POLL_MS)
        }
        step("task $task: all services stopped")
    }

    private fun step(text: String) {
        status = status.copy(steps = status.steps + text)
    }

    private fun error(text: String) {
        status = status.copy(steps = status.steps + "ERROR $text", errors = status.errors + text)
    }

    private fun describe(report: ReleaseReport): String {
        val deleted = report.deleted.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "nothing" }
        val kept = report.kept.keys.joinToString().ifBlank { "–" }
        val notes = if (report.notes.isEmpty()) "" else " · " + report.notes.joinToString(" · ")
        return "${report.addon}: ${if (report.mode == ReleaseReset.PLAN) "would delete" else "deleted"} $deleted; kept $kept$notes"
    }

    private companion object {
        const val POLL_MS = 1000L
        const val DEFAULT_STOP_TIMEOUT_MS = 180_000L
    }
}

/** Resolves and deletes the wipe paths of a workspace; never leaves the workspace. */
object WipeTargets {
    /**
     * Resolves wipe patterns below a workspace.
     *
     * @param root service workspace.
     * @param patterns relative paths; the last segment may contain `*`.
     * @return existing paths inside [root], sorted.
     */
    fun resolve(root: Path, patterns: List<String>): List<Path> {
        val base = root.toAbsolutePath().normalize()
        return patterns.flatMap { pattern ->
            val segments = pattern.trim().trim('/').split('/').filter { it.isNotBlank() && it != "." }
            if (segments.isEmpty() || segments.any { it == ".." }) return@flatMap emptyList()
            val parent = segments.dropLast(1).fold(base) { acc, seg -> acc.resolve(seg) }.normalize()
            val last = segments.last()
            if (!parent.startsWith(base) || !parent.isDirectory()) return@flatMap emptyList()
            if ('*' in last) {
                val regex = Regex(last.split('*').joinToString(".*") { Regex.escape(it) })
                Files.list(parent).use { stream -> stream.filter { regex.matches(it.name) }.toList() }
            } else {
                listOf(parent.resolve(last)).filter { Files.exists(it) }
            }
        }.filter { it.normalize().startsWith(base) && it.normalize() != base }.distinct().sorted()
    }

    /**
     * Deletes a file or a directory tree.
     *
     * @param target the path.
     */
    fun delete(target: Path) {
        if (target.isDirectory()) {
            Files.walk(target).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        } else {
            Files.deleteIfExists(target)
        }
    }
}
