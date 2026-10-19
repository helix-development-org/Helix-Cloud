package org.helix.node.release

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.release.ReleaseReset
import org.helix.node.actions.ActionRegistry
import org.helix.node.backup.BackupService
import org.helix.node.launcher.NodePaths
import org.helix.node.proxy.ProxyRoutingService
import org.helix.node.services.ServiceManager
import org.helix.node.tasks.TaskStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * [ReleaseHost] backed by the running node.
 *
 * @property paths data directory layout (static workspaces).
 * @property taskStore configured tasks (pause flag).
 * @property manager service lifecycle.
 * @property backups backup archives.
 * @property routing maintenance flag.
 * @property registry action registry (participants).
 * @property eventSink dashboard events.
 */
class NodeReleaseHost(
    private val paths: NodePaths,
    private val taskStore: TaskStore,
    private val manager: ServiceManager,
    private val backups: BackupService,
    private val routing: ProxyRoutingService,
    private val registry: ActionRegistry,
    private val eventSink: (category: String, level: String, message: String) -> Unit,
) : ReleaseHost {
    override fun taskExists(task: String): Boolean = taskStore.find(task) != null

    override fun isPaused(task: String): Boolean = taskStore.find(task)?.paused ?: false

    override fun setPaused(task: String, paused: Boolean) {
        val definition = taskStore.find(task) ?: return
        if (definition.paused != paused) taskStore.save(definition.copy(paused = paused))
    }

    override fun stopServices(task: String): List<String> =
        manager.services().filter { it.taskName == task }.map { it.id }.filter { manager.stopService(it) }

    override fun killServices(task: String) {
        manager.services().filter { it.taskName == task }.forEach { manager.killService(it.id) }
    }

    override fun activeCount(task: String): Int = manager.activeCount(task)

    override fun staticWorkspaces(task: String): List<Pair<String, Path>> {
        val root = paths.servicesStatic
        if (!root.isDirectory()) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { it.isDirectory() && it.name.startsWith("$task-") && it.name.removePrefix("$task-").all(Char::isDigit) }
                .toList()
        }.sortedBy { it.name }.map { it.name to it }
    }

    override fun backupService(serviceId: String): String = backups.create(serviceId).fileName

    override fun backupData(): String = backups.createData().fileName

    override var maintenance: Boolean
        get() = routing.maintenance
        set(value) {
            routing.maintenance = value
        }

    override fun participants(): List<String> =
        registry.descriptors().map { it.name }.filter { it.endsWith(ReleaseReset.ACTION_SUFFIX) && it != ReleaseActions.RESET }

    override fun invoke(action: String, arguments: List<String>): ActionResult =
        registry.invoke(ActionInvocation(action, arguments, ActionSource.SYSTEM, actor = "release.reset"))

    override fun event(level: String, message: String) = eventSink("release", level, message)
}
