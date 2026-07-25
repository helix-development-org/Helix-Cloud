package org.helix.api.player

import kotlinx.serialization.Serializable

/**
 * A player's Minecraft client locale, reported by a proxy bridge.
 *
 * The node uses the locale to pick the player's initial language on first
 * join; an explicit `/helix language` choice always wins.
 *
 * @property name player name.
 * @property locale client locale as sent by Minecraft, for example `de_de`.
 */
@Serializable
data class PlayerLocaleReport(
    val name: String,
    val locale: String,
)
