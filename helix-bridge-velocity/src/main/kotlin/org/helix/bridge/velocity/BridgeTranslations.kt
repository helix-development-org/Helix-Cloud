package org.helix.bridge.velocity

import com.velocitypowered.api.proxy.Player
import org.helix.api.i18n.TranslationsSnapshot

/**
 * The bridge-local copy of the node's translations, resolved per player.
 *
 * A player's language is their `/helix language` choice as synced from the
 * node, falling back to their Minecraft client locale (when the network has
 * that language) and finally the network default.
 */
class BridgeTranslations {
    @Volatile
    private var snapshot = TranslationsSnapshot()

    /**
     * Replaces the local snapshot after a sync.
     *
     * @param next the freshly fetched snapshot.
     */
    fun update(next: TranslationsSnapshot) {
        snapshot = next
    }

    /**
     * The effective language of a player.
     *
     * @param player the player, or `null` for the network default (console,
     *   server list pings).
     * @return a language code.
     */
    fun languageOf(player: Player?): String {
        val current = snapshot
        if (player == null) {
            return current.defaultLanguage
        }
        current.playerLanguages[player.username.lowercase()]?.let { return it }
        val clientLanguage = player.playerSettings?.locale?.language.orEmpty()
        return if (clientLanguage in current.languages) clientLanguage else current.defaultLanguage
    }

    /**
     * Resolves a translation key in a player's language.
     *
     * @param key flat translation key.
     * @param player the receiving player, or `null` for the default language.
     * @return the template, or `null` when the key is unknown.
     */
    fun resolve(key: String, player: Player?): String? {
        val current = snapshot
        return current.values[languageOf(player)]?.get(key)
            ?: current.values[current.defaultLanguage]?.get(key)
    }
}
