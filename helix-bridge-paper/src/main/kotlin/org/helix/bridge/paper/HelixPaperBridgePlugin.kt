package org.helix.bridge.paper

import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.display.DisplayProfile
import org.helix.api.message.LegacyToMini
import org.helix.api.proxy.JoinRequest

/**
 * Paper-side bridge between a backend server and the Helix-Cloud node.
 *
 * Reports heartbeats (players, slots, TPS), polls addon-published bridge
 * values (tab list header/footer, chat format) and renders chat with
 * addon-resolved display profiles. The first heartbeat moves the service
 * to `RUNNING` on the node.
 */
class HelixPaperBridgePlugin : JavaPlugin(), Listener {
    private val json = Json { ignoreUnknownKeys = true }
    private val miniMessage = MiniMessage.miniMessage()
    private val displayProfiles = ConcurrentHashMap<String, DisplayProfile>()

    @Volatile
    private var bridgeValues: Map<String, String> = emptyMap()

    @Volatile
    private var tablist: TablistData? = null

    @Volatile
    private var lastFrameIndex: Int = -1
    private var heartbeatTask: BukkitTask? = null
    private var animationTask: BukkitTask? = null
    private var client: NodeHttpClient? = null
    private var settings: BridgeSettings? = null
    private var pollCounter = 0

    /**
     * Starts the sync scheduler when running under a Helix wrapper.
     */
    override fun onEnable() {
        val loaded = BridgeSettings.fromEnvironment()
        if (loaded == null) {
            logger.warning("No Helix environment found — bridge disabled.")
            return
        }
        settings = loaded
        val httpClient = NodeHttpClient(loaded)
        client = httpClient
        server.pluginManager.registerEvents(this, this)
        heartbeatTask = server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable { pulse(loaded, httpClient) },
            INITIAL_DELAY_TICKS,
            PERIOD_TICKS,
        )
        animationTask = server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable { animateTablist() },
            ANIMATION_PERIOD_TICKS,
            ANIMATION_PERIOD_TICKS,
        )
        logger.info("Helix bridge enabled for ${loaded.serviceId} → ${loaded.controlUrl}")
    }

    /**
     * Cancels the sync scheduler.
     */
    override fun onDisable() {
        heartbeatTask?.cancel()
        heartbeatTask = null
        animationTask?.cancel()
        animationTask = null
    }

    /**
     * Fetches the display profile of a joining player.
     *
     * @param event the join event.
     */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val httpClient = client ?: return
        server.scheduler.runTaskAsynchronously(
            this,
            Runnable { refreshDisplay(httpClient, event.player.name) },
        )
    }

    /**
     * Renders chat with the addon-published format and display profiles.
     *
     * Without a published `chat.format` bridge value the vanilla chat
     * stays untouched.
     *
     * @param event the chat event.
     */
    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val format = bridgeValues["chat.format"] ?: return
        val profile = displayProfiles[event.player.name.lowercase()] ?: DisplayProfile()
        event.renderer(
            ChatRenderer { source, _, message, _ ->
                val plainMessage = PlainTextComponentSerializer.plainText().serialize(message)
                colored(
                    format
                        .replace("{prefix}", profile.prefix)
                        .replace("{suffix}", profile.suffix)
                        .replace("{color}", profile.color)
                        .replace("{name}", source.name)
                        // players must not inject MiniMessage tags (e.g. click events)
                        .replace("{message}", miniMessage.escapeTags(plainMessage)),
                )
            },
        )
    }

    private fun pulse(settings: BridgeSettings, client: NodeHttpClient) {
        sendHeartbeat(settings, client)
        syncBridgeValues(settings, client)
        applyTablist()
        if (pollCounter++ % DISPLAY_REFRESH_CYCLES == 0) {
            server.onlinePlayers.forEach { player -> refreshDisplay(client, player.name) }
        }
    }

    private fun sendHeartbeat(settings: BridgeSettings, client: NodeHttpClient) {
        val report = HeartbeatReport(
            serviceId = settings.serviceId,
            onlinePlayers = server.onlinePlayers.size,
            maxPlayers = server.maxPlayers,
            tps = server.tps.firstOrNull(),
        )
        runCatching { client.postJson("/api/v1/internal/heartbeat", json.encodeToString(report)) }
            .onFailure { logger.warning("Helix heartbeat failed: ${it.message}") }
    }

    private fun syncBridgeValues(settings: BridgeSettings, client: NodeHttpClient) {
        runCatching {
            client.getJson("/api/v1/internal/bridge-values?serviceId=${settings.serviceId}")?.let { body ->
                bridgeValues = json.decodeFromString<Map<String, String>>(body)
                tablist = bridgeValues["tablist.config"]?.let { raw ->
                    runCatching { json.decodeFromString<TablistData>(raw) }.getOrNull()
                }
            }
        }.onFailure { logger.warning("Helix bridge value sync failed: ${it.message}") }
    }

    private fun applyTablist() {
        val config = tablist
        val header: String
        val footer: String
        if (config != null) {
            val index = config.frameIndexAt(System.currentTimeMillis())
            lastFrameIndex = index
            header = config.headerAt(index)
            footer = config.footerAt(index)
        } else {
            // Fallback for older tablist addons publishing only the two keys.
            header = bridgeValues["tablist.header"] ?: ""
            footer = bridgeValues["tablist.footer"] ?: ""
            if (header.isEmpty() && footer.isEmpty()) {
                return
            }
        }
        server.onlinePlayers.forEach { player ->
            player.sendPlayerListHeaderAndFooter(
                colored(placeholders(header)),
                colored(placeholders(footer)),
            )
        }
    }

    /**
     * Advances the tab list animation: re-applies header/footer whenever the
     * time-based frame index changed. Static tab lists (one frame) are left
     * to the regular pulse.
     */
    private fun animateTablist() {
        val config = tablist ?: return
        if (config.frameCount() <= 1) {
            return
        }
        if (config.frameIndexAt(System.currentTimeMillis()) != lastFrameIndex) {
            applyTablist()
        }
    }

    private fun placeholders(text: String): String = text
        .replace("{online}", server.onlinePlayers.size.toString())
        .replace("{max}", server.maxPlayers.toString())
        .replace("{prefix}", bridgeValues["network.prefix"] ?: "")
        .replace("{network}", bridgeValues["network.name"] ?: "")

    private fun refreshDisplay(client: NodeHttpClient, playerName: String) {
        runCatching {
            client.postJsonForBody(
                "/api/v1/internal/display",
                json.encodeToString(JoinRequest(name = playerName)),
            )?.let { body ->
                val profile = json.decodeFromString<DisplayProfile>(body)
                displayProfiles[playerName.lowercase()] = profile
                server.getPlayerExact(playerName)?.let { player ->
                    server.scheduler.runTask(this, Runnable { applyDisplay(player, profile) })
                }
            }
        }.onFailure { logger.warning("Helix display fetch failed: ${it.message}") }
    }

    /**
     * Applies a display profile to the player's tab-list entry and the name
     * shown above their head (via a per-player scoreboard team). Must run on
     * the main server thread.
     *
     * @param player the online player.
     * @param profile the resolved prefix/suffix/color.
     */
    private fun applyDisplay(player: Player, profile: DisplayProfile) {
        val scoreboard = server.scoreboardManager?.mainScoreboard ?: return
        val teamName = "hlx" + Integer.toHexString(player.name.lowercase().hashCode())
        val hasContent = profile.prefix.isNotEmpty() || profile.suffix.isNotEmpty() || profile.color.isNotEmpty()
        if (!hasContent) {
            scoreboard.getTeam(teamName)?.takeIf { it.hasEntry(player.name) }?.removeEntry(player.name)
            player.playerListName(null)
            return
        }
        val team = scoreboard.getTeam(teamName) ?: scoreboard.registerNewTeam(teamName)
        team.prefix(colored(profile.prefix))
        team.suffix(colored(profile.suffix))
        namedColor(profile.color)?.let { team.color(it) }
        if (!team.hasEntry(player.name)) {
            team.addEntry(player.name)
        }
        player.playerListName(colored("${profile.prefix}${profile.color}${player.name}${profile.suffix}"))
    }

    /**
     * Maps a legacy `&`-color code to a named colour for scoreboard teams.
     *
     * @param code a color string such as `&c`, or empty.
     * @return the matching colour, or `null` when none applies.
     */
    private fun namedColor(code: String): NamedTextColor? {
        val ch = code.trim().removePrefix("&").removePrefix("§").firstOrNull()?.lowercaseChar() ?: return null
        return when (ch) {
            '0' -> NamedTextColor.BLACK
            '1' -> NamedTextColor.DARK_BLUE
            '2' -> NamedTextColor.DARK_GREEN
            '3' -> NamedTextColor.DARK_AQUA
            '4' -> NamedTextColor.DARK_RED
            '5' -> NamedTextColor.DARK_PURPLE
            '6' -> NamedTextColor.GOLD
            '7' -> NamedTextColor.GRAY
            '8' -> NamedTextColor.DARK_GRAY
            '9' -> NamedTextColor.BLUE
            'a' -> NamedTextColor.GREEN
            'b' -> NamedTextColor.AQUA
            'c' -> NamedTextColor.RED
            'd' -> NamedTextColor.LIGHT_PURPLE
            'e' -> NamedTextColor.YELLOW
            'f' -> NamedTextColor.WHITE
            else -> null
        }
    }

    private fun colored(text: String): Component =
        miniMessage.deserialize(LegacyToMini.translate(text))

    private companion object {
        /** Ticks before the first sync (1 second). */
        const val INITIAL_DELAY_TICKS = 20L

        /** Ticks between syncs (5 seconds). */
        const val PERIOD_TICKS = 100L

        /** Sync cycles between full display profile refreshes (30 s). */
        const val DISPLAY_REFRESH_CYCLES = 6

        /** Ticks between tab list animation checks (250 ms). */
        const val ANIMATION_PERIOD_TICKS = 5L
    }
}
