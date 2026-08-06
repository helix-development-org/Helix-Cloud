package org.helix.addons.labymod

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * One entry of the LabyMod interaction menu (right-click on a player).
 *
 * @property label text shown in the menu.
 * @property command command the clicking player runs; `{name}` is replaced
 *   with the clicked player's name.
 */
@Serializable
data class MenuEntry(val label: String, val command: String)

/**
 * LabyMod emotes of one NPC.
 *
 * @property interactEmotes emote ids played when a player clicks the NPC.
 * @property idleEmotes emote ids played periodically while idle.
 * @property idleIntervalSeconds seconds between idle emotes.
 */
@Serializable
data class NpcEmotes(
    val interactEmotes: List<Int> = emptyList(),
    val idleEmotes: List<Int> = emptyList(),
    val idleIntervalSeconds: Int = 30,
)

/**
 * Configuration of the LabyMod addon, persisted through the addon's
 * document storage under the `labymod` key and published to the Velocity
 * component as the `labymod.config` bridge value.
 *
 * @property economyHud show the helix-economy balance in the LabyMod HUD.
 * @property voiceMuteSync mirror helix-moderation mutes into the LabyMod
 *   voice chat.
 * @property discordRpc send a Discord Rich Presence text to LabyMod users.
 * @property subtitlesSync additionally render helix-subtitles natively for
 *   LabyMod clients.
 * @property npcEmotes play configured LabyMod emotes on NPCs.
 * @property interactionMenu offer the configured [menuEntries].
 * @property rpcFormat Rich Presence template; placeholders `{network}`,
 *   `{task}` and `{service}`.
 * @property menuEntries interaction-menu entries.
 * @property npcs LabyMod emote configuration per NPC id.
 * @property muteSyncIntervalSeconds seconds between mute-export polls.
 */
@Serializable
data class LabyConfig(
    val economyHud: Boolean = true,
    val voiceMuteSync: Boolean = true,
    val discordRpc: Boolean = true,
    val subtitlesSync: Boolean = true,
    val npcEmotes: Boolean = true,
    val interactionMenu: Boolean = true,
    val rpcFormat: String = "{network} — {task}",
    val menuEntries: List<MenuEntry> = DEFAULT_MENU,
    val npcs: Map<String, NpcEmotes> = emptyMap(),
    val muteSyncIntervalSeconds: Int = 10,
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Storage document key holding the configuration. */
        private const val DOCUMENT = "labymod"

        /** Default interaction-menu entries, wired to the bundled addons. */
        val DEFAULT_MENU = listOf(
            MenuEntry("Nachricht senden", "/msg {name}"),
            MenuEntry("Freund hinzufügen", "/friend add {name}"),
            MenuEntry("Party einladen", "/party invite {name}"),
            MenuEntry("Clan einladen", "/clan invite {name}"),
        )

        /**
         * Loads the configuration, writing defaults on first use.
         *
         * @param storage addon-scoped document store.
         * @return the effective configuration.
         */
        fun load(storage: AddonStorage): LabyConfig {
            val raw = storage.read(DOCUMENT) ?: return LabyConfig().also { save(storage, it) }
            return json.decodeFromString(raw)
        }

        /**
         * Persists the configuration.
         *
         * @param storage addon-scoped document store.
         * @param config configuration to persist.
         */
        fun save(storage: AddonStorage, config: LabyConfig) {
            storage.write(DOCUMENT, json.encodeToString(config))
        }

        /**
         * Serializes a configuration for the `labymod.config` bridge value.
         *
         * @param config the configuration.
         * @return compact JSON.
         */
        fun toBridgeValue(config: LabyConfig): String = Json.encodeToString(config)
    }
}
