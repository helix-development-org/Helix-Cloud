package org.helix.addons.subtitles

import kotlinx.serialization.Serializable

/**
 * One operator-predefined subtitle a player can choose.
 *
 * @property id stable identifier, stored as the player's chosen value.
 * @property text the display text.
 * @property permission permission node required to choose this subtitle;
 *  blank means available to everyone.
 */
@Serializable
data class SubtitleDefinition(
    val id: String,
    val text: String,
    val permission: String = "",
)

/**
 * Persisted subtitle configuration.
 *
 * @property definitions the operator-predefined subtitle list.
 * @property customPermission permission node required to set a free-text
 *  subtitle instead of picking from [definitions].
 */
@Serializable
data class SubtitleConfig(
    val definitions: List<SubtitleDefinition> = emptyList(),
    val customPermission: String = "helix.subtitle.custom",
)
