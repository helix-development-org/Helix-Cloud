package org.helix.addons.feedback.paper

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.jar.JarFile

/**
 * Reads the report context from Bukkit (main thread): position, server load, client data from IGuard (looked up
 * reflectively through the services manager so the plugin works without it) and the versions of the installed
 * plugins.
 *
 * @property service this server's `HELIX_SERVICE_ID`.
 * @property task this server's `HELIX_TASK`.
 */
class ContextCapture(private val service: String, private val task: String) {
    @Volatile private var versions: Map<String, String>? = null

    /**
     * Captures the context for a player.
     *
     * @param player the reporter.
     * @param screenshot whether the reporter asked for a screenshot.
     * @return the snapshot.
     */
    fun capture(player: Player, screenshot: Boolean = false): ContextSnapshot {
        val location = player.location
        val guard = guardSnapshot(player)
        return ContextSnapshot(
            service = service,
            task = task,
            world = location.world?.name ?: "?",
            x = location.x,
            y = location.y,
            z = location.z,
            yaw = location.yaw,
            pitch = location.pitch,
            gamemode = player.gameMode.name.lowercase(),
            tps = Bukkit.getTPS().firstOrNull() ?: 20.0,
            mspt = Bukkit.getAverageTickTime(),
            ping = player.ping,
            clientVersion = guard?.get("clientVersion") as? String,
            clientBrand = (guard?.get("clientBrand") as? String) ?: player.clientBrandName,
            clientFamily = guard?.get("clientFamily") as? String,
            labymod = isLabyMod(player),
            versions = pluginVersions(),
            screenshot = screenshot,
        )
    }

    /**
     * A context without player (automatic error reports).
     *
     * @return the snapshot with server data only.
     */
    fun captureServer(): ContextSnapshot = ContextSnapshot(
        service = service,
        task = task,
        world = "-",
        x = 0.0,
        y = 0.0,
        z = 0.0,
        yaw = 0f,
        pitch = 0f,
        gamemode = "-",
        tps = Bukkit.getTPS().firstOrNull() ?: 20.0,
        mspt = Bukkit.getAverageTickTime(),
        ping = 0,
        clientVersion = null,
        clientBrand = null,
        clientFamily = null,
        labymod = false,
        versions = pluginVersions(),
    )

    /**
     * @param player a player.
     * @return true when the client registered a LabyMod plugin channel.
     */
    fun isLabyMod(player: Player): Boolean = player.listeningPluginChannels.any { it.startsWith("labymod") }

    /**
     * @param player a player.
     * @return true when the client's Helix LabyMod addon accepts screenshot requests (channel `helix:feedback`).
     */
    fun canScreenshot(player: Player): Boolean = FeedbackPlugin.SCREENSHOT_CHANNEL in player.listeningPluginChannels

    /** Plugin versions, read once (manifests of the plugin jars). */
    private fun pluginVersions(): Map<String, String> = versions ?: Bukkit.getPluginManager().plugins
        .associate { it.name to ContextBuilder.versionOf(it.pluginMeta.version, manifestOf(it)) }
        .also { versions = it }

    private fun manifestOf(plugin: Plugin): Map<String, String> = runCatching {
        val location = plugin.javaClass.protectionDomain.codeSource?.location ?: return emptyMap()
        JarFile(File(location.toURI())).use { jar ->
            jar.manifest?.mainAttributes?.entries?.associate { (key, value) -> key.toString() to value.toString() } ?: emptyMap()
        }
    }.getOrDefault(emptyMap())

    /** IGuard's per-player snapshot as a property map, read reflectively (IGuard is optional). */
    private fun guardSnapshot(player: Player): Map<String, Any?>? = runCatching {
        val apiClass = Class.forName("de.tytoss.iguard.api.IGuardApi")
        val registration = Bukkit.getServicesManager().getRegistration(apiClass) ?: return null
        val snapshot = apiClass.getMethod("snapshot", java.util.UUID::class.java).invoke(registration.provider, player.uniqueId)
            ?: return null
        listOf("clientVersion", "clientBrand", "clientFamily").associateWith { property ->
            snapshot.javaClass.getMethod("get" + property.replaceFirstChar(Char::uppercase)).invoke(snapshot)
        }
    }.getOrNull()
}
