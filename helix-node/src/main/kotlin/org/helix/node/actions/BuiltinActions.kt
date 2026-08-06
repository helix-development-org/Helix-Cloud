package org.helix.node.actions

import java.nio.file.Files
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.task.AutoScaleSettings
import org.helix.api.task.TaskDefinition
import org.helix.api.message.MapMessages
import org.helix.api.message.Messages
import org.helix.api.storage.InMemoryAddonStorage
import org.helix.node.languages.LanguageRegistry
import org.helix.node.launcher.NodePaths
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.players.PlayerRegistry
import org.helix.node.proxy.ProxyCommandQueue
import org.helix.node.proxy.ProxyEventHub
import org.helix.node.proxy.ProxyRoutingService
import org.helix.api.proxy.ProxyCommand
import org.helix.node.services.RestartCoordinator
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
 * @property eventSink records dashboard events.
 * @property proxyEvents wakes long-polling proxy bridges on new commands.
 * @property languages network languages and player language preferences.
 * @property helixMessages texts of the `/helix` player command.
 * @property adminCheck whether a player holds `helix.admin`, for the admin
 *  subcommands of `/helix`.
 * @property addonSubcommands handler of the `/helix` addon-management
 *  subcommands, wired to [org.helix.node.addons.AddonActions].
 * @property restartBackend restarts the node process while services keep
 *  running headless, wired by the launcher.
 * @property restartLauncher stops services and starts a fresh Launcher.jar,
 *  wired by the launcher.
 * @property networkPackUrl persists the public network resource-pack URL
 *  override (`null` resets to automatic resolution), wired by the launcher.
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
    private val proxyEvents: ProxyEventHub = ProxyEventHub(),
    private val languages: LanguageRegistry = LanguageRegistry(InMemoryAddonStorage()),
    private val helixMessages: Messages = MapMessages(emptyMap()),
    private val adminCheck: (player: String) -> Boolean = { false },
    private val addonSubcommands: (args: List<String>) -> ActionResult = {
        ActionResult.error("addon management unavailable")
    },
    private val restartBackend: () -> Boolean = { false },
    private val restartLauncher: () -> Boolean = { false },
    private val networkPackUrl: (url: String?) -> Unit = {},
) {
    private val restarts = RestartCoordinator(
        manager = manager,
        deliver = { command -> deliver(command) },
        eventSink = eventSink,
    )
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
        register(
            registry,
            "platform.restart",
            "Restarts the node process; services keep running headless and are re-adopted.",
            "platform.restart",
        ) {
            if (restartBackend()) {
                ActionResult.ok("backend restart initiated — services keep running")
            } else {
                ActionResult.error("restart unavailable (not running from Launcher.jar, or already stopping)")
            }
        }
        register(
            registry,
            "launcher.restart",
            "Stops all services and starts a fresh Launcher.jar in place of this one.",
            "launcher.restart",
        ) {
            if (restartLauncher()) {
                ActionResult.ok("launcher restart initiated — services stop, new launcher starts")
            } else {
                ActionResult.error("restart unavailable (not running from Launcher.jar, or already stopping)")
            }
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
                            "executor=${task.executor} services=$services static=${task.staticServices}" +
                            if (task.paused) " PAUSED" else ""
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
                "fallbackEligible: ${task.fallbackEligible}, maintenance: ${task.maintenance}, paused: ${task.paused}",
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
        register(
            registry,
            "task.pause",
            "Pauses a task: the auto-scaler stops managing it; with 'stop' all its services stop too.",
            "task.pause <task> [stop]",
        ) { invocation ->
            val name = argument(invocation, 0, "task")
            val task = taskStore.find(name)
                ?: return@register ActionResult.error("unknown task: $name")
            taskStore.save(task.copy(paused = true))
            val lines = mutableListOf("paused task $name — the auto-scaler leaves it alone")
            if (invocation.arguments.getOrNull(1)?.equals("stop", ignoreCase = true) == true) {
                val stopped = manager.services()
                    .filter { it.taskName == name && manager.stopService(it.id) }
                lines += "stopping ${stopped.size} service(s): ${stopped.joinToString { it.id }}"
            } else if (manager.activeCount(name) > 0) {
                lines += "services keep running — stop them with service.stop or task.pause $name stop"
            }
            eventSink("task", "info", "Paused task $name")
            ActionResult.ok(*lines.toTypedArray())
        }
        register(
            registry,
            "task.resume",
            "Resumes a paused task; the auto-scaler takes over again.",
            "task.resume <task>",
        ) { invocation ->
            val name = argument(invocation, 0, "task")
            val task = taskStore.find(name)
                ?: return@register ActionResult.error("unknown task: $name")
            taskStore.save(task.copy(paused = false))
            eventSink("task", "info", "Resumed task $name")
            ActionResult.ok("resumed task $name")
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
        register(
            registry,
            "service.restart",
            "Restarts a service after an announced countdown.",
            "service.restart <service> [delaySeconds]",
        ) { invocation ->
            val id = argument(invocation, 0, "service")
            val delay = delaySeconds(invocation, 1)
                ?: return@register ActionResult.error("invalid delay: ${invocation.arguments[1]}")
            if (restarts.restartService(id, delay)) {
                ActionResult.ok("restart of $id scheduled in ${delay}s")
            } else {
                ActionResult.error("service not running: $id")
            }
        }
        register(
            registry,
            "task.restart",
            "Rolling-restarts every service of a task after an announced countdown.",
            "task.restart <task> [delaySeconds]",
        ) { invocation ->
            val name = argument(invocation, 0, "task")
            taskStore.find(name) ?: return@register ActionResult.error("unknown task: $name")
            val delay = delaySeconds(invocation, 1)
                ?: return@register ActionResult.error("invalid delay: ${invocation.arguments[1]}")
            val count = restarts.restartTask(name, delay)
            if (count > 0) {
                ActionResult.ok("rolling restart of $count service(s) of $name scheduled in ${delay}s")
            } else {
                ActionResult.error("no active services for task: $name")
            }
        }
        register(
            registry,
            "service.command",
            "Sends a console command line to a running service.",
            "service.command <service> <line...>",
        ) { invocation ->
            val id = argument(invocation, 0, "service")
            val line = invocation.arguments.drop(1).joinToString(" ")
            when {
                line.isBlank() -> ActionResult.error("usage: service.command <service> <line...>")
                manager.sendCommand(id, line) -> ActionResult.ok("sent to $id: $line")
                else -> ActionResult.error("service not running: $id")
            }
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
        registry.register(
            ActionDescriptor(
                name = "helix",
                description = "Helix network command: language, administration and restarts.",
                usage = "helix <language|addons|enable|disable|reload|backend|launcher> [arg]",
                playerCommand = true,
            ),
            ::handleHelixCommand,
        )
        register(
            registry,
            "network.packurl",
            "Sets the public network resource-pack URL clients download; '-' resets to auto.",
            "network.packurl <url|->",
        ) { invocation ->
            val url = invocation.arguments.firstOrNull()
                ?: return@register ActionResult.error("usage: network.packurl <url|->")
            networkPackUrl(if (url == "-") null else url)
            ActionResult.ok(if (url == "-") "network pack url reset to auto" else "network pack url set to $url")
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

    private fun handleHelixCommand(invocation: ActionInvocation): ActionResult {
        val player = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("missing executing player")
        return when (invocation.arguments.getOrNull(1)?.lowercase()) {
            null, "language" -> language(player, invocation.arguments.getOrNull(2))
            "addons", "list", "enable", "disable", "reload" -> requireAdmin(player) {
                addonSubcommands(invocation.arguments.drop(1))
            }
            "backend" -> requireAdmin(player) { moduleRestart(player, "backend", invocation) }
            "launcher" -> requireAdmin(player) { moduleRestart(player, "launcher", invocation) }
            else -> ActionResult.ok(helixMessages.formatFor(player, "usage"))
        }
    }

    private fun moduleRestart(player: String, module: String, invocation: ActionInvocation): ActionResult {
        if (invocation.arguments.getOrNull(2)?.lowercase() != "restart") {
            return ActionResult.ok(helixMessages.formatFor(player, "usage"))
        }
        val initiated = if (module == "backend") restartBackend() else restartLauncher()
        return if (initiated) {
            ActionResult.ok(helixMessages.formatFor(player, "restart.$module"))
        } else {
            ActionResult.error(helixMessages.formatFor(player, "restart.unavailable"))
        }
    }

    private fun requireAdmin(player: String, block: () -> ActionResult): ActionResult =
        if (adminCheck(player)) {
            block()
        } else {
            ActionResult.error(helixMessages.formatFor(player, "no_permission"))
        }

    private fun language(player: String, code: String?): ActionResult {
        val available = languages.languages().joinToString()
        if (code == null) {
            return ActionResult.ok(
                helixMessages.formatFor(
                    player,
                    "language.current",
                    "language" to languages.languageOf(player),
                    "languages" to available,
                ),
            )
        }
        return if (languages.setPlayerLanguage(player, code)) {
            eventSink("player", "info", "$player switched language to ${code.lowercase()}")
            ActionResult.ok(
                helixMessages.formatFor(player, "language.set", "language" to code.lowercase()),
            )
        } else {
            ActionResult.error(
                helixMessages.formatFor(player, "language.unknown", "language" to code, "languages" to available),
            )
        }
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
            proxyEvents.signal()
            ActionResult.ok("${command.type} queued on ${proxies.joinToString()}")
        }
    }

    private fun delaySeconds(invocation: ActionInvocation, index: Int): Long? {
        val raw = invocation.arguments.getOrNull(index) ?: return DEFAULT_RESTART_DELAY_SECONDS
        return raw.toLongOrNull()?.takeIf { it >= 0 }
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

    private companion object {
        /** Countdown length of `service.restart`/`task.restart` without an explicit delay. */
        const val DEFAULT_RESTART_DELAY_SECONDS = 60L
    }
}
