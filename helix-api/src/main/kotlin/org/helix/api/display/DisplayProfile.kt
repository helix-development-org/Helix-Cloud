package org.helix.api.display

import kotlinx.serialization.Serializable

/**
 * How a player is displayed in game: chat, tab list and the name tag.
 *
 * A display name is composed as `prefix + color + name + suffix`. By
 * convention the prefix belongs to permission groups, the name is the
 * changeable nick (empty = real name) and the suffix belongs to clans.
 * Addons each contribute their component through a
 * [org.helix.api.addon.DisplayResolver]; the node merges all resolver
 * results per component (first non-empty value wins).
 *
 * Text may contain `&` color codes, rendered by the bridges.
 *
 * @property prefix text before the player name, for example `&cAdmin &f`.
 * @property name display name override (nick); empty keeps the real name.
 * @property suffix text after the player name, for example a clan tag.
 * @property color name color code, for example `&c`.
 */
@Serializable
data class DisplayProfile(
    val prefix: String = "",
    val name: String = "",
    val suffix: String = "",
    val color: String = "",
) {
    /**
     * The name component to render for a player.
     *
     * @param realName the player's account name.
     * @return the nick when set, otherwise [realName].
     */
    fun nameOr(realName: String): String = name.ifEmpty { realName }

    /**
     * The fully composed display name: `prefix + color + name + suffix`.
     *
     * @param realName the player's account name.
     * @return the composed display name with `&` color codes.
     */
    fun displayName(realName: String): String = "$prefix$color${nameOr(realName)}$suffix"

    /** Whether any component differs from the plain account name. */
    fun isPlain(): Boolean = prefix.isEmpty() && name.isEmpty() && suffix.isEmpty() && color.isEmpty()
}
