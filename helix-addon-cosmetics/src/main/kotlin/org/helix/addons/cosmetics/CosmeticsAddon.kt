package org.helix.addons.cosmetics

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerListener
import org.helix.api.addon.ProfileSettingDescriptor
import org.helix.api.addon.ProfileSettingOption
import org.helix.api.addon.ProfileSettingProvider
import org.helix.api.addon.ProfileSettingType
import org.helix.api.player.OnlinePlayer

/**
 * Cosmetics addon.
 *
 * Wearable wings and headwear, chosen through the profile addon's
 * `wings`/`headwear` settings (this addon owns none of the actual chosen
 * VALUE, only the catalog of what can be chosen — see [CosmeticCatalog]).
 * Rendered Paper-side as item display entities attached to the player,
 * never occupying a real armor/elytra slot, so a cosmetic never conflicts
 * with actually wearing armor or gliding on a real elytra.
 */
class CosmeticsAddon : AddonBase() {
    /**
     * Registers the profile setting provider and the join/leave listener
     * that keep each player's equipped-cosmetic bridge values current.
     */
    override fun enable() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String): List<ProfileSettingDescriptor> = listOf(
                descriptorFor(KEY_WINGS, "Wings", CosmeticCatalog.wings, player),
                descriptorFor(KEY_HEADWEAR, "Headwear", CosmeticCatalog.headwear, player),
            )

            override fun onChanged(player: String, key: String, value: String) {
                republish(player, key)
            }
        })

        context.registerPlayerListener(object : PlayerListener {
            override fun onJoin(player: OnlinePlayer) {
                republish(player.name, KEY_WINGS)
                republish(player.name, KEY_HEADWEAR)
            }

            override fun onLeave(player: OnlinePlayer) {
                context.unpublishBridgeValue(bridgeKey(KEY_WINGS, player.name))
                context.unpublishBridgeValue(bridgeKey(KEY_HEADWEAR, player.name))
            }
        })
    }

    private fun descriptorFor(
        key: String,
        label: String,
        catalog: List<CosmeticDefinition>,
        player: String,
    ): ProfileSettingDescriptor {
        val options = listOf(ProfileSettingOption("none", "None")) + catalog.map { def ->
            ProfileSettingOption(
                id = def.id,
                label = def.label,
                unlocked = def.permission.isBlank() || context.hasPermission(player, def.permission),
            )
        }
        return ProfileSettingDescriptor(key, label, ProfileSettingType.Choice(options), default = "none")
    }

    /**
     * Recomputes and publishes (or unpublishes, if none) the
     * `CustomModelData` value for a player's chosen cosmetic in one
     * category, read back from the profile addon's own storage.
     */
    private fun republish(player: String, key: String) {
        val catalog = if (key == KEY_WINGS) CosmeticCatalog.wings else CosmeticCatalog.headwear
        val chosen = get(player, key)
        val customModelData = catalog.find { it.id == chosen }?.customModelData
        val bridgeKey = bridgeKey(key, player)
        if (customModelData == null) {
            context.unpublishBridgeValue(bridgeKey)
        } else {
            context.publishBridgeValue(bridgeKey, customModelData.toString())
        }
    }

    private fun get(player: String, key: String): String? {
        val result = context.actions.invoke(
            ActionInvocation("profile.setting.get", listOf(player, MANIFEST_ID, key), ActionSource.ADDON),
        )
        return result.lines.firstOrNull().takeIf { result.success }
    }

    private fun bridgeKey(key: String, player: String) = "cosmetic.$key.${player.lowercase()}"

    private companion object {
        /** This addon's manifest id, matching `addon.json` — used as the profile-setting owner. */
        const val MANIFEST_ID = "helix.cosmetics"

        /** Profile-setting key for the chosen wings. */
        const val KEY_WINGS = "wings"

        /** Profile-setting key for the chosen headwear. */
        const val KEY_HEADWEAR = "headwear"
    }
}
