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
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
class FeedbackPlugin : JavaPlugin(), Listener, PluginMessageListener {
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

    /** The node's `maxAttachmentBytes` setting (bridge value `feedback.settings`). */
    @Volatile var maxAttachmentBytes: Int = ScreenshotUploads.DEFAULT_MAX_BYTES
        private set

    private val uploads = ScreenshotUploads()

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
        server.messenger.registerOutgoingPluginChannel(this, SCREENSHOT_CHANNEL)
        server.messenger.registerIncomingPluginChannel(this, SCREENSHOT_CHANNEL, this)
        server.scheduler.runTaskTimer(this, Runnable { expireUploads() }, 20L, 20L)
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
        val player = event.player
        server.scheduler.runTaskLaterAsynchronously(this, Runnable { payReleaseRewards(player) }, REWARD_DELAY_TICKS)
    }

    /**
     * Pays the network part of release rewards (crowns, unlocks) on join and points to CityBuild for item parts;
     * the CityBuild plugin delivers those itself. Runs asynchronously.
     */
    private fun payReleaseRewards(player: Player) {
        val client = client ?: return
        if (!player.isOnline) return
        val pending = client.pendingRewards(player.uniqueId) ?: return
        var citybuildWaiting = false
        pending.forEach { reward ->
            val body = reward["reward"] as? kotlinx.serialization.json.JsonObject ?: return@forEach
            val id = (reward["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
            if ((body["release"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "true") return@forEach
            when ((body["part"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                "network" -> {
                    val reply = client.redeemReward(player.uniqueId, id, player.name)
                    val kronen = (body["kronen"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "0"
                    val unlocks = (body["permissions"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true
                    server.scheduler.runTask(this, Runnable {
                        if (!player.isOnline) return@Runnable
                        if (reply.ok) {
                            val extra = if (unlocks) text(player, "release.reward.extra") else ""
                            player.sendMessage(chat(player, "release.reward.network", "kronen" to kronen, "extra" to extra))
                        } else if (reply.code != "claimed") {
                            player.sendMessage(chat(player, "release.reward.failed"))
                        }
                    })
                }
                "citybuild" -> citybuildWaiting = true
            }
        }
        if (citybuildWaiting && !task.equals(CITYBUILD_TASK, ignoreCase = true)) {
            server.scheduler.runTask(this, Runnable { if (player.isOnline) player.sendMessage(chat(player, "release.reward.citybuild")) })
        }
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
        val settings = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        settings["testphase"]?.jsonPrimitive?.booleanOrNull?.let { testphase = it }
        settings["maxAttachmentBytes"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { maxAttachmentBytes = it }
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
     * Asks the reporter's LabyMod addon for a screenshot of report [nr] (channel `helix:feedback`): the client hides
     * its GUI, captures one frame, and streams the JPEG back in chunks; [onPluginMessageReceived] assembles it and
     * uploads it to the node. Without the channel or a report number the player is told that no screenshot was taken.
     *
     * @param player the reporter.
     * @param nr the created report number, null if unknown.
     */
    fun screenshotRequested(player: Player, nr: Long?) {
        if (!Bukkit.isPrimaryThread()) {
            server.scheduler.runTask(this, Runnable { screenshotRequested(player, nr) })
            return
        }
        if (nr == null || !capture.canScreenshot(player)) {
            player.sendMessage(chat(player, "screenshot.failed"))
            return
        }
        val upload = uploads.start(player.uniqueId, nr, maxAttachmentBytes, System.currentTimeMillis())
        val request = buildJsonObject {
            put("type", "capture")
            put("id", upload.id)
            put("maxWidth", SCREENSHOT_MAX_WIDTH)
            put("quality", SCREENSHOT_QUALITY)
            put("maxBytes", upload.maxBytes)
        }
        player.sendPluginMessage(this, SCREENSHOT_CHANNEL, request.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Receives screenshot chunks, `done` and `error` messages from the LabyMod addon.
     *
     * @param channel the channel (`helix:feedback`).
     * @param player the sender.
     * @param message UTF-8 JSON.
     */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != SCREENSHOT_CHANNEL) return
        val body = runCatching { json.parseToJsonElement(message.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return
        val id = body["id"]?.jsonPrimitive?.content ?: return
        when (body["type"]?.jsonPrimitive?.content) {
            "chunk" -> {
                val outcome = uploads.chunk(
                    id, player.uniqueId,
                    body["i"]?.jsonPrimitive?.intOrNull ?: -1,
                    body["n"]?.jsonPrimitive?.intOrNull ?: 0,
                    body["data"]?.jsonPrimitive?.content.orEmpty(),
                )
                if (outcome is ScreenshotUploads.Outcome.Failed && outcome.upload != null) screenshotFailed(player, outcome.reason)
            }
            "done" -> when (val outcome = uploads.finish(id, player.uniqueId)) {
                is ScreenshotUploads.Outcome.Complete -> uploadScreenshot(player, outcome)
                is ScreenshotUploads.Outcome.Failed -> if (outcome.upload != null) screenshotFailed(player, outcome.reason)
                else -> Unit
            }
            "error" -> uploads.cancel(id, player.uniqueId)?.let { screenshotFailed(player, body["reason"]?.jsonPrimitive?.content ?: "failed") }
        }
    }

    private fun uploadScreenshot(player: Player, outcome: ScreenshotUploads.Outcome.Complete) {
        val viewer = PlayerRef(player.uniqueId.toString(), player.name)
        val name = "screenshot-${outcome.upload.nr}.jpg"
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val reply = client?.attach(outcome.upload.nr, viewer, name, "image/jpeg", outcome.bytes)
            server.scheduler.runTask(this, Runnable {
                if (!player.isOnline) return@Runnable
                if (reply?.ok == true) {
                    player.sendMessage(chat(player, "screenshot.attached", "nr" to outcome.upload.nr.toString()))
                } else {
                    screenshotFailed(player, reply?.code ?: "unavailable")
                }
            })
        })
    }

    private fun screenshotFailed(player: Player, reason: String) {
        logger.info("Screenshot of ${player.name} failed: $reason")
        if (player.isOnline) player.sendMessage(chat(player, if (reason == "disabled") "screenshot.disabled" else "screenshot.failed"))
    }

    private fun expireUploads() {
        uploads.expire(System.currentTimeMillis()).forEach { upload ->
            server.getPlayer(upload.player)?.let { screenshotFailed(it, "timeout") }
        }
    }

    /**
     * Forgets open screenshot uploads of a leaving player.
     *
     * @param event the quit.
     */
    @EventHandler
    fun onQuitUploads(event: PlayerQuitEvent) {
        uploads.forget(event.player.uniqueId)
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

        /** Delay before release rewards are paid after a join (let the client settle). */
        const val REWARD_DELAY_TICKS = 60L

        /** Task whose plugin delivers the item part of release rewards. */
        const val CITYBUILD_TASK = "CityBuild"

        /** May open `/testtools`. */
        const val PERMISSION_TOOLS = "tester.tools"

        /** Staff of the feedback system. */
        const val PERMISSION_STAFF = "feedback.staff"

        /** May manage test points. */
        const val PERMISSION_ADMIN = "feedback.admin"

        /** Plugin channel of the screenshot transfer with the Helix LabyMod addon. */
        const val SCREENSHOT_CHANNEL = "helix:feedback"

        /** Largest screenshot width the client sends. */
        const val SCREENSHOT_MAX_WIDTH = 1280

        /** JPEG start quality the client uses. */
        const val SCREENSHOT_QUALITY = 0.8

        /** Bridge value the node may publish with its settings (`{"testphase":true,…}`). */
        const val SETTINGS_BRIDGE_KEY = "feedback.settings"

        private const val TRANSLATION_SYNC_TICKS = 100L
        private const val SETTINGS_SYNC_TICKS = 300L
        private const val NOTIFICATION_TICKS = 600L
        private const val TOP_LINES = 10

    }
}
