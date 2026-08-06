package org.helix.addons.labymod.velocity

import kotlinx.serialization.Serializable

/**
 * The `labymod.config` bridge value, mirrored from the node addon.
 *
 * @property economyHud show the balance in the LabyMod HUD.
 * @property voiceMuteSync mirror mutes into the LabyMod voice chat.
 * @property discordRpc send Discord Rich Presence texts.
 * @property subtitlesSync render helix-subtitles natively.
 * @property npcEmotes play configured NPC emotes.
 * @property interactionMenu offer the configured menu entries.
 * @property rpcFormat Rich Presence template with `{network}`, `{task}`,
 *   `{service}` placeholders.
 * @property menuEntries interaction-menu entries.
 * @property npcs per-NPC emote configuration.
 */
@Serializable
data class LabyConfigValue(
    val economyHud: Boolean = true,
    val voiceMuteSync: Boolean = true,
    val discordRpc: Boolean = true,
    val subtitlesSync: Boolean = true,
    val npcEmotes: Boolean = true,
    val interactionMenu: Boolean = true,
    val rpcFormat: String = "{network} — {task}",
    val menuEntries: List<MenuEntryValue> = emptyList(),
    val npcs: Map<String, NpcEmotesValue> = emptyMap(),
)

/**
 * One interaction-menu entry.
 *
 * @property label menu text.
 * @property command command template; `{name}` is the clicked player.
 */
@Serializable
data class MenuEntryValue(val label: String, val command: String)

/**
 * Emote configuration of one NPC.
 *
 * @property interactEmotes emote ids played on click.
 * @property idleEmotes emote ids played periodically.
 * @property idleIntervalSeconds seconds between idle emotes.
 */
@Serializable
data class NpcEmotesValue(
    val interactEmotes: List<Int> = emptyList(),
    val idleEmotes: List<Int> = emptyList(),
    val idleIntervalSeconds: Int = 30,
)

/**
 * One one-shot command from the `labymod.cmd` bridge value.
 *
 * @property seq monotonically increasing sequence number.
 * @property type command type.
 * @property player receiving player name or `all`.
 * @property service backend service scoping the command.
 * @property args type-specific arguments.
 */
@Serializable
data class LabyCommandValue(
    val seq: Long,
    val type: String,
    val player: String = "",
    val service: String = "",
    val args: List<String> = emptyList(),
)

/**
 * The queue payload of the `labymod.cmd` bridge value.
 *
 * @property entries newest commands, oldest first.
 */
@Serializable
data class LabyCommandQueueValue(val entries: List<LabyCommandValue> = emptyList())

/**
 * One active mute from the moderation addon's `mute.export`.
 *
 * @property player muted player name.
 * @property reason mute reason.
 * @property expiresAtEpochMs expiry, `null` while permanent.
 */
@Serializable
data class MuteValue(
    val player: String,
    val reason: String = "",
    val expiresAtEpochMs: Long? = null,
)
