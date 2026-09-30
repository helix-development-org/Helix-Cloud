package org.helix.addons.cosmetics.paper

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent
import io.papermc.paper.event.player.PlayerTrackEntityEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * Renders a player's equipped cosmetics (published by the cosmetics addon
 * as `cosmetic.wings.<player>`/`cosmetic.headwear.<player>` bridge values,
 * each a `CustomModelData` value) as fake equipment ([CosmeticEquipmentService]),
 * so the client draws them as part of the player model.
 *
 * A slow task refetches which players wear what (a network round trip); the
 * cosmetics are reapplied right after the server resent real equipment (armor
 * change, a viewer starting to track the wearer, respawn, world change) and
 * periodically as a safety net.
 */
class CosmeticsPlugin : JavaPlugin(), Listener {
    private val cosmetics = CosmeticEquipmentService()
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
        trackTask = server.scheduler.runTaskTimer(this, Runnable { cosmetics.refreshAll(server.onlinePlayers) }, REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS)
        logger.info("HelixCosmetics enabled (node: $controlUrl)")
    }

    /** Stops both tasks and removes every display entity. */
    override fun onDisable() {
        fetchTask?.cancel()
        trackTask?.cancel()
        cosmetics.shutdown(server.onlinePlayers)
        client?.close()
        client = null
    }

    /** Drops the quitting player's cosmetic displays; nothing else references them after this. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        cosmetics.remove(event.player.uniqueId)
    }

    /** A viewer starts tracking a wearer: the server just sent the real equipment – show the cosmetic again. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onTrack(event: PlayerTrackEntityEvent) {
        val wearer = event.entity as? Player ?: return
        if (!cosmetics.wears(wearer.uniqueId)) return
        val viewer = event.player
        server.scheduler.runTask(this, Runnable { cosmetics.refresh(wearer, listOf(viewer)) })
    }

    /** Real armor changed: the server broadcast it – put the cosmetic back on top. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onArmorChange(event: PlayerArmorChangeEvent) = refreshSoon(event.player)

    /** Respawn resends the equipment. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = refreshSoon(event.player)

    /** A world change resends the equipment. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = refreshSoon(event.player)

    private fun refreshSoon(player: Player) {
        if (!cosmetics.wears(player.uniqueId)) return
        server.scheduler.runTask(this, Runnable { if (player.isOnline) cosmetics.refresh(player) })
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
                    cosmetics.updateWings(player, wings[name])
                    cosmetics.updateHeadwear(player, headwear[name])
                }
            },
        )
    }

    private companion object {
        /** How often equipped cosmetics are refetched from the node. */
        const val FETCH_PERIOD_TICKS = 100L

        /** Safety-net reapplication of all cosmetics (e.g. after an inventory resync). */
        const val REFRESH_PERIOD_TICKS = 40L
    }
}
