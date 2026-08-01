package org.helix.bridge.paper

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.display.DisplayProfile

/**
 * Registers the `%helix_<identifier>%` PlaceholderAPI expansion, resolving
 * through the same [NetworkPlaceholders] the tab list and scoreboard use —
 * balance, clan, online, network, prefix, nick, displayname.
 *
 * Only referenced when the PlaceholderAPI plugin is present — this class
 * touching PlaceholderAPI types must never be loaded otherwise.
 */
object PlaceholderApiHook {
    /**
     * Builds and registers the expansion.
     *
     * @param plugin the bridge plugin, source of the expansion version.
     * @param bridgeValues supplier of the synced bridge values.
     * @param profileOf resolver of a player's cached display profile.
     * @param onlineCount fallback online counter.
     */
    fun register(
        plugin: JavaPlugin,
        bridgeValues: () -> Map<String, String>,
        profileOf: (String) -> DisplayProfile?,
        onlineCount: () -> Int,
    ) {
        HelixPlaceholderExpansion(
            version = plugin.pluginMeta.version,
            bridgeValues = bridgeValues,
            profileOf = profileOf,
            onlineCount = onlineCount,
        ).register()
    }
}

/**
 * The `%helix_<identifier>%` expansion.
 *
 * @property version expansion version, mirrors the bridge plugin version.
 * @property bridgeValues supplier of the synced bridge values.
 * @property profileOf resolver of a player's cached display profile.
 * @property onlineCount fallback online counter.
 */
class HelixPlaceholderExpansion(
    private val version: String,
    private val bridgeValues: () -> Map<String, String>,
    private val profileOf: (String) -> DisplayProfile?,
    private val onlineCount: () -> Int,
) : PlaceholderExpansion() {
    /** The `helix` part of `%helix_<identifier>%`. */
    override fun getIdentifier(): String = "helix"

    /** Expansion author shown by `/papi info`. */
    override fun getAuthor(): String = "Helix"

    /** Expansion version shown by `/papi info`. */
    override fun getVersion(): String = version

    /** Survives `/papi reload` — the expansion is plugin-provided. */
    override fun persist(): Boolean = true

    /**
     * Resolves one placeholder.
     *
     * @param player the requesting player, may be offline or `null`.
     * @param params the identifier after `helix_`.
     * @return the value, or `null` for unknown identifiers.
     */
    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val name = player?.name ?: return null
        return NetworkPlaceholders.resolve(
            identifier = params,
            playerName = name,
            bridgeValues = bridgeValues(),
            profile = profileOf(name),
            onlineCount = onlineCount(),
        )
    }
}
