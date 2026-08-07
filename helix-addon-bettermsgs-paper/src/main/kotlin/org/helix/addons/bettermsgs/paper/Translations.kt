package org.helix.addons.bettermsgs.paper

import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.helix.api.i18n.TranslationsSnapshot
import org.helix.api.message.LegacyToMini

/**
 * Bridge-style copy of the node's translations, resolved per player.
 *
 * The snapshot is refreshed periodically by the plugin scheduler; keys of
 * this addon live under `helix.translations.helix.bettermsgs.*` and are
 * panel-editable per language.
 */
class Translations(private val client: NodeClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val miniMessage = MiniMessage.miniMessage()

    @Volatile
    private var snapshot = TranslationsSnapshot()

    /**
     * Fetches the latest snapshot from the node; failures keep the old one.
     */
    fun sync() {
        client.translations()?.let { snapshot = it }
    }

    /**
     * Resolves a short key of this addon in the player's language.
     *
     * @param player receiving player.
     * @param key key below `helix.translations.helix.bettermsgs.`.
     * @param fallback template used while the key is not yet synced.
     * @param params placeholder name to value pairs.
     * @return the resolved template.
     */
    fun text(player: Player, key: String, fallback: String, vararg params: Pair<String, String>): String {
        val current = snapshot
        val language = current.playerLanguages[player.name.lowercase()]
            ?: player.locale().language.takeIf { it in current.languages }
            ?: current.defaultLanguage
        val flat = "helix.translations.helix.bettermsgs.$key"
        var template = current.values[language]?.get(flat)
            ?: current.values[current.defaultLanguage]?.get(flat)
            ?: fallback
        params.forEach { (name, value) -> template = template.replace("{$name}", value) }
        return template
    }

    /**
     * Resolves a key and renders it as a MiniMessage component.
     *
     * @param player receiving player.
     * @param key key below the addon namespace.
     * @param fallback template used while the key is not yet synced.
     * @param params placeholder name to value pairs.
     * @return the rendered component.
     */
    fun component(player: Player, key: String, fallback: String, vararg params: Pair<String, String>): Component =
        render(text(player, key, fallback, *params))

    /**
     * Resolves a key as a chat line WITH the network prefix, so player
     * messages match the `{prefix} {message}` default of every other addon.
     * Use this for chat feedback, not for GUI item text ([component]).
     *
     * @param player receiving player.
     * @param key key below the addon namespace.
     * @param fallback template used while the key is not yet synced.
     * @param params placeholder name to value pairs.
     * @return the rendered, prefixed component.
     */
    fun chatComponent(player: Player, key: String, fallback: String, vararg params: Pair<String, String>): Component {
        val prefix = networkPrefix(player)
        val body = text(player, key, fallback, *params)
        return render(if (prefix.isBlank()) body else "$prefix $body")
    }

    private fun networkPrefix(player: Player): String {
        val current = snapshot
        val language = current.playerLanguages[player.name.lowercase()]
            ?: player.locale().language.takeIf { it in current.languages }
            ?: current.defaultLanguage
        val flat = "helix.translations.network.prefix"
        return (current.values[language]?.get(flat) ?: current.values[current.defaultLanguage]?.get(flat)).orEmpty()
    }

    /**
     * Renders raw MiniMessage/legacy text into a component.
     *
     * @param text the template.
     * @return the rendered component.
     */
    fun render(text: String): Component = miniMessage.deserialize(LegacyToMini.translate(text))
}
