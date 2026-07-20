package org.helix.node.actions

import java.nio.file.Files
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.task.AutoScaleSettings
import org.helix.api.task.TaskDefinition
import org.helix.node.launcher.NodePaths
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.players.PlayerRegistry
import org.helix.node.proxy.ProxyCommandQueue
import org.helix.node.proxy.ProxyRoutingService
import org.helix.api.proxy.ProxyCommand
import org.helix.node.services.ServiceManager
import org.helix.node.tasks.TaskStore
import org.helix.node.versions.VersionCatalog

/**
 * Registers the built-in platform actions.
 *
 * @property paths data directory layout.
 * @property taskStore configured tasks.
 * @property manager service lifecycle owner.
 * @property routing proxy routing state.
 * @property overviewService aggregated platform counters.
 * @property versionCatalog loads the current version catalog.
 * @property shutdown initiates node shutdown, wired by the launcher.
 * @property commandQueue pending commands for proxy bridges.
 * @property playerRegistry online players of the network.
 */
class BuiltinActions(
    private val paths: NodePaths,
    private val taskStore: TaskStore,
    private val manager: ServiceManager,
    private val routing: ProxyRoutingService,
    private val overviewService: PlatformOverviewService,
    private val versionCatalog: () -> VersionCatalog,
    private val shutdown: () -> Unit,
    private val commandQueue: ProxyCommandQueue = ProxyCommandQueue(),
    private val playerRegistry: PlayerRegistry = PlayerRegistry(),
    private val eventSink: (category: String, level: String, message: String) -> Unit = { _, _, _ -> },
) {
    /**
     * Registers every built-in action.
     *
     * @param registry target registry.
     */
    fun registerAll(registry: ActionRegistry) {
        register(registry, "platform.overview", "Shows aggregated platform status.", "platform.overview") {
            val overview = overviewService.overview()
            ActionResult.ok(
                "Helix-Cloud ${overview.version}",
                "tasks: ${overview.taskCount}",
                "services: ${overview.servicesRunning}/${overview.servicesTotal} running",
                "players: ${overview.onlinePlayers}/${overview.maxPlayers}",
            )
        }
        register(registry, "platform.stop", "Stops all services and shuts the node down.", "platform.stop") {
            shutdown()
            ActionResult.ok("shutdown initiated")
        }
        register(registry, "actions.list", "Lists all registered actions.", "actions.list") {
            ActionResult.ok(
                *registry.descriptors()
                    .map { "${it.usage} — ${it.description}" }
                    .toTypedArray(),
            )
        }
        register(registry, "task.list", "Lists all configured tasks.", "task.list") {
            val tasks = taskStore.all()
            if (tasks.isEmpty()) {
                ActionResult.ok("no tasks configured — create one with task.create")
            } else {
                ActionResult.ok(
                    *tasks.map { task ->
                        val services = "${manager.activeCount(task.name)}/${task.maxServiceCount}"
                        "${task.name} [${task.environment} ${task.version}] " +
                            "executor=${task.executor} services=$services static=${task.staticServices}"
                    }.toTypedArray(),
                )
            }
        }
        register(registry, "task.info", "Shows the full configuration of a task.", "task.info <task>") { invocation ->
            val task = taskStore.find(argument(invocation, 0, "task"))
                ?: return@register ActionResult.error("unknown task: ${invocation.arguments.firstOrNull()}")
            ActionResult.ok(
                "name: ${task.name}",
                "environment: ${task.environment} ${task.version}",
                "executor: ${task.executor}",
                "static: ${task.staticServices}",
                "services: min=${task.minServiceCount} max=${task.maxServiceCount} active=${manager.activeCount(task.name)}",
                "memory: ${task.memoryMb} MB, maxPlayers: ${task.maxPlayers}, startPort: ${task.startPort}",
                "templates: ${task.templates.joinToString()}",
                "fallbackEligible: ${task.fallbackEligible}, maintenance: ${task.maintenance}",
                "autoScale: enabled=${task.autoScale.enabled} threshold=${task.autoScale.playerRatioThreshold} " +
                    "idleStopSeconds=${task.autoScale.idleStopSeconds}",
            )
        }
        register(
            registry,
            "task.create",
            "Creates a task. Options: executor, static, min, max, memory, maxPlayers, " +
                "startPort, fallback, autoscale, threshold, idleStop.",
            "task.create <name> <PAPER|VELOCITY> <version> [key=value...]",
        ) { invocation ->
            createTask(invocation)
        }
        register(registry, "task.delete", "Deletes a task without active services.", "task.delete <task>") { invocation ->
            val name = argument(invocation, 0, "task")
            if (manager.activeCount(name) > 0) {
                return@register ActionResult.error("task $name still has active services")
            }
            if (taskStore.delete(name)) {
                eventSink("task", "info", "Deleted task $name")
                ActionResult.ok("deleted task $name")
            } else {
                ActionResult.error("unknown task: $name")
            }
        }
        register(registry, "service.list", "Lists all services with their state.", "service.list") {
            val services = manager.services()
            if (services.isEmpty()) {
                ActionResult.ok("no services")
            } else {
                ActionResult.ok(
                    *services.map { service ->
                        "${service.id} [${service.state}] port=${service.port} " +
                            "players=${service.onlinePlayers}/${service.maxPlayers} executor=${service.executor}"
                    }.toTypedArray(),
                )
            }
        }
        register(registry, "service.start", "Starts a new service of a task.", "service.start <task>") { invocation ->
            val info = manager.startService(argument(invocation, 0, "task"))
            ActionResult.ok("started ${info.id} on port ${info.port}")
        }
        register(registry, "service.stop", "Stops a service gracefully.", "service.stop <service>") { invocation ->
            val id = argument(invocation, 0, "service")
            if (manager.stopService(id)) ActionResult.ok("stopping $id") else ActionResult.error("service not running: $id")
        }
        register(registry, "service.kill", "Kills a service immediately.", "service.kill <service>") { invocation ->
            val id = argument(invocation, 0, "service")
            if (manager.killService(id)) ActionResult.ok("killed $id") else ActionResult.error("service not running: $id")
        }
        register(registry, "service.logs", "Shows the newest log lines of a service.", "service.logs <service> [lines]") { invocation ->
            val id = argument(invocation, 0, "service")
            val tail = invocation.arguments.getOrNull(1)?.toIntOrNull() ?: 25
            val lines = manager.logs(id, tail)
            if (lines.isEmpty()) ActionResult.ok("no logs for $id") else ActionResult.ok(*lines.toTypedArray())
        }
        register(
            registry,
            "proxy.maintenance",
            "Shows or toggles network maintenance.",
            "proxy.maintenance [on|off]",
        ) { invocation ->
            when (invocation.arguments.firstOrNull()?.lowercase()) {
                null -> ActionResult.ok("maintenance: ${if (routing.maintenance) "on" else "off"}")
                "on" -> {
                    routing.maintenance = true
                    eventSink("proxy", "warn", "Maintenance enabled")
                    ActionResult.ok("maintenance enabled")
                }
                "off" -> {
                    routing.maintenance = false
                    eventSink("proxy", "info", "Maintenance disabled")
                    ActionResult.ok("maintenance disabled")
                }
                else -> ActionResult.error("usage: proxy.maintenance [on|off]")
            }
        }
        register(
            registry,
            "player.kick",
            "Kicks a player from the network through all active proxies.",
            "player.kick <player> [reason...]",
        ) { invocation ->
            val player = argument(invocation, 0, "player")
            val reason = invocation.arguments.drop(1).joinToString(" ").ifBlank { null }
            deliver(ProxyCommand.kick(player, reason))
        }
        register(
            registry,
            "player.message",
            "Sends a chat message to a player anywhere on the network.",
            "player.message <player> <text...>",
        ) { invocation ->
            val player = argument(invocation, 0, "player")
            val text = invocation.arguments.drop(1).joinToString(" ")
            if (text.isBlank()) {
                ActionResult.error("usage: player.message <player> <text...>")
            } else {
                deliver(ProxyCommand.message(player, text))
            }
        }
        register(
            registry,
            "player.broadcast",
            "Broadcasts a chat message to every player on the network.",
            "player.broadcast <text...>",
        ) { invocation ->
            val text = invocation.arguments.joinToString(" ")
            if (text.isBlank()) {
                ActionResult.error("usage: player.broadcast <text...>")
            } else {
                deliver(ProxyCommand.broadcast(text))
            }
        }
        register(registry, "player.list", "Lists all players online on the network.", "player.list") {
            val players = playerRegistry.online()
            if (players.isEmpty()) {
                ActionResult.ok("no players online")
            } else {
                ActionResult.ok(
                    "${players.size} online: ${players.joinToString { it.name }}",
                )
            }
        }
        register(registry, "versions.list", "Lists configured platform versions.", "versions.list") {
            val entries = versionCatalog().entries
            if (entries.isEmpty()) {
                ActionResult.ok("no versions configured in config/versions.toml")
            } else {
                ActionResult.ok(
                    *entries.map { entry ->
                        "${entry.environment} ${entry.version}" + (entry.url?.let { " (override: $it)" } ?: "")
                    }.toTypedArray(),
                )
            }
        }
    }

    private fun createTask(invocation: ActionInvocation): ActionResult {
        val arguments = invocation.arguments
        if (arguments.size < 3) {
            return ActionResult.error("usage: task.create <name> <PAPER|VELOCITY> <version> [key=value...]")
        }
        val name = arguments[0]
        if (taskStore.find(name) != null) {
            return ActionResult.error("task already exists: $name")
        }
        val environment = runCatching { Environment.valueOf(arguments[1].uppercase()) }.getOrNull()
            ?: return ActionResult.error("unknown environment: ${arguments[1]}")
        if (arguments[2].isBlank()) {
            val known = versionCatalog().entries
                .filter { it.environment == environment }
                .joinToString { it.version }
            return ActionResult.error(
                "version must not be blank" +
                    if (known.isNotBlank()) " — configured for $environment: $known" else "",
            )
        }
        val options = arguments.drop(3).mapNotNull { option ->
            val parts = option.split("=", limit = 2)
            if (parts.size == 2) parts[0].lowercase() to parts[1] else null
        }.toMap()
        val defaultPort = if (environment.proxy) 25577 else 25565
        val task = TaskDefinition(
            name = name,
            environment = environment,
            version = arguments[2],
            executor = options["executor"]?.uppercase()?.let(ExecutorType::valueOf) ?: ExecutorType.PROCESS,
            staticServices = options["static"]?.toBooleanStrict() ?: false,
            minServiceCount = options["min"]?.toInt() ?: 1,
            maxServiceCount = options["max"]?.toInt() ?: (options["min"]?.toInt() ?: 1),
            memoryMb = options["memory"]?.toInt() ?: 1024,
            maxPlayers = options["maxplayers"]?.toInt() ?: 100,
            startPort = options["startport"]?.toInt() ?: defaultPort,
            templates = listOf(name),
            fallbackEligible = options["fallback"]?.toBooleanStrict() ?: (!environment.proxy),
            autoScale = AutoScaleSettings(
                enabled = options["autoscale"]?.toBooleanStrict() ?: false,
                playerRatioThreshold = options["threshold"]?.toDouble() ?: 0.8,
                idleStopSeconds = options["idlestop"]?.toLong() ?: 300,
            ),
        )
        taskStore.save(task)
        Files.createDirectories(paths.templates.resolve(name))
        eventSink("task", "info", "Created task ${task.name} (${task.environment} ${task.version})")
        return ActionResult.ok(
            "created task ${task.name} (${task.environment} ${task.version}, executor=${task.executor})",
            "template directory: templates/${task.name}",
        )
    }

    private fun deliver(command: ProxyCommand): ActionResult {
        val managedProxies = manager.managedServices()
            .filter { it.task.environment.proxy && it.active() }
            .map { it.id }
        val reportingProxies = playerRegistry.online().map { it.proxyServiceId }.filter { it.isNotBlank() }
        val proxies = (managedProxies + reportingProxies).distinct()
        return if (proxies.isEmpty()) {
            ActionResult.error("no active proxy to deliver to")
        } else {
            commandQueue.enqueue(proxies, command)
            ActionResult.ok("${command.type} queued on ${proxies.joinToString()}")
        }
    }

    private fun argument(invocation: ActionInvocation, index: Int, label: String): String =
        requireNotNull(invocation.arguments.getOrNull(index)) { "missing argument: <$label>" }

    private fun register(
        registry: ActionRegistry,
        name: String,
        description: String,
        usage: String,
        handler: (ActionInvocation) -> ActionResult,
    ) {
        registry.register(ActionDescriptor(name, description, usage), handler)
    }
}
