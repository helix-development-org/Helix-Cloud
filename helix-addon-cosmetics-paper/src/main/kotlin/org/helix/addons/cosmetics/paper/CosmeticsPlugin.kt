package org.helix.addons.cosmetics.paper

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * Renders a player's equipped cosmetics (published by the cosmetics addon
 * as `cosmetic.wings.<player>`/`cosmetic.headwear.<player>` bridge values,
 * each a `CustomModelData` value) as item display entities attached to
 * the player.
 *
 * Two independent tasks: a slow one refetches which players currently
 * have a cosmetic equipped at all (a network round trip), a fast one
 * re-teleports and re-rotates every already-known display every tick so
 * it tracks a moving, turning player without waiting on the network.
 */
class CosmeticsPlugin : JavaPlugin(), Listener {
    private val displays = CosmeticDisplayService()
    private var fetchTask: BukkitTask? = null
    private var trackTask: BukkitTask? = null
    private var client: BridgeValuesClient? = null

    /** Reads the node connection from the environment and starts both tasks. */
    override fun onEnable() {
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val controlToken = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        val serviceId = System.getenv("HELIX_SERVICE_ID").orEmpty()
        if (controlUrl.isBlank() || controlToken.isBlank() || serviceId.isBlank()) {
            logger.severe("HelixCosmetics requires HELIX_CONTROL_URL, HELIX_CONTROL_TOKEN and HELIX_SERVICE_ID")
            server.pluginManager.disablePlugin(this)
            return
        }
        val client = BridgeValuesClient(controlUrl, controlToken, serviceId)
        this.client = client
        server.pluginManager.registerEvents(this, this)

        fetchTask = server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable { applyFetch(client) },
            0L,
            FETCH_PERIOD_TICKS,
        )
        trackTask = server.scheduler.runTaskTimer(this, Runnable { displays.track(server.onlinePlayers) }, 1L, 1L)
        logger.info("HelixCosmetics enabled (node: $controlUrl)")
    }

    /** Stops both tasks and removes every display entity. */
    override fun onDisable() {
        fetchTask?.cancel()
        trackTask?.cancel()
        displays.shutdown()
        client?.close()
        client = null
    }

    /** Drops the quitting player's cosmetic displays; nothing else references them after this. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        displays.remove(event.player.uniqueId)
    }

    private fun applyFetch(client: BridgeValuesClient) {
        val bridgeValues = client.fetch() ?: return
        val wings = CosmeticValues.wings(bridgeValues)
        val headwear = CosmeticValues.headwear(bridgeValues)
        server.scheduler.runTask(
            this,
            Runnable {
                server.onlinePlayers.forEach { player ->
                    val name = player.name.lowercase()
                    displays.updateWings(player, wings[name])
                    displays.updateHeadwear(player, headwear[name])
                }
            },
        )
    }

    private companion object {
        /** How often equipped cosmetics are refetched from the node. */
        const val FETCH_PERIOD_TICKS = 100L
    }
}
