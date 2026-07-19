package org.helix.api.display

import kotlinx.serialization.Serializable

/**
 * How a player is displayed in chat and tab list.
 *
 * Text may contain `&` color codes, rendered by the bridges.
 *
 * @property prefix text before the player name, for example `&cAdmin &f`.
 * @property suffix text after the player name.
 * @property color name color code, for example `&c`.
 */
@Serializable
data class DisplayProfile(
    val prefix: String = "",
    val suffix: String = "",
    val color: String = "",
)
