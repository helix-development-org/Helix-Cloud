package org.helix.addons.cinematic

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerListener
import org.helix.api.addon.ProfileSettingDescriptor
import org.helix.api.addon.ProfileSettingProvider
import org.helix.api.addon.ProfileSettingType
import org.helix.api.player.OnlinePlayer

/**
 * AFK handling: after [CinematicConfig.afkSeconds] without input the Paper component (`helix-addon-cinematic-paper`)
 * shows the AFK screen (or, in mode `cinematic`, the cinematic camera).
 *
 * This node side owns the settings ([CinematicConfig], actions `cinematic.config` / `cinematic.set`, dashboard
 * panel), publishes them as bridge value `cinematic.config`, and contributes the profile toggle `cinematic`
 * (default on) – players who turned it off are published as `cinematic.off.<player>`.
 */
class CinematicAddon : AddonBase() {
    private var config = CinematicConfig()

    /** Loads the settings, registers actions, panel, profile toggle and the join listener. */
    override fun enable() {
        config = CinematicConfig.parse(context.storage().read(CONFIG_KEY))
        publishConfig()

        action("cinematic.config", "Shows the cinematic AFK settings.", "cinematic.config", permission = ADMIN) {
            ActionResult(true, listOf(config.toJson()))
        }
        action(
            "cinematic.set",
            "Changes a cinematic AFK setting (${CinematicConfig.KEYS.joinToString()}).",
            "cinematic.set <setting> <value…>",
            permission = ADMIN,
        ) { inv ->
            val key = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: cinematic.set <setting> <value…>")
            runCatching { config.with(key, inv.arguments.drop(1).joinToString(" ")) }.fold(
                onSuccess = {
                    config = it
                    context.storage().write(CONFIG_KEY, it.toJson())
                    publishConfig()
                    ActionResult.ok("$key updated")
                },
                onFailure = { ActionResult.error(it.message ?: "invalid value") },
            )
        }
        panel("cinematic", "AFK", "/panel.html")

        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String): List<ProfileSettingDescriptor> =
                listOf(ProfileSettingDescriptor(KEY_TOGGLE, "AFK screen", ProfileSettingType.Toggle, default = "true"))

            override fun onChanged(player: String, key: String, value: String) {
                if (key == KEY_TOGGLE) publishOptOut(player, value)
            }
        })
        context.registerPlayerListener(object : PlayerListener {
            override fun onJoin(player: OnlinePlayer) {
                publishOptOut(player.name, setting(player.name))
            }

            override fun onLeave(player: OnlinePlayer) {
                context.unpublishBridgeValue(optOutKey(player.name))
            }
        })
    }

    private fun publishConfig() = context.publishBridgeValue(CONFIG_BRIDGE_KEY, config.toJson())

    private fun publishOptOut(player: String, value: String?) {
        if (value == "false") context.publishBridgeValue(optOutKey(player), "1") else context.unpublishBridgeValue(optOutKey(player))
    }

    private fun setting(player: String): String? {
        val result = context.actions.invoke(
            ActionInvocation("profile.setting.get", listOf(player, MANIFEST_ID, KEY_TOGGLE), ActionSource.ADDON),
        )
        return result.lines.firstOrNull().takeIf { result.success }
    }

    private fun optOutKey(player: String) = "cinematic.off.${player.lowercase()}"

    private companion object {
        /** This addon's manifest id (profile-setting owner). */
        const val MANIFEST_ID = "helix.cinematic"

        /** Permission for changing the settings. */
        const val ADMIN = "helix.cinematic.admin"

        const val CONFIG_KEY = "config"
        const val CONFIG_BRIDGE_KEY = "cinematic.config"
        const val KEY_TOGGLE = "cinematic"
    }
}
