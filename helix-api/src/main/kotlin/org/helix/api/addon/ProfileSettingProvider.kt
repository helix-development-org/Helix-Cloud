package org.helix.api.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One selectable option of a [ProfileSettingType.Choice] setting (for
 * example one wing cosmetic, or one predefined subtitle).
 *
 * @property id stable identifier stored as the setting's value when chosen.
 * @property label display name shown in the profile GUI.
 * @property icon renderer-interpreted icon hint (for example a Material
 *  name or a custom-texture id); empty when the option has no icon.
 * @property unlocked whether the player this option was computed for is
 *  currently allowed to choose it (for example a rank-gated cosmetic) — the
 *  option is still shown so a locked choice is visible, just not selectable.
 */
@Serializable
data class ProfileSettingOption(
    val id: String,
    val label: String,
    val icon: String = "",
    val unlocked: Boolean = true,
)

/**
 * The kind of value a [ProfileSettingDescriptor] accepts.
 */
@Serializable
sealed interface ProfileSettingType {
    /** A simple on/off switch, stored as the strings `"true"`/`"false"`. */
    @Serializable @SerialName("toggle")
    data object Toggle : ProfileSettingType

    /**
     * A choice from a fixed, addon-computed list of options — the value is
     * one of [options]' ids.
     *
     * @property options the choices, per-player gating already applied.
     */
    @Serializable @SerialName("choice")
    data class Choice(val options: List<ProfileSettingOption>) : ProfileSettingType

    /**
     * Free player-entered text.
     *
     * @property maxLength longest value the profile addon accepts; longer
     *  input is rejected rather than truncated.
     */
    @Serializable @SerialName("freetext")
    data class FreeText(val maxLength: Int = 32) : ProfileSettingType
}

/**
 * Descriptor of one interactive setting an addon contributes to a player's
 * profile (for example an equipped cosmetic, a chosen subtitle, or the
 * player's UI language).
 *
 * @property key stable identifier for this setting, unique within the
 *  owning addon — the profile addon scopes storage further by addon id, so
 *  two addons may reuse the same key without colliding.
 * @property label display name shown in the profile GUI/dashboard.
 * @property type the kind of value this setting accepts.
 * @property default the value a player who never chose one effectively has.
 */
@Serializable
data class ProfileSettingDescriptor(
    val key: String,
    val label: String,
    val type: ProfileSettingType,
    val default: String = "",
)

/**
 * Contributes one or more interactive settings to a player's profile.
 *
 * Settings are addon-defined — what can be chosen, and which options a
 * given player is currently allowed to pick — but their current VALUE is
 * stored centrally by the profile addon, not by the contributing addon, so
 * a player's whole profile lives in one place regardless of how many
 * addons contribute to it. A contributing addon that needs to know a
 * player's current value (to render it, for example) reads it back through
 * the profile addon's own actions, the same way any other cross-addon
 * lookup in this platform works.
 */
interface ProfileSettingProvider {
    /**
     * The settings this addon contributes for a player, evaluated fresh on
     * every profile view (an option's [ProfileSettingOption.unlocked] may
     * depend on the player, for example a rank-gated cosmetic).
     *
     * @param player player name, matched case-insensitively.
     * @return this addon's settings, or an empty list to contribute none
     *  for that player.
     */
    fun settingsFor(player: String): List<ProfileSettingDescriptor>

    /**
     * Validates a candidate value before the profile addon persists it,
     * for checks the [ProfileSettingType] alone cannot express — for
     * example a free-text value colliding with a known account or staff
     * member. Called after the profile addon's own type/gating checks
     * already passed. A no-op (always valid) by default.
     *
     * @param player player name.
     * @param key the setting's [ProfileSettingDescriptor.key].
     * @param value the candidate value.
     * @return `null` when valid, or a player-facing rejection reason.
     */
    fun validate(player: String, key: String, value: String): String? = null

    /**
     * Notified after the profile addon persists a new value for one of this
     * addon's settings, so it can react (for example re-rendering an
     * equipped cosmetic). A no-op by default.
     *
     * @param player player name.
     * @param key the changed setting's [ProfileSettingDescriptor.key].
     * @param value the newly persisted value.
     */
    fun onChanged(player: String, key: String, value: String) {}
}
