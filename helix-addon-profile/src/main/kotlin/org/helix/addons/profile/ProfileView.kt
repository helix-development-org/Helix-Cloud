package org.helix.addons.profile

import kotlinx.serialization.Serializable
import org.helix.api.addon.ProfileInfoEntry
import org.helix.api.addon.ProfileSettingDescriptor

/**
 * Full rendering of one player's profile, returned by `profile.view` for
 * the in-game GUI and the dashboard panel to draw.
 *
 * @property player player name the view was built for.
 * @property info owning addon id to that addon's read-only display lines.
 * @property settings every setting contributing addons expose for this
 *  player, with its current value already resolved.
 */
@Serializable
data class ProfileView(
    val player: String,
    val info: Map<String, List<ProfileInfoEntry>>,
    val settings: List<ResolvedSetting>,
)

/**
 * One [ProfileSettingDescriptor] together with the player's current value.
 *
 * @property owner the addon id that registered this setting.
 * @property descriptor the setting's shape (label, type, options).
 * @property current the player's chosen value, or [ProfileSettingDescriptor.default]
 *  when they never chose one.
 */
@Serializable
data class ResolvedSetting(
    val owner: String,
    val descriptor: ProfileSettingDescriptor,
    val current: String,
)
