package org.helix.addons.subtitles

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerListener
import org.helix.api.addon.ProfileSettingDescriptor
import org.helix.api.addon.ProfileSettingOption
import org.helix.api.addon.ProfileSettingProvider
import org.helix.api.addon.ProfileSettingType
import org.helix.api.player.OnlinePlayer

/**
 * Subtitles addon.
 *
 * A second display line below a player's name tag, rendered Paper-side as
 * a Text Display entity that tracks the player. What can be shown is
 * operator-predefined ([SubtitleDefinition], `subtitle.config.*`); players
 * pick one through the profile addon's `subtitle` setting, or set a free
 * custom text through the permission-gated `custom` setting. This addon
 * owns none of the actual chosen VALUE — the profile addon does — it only
 * computes the effective text whenever either setting changes and
 * publishes it as a bridge value the Paper component reads.
 */
class SubtitleAddon : AddonBase() {
    private val json = Json { prettyPrint = true }
    private lateinit var config: SubtitleConfig

    /**
     * Loads the config, registers the profile setting provider and the
     * join/leave listener, and exposes the config-management actions.
     */
    override fun enable() {
        config = loadConfig()

        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String): List<ProfileSettingDescriptor> = buildList {
                add(
                    ProfileSettingDescriptor(
                        key = KEY_SUBTITLE,
                        label = "Subtitle",
                        type = ProfileSettingType.Choice(
                            listOf(ProfileSettingOption("none", "None")) +
                                config.definitions.map { def ->
                                    ProfileSettingOption(
                                        id = def.id,
                                        label = def.text,
                                        unlocked = def.permission.isBlank() || context.hasPermission(player, def.permission),
                                    )
                                },
                        ),
                        default = "none",
                    ),
                )
                if (context.hasPermission(player, config.customPermission)) {
                    add(
                        ProfileSettingDescriptor(
                            key = KEY_CUSTOM,
                            label = "Custom Subtitle",
                            type = ProfileSettingType.FreeText(maxLength = MAX_CUSTOM_LENGTH),
                            default = "",
                        ),
                    )
                }
            }

            override fun onChanged(player: String, key: String, value: String) {
                republish(player)
            }
        })

        context.registerPlayerListener(object : PlayerListener {
            override fun onJoin(player: OnlinePlayer) {
                republish(player.name)
            }

            override fun onLeave(player: OnlinePlayer) {
                context.unpublishBridgeValue(bridgeKey(player.name))
            }
        })

        action(
            "subtitle.config.add",
            "Adds or replaces a predefined subtitle.",
            "subtitle.config.add <id> <text> [permission]",
        ) { invocation ->
            val id = invocation.arguments.getOrNull(0)
            val text = invocation.arguments.getOrNull(1)
            if (id == null || text == null) {
                return@action ActionResult.error("usage: subtitle.config.add <id> <text> [permission]")
            }
            val permission = invocation.arguments.getOrNull(2).orEmpty()
            config = config.copy(
                definitions = config.definitions.filterNot { it.id == id } + SubtitleDefinition(id, text, permission),
            )
            saveConfig()
            ActionResult.ok("saved")
        }

        action(
            "subtitle.config.remove",
            "Removes a predefined subtitle.",
            "subtitle.config.remove <id>",
        ) { invocation ->
            val id = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: subtitle.config.remove <id>")
            if (config.definitions.none { it.id == id }) {
                return@action ActionResult.error("no such subtitle: $id")
            }
            config = config.copy(definitions = config.definitions.filterNot { it.id == id })
            saveConfig()
            ActionResult.ok("removed")
        }

        action("subtitle.config.list", "Lists every predefined subtitle.", "subtitle.config.list") {
            if (config.definitions.isEmpty()) {
                ActionResult.ok("no predefined subtitles")
            } else {
                ActionResult.ok(
                    *config.definitions.map { "${it.id}: ${it.text}${if (it.permission.isNotBlank()) " (${it.permission})" else ""}" }
                        .toTypedArray(),
                )
            }
        }
    }

    /**
     * Recomputes and publishes (or unpublishes, if empty) a player's
     * effective subtitle text: a set custom value wins over the chosen
     * predefined subtitle, which wins over showing nothing.
     */
    private fun republish(player: String) {
        val custom = get(player, KEY_CUSTOM)
        val text = custom?.takeIf { it.isNotBlank() }
            ?: get(player, KEY_SUBTITLE)?.let { id -> config.definitions.find { it.id == id }?.text }
        if (text.isNullOrBlank()) {
            context.unpublishBridgeValue(bridgeKey(player))
        } else {
            context.publishBridgeValue(bridgeKey(player), text)
        }
    }

    private fun get(player: String, key: String): String? {
        val result = context.actions.invoke(
            ActionInvocation("profile.setting.get", listOf(player, MANIFEST_ID, key), ActionSource.ADDON),
        )
        return result.lines.firstOrNull().takeIf { result.success }
    }

    private fun bridgeKey(player: String) = "subtitle.text.${player.lowercase()}"

    private fun loadConfig(): SubtitleConfig =
        context.storage().read(CONFIG_DOCUMENT)?.let { json.decodeFromString(it) } ?: SubtitleConfig()

    private fun saveConfig() {
        context.storage().write(CONFIG_DOCUMENT, json.encodeToString(config))
    }

    private companion object {
        /** This addon's manifest id, matching `addon.json` — used as the profile-setting owner. */
        const val MANIFEST_ID = "helix.subtitles"

        /** Document key holding [SubtitleConfig]. */
        const val CONFIG_DOCUMENT = "config"

        /** Profile-setting key for the chosen predefined subtitle. */
        const val KEY_SUBTITLE = "subtitle"

        /** Profile-setting key for a custom free-text subtitle. */
        const val KEY_CUSTOM = "custom"

        /** Longest accepted custom subtitle. */
        const val MAX_CUSTOM_LENGTH = 32
    }
}
