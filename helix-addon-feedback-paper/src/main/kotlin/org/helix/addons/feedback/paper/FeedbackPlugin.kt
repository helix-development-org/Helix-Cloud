package org.helix.addons.feedback.paper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations
import org.helix.api.message.LegacyToMini
import java.io.File
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * Paper component of `helix-addon-feedback` (plan 1 of the closed test phase): `/bug` (report form with automatic
 * context), `/bugs` (own reports, detail, comments, `/bugs top`), the tester agreement on first join, automatic
 * error reports from unhandled exceptions and the `/testtools` of the test phase. The node owns all data; this
 * plugin talks to it through [FeedbackNodeClient] (contract: `helix-addon-feedback/ACTIONS.md`).
 *
 * Without a node connection (`HELIX_CONTROL_URL`/`HELIX_CONTROL_TOKEN`) the plugin disables itself.
 */
class FeedbackPlugin : JavaPlugin(), Listener {
    /** This server's service id (`HELIX_SERVICE_ID`). */
    var service: String = "local"
        private set

    /** This server's task (`HELIX_TASK`). */
    var task: String = "local"
        private set

    /** Shared JSON instance. */
    val json: Json = Json { ignoreUnknownKeys = true }

    /** The test tools settings of this server (config.yml). */
    @Volatile var toolsConfig: TestToolsConfig = TestToolsConfig()
        private set

    /** The node's `testphase` setting (bridge value `feedback.settings`; true until known otherwise). */
    @Volatile var testphase: Boolean = true
        private set

    private var client: FeedbackNodeClient? = null
    private var translations: NodeTranslations? = null
    private var menus: FeedbackMenus? = null
    private var gate: AgreementGate? = null
    private var errors: ExceptionCapture? = null
    private lateinit var capture: ContextCapture
    private lateinit var scope: CoroutineScope
    private val mini = MiniMessage.miniMessage()
    private val fallback: Map<String, String> by lazy { loadFallback() }

    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (isEnabled) server.scheduler.runTask(this@FeedbackPlugin, block)
        }
    }

    /** Connects to the node and installs commands, menus, the agreement gate and the error capture. */
    override fun onEnable() {
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val controlToken = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        if (controlUrl.isBlank() || controlToken.isBlank()) {
            logger.severe("HelixFeedback requires HELIX_CONTROL_URL and HELIX_CONTROL_TOKEN")
            server.pluginManager.disablePlugin(this)
            return
        }
        service = System.getenv("HELIX_SERVICE_ID")?.ifBlank { null } ?: "local"
        task = System.getenv("HELIX_TASK")?.ifBlank { null } ?: "local"
        saveDefaultConfig()
        reloadTools()
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        capture = ContextCapture(service, task)

        val client = FeedbackNodeClient(controlUrl, controlToken)
        this.client = client
        val translations = NodeTranslations(controlUrl, controlToken, OWNER)
        this.translations = translations
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { translations.sync() }, 1L, TRANSLATION_SYNC_TICKS)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { pollSettings() }, 20L, SETTINGS_SYNC_TICKS)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { pollNotifications() }, NOTIFICATION_TICKS, NOTIFICATION_TICKS)

        menus = FeedbackMenus(this, client, capture, scope).also { it.install() }
        gate = AgreementGate(this, client) { player, key -> text(player, key) }.also { server.pluginManager.registerEvents(it, this) }
        errors = ExceptionCapture(
            send = { hash, title, description, context ->
                server.scheduler.runTaskAsynchronously(this, Runnable { client.auto(hash, title, description, service, context, null) })
            },
            context = { if (Bukkit.isPrimaryThread()) ContextBuilder.toJson(capture.captureServer()) else null },
        ).also {
            it.install()
            server.pluginManager.registerEvents(it, this)
        }
        server.pluginManager.registerEvents(this, this)
        logger.info("HelixFeedback enabled for $service ($task)")
    }

    /** Removes hooks and closes the node connection. */
    override fun onDisable() {
        errors?.uninstall()
        menus?.shutdown()
        if (::scope.isInitialized) scope.cancel()
        client?.close()
        translations?.close()
        client = null
        translations = null
    }

    /** Checks the agreement of a joining tester. */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        gate?.check(event.player)
    }

    /** Handles `/bug`, `/bugs`, `/testtools` and `/testervereinbarung`. */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Only players can use this command.")
            return true
        }
        when (command.name.lowercase()) {
            "bug" -> if (!testphase && !player.hasPermission(PERMISSION_STAFF)) player.sendMessage(chat(player, "error.testphase-closed")) else menus?.openForm(player)
            "bugs" -> if (args.firstOrNull()?.lowercase() == "top") showTop(player) else menus?.openMine(player)
            "testtools" -> testTools(player, args)
            AgreementGate.COMMAND -> gate?.choose(player, args.firstOrNull())
        }
        return true
    }

    /** Tab completion of the sub-commands. */
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        when (command.name.lowercase()) {
            "bugs" -> if (args.size == 1) listOf("top").filter { it.startsWith(args[0].lowercase()) } else emptyList()
            "testtools" -> when {
                args.size == 1 && sender.hasPermission(PERMISSION_ADMIN) -> listOf("punkt").filter { it.startsWith(args[0].lowercase()) }
                args.size == 2 && sender.hasPermission(PERMISSION_ADMIN) -> listOf("setzen", "loeschen").filter { it.startsWith(args[1].lowercase()) }
                args.size == 3 -> toolsConfig.points.keys.filter { it.startsWith(args[2]) }
                else -> emptyList()
            }
            AgreementGate.COMMAND -> listOf("annehmen", "ablehnen").filter { it.startsWith(args.lastOrNull().orEmpty()) }
            else -> emptyList()
        }

    private fun testTools(player: Player, args: Array<out String>) {
        if (args.size >= 3 && args[0].equals("punkt", true) && player.hasPermission(PERMISSION_ADMIN)) {
            val name = args[2]
            if (!TestToolRules.validPointName(name)) {
                player.sendMessage(chat(player, "tools.point.invalid"))
                return
            }
            when (args[1].lowercase()) {
                "setzen", "set" -> {
                    val l = player.location
                    config.set("testtools.points.$name", mapOf("world" to l.world.name, "x" to l.x, "y" to l.y, "z" to l.z, "yaw" to l.yaw.toDouble(), "pitch" to l.pitch.toDouble()))
                    saveConfig()
                    reloadTools()
                    player.sendMessage(chat(player, "tools.point.saved", "name" to name))
                }
                "loeschen", "löschen", "delete" -> {
                    config.set("testtools.points.$name", null)
                    saveConfig()
                    reloadTools()
                    player.sendMessage(chat(player, "tools.point.deleted", "name" to name))
                }
            }
            return
        }
        if (!TestToolRules.available(testphase, player.hasPermission(PERMISSION_TOOLS))) {
            player.sendMessage(chat(player, "tools.closed"))
            return
        }
        menus?.openTools(player)
    }

    private fun showTop(player: Player) {
        val client = client ?: return
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val top = client.top(TOP_LINES)
            server.scheduler.runTask(this, Runnable {
                if (top == null) {
                    player.sendMessage(chat(player, "error.unavailable"))
                    return@Runnable
                }
                player.sendMessage(chat(player, "top.heading"))
                if (top.isEmpty()) player.sendMessage(render(text(player, "top.empty")))
                top.forEach { entry ->
                    player.sendMessage(render(text(player, "top.line", "rank" to entry.rank.toString(), "name" to entry.name, "points" to entry.points.toString())))
                }
            })
        })
    }

    /** Polls unread status notifications of online players and announces them in chat. */
    private fun pollNotifications() {
        val client = client ?: return
        server.onlinePlayers.toList().forEach { player ->
            val unread = client.notifications(player.uniqueId) ?: return
            if (unread.isEmpty()) return@forEach
            server.scheduler.runTask(this, Runnable {
                if (!player.isOnline) return@Runnable
                unread.forEach { note ->
                    player.sendMessage(
                        chat(
                            player,
                            "notify.status",
                            "nr" to note.nr.toString(),
                            "title" to note.title,
                            "status" to text(player, ReportStatus.of(note.status).langKey),
                        ),
                    )
                }
                server.scheduler.runTaskAsynchronously(this, Runnable { client.ack(player.uniqueId, unread.map { it.id }) })
            })
        }
    }

    /** Reads the node's `feedback.settings` bridge value (if the node publishes it) for the `testphase` flag. */
    private fun pollSettings() {
        val values = client?.bridgeValues() ?: return
        val raw = values[SETTINGS_BRIDGE_KEY] ?: return
        runCatching { json.parseToJsonElement(raw).jsonObject["testphase"]?.jsonPrimitive?.booleanOrNull }
            .getOrNull()?.let { testphase = it }
    }

    private fun reloadTools() {
        reloadConfig()
        val section = config.getConfigurationSection("testtools")
        val points = section?.getConfigurationSection("points")?.getKeys(false)?.mapNotNull { name ->
            val p = section.getConfigurationSection("points.$name") ?: return@mapNotNull null
            name to TestPoint(p.getString("world") ?: return@mapNotNull null, p.getDouble("x"), p.getDouble("y"), p.getDouble("z"), p.getDouble("yaw").toFloat(), p.getDouble("pitch").toFloat())
        }?.toMap().orEmpty()
        toolsConfig = TestToolsConfig(
            flyWorlds = section?.getStringList("fly-worlds")?.toSet()?.ifEmpty { null } ?: TestToolsConfig().flyWorlds,
            kronenAmount = section?.getLong("kronen-amount", 1000) ?: 1000,
            kronenDailyLimit = section?.getLong("kronen-daily-limit", 10_000) ?: 10_000,
            points = points,
        )
    }

    /**
     * Writes one test tool use to the server log and `plugins/HelixFeedback/testtools.log` (abuse review and context
     * for reports) and records it on the node via `feedback.toollog`.
     *
     * @param player the tester.
     * @param tool the tool id.
     * @param value what was set.
     */
    fun logTool(player: Player, tool: String, value: String) {
        val line = "${Instant.now()} ${player.name} ${player.uniqueId} $service ${player.world.name} $tool=$value"
        logger.info("[testtools] $line")
        server.scheduler.runTaskAsynchronously(this, Runnable {
            runCatching { File(dataFolder, "testtools.log").appendText(line + "\n") }
            val entry = kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(player.name))
                put("action", kotlinx.serialization.json.JsonPrimitive(tool))
                put("details", kotlinx.serialization.json.JsonPrimitive(value))
                put("service", kotlinx.serialization.json.JsonPrimitive(service))
            }
            runCatching { client?.toolLog(player.uniqueId, entry) }
        })
    }

    /**
     * Hook for the screenshot transfer (plan 1 wave T2, LabyMod addon): the report [nr] asked for a screenshot.
     * Until the transfer exists this only notes it in the log.
     *
     * @param player the reporter.
     * @param nr the created report number, null if unknown.
     */
    fun screenshotRequested(player: Player, nr: Long?) {
        logger.info("Screenshot requested for report #${nr ?: "?"} by ${player.name} (transfer not available yet)")
    }

    /**
     * A chat message (network prefix included) in the player's language, rendered.
     *
     * @param player the receiver.
     * @param key translation key.
     * @param params placeholders.
     * @return the component.
     */
    fun chat(player: Player, key: String, vararg params: Pair<String, String>): Component {
        val resolved = translations?.text(player.name, player.locale().language, key, *params)
        val raw = if (resolved == null || resolved.endsWith(key)) "<#ffcf4a>KronlandMC</#ffcf4a> <#7a4a24>»</#7a4a24> " + local(key, params) else resolved
        return render(raw)
    }

    /**
     * Prefix-free screen text in the player's language (GUI rows, book pages).
     *
     * @param player the viewer.
     * @param key translation key.
     * @param params placeholders.
     * @return the text.
     */
    fun screen(player: Player, key: String, vararg params: Pair<String, String>): String {
        val resolved = translations?.screen(player.name, player.locale().language, key, *params)
        return if (resolved == null || resolved == key || resolved.endsWith(".$key")) local(key, params) else resolved
    }

    private fun text(player: Player, key: String, vararg params: Pair<String, String>): String = screen(player, key, *params)

    private fun render(raw: String): Component = mini.deserialize(LegacyToMini.translate(raw)).decoration(TextDecoration.ITALIC, false)

    /** Bundled German fallback while the node translations are not synced yet. */
    private fun local(key: String, params: Array<out Pair<String, String>>): String {
        var template = fallback[key] ?: key
        params.forEach { (name, value) -> template = template.replace("{$name}", value) }
        return template
    }

    private fun loadFallback(): Map<String, String> = runCatching {
        val raw = getResource("lang/de-DE.json")?.bufferedReader()?.use { it.readText() } ?: return emptyMap()
        json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())

    /** Names, permissions and timings. */
    companion object {
        /** Owner of the node translations. */
        const val OWNER = "feedback"

        /** May open `/testtools`. */
        const val PERMISSION_TOOLS = "tester.tools"

        /** Staff of the feedback system. */
        const val PERMISSION_STAFF = "feedback.staff"

        /** May manage test points. */
        const val PERMISSION_ADMIN = "feedback.admin"

        /** Bridge value the node may publish with its settings (`{"testphase":true,…}`). */
        const val SETTINGS_BRIDGE_KEY = "feedback.settings"

        private const val TRANSLATION_SYNC_TICKS = 100L
        private const val SETTINGS_SYNC_TICKS = 300L
        private const val NOTIFICATION_TICKS = 600L
        private const val TOP_LINES = 10

    }
}
