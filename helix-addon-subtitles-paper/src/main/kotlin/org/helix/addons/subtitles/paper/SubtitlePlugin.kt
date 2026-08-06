package org.helix.addons.subtitles.paper

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * Renders a player's chosen subtitle (published by the subtitles addon as
 * a `subtitle.text.<player>` bridge value) as a Text Display entity
 * beneath their name tag.
 *
 * Two independent tasks: a slow one refetches which players currently
 * have a subtitle at all (a network round trip), a fast one re-teleports
 * every already-known display every tick so it tracks a moving player
 * without waiting on the network.
 */
class SubtitlePlugin : JavaPlugin(), Listener {
    private val displays = SubtitleDisplayService()
    private var fetchTask: BukkitTask? = null
    private var trackTask: BukkitTask? = null
    private var client: BridgeValuesClient? = null

    /** Reads the node connection from the environment and starts both tasks. */
    override fun onEnable() {
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val controlToken = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        val serviceId = System.getenv("HELIX_SERVICE_ID").orEmpty()
        if (controlUrl.isBlank() || controlToken.isBlank() || serviceId.isBlank()) {
            logger.severe("HelixSubtitles requires HELIX_CONTROL_URL, HELIX_CONTROL_TOKEN and HELIX_SERVICE_ID")
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
        trackTask = server.scheduler.runTaskTimer(
            this,
            Runnable { displays.track(server.onlinePlayers) },
            1L,
            1L,
        )
        logger.info("HelixSubtitles enabled (node: $controlUrl)")
    }

    /** Stops both tasks and removes every display entity. */
    override fun onDisable() {
        fetchTask?.cancel()
        trackTask?.cancel()
        displays.shutdown()
        client?.close()
        client = null
    }

    /** Drops the quitting player's subtitle display; nothing else references it after this. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        displays.remove(event.player.uniqueId)
    }

    private fun applyFetch(client: BridgeValuesClient) {
        val subtitles = client.fetch()?.let(SubtitleValues::parse) ?: return
        server.scheduler.runTask(
            this,
            Runnable {
                server.onlinePlayers.forEach { player ->
                    displays.update(player, subtitles[player.name.lowercase()])
                }
            },
        )
    }

    private companion object {
        /** How often the subtitle text itself is refetched from the node. */
        const val FETCH_PERIOD_TICKS = 100L
    }
}
