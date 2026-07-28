package org.helix.bridge.paper

import org.helix.api.display.DisplayProfile

/**
 * Resolves the small set of network-wide placeholders (balance, clan tag,
 * online count, network name/prefix, nick) that other plugins commonly need
 * for display purposes.
 *
 * Pure and dependency-free so the values are derived exactly once and
 * reused everywhere they are needed — the tab list/scoreboard placeholder
 * substitution in [HelixPaperBridgePlugin], and (once the optional
 * PlaceholderAPI dependency is wired in) a `%helix_<identifier>%`
 * expansion — instead of re-deriving them per consumer.
 */
object NetworkPlaceholders {
    /**
     * Resolves one placeholder identifier (without the surrounding `%…%` or
     * `{…}` markers a specific consumer uses).
     *
     * @param identifier the placeholder name, for example `balance` or `clan`.
     * @param playerName the requesting player's account name.
     * @param bridgeValues the bridge's currently synced global values.
     * @param profile the player's resolved display profile, if fetched.
     * @param onlineCount fallback online player count when the network total
     *  bridge value is not (yet) published.
     * @return the resolved value, or `null` for an unknown identifier.
     */
    fun resolve(
        identifier: String,
        playerName: String,
        bridgeValues: Map<String, String>,
        profile: DisplayProfile?,
        onlineCount: Int,
    ): String? {
        val lowerName = playerName.lowercase()
        return when (identifier.lowercase()) {
            "balance" -> bridgeValues["economy.balance.$lowerName"] ?: ""
            "clan" -> bridgeValues["clan.tag.$lowerName"] ?: ""
            "online" -> bridgeValues["network.online"] ?: onlineCount.toString()
            "network" -> bridgeValues["network.name"] ?: ""
            "prefix" -> bridgeValues["network.prefix"] ?: ""
            "nick" -> profile?.nameOr(playerName) ?: playerName
            "displayname" -> profile?.displayName(playerName) ?: playerName
            else -> null
        }
    }
}
