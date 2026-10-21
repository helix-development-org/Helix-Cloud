package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.bukkit.GameMode
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import org.helix.api.action.ActionInvocation
import org.helix.wire.ServiceNodeApi
import java.io.File
import java.util.UUID

/** Used when IGuard is missing: every run is refused. */
object MissingProbe : GuardProbe {
    /** Description for the report. */
    override val description: String = "IGuard fehlt"

    /** No levels. */
    override fun levels(playerId: UUID): Map<String, Double> = emptyMap()

    /** Default alert level. */
    override fun alertVl(check: String): Double = 5.0

    /** Unknown. */
    override val sanctionsMode: String = "unbekannt"
}

/**
 * GuardLab on Paper: the automated IGuard test lab.
 *
 * Active only on the task `IGuard-Test` (or with `GUARDLAB_FORCE=1`); everywhere else it stays dormant.
 * When a client registers `helix:guardlab` the plugin sends `hello` with the lab token (bridge value
 * `guardlab.settings` of the node addon, or `GUARDLAB_TOKEN`). The client verifies the token and answers
 * `ready`; the configured suite then starts automatically (or on `guardlab.run` / `/guardlab run`). The
 * report lands in `plugins/GuardLab/reports/<timestamp>-<suite>/` and its summary goes to the node.
 */
class GuardLabPlugin : JavaPlugin(), Listener, PluginMessageListener {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var catalog: SuiteCatalog
    private lateinit var lab: LabWorld
    private lateinit var runner: LabRunner
    private var node: ServiceNodeApi? = null
    private var toxiproxy: ToxiproxyClient? = null
    private var service = "local"
    private var active = false
    private val enabledAt = System.currentTimeMillis()

    @Volatile private var token = ""

    @Volatile private var suite = "all"

    @Volatile private var autostart = true

    private var dummyName = "GuardDummy"
    private val ready = HashMap<UUID, ClientInfo>()
    private val helloSent = HashSet<UUID>()
    private val seenNonces = HashSet<String>()
    private var pending: Pair<String, String>? = null

    /** Starts the lab on the test task; stays dormant elsewhere. */
    override fun onEnable() {
        val task = System.getenv("HELIX_TASK").orEmpty()
        if (task != TEST_TASK && System.getenv("GUARDLAB_FORCE") != "1") {
            logger.info("GuardLab ruht (Task '$task', aktiv nur auf $TEST_TASK)")
            return
        }
        active = true
        service = System.getenv("HELIX_SERVICE_ID")?.ifBlank { null } ?: "local"
        saveDefaultConfig()
        dummyName = config.getString("dummy-name", "GuardDummy") ?: "GuardDummy"
        System.getenv("GUARDLAB_TOKEN")?.takeIf { it.isNotBlank() }?.let { token = it }
        System.getenv("GUARDLAB_SUITE")?.takeIf { it.isNotBlank() }?.let { suite = it }

        catalog = runCatching { loadCatalog() }.getOrElse {
            logger.severe("GuardLab: Suite-Datei ungültig – ${it.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        logger.info("GuardLab: ${catalog.scenarios.size} Szenarien, Suites ${catalog.suites.keys}")

        lab = LabWorld(this).also { it.load() }
        val probe = IGuardApiProbe.create(server) ?: MissingProbe.also { logger.severe("GuardLab: IGuard ist nicht aktiv – Läufe werden abgelehnt") }
        toxiproxy = config.getString("toxiproxy.url").orEmpty().takeIf { it.isNotBlank() }?.let {
            ToxiproxyClient(it, config.getString("toxiproxy.proxy") ?: "minecraft")
        }
        toxiproxy?.let { client ->
            val listen = config.getString("toxiproxy.listen") ?: "0.0.0.0:25570"
            val upstream = config.getString("toxiproxy.upstream") ?: "helix-proxy-1:25565"
            async { client.ensureProxy(listen, upstream)?.let { logger.warning("GuardLab: $it") } }
        }
        runner = LabRunner(
            plugin = this,
            lab = lab,
            probe = probe,
            toxiproxy = toxiproxy,
            settings = RunnerSettings(
                cooldownTicks = config.getInt("cooldown-ticks", 60),
                maxCooldownTicks = config.getInt("max-cooldown-ticks", 400),
                quitAfterRun = config.getBoolean("quit-after-run", true),
            ),
            reportsDir = File(dataFolder, "reports"),
            service = service,
            dummy = { dummyPlayer() },
            onReport = { report, dir -> pushSummary(report, dir) },
        )

        server.messenger.registerOutgoingPluginChannel(this, Protocol.CHANNEL)
        server.messenger.registerIncomingPluginChannel(this, Protocol.CHANNEL, this)
        server.pluginManager.registerEvents(this, this)

        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val controlToken = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        if (controlUrl.isNotBlank() && controlToken.isNotBlank()) {
            node = ServiceNodeApi(
                controlUrl,
                System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl,
                service,
                controlToken,
            ).also { it.start() }
            server.scheduler.runTaskTimerAsynchronously(this, Runnable { pollNode() }, 20L, NODE_POLL_TICKS)
        } else {
            logger.warning("GuardLab: ohne Node-Verbindung – Token nur über GUARDLAB_TOKEN")
        }
        server.scheduler.runTaskTimer(this, Runnable { sweepHello() }, HELLO_SWEEP_TICKS, HELLO_SWEEP_TICKS)
    }

    /** Aborts a running suite and closes the node connection. */
    override fun onDisable() {
        if (!active) return
        if (::runner.isInitialized) runCatching { runner.abort("Server stoppt") }
        toxiproxy?.let { client -> runCatching { client.clear() } }
        node?.close()
    }

    // ---------------------------------------------------------------- client channel

    /** Parses a client message and dispatches it. */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != Protocol.CHANNEL || !active) return
        val parsed = Protocol.parseClient(message) ?: return
        if (parsed is ClientMessage.Ready) {
            onReady(player, parsed)
        } else {
            runner.onClientMessage(player, parsed)
        }
    }

    private fun onReady(player: Player, ready: ClientMessage.Ready) {
        val info = ClientInfo(player.uniqueId, player.name, ready.version, ready.brand)
        this.ready[player.uniqueId] = info
        if (isDummy(player)) {
            logger.info("GuardLab: Dummy-Client ${player.name} bereit (${ready.version})")
            return
        }
        logger.info("GuardLab: Client ${player.name} bereit (${ready.version}, ${ready.brand})")
        val request = pending
        when {
            request != null && (request.second.isBlank() || request.second.equals(player.name, ignoreCase = true)) -> {
                pending = null
                requestRun(request.first, info)
            }
            autostart && !runner.running -> server.scheduler.runTaskLater(this, Runnable {
                // Short delay so a dummy client started alongside can still connect.
                if (player.isOnline && !runner.running) requestRun(suite, info)
            }, config.getLong("autostart-delay-ticks", AUTOSTART_DELAY_TICKS))
        }
    }

    private fun sweepHello() {
        server.onlinePlayers.forEach { player ->
            if (player.uniqueId !in helloSent && Protocol.CHANNEL in player.listeningPluginChannels) sendHello(player)
        }
    }

    private fun sendHello(player: Player) {
        val current = token
        if (current.isBlank()) return
        val count = catalog.suite(suite)?.size ?: 0
        player.sendPluginMessage(this, Protocol.CHANNEL, Protocol.hello(current, suite, count))
        helloSent += player.uniqueId
    }

    /** Sends `hello` as soon as the client registers the GuardLab channel. */
    @EventHandler
    fun onRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (active && event.channel == Protocol.CHANNEL) server.scheduler.runTask(this, Runnable { sendHello(event.player) })
    }

    /** Moves joining players into the lab. */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!active || !lab.ready) return
        val player = event.player
        val spot = Stations.resolve(if (isDummy(player)) "flat.dummy" else "flat.wait") ?: return
        server.scheduler.runTask(this, Runnable {
            if (!player.isOnline) return@Runnable
            player.gameMode = GameMode.SURVIVAL
            player.teleport(lab.location(spot))
        })
    }

    /** Forgets a leaving client. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        if (!active) return
        ready.remove(event.player.uniqueId)
        helloSent.remove(event.player.uniqueId)
    }

    /** Counts IGuard setbacks. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (active) runner.onTeleport(event)
    }

    /** Notes deaths during a scenario. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        if (active) runner.onDeath(event.player)
    }

    /** Respawns inside the lab. */
    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        if (!active || !lab.ready) return
        runner.respawnLocation()?.let { event.respawnLocation = it }
    }

    // ---------------------------------------------------------------- runs

    private fun requestRun(suiteId: String, client: ClientInfo, sender: CommandSender? = null) {
        if (!lab.ready) {
            // First start: the stations are still being built – try again shortly.
            server.scheduler.runTaskLater(this, Runnable { if (server.getPlayer(client.playerId) != null) requestRun(suiteId, client, sender) }, 40L)
            return
        }
        val scenarios = catalog.suite(suiteId)
        if (scenarios == null) {
            val reason = "unbekannte Suite $suiteId (vorhanden: ${catalog.suites.keys.joinToString()})"
            sender?.sendMessage("GuardLab: $reason")
            runner.refuse(suiteId.takeIf { SUITE_ID.matches(it) } ?: "unknown", emptyList(), catalog.scenarios, client, reason)
            server.getPlayer(client.playerId)?.let { abortClient(it, reason) }
            return
        }
        val client0 = toxiproxy
        if (client0 == null) {
            startNow(suiteId, scenarios, client, "nicht konfiguriert", sender)
        } else {
            async {
                val status = client0.probe() ?: "ok"
                server.scheduler.runTask(this, Runnable { startNow(suiteId, scenarios, client, status, sender) })
            }
        }
    }

    private fun startNow(suiteId: String, scenarios: List<Scenario>, client: ClientInfo, toxiproxyStatus: String, sender: CommandSender?) {
        val error = runner.start(suiteId, scenarios, catalog.scenarios, client, toxiproxyStatus)
        if (error == null) {
            sender?.sendMessage("GuardLab: Suite $suiteId läuft (${scenarios.size} Szenarien) für ${client.name}")
            return
        }
        logger.warning("GuardLab: Lauf abgelehnt – $error")
        sender?.sendMessage("GuardLab: $error")
        if (error == "es läuft schon ein Lauf") return
        runner.refuse(suiteId, scenarios, catalog.scenarios, client, error)
        server.getPlayer(client.playerId)?.let { abortClient(it, error) }
    }

    private fun abortClient(player: Player, reason: String) {
        if (Protocol.CHANNEL in player.listeningPluginChannels) player.sendPluginMessage(this, Protocol.CHANNEL, Protocol.abort(reason))
    }

    private fun pushSummary(report: RunReport, dir: String) {
        val api = node ?: return
        val summary = buildJsonObject {
            put("suite", report.info.suite)
            put("player", report.info.player)
            put("startedAt", report.info.startedAt)
            put("finishedAt", report.info.finishedAt)
            put("passed", report.count(Status.PASS))
            put("warned", report.count(Status.WARN))
            put("failed", report.count(Status.FAIL))
            put("skipped", report.count(Status.SKIP))
            put("report", "plugins/GuardLab/reports/$dir/report.md")
            put("service", service)
        }
        async { api.action(ActionInvocation("guardlab.report.store", listOf(summary.toString()))) }
    }

    private fun pollNode() {
        val values = node?.bridgeValues() ?: return
        values[SETTINGS_KEY]?.let { raw ->
            val settings = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@let
            val nextToken = settings["token"]?.jsonPrimitive?.content.orEmpty()
            if (System.getenv("GUARDLAB_TOKEN").isNullOrBlank() && nextToken != token) {
                token = nextToken
                server.scheduler.runTask(this, Runnable { helloSent.clear() })
            }
            settings["suite"]?.jsonPrimitive?.content?.takeIf { SUITE_ID.matches(it) }?.let { suite = it }
            settings["autostart"]?.jsonPrimitive?.booleanOrNull?.let { autostart = it }
        }
        values[REQUEST_KEY]?.let { raw ->
            val request = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@let
            val nonce = request["nonce"]?.jsonPrimitive?.content ?: return@let
            val at = request["at"]?.jsonPrimitive?.longOrNull ?: 0L
            val suiteId = request["suite"]?.jsonPrimitive?.content ?: return@let
            val player = request["player"]?.jsonPrimitive?.content.orEmpty()
            server.scheduler.runTask(this, Runnable { onRequest(nonce, at, suiteId, player) })
        }
    }

    private fun onRequest(nonce: String, at: Long, suiteId: String, player: String) {
        if (!seenNonces.add(nonce) || at < enabledAt - REQUEST_MAX_AGE_MS) return
        val client = ready.values.firstOrNull { info ->
            server.getPlayer(info.playerId)?.let { !isDummy(it) } == true && (player.isBlank() || info.name.equals(player, ignoreCase = true))
        }
        if (client == null || runner.running) {
            pending = suiteId to player
            logger.info("GuardLab: Lauf $suiteId vorgemerkt – wartet auf einen bereiten Client")
            return
        }
        requestRun(suiteId, client)
    }

    // ---------------------------------------------------------------- command

    /** `/guardlab run|stop|status|suites|rebuild`. */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!active) {
            sender.sendMessage("GuardLab ruht auf diesem Server (nur $TEST_TASK)")
            return true
        }
        when (args.getOrNull(0)?.lowercase()) {
            "run" -> {
                val suiteId = args.getOrNull(1) ?: return usage(sender)
                val name = args.getOrNull(2) ?: (sender as? Player)?.name
                val client = ready.values.firstOrNull { info ->
                    server.getPlayer(info.playerId)?.let { !isDummy(it) } == true && (name == null || info.name.equals(name, ignoreCase = true))
                } ?: ready.values.firstOrNull { server.getPlayer(it.playerId)?.let { p -> !isDummy(p) } == true }
                if (client == null) {
                    pending = suiteId to (args.getOrNull(2) ?: "")
                    sender.sendMessage("GuardLab: kein bereiter Client – Lauf $suiteId vorgemerkt")
                } else {
                    requestRun(suiteId, client, sender)
                }
            }
            "stop" -> if (runner.running) runner.abort("gestoppt von ${sender.name}") else sender.sendMessage("GuardLab: kein Lauf aktiv")
            "status" -> {
                runner.status().forEach { sender.sendMessage("GuardLab: $it") }
                sender.sendMessage("GuardLab: Welt ${if (lab.ready) "bereit" else "wird gebaut"}, Token ${if (token.isBlank()) "fehlt" else "gesetzt"}, Suite $suite, Autostart $autostart")
                sender.sendMessage("GuardLab: Clients ${ready.values.joinToString { it.name }.ifBlank { "keine" }}, Dummy ${dummyPlayer()?.name ?: "keiner"}")
            }
            "suites" -> catalog.suites.forEach { (id, ids) -> sender.sendMessage("GuardLab: $id – ${ids.size} Szenarien") }
            "rebuild" -> if (runner.running) sender.sendMessage("GuardLab: erst den Lauf stoppen") else {
                lab.buildAll()
                sender.sendMessage("GuardLab: Testwelt wird neu gebaut")
            }
            else -> return usage(sender)
        }
        return true
    }

    private fun usage(sender: CommandSender): Boolean {
        sender.sendMessage("/guardlab run <suite> [spieler] | stop | status | suites | rebuild")
        return true
    }

    /** Tab completion for the sub commands and suite ids. */
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> = when (args.size) {
        1 -> listOf("run", "stop", "status", "suites", "rebuild").filter { it.startsWith(args[0].lowercase()) }
        2 -> if (args[0].equals("run", true) && active) catalog.suites.keys.filter { it.startsWith(args[1]) } else emptyList()
        else -> emptyList()
    }

    // ---------------------------------------------------------------- helpers

    private fun loadCatalog(): SuiteCatalog {
        val override = File(dataFolder, "suites.json")
        val text = if (override.isFile) {
            logger.info("GuardLab: nutze ${override.path} statt der eingebauten Suites")
            override.readText()
        } else {
            getResource("suites.json")?.bufferedReader()?.use { it.readText() } ?: error("suites.json fehlt im Plugin")
        }
        return SuiteParser.parse(text, Stations::exists)
    }

    private fun isDummy(player: Player) = player.name.equals(dummyName, ignoreCase = true)

    private fun dummyPlayer(): Player? = ready.keys.mapNotNull { server.getPlayer(it) }.firstOrNull { isDummy(it) }

    private fun async(block: () -> Unit) {
        server.scheduler.runTaskAsynchronously(this, Runnable { runCatching(block).onFailure { logger.warning("GuardLab: ${it.message}") } })
    }

    private companion object {
        const val TEST_TASK = "IGuard-Test"
        const val SETTINGS_KEY = "guardlab.settings"
        const val REQUEST_KEY = "guardlab.request"
        const val NODE_POLL_TICKS = 100L
        const val HELLO_SWEEP_TICKS = 40L
        const val REQUEST_MAX_AGE_MS = 300_000L
        const val AUTOSTART_DELAY_TICKS = 100L
        val SUITE_ID = Regex("^[a-z0-9_-]{1,32}$")
    }
}
