package org.helix.bridge.paper

import kotlinx.serialization.json.Json
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.helix.api.bridge.HeartbeatReport

/**
 * Paper-side bridge between a backend server and the Helix-Cloud node.
 *
 * Reads its connection settings from the wrapper environment and reports a
 * heartbeat (players, slots, TPS) every few seconds. The first heartbeat
 * moves the service to `RUNNING` on the node.
 */
class HelixPaperBridgePlugin : JavaPlugin() {
    private var heartbeatTask: BukkitTask? = null

    /**
     * Starts the heartbeat scheduler when running under a Helix wrapper.
     */
    override fun onEnable() {
        val settings = BridgeSettings.fromEnvironment()
        if (settings == null) {
            logger.warning("No Helix environment found — bridge disabled.")
            return
        }
        val client = NodeHttpClient(settings)
        heartbeatTask = server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable { sendHeartbeat(settings, client) },
            INITIAL_DELAY_TICKS,
            PERIOD_TICKS,
        )
        logger.info("Helix bridge enabled for ${settings.serviceId} → ${settings.controlUrl}")
    }

    /**
     * Cancels the heartbeat scheduler.
     */
    override fun onDisable() {
        heartbeatTask?.cancel()
        heartbeatTask = null
    }

    private fun sendHeartbeat(settings: BridgeSettings, client: NodeHttpClient) {
        val report = HeartbeatReport(
            serviceId = settings.serviceId,
            onlinePlayers = server.onlinePlayers.size,
            maxPlayers = server.maxPlayers,
            tps = server.tps.firstOrNull(),
        )
        runCatching { client.postJson("/api/v1/internal/heartbeat", Json.encodeToString(report)) }
            .onFailure { logger.warning("Helix heartbeat failed: ${it.message}") }
    }

    private companion object {
        /** Ticks before the first heartbeat (1 second). */
        const val INITIAL_DELAY_TICKS = 20L

        /** Ticks between heartbeats (5 seconds). */
        const val PERIOD_TICKS = 100L
    }
}
