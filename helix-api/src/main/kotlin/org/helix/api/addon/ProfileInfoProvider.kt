package org.helix.api.addon

import kotlinx.serialization.Serializable

/**
 * One read-only label/value line an addon contributes to a player's
 * profile (for example a stat total or a clan membership summary).
 *
 * @property label display name of the line.
 * @property value the line's text, already formatted for display.
 */
@Serializable
data class ProfileInfoEntry(
    val label: String,
    val value: String,
)

/**
 * Contributes read-only information to a player's profile (stats, clan,
 * whatever an addon wants surfaced), shown alongside the interactive
 * settings from [ProfileSettingProvider]s.
 *
 * Registered exactly like [PlayerDataProvider]: the node aggregates every
 * registered provider under its owning addon id, so the profile addon can
 * render a full profile without knowing which addons exist.
 */
interface ProfileInfoProvider {
    /**
     * The lines this addon contributes for a player, evaluated fresh on
     * every profile view.
     *
     * @param player player name, matched case-insensitively.
     * @return display lines, or an empty list when this addon has nothing
     *  to show for that player.
     */
    fun infoFor(player: String): List<ProfileInfoEntry>
}
