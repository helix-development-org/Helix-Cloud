package org.helix.bridge.paper

import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.display.DisplayProfile
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
    private val displayProfiles = ConcurrentHashMap<String, DisplayProfile>()

    @Volatile
    private var bridgeValues: Map<String, String> = emptyMap()
    private var heartbeatTask: BukkitTask? = null
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
        logger.info("Helix bridge enabled for ${loaded.serviceId} → ${loaded.controlUrl}")
    }

    /**
     * Cancels the sync scheduler.
     */
    override fun onDisable() {
        heartbeatTask?.cancel()
        heartbeatTask = null
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
                        .replace("{message}", plainMessage),
                )
            },
        )
    }

    private fun pulse(settings: BridgeSettings, client: NodeHttpClient) {
        sendHeartbeat(settings, client)
        syncBridgeValues(client)
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

    private fun syncBridgeValues(client: NodeHttpClient) {
        runCatching {
            client.getJson("/api/v1/internal/bridge-values")?.let { body ->
                bridgeValues = json.decodeFromString<Map<String, String>>(body)
            }
        }.onFailure { logger.warning("Helix bridge value sync failed: ${it.message}") }
    }

    private fun applyTablist() {
        val header = bridgeValues["tablist.header"]
        val footer = bridgeValues["tablist.footer"]
        if (header == null && footer == null) {
            return
        }
        server.onlinePlayers.forEach { player ->
            player.sendPlayerListHeaderAndFooter(
                colored(placeholders(header ?: "")),
                colored(placeholders(footer ?: "")),
            )
        }
    }

    private fun placeholders(text: String): String = text
        .replace("{online}", server.onlinePlayers.size.toString())
        .replace("{max}", server.maxPlayers.toString())

    private fun refreshDisplay(client: NodeHttpClient, playerName: String) {
        runCatching {
            client.postJsonForBody(
                "/api/v1/internal/display",
                json.encodeToString(JoinRequest(name = playerName)),
            )?.let { body ->
                displayProfiles[playerName.lowercase()] = json.decodeFromString<DisplayProfile>(body)
            }
        }.onFailure { logger.warning("Helix display fetch failed: ${it.message}") }
    }

    private fun colored(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)

    private companion object {
        /** Ticks before the first sync (1 second). */
        const val INITIAL_DELAY_TICKS = 20L

        /** Ticks between syncs (5 seconds). */
        const val PERIOD_TICKS = 100L

        /** Sync cycles between full display profile refreshes (30 s). */
        const val DISPLAY_REFRESH_CYCLES = 6
    }
}
