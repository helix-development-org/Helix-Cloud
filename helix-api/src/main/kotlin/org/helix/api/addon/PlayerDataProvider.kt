package org.helix.api.addon

/**
 * Exposes an addon's per-player data for GDPR export/delete requests.
 *
 * Providers are registered by addons that hold personal data (bans, warns,
 * permissions, friends, clan membership, balances, …); the node aggregates
 * every registered provider into one export, or fans a delete request out
 * to all of them, so no addon-specific logic lives in the node core.
 */
interface PlayerDataProvider {
    /**
     * Exports everything this addon holds about a player, already encoded
     * as JSON (the same convention `ban.export`/`eco.export`-style actions
     * use), so the node can fold it into the aggregate export unparsed.
     *
     * @param player player name, matched case-insensitively.
     * @return a JSON object/array/value as text, or `null` when the addon
     *   holds nothing about this player.
     */
    fun export(player: String): String?

    /**
     * Deletes (or anonymizes) everything this addon holds about a player.
     *
     * @param player player name, matched case-insensitively.
     * @return `true` when data was removed, `false` when nothing existed.
     */
    fun delete(player: String): Boolean
}
